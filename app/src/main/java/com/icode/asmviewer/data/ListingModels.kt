package com.icode.asmviewer.data

import org.json.JSONArray
import org.json.JSONObject

enum class ListingKind {
    Insn,
    Data,
    Gap,
    Unmapped,
    FunctionLabel,
}

data class ListingRow(
    val kind: ListingKind,
    val address: Long,
    val size: Long,
    val insn: InstructionSnap? = null,
    val title: String = "",
    val subtitle: String = "",
) {
    val key: String
        get() = when (kind) {
            ListingKind.FunctionLabel -> "fn:${address.toULong().toString(16)}:$title"
            else -> "${kind.name}:${address.toULong().toString(16)}:$size"
        }

    fun covers(va: Long): Boolean {
        val span = size.coerceAtLeast(1L)
        return va >= address && va < address + span
    }
}

data class ListingWindowPage(
    val origin: Long,
    val backward: Boolean,
    val reachedStart: Boolean,
    val reachedEnd: Boolean,
    val items: List<ListingRow>,
)

fun parseListingWindow(json: String): ListingWindowPage {
    val obj = JSONObject(json)
    val arr = obj.optJSONArray("items") ?: JSONArray()
    val items = buildList {
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            val kind = when (it.optString("kind")) {
                "insn" -> ListingKind.Insn
                "gap" -> ListingKind.Gap
                "unmapped" -> ListingKind.Unmapped
                else -> ListingKind.Data
            }
            val address = it.optLong("address")
            val size = it.optLong("size")
            val insn = if (kind == ListingKind.Insn) {
                val target = if (it.isNull("branchTarget")) null else it.optLong("branchTarget")
                InstructionSnap(
                    address = address,
                    size = size.toInt().coerceAtLeast(1),
                    bytes = it.optString("bytes"),
                    mnemonic = it.optString("mnemonic"),
                    operands = it.optString("operands"),
                    category = it.optString("category"),
                    branchTarget = target,
                )
            } else {
                null
            }
            add(
                ListingRow(
                    kind = kind,
                    address = address,
                    size = size.coerceAtLeast(0L),
                    insn = insn,
                    title = it.optString("mnemonic"),
                    subtitle = it.optString("bytes"),
                ),
            )
        }
    }
    return ListingWindowPage(
        origin = obj.optLong("origin"),
        backward = obj.optBoolean("backward"),
        reachedStart = obj.optBoolean("reachedStart"),
        reachedEnd = obj.optBoolean("reachedEnd"),
        items = items,
    )
}

object ListingMerger {
    fun merge(existing: List<ListingRow>, incoming: List<ListingRow>): List<ListingRow> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming
        val content = existing.filter { it.kind != ListingKind.FunctionLabel }.toMutableList()
        for (row in incoming) {
            if (row.kind == ListingKind.FunctionLabel) continue
            if (content.any { overlapsSame(it, row) }) continue
            content.add(row)
        }
        content.sortWith(compareBy<ListingRow> { it.address }.thenBy { kindOrder(it.kind) }.thenBy { it.size })
        return dropCoveredMarkers(content)
    }

    fun insertFunctionLabels(rows: List<ListingRow>, functions: List<FunctionItem>): List<ListingRow> {
        val content = rows.filter { it.kind != ListingKind.FunctionLabel }
        if (content.isEmpty() || functions.isEmpty()) return content
        val from = content.first().address
        val last = content.last()
        val to = last.address + last.size.coerceAtLeast(1L)
        val labels = functions
            .filter { it.start >= from && it.start < to }
            .sortedWith(compareBy<FunctionItem> { it.start }.thenBy { it.displayName })
        if (labels.isEmpty()) return content
        val split = splitMarkersAt(content, labels.map { it.start }.toSet())
        val out = ArrayList<ListingRow>(split.size + labels.size)
        var li = 0
        for (row in split) {
            while (li < labels.size && labels[li].start < row.address) {
                val fn = labels[li]
                out.add(functionLabel(fn))
                li++
            }
            if (li < labels.size && labels[li].start == row.address) {
                var same = li
                while (same < labels.size && labels[same].start == row.address) {
                    out.add(functionLabel(labels[same]))
                    same++
                }
                li = same
            }
            out.add(row)
        }
        while (li < labels.size) {
            out.add(functionLabel(labels[li]))
            li++
        }
        return out
    }

    fun evict(rows: List<ListingRow>, maxRows: Int, keepNear: Long?): List<ListingRow> {
        val content = rows.filter { it.kind != ListingKind.FunctionLabel }
        if (content.size <= maxRows) return rows
        val anchor = keepNear ?: content[content.size / 2].address
        val idx = coveringIndex(content, anchor).let { if (it >= 0) it else content.size / 2 }
        var from = (idx - maxRows / 2).coerceAtLeast(0)
        var to = (from + maxRows).coerceAtMost(content.size)
        from = (to - maxRows).coerceAtLeast(0)
        return content.subList(from, to)
    }

    fun coveringIndex(rows: List<ListingRow>, va: Long): Int {
        rows.forEachIndexed { i, row ->
            if (row.kind != ListingKind.FunctionLabel && row.covers(va)) return i
        }
        // nearest instruction at or before va
        var best = -1
        rows.forEachIndexed { i, row ->
            if (row.kind != ListingKind.FunctionLabel && row.address <= va) best = i
        }
        return best
    }

    fun covers(rows: List<ListingRow>, va: Long): Boolean =
        rows.any { it.kind != ListingKind.FunctionLabel && it.covers(va) }

    private fun functionLabel(fn: FunctionItem): ListingRow =
        ListingRow(
            kind = ListingKind.FunctionLabel,
            address = fn.start,
            size = 0,
            title = fn.displayName,
            subtitle = formatHex(fn.start, 8),
        )

    private fun kindOrder(kind: ListingKind): Int = when (kind) {
        ListingKind.FunctionLabel -> 0
        ListingKind.Insn -> 1
        ListingKind.Data -> 2
        ListingKind.Gap -> 3
        ListingKind.Unmapped -> 4
    }

    private fun overlapsSame(a: ListingRow, b: ListingRow): Boolean {
        if (a.kind == ListingKind.Insn && b.kind == ListingKind.Insn) {
            return a.address == b.address
        }
        if (a.kind == b.kind && a.address == b.address && a.size == b.size) return true
        if (a.kind != ListingKind.Insn && b.kind == ListingKind.Insn) {
            return a.covers(b.address)
        }
        if (b.kind != ListingKind.Insn && a.kind == ListingKind.Insn) {
            return b.covers(a.address)
        }
        return a.address == b.address && a.kind == b.kind
    }

    private fun dropCoveredMarkers(rows: List<ListingRow>): List<ListingRow> {
        val insns = rows.filter { it.kind == ListingKind.Insn }.map { it.address }.toHashSet()
        return rows.filter { row ->
            if (row.kind == ListingKind.Insn || row.kind == ListingKind.FunctionLabel) true
            else !insns.contains(row.address)
        }
    }

    private fun splitMarkersAt(rows: List<ListingRow>, cuts: Set<Long>): List<ListingRow> {
        if (cuts.isEmpty()) return rows
        val out = ArrayList<ListingRow>(rows.size)
        for (row in rows) {
            if (row.kind == ListingKind.Insn || row.size <= 0) {
                out.add(row)
                continue
            }
            val end = row.address + row.size
            val inner = cuts.filter { it > row.address && it < end }.sorted()
            if (inner.isEmpty()) {
                out.add(row)
                continue
            }
            var start = row.address
            var byteOff = 0
            for (cut in inner) {
                val chunk = cut - start
                if (chunk > 0) {
                    out.add(sliceMarker(row, start, chunk, byteOff))
                    byteOff += chunk.toInt().coerceAtLeast(0)
                }
                start = cut
            }
            if (end > start) {
                out.add(sliceMarker(row, start, end - start, byteOff))
            }
        }
        return out
    }

    private fun sliceMarker(row: ListingRow, start: Long, size: Long, byteOff: Int): ListingRow {
        val bytes = row.subtitle
        val slice = if (bytes.isNotEmpty() && byteOff * 2 < bytes.length) {
            val end = (byteOff * 2 + size.toInt().coerceAtLeast(0) * 2).coerceAtMost(bytes.length)
            bytes.substring(byteOff * 2, end)
        } else {
            bytes
        }
        return row.copy(address = start, size = size, subtitle = slice)
    }
}

/**
 * Address used to open CFG from the listing tab.
 * An explicit selection still in the viewport wins; otherwise the first
 * visible code row / viewport anchor is used so a mere scroll to another
 * function is not ignored in favor of an off-screen previous selection.
 */
fun resolveCfgSyncAddress(
    selectedAddress: Long?,
    selectionInViewport: Boolean,
    viewportAnchor: Long?,
): Long? {
    if (selectedAddress != null && selectionInViewport) return selectedAddress
    if (viewportAnchor != null) return viewportAnchor
    return selectedAddress
}
