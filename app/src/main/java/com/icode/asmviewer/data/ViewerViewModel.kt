package com.icode.asmviewer.data

import android.app.Application
import android.net.Uri
import com.icode.asmviewer.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

enum class ViewMode { Flat, Cfg }

class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = EngineNative()

    var darkTheme by mutableStateOf(true)
        private set
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    var image by mutableStateOf<ImageInfo?>(null)
        private set
    var functions by mutableStateOf<List<FunctionItem>>(emptyList())
        private set
    var functionQuery by mutableStateOf("")
        private set
    var selectedFunction by mutableStateOf<FunctionItem?>(null)
        private set
    var snapshot by mutableStateOf<CfgSnapshot?>(null)
        private set
    var layout by mutableStateOf<CfgLayout?>(null)
        private set
    var viewMode by mutableStateOf(ViewMode.Cfg)
        private set
    var selectedAddress by mutableStateOf<Long?>(null)
        private set
    var browseScreen by mutableStateOf(BrowseScreen.Home)
        private set
    var pendingLibraries by mutableStateOf<List<NativeLibRef>>(emptyList())
        private set
    var pendingLibraryTitle by mutableStateOf("")
        private set
    var installedAppQuery by mutableStateOf("")
        private set
    var replayReport by mutableStateOf<ReplayReport?>(null)
        private set
    var replayPanelExpanded by mutableStateOf(false)
    var replayDetailsOpen by mutableStateOf(false)
        private set
    var addressKind by mutableStateOf(AppLocale.storedAddressKind(app))
        private set
    var viewportKind by mutableStateOf(ViewportRequest.None)
        private set
    var viewportSeq by mutableStateOf(0)
        private set
    var canJumpBack by mutableStateOf(false)
        private set
    var maxBlocks by mutableStateOf(4096L)
        private set
    var extractedLibraries by mutableStateOf<List<ApkImporter.ExtractedSo>>(emptyList())
        private set

    var listingRows by mutableStateOf<List<ListingRow>>(emptyList())
        private set
    var listingReachedStart by mutableStateOf(false)
        private set
    var listingReachedEnd by mutableStateOf(false)
        private set
    var listingLoadingBefore by mutableStateOf(false)
        private set
    var listingLoadingAfter by mutableStateOf(false)
        private set
    var listingAnchorAddress by mutableStateOf<Long?>(null)
        private set
    var listingAnchorSeq by mutableStateOf(0)
        private set

    private var previousBrowse: BrowseScreen = BrowseScreen.Home
    private var sessionHandle: Long = 0
    private var analysisJob: Job? = null
    private var cfgRequestId: Int = 0
    private var listingViewportAnchor: Long? = null
    private var listingSelectionInViewport: Boolean = true
    private var listingJob: Job? = null
    private var listingEpoch: Int = 0
    private var listingRaw: List<ListingRow> = emptyList()
    private val jumpHistory = ArrayDeque<Long>()
    private val cfgCache = LinkedHashMap<String, CfgSnapshot>()

    private companion object {
        const val LISTING_PAGE = 128L
        const val LISTING_MAX_ROWS = 4096
    }

    val filteredFunctions: List<FunctionItem>
        get() {
            val q = functionQuery.trim().lowercase()
            if (q.isEmpty()) return functions
            return functions.filter {
                it.displayName.lowercase().contains(q) ||
                    it.start.toString(16).contains(q) ||
                    ("0x" + it.start.toString(16)).contains(q)
            }
        }

    val replayPathBlocks: Set<Long>
        get() = replayReport?.pathBlocks?.toSet().orEmpty()

    private fun str(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun fail(error: Throwable) {
        errorMessage = EngineErrors.localize(getApplication(), error)
    }

    private fun fail(message: String) {
        errorMessage = message
    }

    private fun requestViewport(kind: ViewportRequest) {
        viewportKind = kind
        viewportSeq += 1
    }

    fun consumeViewportRequest() {
        if (viewportKind != ViewportRequest.None) {
            viewportKind = ViewportRequest.None
        }
    }

    private fun refreshJumpBack() {
        canJumpBack = jumpHistory.isNotEmpty()
    }

    fun toggleTheme() { darkTheme = !darkTheme }
    fun dismissError() { errorMessage = null }
    fun dismissNotice() { notice = null }
    fun notifyCopied() { notice = str(R.string.action_copied) }
    fun setQuery(value: String) { functionQuery = value }
    fun updateViewMode(mode: ViewMode) {
        if (viewMode == mode) {
            if (mode == ViewMode.Flat) {
                val va = selectedAddress ?: selectedFunction?.start
                if (va != null) ensureListingAround(va)
            } else if (mode == ViewMode.Cfg) {
                syncCfgFromListingContext()
            }
            return
        }
        viewMode = mode
        if (mode == ViewMode.Cfg) {
            syncCfgFromListingContext()
        }
        if (mode == ViewMode.Flat) {
            val va = selectedAddress ?: selectedFunction?.start
            if (va != null) ensureListingAround(va)
        }
    }

    fun updateListingViewport(firstVisibleCode: Long?, selectionInViewport: Boolean) {
        listingViewportAnchor = firstVisibleCode
        listingSelectionInViewport = selectionInViewport
    }

    /**
     * Tab switch only: resolve the function that the listing is actually
     * showing, then load/reuse that function's graph. Ordinary scrolling
     * reports a viewport but does not rebuild CFG.
     */
    fun syncCfgFromListingContext() {
        val addr = resolveCfgSyncAddress(
            selectedAddress = selectedAddress,
            selectionInViewport = listingSelectionInViewport,
            viewportAnchor = listingViewportAnchor,
        )
        if (addr == null) {
            if (selectedFunction != null && snapshot != null) {
                requestViewport(ViewportRequest.CenterSelection)
            }
            return
        }
        val fn = functionContaining(addr)
        if (fn == null) {
            showUnownedCfgState(addr)
            return
        }
        if (selectedFunction?.start == fn.start) {
            if (selectedAddress != addr) {
                replayReport = null
                replayDetailsOpen = false
            }
            selectedAddress = addr
            if (snapshot != null && snapshot?.functionStart == fn.start) {
                requestViewport(ViewportRequest.CenterSelection)
            } else {
                analyzeFunction(fn, addr)
            }
            return
        }
        selectedFunction = fn
        selectedAddress = addr
        replayReport = null
        replayDetailsOpen = false
        analyzeFunction(fn, addr)
    }

    private fun showUnownedCfgState(addr: Long) {
        ++cfgRequestId
        analysisJob?.cancel()
        selectedFunction = null
        selectedAddress = addr
        snapshot = null
        layout = null
        replayReport = null
        replayDetailsOpen = false
        notice = str(R.string.cfg_no_function)
    }
    fun updateAddressKind(kind: AddressKind) {
        addressKind = kind
        AppLocale.storeAddressKind(getApplication(), kind)
    }
    fun updateMaxBlocks(value: Long) { maxBlocks = value.coerceIn(16L, 50_000L) }

    fun openBuiltinSample() {
        viewModelScope.launch {
            try {
                busyMessage = str(R.string.busy_loading_sample)
                val file = withContext(Dispatchers.IO) { ApkImporter.installBuiltinSample(getApplication()) }
                openFileInternal(file, builtin = true, label = str(R.string.builtin_sample_label))
            } catch (t: Throwable) {
                fail(t)
            } finally {
                busyMessage = null
            }
        }
    }

    fun importApkUri(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            try {
                busyMessage = str(R.string.busy_listing_apk)
                val dest = File(getApplication<Application>().filesDir, "imported/apk_${System.currentTimeMillis()}/container.apk")
                val label = displayName ?: str(R.string.chosen_apk)
                val libs = withContext(Dispatchers.IO) {
                    ApkImporter.listArm64LibrariesFromUri(getApplication(), uri, dest, label)
                }
                pendingLibraries = libs
                pendingLibraryTitle = str(R.string.libs_from_apk, label)
                previousBrowse = BrowseScreen.Home
                browseScreen = BrowseScreen.LibraryPicker
                notice = str(R.string.libs_listed_notice, libs.size)
            } catch (t: Throwable) {
                fail(t)
            } finally {
                busyMessage = null
            }
        }
    }

    fun importElfUri(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            try {
                busyMessage = str(R.string.busy_opening_elf)
                val dest = File(getApplication<Application>().filesDir, "imported/elf_${System.currentTimeMillis()}.bin")
                val file = withContext(Dispatchers.IO) {
                    ApkImporter.copyUriToFile(getApplication(), uri, dest)
                }
                pendingLibraries = emptyList()
                openFileInternal(file, builtin = false, label = displayName ?: file.name)
            } catch (t: Throwable) {
                fail(t)
            } finally {
                busyMessage = null
            }
        }
    }

    /** Open an already-copied ELF/.so from local storage (used by tests and any local path). */
    fun openElfFromFile(file: File, label: String) {
        viewModelScope.launch {
            try {
                busyMessage = str(R.string.busy_opening_elf)
                pendingLibraries = emptyList()
                openFileInternal(file, builtin = false, label = label)
            } catch (t: Throwable) {
                fail(t)
            } finally {
                busyMessage = null
            }
        }
    }

    fun showInstalledApps() {
        installedAppQuery = ""
        browseScreen = BrowseScreen.InstalledApps
    }

    fun updateInstalledAppQuery(value: String) { installedAppQuery = value }

    fun importInstalledApp(entry: InstalledAppEntry) {
        viewModelScope.launch {
            try {
                busyMessage = str(R.string.busy_listing_installed)
                val containers = InstalledApps.apkContainersFor(entry)
                val libs = withContext(Dispatchers.IO) { ApkImporter.listArm64Libraries(containers) }
                pendingLibraries = libs
                pendingLibraryTitle = "${entry.label}（${entry.packageName}）"
                previousBrowse = BrowseScreen.InstalledApps
                browseScreen = BrowseScreen.LibraryPicker
                notice = str(R.string.libs_installed_notice)
            } catch (t: Throwable) {
                fail(t)
            } finally {
                busyMessage = null
            }
        }
    }

    fun openSelectedLibrary(ref: NativeLibRef) {
        viewModelScope.launch {
            try {
                busyMessage = str(R.string.busy_opening_lib, ref.soName)
                val dir = File(getApplication<Application>().filesDir, "imported/so_${System.currentTimeMillis()}")
                val so = withContext(Dispatchers.IO) { ApkImporter.extractLibrary(ref, dir) }
                extractedLibraries = listOf(so)
                openFileInternal(so.file, builtin = false, label = "${pendingLibraryTitle} / ${ref.soName}")
            } catch (t: Throwable) {
                fail(t)
            } finally {
                busyMessage = null
            }
        }
    }

    fun goHome() {
        closeSession()
        pendingLibraries = emptyList()
        pendingLibraryTitle = ""
        browseScreen = BrowseScreen.Home
    }

    fun cancelLibraryPick() {
        pendingLibraries = emptyList()
        browseScreen = previousBrowse
    }

    fun navigateBack(): Boolean {
        if (replayDetailsOpen) {
            closeReplayDetails()
            return true
        }
        if (selectedFunction != null) {
            if (jumpBack()) return true
            clearFunctionSelection()
            return true
        }
        return when (browseScreen) {
            BrowseScreen.Functions -> {
                closeSession()
                browseScreen = if (pendingLibraries.isNotEmpty()) BrowseScreen.LibraryPicker else BrowseScreen.Home
                true
            }
            BrowseScreen.LibraryPicker -> {
                cancelLibraryPick()
                true
            }
            BrowseScreen.InstalledApps -> {
                browseScreen = BrowseScreen.Home
                true
            }
            BrowseScreen.Home -> false
        }
    }

    private suspend fun openFileInternal(file: File, builtin: Boolean, label: String) {
        if (sessionHandle != 0L) {
            engine.close(sessionHandle)
            sessionHandle = 0
        }
        snapshot = null
        layout = null
        selectedFunction = null
        selectedAddress = null
        replayReport = null
        replayDetailsOpen = false
        jumpHistory.clear()
        refreshJumpBack()
        viewportKind = ViewportRequest.None
        cfgCache.clear()
        resetListing()
        val handle = withContext(Dispatchers.IO) { engine.open(file.absolutePath) }
        sessionHandle = handle
        val json = withContext(Dispatchers.IO) { engine.listFunctions(handle) }
        val (info, list) = parseFunctionList(json)
        image = info.copy(
            isBuiltinSample = builtin,
            label = label,
            isArm64 = engine.isArm64(handle),
            arch = engine.archName(handle),
        )
        functions = list
        browseScreen = BrowseScreen.Functions
        viewMode = ViewMode.Cfg
        if (!engine.isArm64(handle)) {
            errorMessage = str(R.string.not_arm64_open, engine.archName(handle))
        }
    }

    fun selectFunction(item: FunctionItem, focusAddress: Long? = null, clearHistory: Boolean = true) {
        replayReport = null
        replayDetailsOpen = false
        selectedFunction = item
        selectedAddress = focusAddress ?: item.start
        if (clearHistory) {
            jumpHistory.clear()
            refreshJumpBack()
        }
        analyzeFunction(item, focusAddress)
        ensureListingAround(focusAddress ?: item.start)
    }

    fun clearFunctionSelection() {
        cancelAnalysis()
        selectedFunction = null
        snapshot = null
        layout = null
        selectedAddress = null
        replayReport = null
        replayDetailsOpen = false
        jumpHistory.clear()
        refreshJumpBack()
        resetListing()
    }

    fun cancelAnalysis() {
        ++cfgRequestId
        val handle = sessionHandle
        if (handle != 0L) engine.cancel(handle)
        analysisJob?.cancel()
        busyMessage = null
    }

    private fun cacheKey(item: FunctionItem): String {
        val img = image
        return listOf(
            img?.fingerprint.orEmpty(),
            item.start.toString(),
            maxBlocks.toString(),
            img?.fileSize.toString(),
        ).joinToString("|")
    }

    private fun analyzeFunction(item: FunctionItem, focusAddress: Long? = null) {
        val handle = sessionHandle
        if (handle == 0L) return
        if (image?.isArm64 != true) {
            errorMessage = str(R.string.not_arm64_cfg)
            return
        }
        val requestId = ++cfgRequestId
        val key = cacheKey(item)
        val applySnap = { snap: CfgSnapshot ->
            if (requestId != cfgRequestId) {
                false
            } else if (selectedFunction?.start != item.start) {
                false
            } else {
                snapshot = snap
                layout = null
                selectedAddress = focusAddress ?: selectedAddress ?: item.start
                requestViewport(if (focusAddress != null) ViewportRequest.CenterSelection else ViewportRequest.FitAll)
                true
            }
        }
        cfgCache[key]?.let { snap ->
            applySnap(snap)
            return
        }
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            try {
                busyMessage = str(R.string.busy_analyzing, item.displayName)
                val json = withContext(Dispatchers.Default) {
                    engine.buildCfg(handle, item.start, maxBlocks, timeoutMs = 30_000)
                }
                if (requestId != cfgRequestId || selectedFunction?.start != item.start) {
                    return@launch
                }
                val snap = parseCfgSnapshot(json)
                if (cfgCache.size > 16) {
                    val first = cfgCache.keys.first()
                    cfgCache.remove(first)
                }
                cfgCache[key] = snap
                if (!applySnap(snap)) {
                    return@launch
                }
                if (snap.truncated) {
                    notice = str(R.string.cfg_truncated, snap.truncationReason ?: str(R.string.cfg_truncated_default), snap.blockCount)
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (requestId == cfgRequestId) fail(t)
            } finally {
                if (requestId == cfgRequestId) busyMessage = null
            }
        }
    }

    fun selectFunctionLabel(name: String, start: Long) {
        val fn = functions.firstOrNull { it.start == start && (it.displayName == name || it.name == name) }
            ?: functions.firstOrNull { it.start == start }
            ?: functionContaining(start)
        if (fn != null) {
            selectFunction(fn, focusAddress = start, clearHistory = false)
        } else {
            navigateToAddress(start, recordHistory = true)
        }
    }

    fun selectAddress(address: Long, recordHistory: Boolean = true) {

        navigateToAddress(address, recordHistory)
    }

    fun jumpToBranch(instruction: InstructionSnap) {
        val target = instruction.branchTarget ?: return
        navigateToAddress(target, recordHistory = true)
    }

    fun jumpBack(): Boolean {
        val prev = if (jumpHistory.isEmpty()) null else jumpHistory.removeLast()
        refreshJumpBack()
        if (prev == null) return false
        navigateToAddress(prev, recordHistory = false)
        return true
    }

    fun jumpToTypedAddress(text: String, kind: AddressKind = addressKind) {
        val map = image?.addressMap
        if (image == null || map == null) {
            fail(str(R.string.jump_need_file))
            return
        }
        val value = parseHexOffset(text)
        if (value == null) {
            fail(str(R.string.jump_bad_hex))
            return
        }
        when (val resolved = map.resolve(kind, value, requireCode = true, requireAligned = image?.isArm64 == true)) {
            is AddressResolve.Err -> fail(EngineErrors.message(getApplication(), resolved.reason))
            is AddressResolve.Ok -> navigateToAddress(resolved.va, recordHistory = true)
        }
    }

    fun functionContaining(va: Long): FunctionItem? {
        functions.firstOrNull { it.start == va }?.let { return it }
        return functions.filter { fn ->
            val end = fn.start + fn.size.coerceAtLeast(1)
            va >= fn.start && va < end
        }.minByOrNull { it.size.coerceAtLeast(1) }
    }

    fun navigateToAddress(address: Long, recordHistory: Boolean = true) {
        val current = selectedAddress
        if (recordHistory && current != null && current != address) {
            jumpHistory.addLast(current)
            while (jumpHistory.size > 64) jumpHistory.removeFirst()
        }
        refreshJumpBack()

        val fn = functionContaining(address)
        val currentFn = selectedFunction
        if (fn != null) {
            if (currentFn?.start == fn.start) {
                if (selectedAddress != address) {
                    replayReport = null
                    replayDetailsOpen = false
                }
                selectedAddress = address
                if (snapshot != null) requestViewport(ViewportRequest.CenterSelection)
                ensureListingAround(address)
            } else {
                selectFunction(fn, focusAddress = address, clearHistory = false)
            }
            return
        }
        if (currentFn != null && snapshot?.instructionsInOrder?.any { it.address == address } == true) {
            selectedAddress = address
            requestViewport(ViewportRequest.CenterSelection)
            return
        }

        openExecutableView(address)
    }

    private fun openExecutableView(va: Long) {
        val map = image?.addressMap
        val seg = map?.executableSegmentForVa(va) ?: map?.segmentForVa(va)
        if (map == null || seg == null) {
            fail(str(R.string.jump_no_region))
            return
        }
        // Unowned gap/data is still a valid listing location. Do not invent a
        // synthetic function or reuse a stale function as the CFG/replay entry.
        selectedAddress = va
        if (viewMode != ViewMode.Flat) {
            viewMode = ViewMode.Flat
        }
        notice = str(R.string.jump_no_neighbor)
        ensureListingAround(va)
    }

    fun replayToSelected() {
        val handle = sessionHandle
        val addr = selectedAddress
        if (handle == 0L || addr == null) {
            fail(str(R.string.replay_need_selection))
            return
        }
        val fn = functionContaining(addr) ?: selectedFunction
        if (fn == null || !functionContains(fn, addr)) {
            fail(str(R.string.replay_not_in_function))
            return
        }
        if (fn.start != selectedFunction?.start) {
            selectedFunction = fn
        }
        if (image?.isArm64 != true) {
            fail(str(R.string.replay_need_arm64))
            return
        }
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            try {
                busyMessage = str(R.string.replay_busy, formatAddr(addr))
                val json = withContext(Dispatchers.Default) {
                    engine.replayTo(handle, fn.start, addr, 15_000)
                }
                val report = parseReplayReport(json)
                replayReport = report
                replayPanelExpanded = true
                // Structured reach reports (reached, not reached, infeasible,
                // timeout, cancelled, unsupported) belong on the panel and
                // Details page. Do not pop a modal over them.
            } catch (t: Throwable) {
                fail(t)
            } finally {
                busyMessage = null
            }
        }
    }

    fun updateReplayPanelExpanded(expanded: Boolean) {
        replayPanelExpanded = expanded
    }

    fun openReplayDetails() {
        if (replayReport == null) return
        replayDetailsOpen = true
    }

    fun closeReplayDetails() { replayDetailsOpen = false }

    private fun closeSession() {
        cancelAnalysis()
        if (sessionHandle != 0L) {
            engine.close(sessionHandle)
            sessionHandle = 0
        }
        image = null
        functions = emptyList()
        selectedFunction = null
        snapshot = null
        layout = null
        selectedAddress = null
        replayReport = null
        replayDetailsOpen = false
        jumpHistory.clear()
        refreshJumpBack()
        viewportKind = ViewportRequest.None
        cfgCache.clear()
        resetListing()
    }

    fun functionContains(fn: FunctionItem, va: Long): Boolean {
        if (fn.start == va) return true
        val end = fn.start + fn.size.coerceAtLeast(1)
        return va >= fn.start && va < end
    }

    private fun resetListing() {
        listingEpoch += 1
        listingJob?.cancel()
        listingJob = null
        listingRaw = emptyList()
        listingRows = emptyList()
        listingReachedStart = false
        listingReachedEnd = false
        listingLoadingBefore = false
        listingLoadingAfter = false
        listingAnchorAddress = null
        listingViewportAnchor = null
        listingSelectionInViewport = true
    }

    private fun requestListingAnchor(va: Long) {
        listingAnchorAddress = va
        listingAnchorSeq += 1
        // Explicit jump/click just asked the list to place this address on screen.
        // Until the listing reporter catches up, treat it as the viewport selection
        // so a fast tab switch to CFG cannot fall back to a stale off-screen flag.
        listingViewportAnchor = va
        listingSelectionInViewport = true
    }

    private fun publishListing(raw: List<ListingRow>) {
        val evicted = ListingMerger.evict(raw, LISTING_MAX_ROWS, selectedAddress)
        listingRaw = evicted.filter { it.kind != ListingKind.FunctionLabel }
        listingRows = ListingMerger.insertFunctionLabels(listingRaw, functions)
    }

    fun ensureListingAround(va: Long) {
        if (sessionHandle == 0L) return
        if (ListingMerger.covers(listingRaw, va)) {
            requestListingAnchor(va)
            return
        }
        val epoch = listingEpoch + 1
        listingEpoch = epoch
        listingJob?.cancel()
        listingJob = viewModelScope.launch {
            try {
                val handle = sessionHandle
                if (handle == 0L) return@launch
                val forward = withContext(Dispatchers.Default) {
                    parseListingWindow(engine.disasmWindow(handle, va, false, LISTING_PAGE))
                }
                if (epoch != listingEpoch) return@launch
                val backward = withContext(Dispatchers.Default) {
                    parseListingWindow(engine.disasmWindow(handle, va, true, LISTING_PAGE))
                }
                if (epoch != listingEpoch) return@launch
                val merged = ListingMerger.merge(backward.items, forward.items)
                listingReachedStart = backward.reachedStart
                listingReachedEnd = forward.reachedEnd
                publishListing(merged)
                requestListingAnchor(va)
            } catch (_: CancellationException) {
            } catch (t: Throwable) {
                if (listingRows.isEmpty()) fail(t)
            }
        }
    }

    fun loadListingBefore() {
        if (listingLoadingBefore || listingReachedStart) return
        val origin = listingRaw.firstOrNull()?.address ?: return
        val epoch = listingEpoch
        listingLoadingBefore = true
        viewModelScope.launch {
            try {
                val handle = sessionHandle
                if (handle == 0L || epoch != listingEpoch) return@launch
                val page = withContext(Dispatchers.Default) {
                    parseListingWindow(engine.disasmWindow(handle, origin, true, LISTING_PAGE))
                }
                if (epoch != listingEpoch) return@launch
                if (page.items.isEmpty()) {
                    listingReachedStart = true
                    return@launch
                }
                listingReachedStart = page.reachedStart
                publishListing(ListingMerger.merge(listingRaw, page.items))
            } catch (_: CancellationException) {
            } catch (t: Throwable) {
                fail(t)
            } finally {
                if (epoch == listingEpoch) listingLoadingBefore = false
            }
        }
    }

    fun loadListingAfter() {
        if (listingLoadingAfter || listingReachedEnd) return
        val last = listingRaw.lastOrNull() ?: return
        val origin = last.address + last.size.coerceAtLeast(1)
        val epoch = listingEpoch
        listingLoadingAfter = true
        viewModelScope.launch {
            try {
                val handle = sessionHandle
                if (handle == 0L || epoch != listingEpoch) return@launch
                val page = withContext(Dispatchers.Default) {
                    parseListingWindow(engine.disasmWindow(handle, origin, false, LISTING_PAGE))
                }
                if (epoch != listingEpoch) return@launch
                if (page.items.isEmpty()) {
                    listingReachedEnd = true
                    return@launch
                }
                listingReachedEnd = page.reachedEnd
                publishListing(ListingMerger.merge(listingRaw, page.items))
            } catch (_: CancellationException) {
            } catch (t: Throwable) {
                fail(t)
            } finally {
                if (epoch == listingEpoch) listingLoadingAfter = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeSession()
    }
}
