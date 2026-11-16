package com.icode.asmviewer.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.icode.asmviewer.R
import com.icode.asmviewer.data.BrowseScreen
import com.icode.asmviewer.data.ViewMode
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.data.formatAddr

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun MainScreen(viewModel: ViewerViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showJump by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }

    val openApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importApkUri(uri, queryDisplayName(context, uri))
        }
    }
    val openApkAny = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importApkUri(uri, queryDisplayName(context, uri))
        }
    }
    val openElf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importElfUri(uri, queryDisplayName(context, uri))
        }
    }

    LaunchedEffect(viewModel.notice) {
        val n = viewModel.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(n)
        viewModel.dismissNotice()
    }

    BackHandler(enabled = viewModel.browseScreen != BrowseScreen.Home || viewModel.selectedFunction != null) {
        if (!viewModel.navigateBack()) {
            // stay
        }
    }

    if (viewModel.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(viewModel.errorMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { viewModel.dismissError() }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    if (showJump) {
        JumpDialog(viewModel, onDismiss = { showJump = false })
    }
    if (showLanguage) {
        LanguageDialog(onDismiss = { showLanguage = false })
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("app_bar_title"),
                        )
                        val subtitle = when (viewModel.browseScreen) {
                            BrowseScreen.Home -> ""
                            BrowseScreen.InstalledApps -> stringResource(R.string.subtitle_installed)
                            BrowseScreen.LibraryPicker -> stringResource(R.string.subtitle_libraries)
                            BrowseScreen.Functions -> viewModel.image?.label.orEmpty()
                        }
                        if (subtitle.isNotBlank()) {
                            Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    val canBack = viewModel.replayDetailsOpen ||
                        viewModel.browseScreen != BrowseScreen.Home ||
                        viewModel.selectedFunction != null
                    IconButton(onClick = { viewModel.navigateBack() }, enabled = canBack, modifier = Modifier.testTag("app_bar_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (viewModel.browseScreen == BrowseScreen.Functions) {
                        if (viewModel.canJumpBack) {
                            IconButton(onClick = { viewModel.jumpBack() }, modifier = Modifier.testTag("jump_back")) {
                                Icon(Icons.Filled.History, contentDescription = stringResource(R.string.action_jump_back))
                            }
                        }
                        IconButton(onClick = { showJump = true }, modifier = Modifier.testTag("jump_button")) {
                            Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.action_jump))
                        }
                    }
                    IconButton(onClick = { showLanguage = true }, modifier = Modifier.testTag("language_button")) {
                        Icon(Icons.Filled.Language, contentDescription = stringResource(R.string.action_language))
                    }
                    IconButton(onClick = { viewModel.toggleTheme() }) {
                        Icon(
                            if (viewModel.darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = stringResource(R.string.action_theme),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
            if (viewModel.busyMessage != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(viewModel.busyMessage.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.cancelAnalysis() }, modifier = Modifier.testTag("busy_cancel")) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            when (viewModel.browseScreen) {
                BrowseScreen.Home -> HomePane(
                    onOpenApk = {
                        openApk.launch(
                            arrayOf(
                                "application/vnd.android.package-archive",
                                "application/zip",
                                "application/octet-stream",
                            ),
                        )
                    },
                    onOpenApkAnyType = { openApkAny.launch(arrayOf("*/*")) },
                    onOpenInstalled = { viewModel.showInstalledApps() },
                    onOpenElf = {
                        openElf.launch(
                            arrayOf(
                                "application/octet-stream",
                                "application/x-sharedlib",
                                "application/x-elf",
                                "*/*",
                            ),
                        )
                    },
                    onSample = { viewModel.openBuiltinSample() },
                )
                BrowseScreen.InstalledApps -> InstalledAppListPane(viewModel, Modifier.fillMaxSize())
                BrowseScreen.LibraryPicker -> LibraryPickerPane(viewModel, Modifier.fillMaxSize())
                BrowseScreen.Functions -> {
                    ViewerNavBar(viewModel)
                    NonArm64Hint(viewModel)
                    if (viewModel.selectedFunction == null && viewModel.selectedAddress == null) {
                        FunctionListPane(viewModel, Modifier.fillMaxSize())
                    } else {
                        when (viewModel.viewMode) {
                            ViewMode.Flat -> FlatListing(viewModel, Modifier.weight(1f))
                            ViewMode.Cfg -> CfgCanvas(viewModel, Modifier.weight(1f))
                        }
                        if (viewModel.replayReport != null) {
                            ReplayPanel(viewModel, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
        if (viewModel.replayDetailsOpen) {
            ReplayDetailsPage(viewModel, Modifier.fillMaxSize().zIndex(1f))
        }
    }
}

@Composable
private fun NonArm64Hint(viewModel: ViewerViewModel) {
    val img = viewModel.image
    if (img != null && !img.isArm64) {
        Text(
            stringResource(R.string.not_arm64, img.arch),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return it.getString(idx)
        }
    }
    return uri.lastPathSegment
}
