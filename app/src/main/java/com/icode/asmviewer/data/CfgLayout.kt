package com.icode.asmviewer.data

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

data class LaidOutBlock(
    val block: CfgBlockSnap,
    val rect: Rect,
)

data class LaidOutEdge(
    val edge: CfgEdgeSnap,
    val fromId: Int,
    val start: Offset,
    val end: Offset,
    val control: Offset,
    val labelAt: Offset,
)

/** All fields are in pixels, derived from the same Density / text measurer used to draw. */
data class CfgLayoutMetrics(
    val blockWidth: Float,
    val lineHeight: Float,
    val headerHeight: Float,
    val padInside: Float,
    val hGap: Float,
    val vGap: Float,
    val graphPad: Float,
    val fontSizeSp: Float,
    val labelFontSizeSp: Float,
    val maxInsnLines: Int = 128,
) {
    fun blockHeight(block: CfgBlockSnap): Float {
        if (block.unknownTarget) return headerHeight + padInside * 2f
        val shown = block.instructions.size.coerceAtMost(maxInsnLines)
        val ellipsis = if (block.instructions.size > maxInsnLines) 1 else 0
        return padInside + headerHeight + padInside * 0.5f + (shown + ellipsis) * lineHeight + padInside
    }

    companion object {
        fun from(density: Density, measurer: TextMeasurer): CfgLayoutMetrics = with(density) {
            val fontSp = 12f
            val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSp.sp)
            // Representative widest line we intend to show inside a node.
            val measured = measurer.measure("0xFFFF  WWWW  ww, ww, #0xFFFF", style)
            val glyphH = measured.size.height.toFloat()
            // Line boxes are integer pixels (drawText constraints are Int).
            // Ceil the measured glyph height so a rounded-down box cannot drop
            // the last instruction line. Do not invent extra empty rows.
            val lineH = ceil(glyphH).toFloat()
            val headerH = lineH
            val pad = ceil(8.dp.toPx().toDouble()).toFloat()
            val width = max(measured.size.width.toFloat() + pad * 2f, 200.dp.toPx())
            CfgLayoutMetrics(
                blockWidth = width,
                lineHeight = lineH,
                headerHeight = headerH,
                padInside = pad,
                hGap = 28.dp.toPx(),
                // Enough vertical room for an edge label between rows.
                vGap = 28.dp.toPx() + headerH,
                graphPad = 32.dp.toPx(),
                fontSizeSp = fontSp,
                labelFontSizeSp = 11f,
            )
        }
    }
}

data class CfgLayout(
    val blocks: List<LaidOutBlock>,
    val edges: List<LaidOutEdge>,
    val width: Float,
    val height: Float,
    val metrics: CfgLayoutMetrics,
) {
    fun blockAt(position: Offset): LaidOutBlock? =
        blocks.lastOrNull { it.rect.inflate(8f).contains(position) }
}

object CfgLayoutEngine {
    fun layout(snapshot: CfgSnapshot, metrics: CfgLayoutMetrics): CfgLayout {
        if (snapshot.blocks.isEmpty()) {
            return CfgLayout(emptyList(), emptyList(), 400f, 400f, metrics)
        }
        val byId = snapshot.blocks.associateBy { it.id }
        val entryId = snapshot.blocks.firstOrNull { it.start == snapshot.entry }?.id
            ?: snapshot.blocks.first().id

        val layer = LinkedHashMap<Int, Int>()
        val queue = ArrayDeque<Int>()
        layer[entryId] = 0
        queue.add(entryId)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val depth = layer[id] ?: 0
            val block = byId[id] ?: continue
            for (e in block.successors) {
                if (!layer.containsKey(e.targetId)) {
                    layer[e.targetId] = depth + 1
                    queue.add(e.targetId)
                }
            }
        }
        snapshot.blocks.forEach { b ->
            if (!layer.containsKey(b.id)) layer[b.id] = (layer.values.maxOrNull() ?: 0) + 1
        }

        val byLayer = layer.entries.groupBy({ it.value }, { it.key }).toSortedMap()
        val positions = HashMap<Int, Rect>()
        var maxX = 0f
        var maxY = 0f
        var y = metrics.graphPad
        val widths = HashMap<Int, Float>()
        // Use a consistent block width so rows stay aligned; metrics already sized for typical lines.
        val bw = metrics.blockWidth
        for ((_, ids) in byLayer) {
            val rowH = ids.maxOf { metrics.blockHeight(byId[it]!!) }
            val estimated = ids.size * bw + (ids.size - 1).coerceAtLeast(0) * metrics.hGap
            var x = metrics.graphPad
            for (id in ids) {
                val h = metrics.blockHeight(byId[id]!!)
                positions[id] = Rect(x, y, x + bw, y + h)
                widths[id] = bw
                x += bw + metrics.hGap
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y + h)
            }
            maxX = maxOf(maxX, metrics.graphPad * 2 + estimated)
            y += rowH + metrics.vGap
        }

        val contentWidth = maxX
        for ((_, ids) in byLayer) {
            val rowWidth = ids.size * bw + (ids.size - 1).coerceAtLeast(0) * metrics.hGap
            val shift = ((contentWidth - metrics.graphPad * 2) - rowWidth) / 2f
            if (shift <= 1f) continue
            for (id in ids) {
                val r = positions[id] ?: continue
                positions[id] = r.translate(shift, 0f)
            }
        }

        val laidBlocks = snapshot.blocks.map { b ->
            LaidOutBlock(
                b,
                positions[b.id] ?: Rect(
                    metrics.graphPad,
                    metrics.graphPad,
                    metrics.graphPad + bw,
                    metrics.graphPad + 40f,
                ),
            )
        }
        val edges = mutableListOf<LaidOutEdge>()
        val labelLift = metrics.lineHeight * 0.65f
        val labelSide = 12f
        for (lb in laidBlocks) {
            val src = lb.rect
            val succs = lb.block.successors
            succs.forEachIndexed { index, edge ->
                val dst = positions[edge.targetId] ?: return@forEachIndexed
                val start = Offset(src.center.x, src.bottom)
                val end = Offset(dst.center.x, dst.top)
                val midY = (start.y + end.y) / 2f
                val bend = if (abs(end.x - start.x) < 8f) {
                    // Vertical edge: bow sideways so a label can sit off the stroke.
                    val dir = if (index % 2 == 0) 1f else -1f
                    Offset((start.x + end.x) / 2f + dir * 22f, midY)
                } else {
                    Offset((start.x + end.x) / 2f, midY)
                }
                val side = if (end.x >= start.x) labelSide else -labelSide
                val labelAt = Offset(bend.x + side, midY - labelLift)
                edges += LaidOutEdge(edge, lb.block.id, start, end, bend, labelAt)
            }
        }
        return CfgLayout(
            laidBlocks,
            edges,
            maxX + metrics.graphPad,
            maxY + metrics.graphPad,
            metrics,
        )
    }
}

private fun Rect.inflate(delta: Float) =
    Rect(left - delta, top - delta, right + delta, bottom + delta)

private fun Rect.translate(dx: Float, dy: Float) =
    Rect(left + dx, top + dy, right + dx, bottom + dy)


/** Visible CFG node title: start address only. Hit testing still uses [start, end). */
fun formatCfgNodeHeader(start: Long): String = formatAddr(start)
