package com.icode.asmviewer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingMergerTest {
    private fun insn(addr: Long) = ListingRow(
        kind = ListingKind.Insn,
        address = addr,
        size = 4,
        insn = InstructionSnap(addr, 4, "00000000", "NOP", "", "insn", null),
    )

    private fun marker(addr: Long, size: Long, kind: ListingKind = ListingKind.Unmapped) =
        ListingRow(kind = kind, address = addr, size = size)

    private fun fn(name: String, start: Long, size: Long = 16) = FunctionItem(
        name = name, start = start, size = size, source = "symbol",
        fromSymbol = true, fromUnwind = false, fromEntry = false,
    )

    @Test
    fun mergeDoesNotDuplicateAndStaysOrdered() {
        val a = listOf(insn(0x10), insn(0x14), insn(0x18))
        val b = listOf(insn(0x18), insn(0x1c), insn(0x20))
        val merged = ListingMerger.merge(a, b)
        assertEquals(listOf(0x10L, 0x14, 0x18, 0x1c, 0x20), merged.map { it.address })
        assertEquals(5, merged.size)
    }

    @Test
    fun backwardPagePrependsWithoutDropping() {
        val existing = listOf(insn(0x20), insn(0x24))
        val before = listOf(insn(0x10), insn(0x14), insn(0x18), insn(0x1c))
        val merged = ListingMerger.merge(existing, before)
        assertEquals((0x10L..0x24L step 4).toList(), merged.map { it.address })
    }

    @Test
    fun evictKeepsNeighborhoodOfAnchor() {
        val rows = (0L until 200L).map { insn(it * 4) }
        val kept = ListingMerger.evict(rows, maxRows = 40, keepNear = 400)
        assertTrue(kept.size <= 40)
        assertTrue(ListingMerger.covers(kept, 400))
        assertFalse(ListingMerger.covers(kept, 0))
    }

    @Test
    fun functionLabelsInsertedAtStartsIncludingAliases() {
        val rows = (0x100L..0x130L step 4).map { insn(it) }
        val fns = listOf(fn("foo", 0x100), fn("foo_alias", 0x100), fn("bar", 0x120), fn("outside", 0x800))
        val labeled = ListingMerger.insertFunctionLabels(rows, fns)
        val labels = labeled.filter { it.kind == ListingKind.FunctionLabel }
        assertEquals(listOf("foo", "foo_alias", "bar"), labels.map { it.title })
        assertEquals(0x100L, labels[0].address)
        assertEquals(0x100L, labels[1].address)
        assertEquals(0x120L, labels[2].address)
        val idxBar = labeled.indexOfFirst { it.title == "bar" }
        assertTrue(labeled[idxBar + 1].address == 0x120L && labeled[idxBar + 1].kind == ListingKind.Insn)
    }

    @Test
    fun markerSplitAtFunctionStart() {
        val rows = listOf(marker(0x100, 0x40, ListingKind.Data), insn(0x140))
        val labeled = ListingMerger.insertFunctionLabels(rows, listOf(fn("mid", 0x110)))
        assertTrue(labeled.any { it.kind == ListingKind.FunctionLabel && it.title == "mid" })
        val data = labeled.filter { it.kind == ListingKind.Data }
        assertEquals(0x100L, data.first().address)
        assertEquals(0x110L, data[0].address + data[0].size)
        assertEquals(0x110L, data[1].address)
    }

    @Test
    fun windowJsonParsesInsnAndMarkers() {
        val json = """
            {"schema":"icode.listing.v1","origin":1,"backward":false,"reachedStart":false,"reachedEnd":true,
             "items":[
               {"kind":"insn","address":4096,"size":4,"bytes":"1f2003d5","mnemonic":"NOP","operands":"","category":"insn","branchTarget":null},
               {"kind":"unmapped","address":4100,"size":16,"bytes":"","mnemonic":"","operands":"","category":"unmapped","branchTarget":null}
             ]}
        """.trimIndent()
        val page = parseListingWindow(json)
        assertEquals(4096L, page.items[0].address)
        assertEquals(ListingKind.Insn, page.items[0].kind)
        assertEquals("NOP", page.items[0].insn?.mnemonic)
        assertEquals(ListingKind.Unmapped, page.items[1].kind)
        assertTrue(page.reachedEnd)
        assertFalse(page.reachedStart)
    }

    @Test
    fun selectionInViewportWinsOverAnchor() {
        val addr = resolveCfgSyncAddress(
            selectedAddress = 0x15a4L,
            selectionInViewport = true,
            viewportAnchor = 0x15c0L,
        )
        assertEquals(0x15a4L, addr)
    }

    @Test
    fun offScreenSelectionDefersToViewportAnchor() {
        val addr = resolveCfgSyncAddress(
            selectedAddress = 0x15a4L,
            selectionInViewport = false,
            viewportAnchor = 0x15c0L,
        )
        assertEquals(0x15c0L, addr)
    }

    @Test
    fun missingAnchorFallsBackToSelection() {
        val addr = resolveCfgSyncAddress(
            selectedAddress = 0x15a4L,
            selectionInViewport = false,
            viewportAnchor = null,
        )
        assertEquals(0x15a4L, addr)
    }

    @Test
    fun noSelectionUsesViewportAnchor() {
        val addr = resolveCfgSyncAddress(
            selectedAddress = null,
            selectionInViewport = false,
            viewportAnchor = 0x15f0L,
        )
        assertEquals(0x15f0L, addr)
    }
}
