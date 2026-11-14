package com.icode.asmviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.icode.asmviewer.R
import com.icode.asmviewer.data.CfgLayout
import com.icode.asmviewer.data.CfgLayoutEngine
import com.icode.asmviewer.data.CfgLayoutMetrics
import com.icode.asmviewer.data.LaidOutBlock
import com.icode.asmviewer.data.LaidOutEdge
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.data.ViewportRequest
import com.icode.asmviewer.data.formatAddr
import com.icode.asmviewer.ui.theme.LocalAsmColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun CfgCanvas(viewModel: ViewerViewModel, modifier: Modifier = Modifier) {
    val snap = viewModel.snapshot
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val metrics = remember(density.density, density.fontScale, measurer) {
        CfgLayoutMetrics.from(density, measurer)
    }
    val layout = remember(snap, metrics) {
        snap?.let { CfgLayoutEngine.layout(it, metrics) }
    }
    val colors = LocalAsmColors.current
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    val selected = viewModel.selectedAddress
    val simPc = viewModel.replayReport?.stopAddress
    val pathBlocks = viewModel.replayPathBlocks

    fun fitAll() {
        val lay = layout ?: return
        if (viewport.width < 8f || viewport.height < 8f) return
        val sx = viewport.width / lay.width.coerceAtLeast(1f)
        val sy = viewport.height / lay.height.coerceAtLeast(1f)
        scale = min(sx, sy).coerceIn(0.12f, 6f) * 0.92f
        pan = Offset(
            (viewport.width - lay.width * scale) / 2f,
            (viewport.height - lay.height * scale) / 2f,
        )
    }

    fun centerBlock() {
        val lay = layout ?: return
        if (viewport.width < 8f || viewport.height < 8f) return
        val addr = selected ?: return
        val block = lay.blocks.firstOrNull { lb ->
            lb.block.instructions.any { it.address == addr } ||
                (addr >= lb.block.start && addr < lb.block.end)
        } ?: return
        pan = Offset(
            viewport.width / 2f - block.rect.center.x * scale,
            viewport.height / 2f - block.rect.center.y * scale,
        )
    }

    LaunchedEffect(layout, viewport, viewModel.viewportSeq) {
        if (layout == null || viewport.width < 8f) return@LaunchedEffect
        when (viewModel.viewportKind) {
            ViewportRequest.CenterSelection -> {
                centerBlock()
                viewModel.consumeViewportRequest()
            }
            ViewportRequest.FitAll -> {
                fitAll()
                viewModel.consumeViewportRequest()
            }
            ViewportRequest.None -> Unit
        }
    }

    val context = LocalContext.current
    val labels = CfgDrawLabels(
        unknown = stringResource(R.string.cfg_edge_unknown),
        trueLabel = stringResource(R.string.cfg_edge_true),
        falseLabel = stringResource(R.string.cfg_edge_false),
        fallthrough = stringResource(R.string.cfg_edge_fallthrough),
        jump = stringResource(R.string.cfg_edge_jump),
        moreInsns = { n -> context.getString(R.string.cfg_more_insns, n) },
        unknownBlock = { addr -> context.getString(R.string.cfg_unknown_block, addr) },
    )

    Box(
        modifier
            .background(surface)
            .onSizeChanged { size: IntSize ->
                viewport = Size(size.width.toFloat(), size.height.toFloat())
            },
    ) {
        if (layout == null || snap == null) {
            Text(stringResource(R.string.cfg_empty), modifier = Modifier.align(Alignment.Center))
        } else {
            if (snap.truncated) {
                Text(
                    stringResource(R.string.cfg_truncated, snap.truncationReason ?: stringResource(R.string.cfg_truncated_default), snap.blockCount),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .testTag(CfgCanvasSemantics.TEST_TAG)
                    .semantics {
                        publishCfgTransform(scale, pan)
                    }
                    .pointerInput(layout, metrics) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var lastCentroid = down.position
                            var lastSpan = 0f
                            var moved = false
                            var sawMulti = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    if (!moved) {
                                        val world = Offset(
                                            (down.position.x - pan.x) / scale,
                                            (down.position.y - pan.y) / scale,
                                        )
                                        val hit = layout.blockAt(world)
                                        if (hit != null) {
                                            val localY = world.y - hit.rect.top - metrics.padInside - metrics.headerHeight - metrics.padInside * 0.5f
                                            val index = (localY / metrics.lineHeight).toInt()
                                            // Clicking an instruction line selects that instruction.
                                            // Clicking the node header/body (no line) means the state after the node:
                                            // the last instruction of the block.
                                            val addr = hit.block.instructions.getOrNull(index)?.address
                                                ?: hit.block.instructions.lastOrNull()?.address
                                                ?: hit.block.start
                                            viewModel.selectAddress(addr)
                                        }
                                    }
                                    break
                                }
                                if (pressed.size >= 2) {
                                    val c = Offset(
                                        (pressed[0].position.x + pressed[1].position.x) / 2f,
                                        (pressed[0].position.y + pressed[1].position.y) / 2f,
                                    )
                                    val span = (pressed[0].position - pressed[1].position).getDistance()
                                    sawMulti = true
                                    if (lastSpan <= 0f) {
                                        // First two-finger event: lock the centroid, do not inherit a one-finger pan.
                                        lastCentroid = c
                                        lastSpan = span
                                        moved = true
                                    } else {
                                        val delta = c - lastCentroid
                                        if (delta.getDistance() > 0.5f) {
                                            pan += delta
                                            moved = true
                                        }
                                        lastCentroid = c
                                        if (lastSpan > 8f && span > 8f) {
                                            val zoom = (span / lastSpan).coerceIn(0.5f, 2f)
                                            val newScale = (scale * zoom).coerceIn(0.12f, 6f)
                                            val world = Offset((c.x - pan.x) / scale, (c.y - pan.y) / scale)
                                            pan = Offset(c.x - world.x * newScale, c.y - world.y * newScale)
                                            scale = newScale
                                            moved = true
                                        }
                                        lastSpan = span
                                    }
                                    pressed.forEach { if (it.positionChanged()) it.consume() }
                                } else {
                                    val pos = pressed[0].position
                                    if (!sawMulti) {
                                        val delta = pos - lastCentroid
                                        if (delta.getDistance() > 3f || moved) {
                                            pan += delta
                                            moved = true
                                            if (pressed[0].positionChanged()) pressed[0].consume()
                                        }
                                    }
                                    lastCentroid = pos
                                    lastSpan = 0f
                                }
                            }
                        }
                    },
            ) {
                val worldView = Rect(
                    -pan.x / scale,
                    -pan.y / scale,
                    (size.width - pan.x) / scale,
                    (size.height - pan.y) / scale,
                ).inflateWorld(96f)
                // LOD is based on on-screen glyph size, not a raw scale cutoff.
                // Layout is already in device pixels, so fit-all for a 3-block graph
                // is often ~0.6; a 0.7 threshold would hide readable instruction text.
                val onScreenLine = metrics.lineHeight * scale
                val lod = when {
                    onScreenLine < 8f -> 0
                    onScreenLine < 12f -> 1
                    else -> 2
                }
                withTransform({
                    translate(pan.x, pan.y)
                    scale(scale, scale, pivot = Offset.Zero)
                }) {
                    for (edge in layout.edges) {
                        val bounds = Rect(
                            minOf(edge.start.x, edge.end.x, edge.control.x),
                            minOf(edge.start.y, edge.end.y, edge.control.y),
                            maxOf(edge.start.x, edge.end.x, edge.control.x),
                            maxOf(edge.start.y, edge.end.y, edge.control.y),
                        )
                        if (!bounds.overlaps(worldView)) continue
                        drawCfgEdge(edge, colors, lod, measurer, metrics, labels)
                    }
                    for (lb in layout.blocks) {
                        if (!lb.rect.overlaps(worldView)) continue
                        drawCfgBlock(
                            lb = lb,
                            metrics = metrics,
                            colors = colors,
                            surface = surface,
                            onSurface = onSurface,
                            selected = selected,
                            simPc = simPc,
                            onPath = pathBlocks.contains(lb.block.start),
                            lod = lod,
                            measurer = measurer,
                            labels = labels,
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.cfg_caption),
                color = colors.muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
            Row(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).background(surface.copy(alpha = 0.86f)),
            ) {
                TextButton(onClick = { fitAll() }) { Text(stringResource(R.string.cfg_fit_all)) }
                TextButton(onClick = { centerBlock() }) { Text(stringResource(R.string.cfg_center_block)) }
            }
        }
    }
}

private data class CfgDrawLabels(
    val unknown: String,
    val trueLabel: String,
    val falseLabel: String,
    val fallthrough: String,
    val jump: String,
    val moreInsns: (Int) -> String,
    val unknownBlock: (String) -> String,
)

private fun DrawScope.drawCfgEdge(
    edge: LaidOutEdge,
    colors: com.icode.asmviewer.ui.theme.AsmColors,
    lod: Int,
    measurer: TextMeasurer,
    metrics: CfgLayoutMetrics,
    labels: CfgDrawLabels,
) {
    val unknown = edge.edge.unknownTarget
    val color = when {
        unknown -> colors.unknownEdge
        edge.edge.type.equals("TrueBranch", ignoreCase = true) -> colors.trueEdge
        edge.edge.type.equals("FalseBranch", ignoreCase = true) -> colors.falseEdge
        else -> colors.muted
    }
    val stroke = if (unknown) 2.6f else 1.8f
    val path = Path().apply {
        moveTo(edge.start.x, edge.start.y)
        quadraticTo(edge.control.x, edge.control.y, edge.end.x, edge.end.y)
    }
    drawPath(path, color, style = Stroke(width = stroke))
    val dir = edge.end - edge.control
    val fallback = edge.end - edge.start
    val use = if (dir.getDistance() < 1f) fallback else dir
    val angle = atan2(use.y, use.x)
    val len = 11f
    val p1 = Offset(edge.end.x - len * cos(angle - 0.45f), edge.end.y - len * sin(angle - 0.45f))
    val p2 = Offset(edge.end.x - len * cos(angle + 0.45f), edge.end.y - len * sin(angle + 0.45f))
    drawLine(color, p1, edge.end, strokeWidth = stroke)
    drawLine(color, p2, edge.end, strokeWidth = stroke)
    if (lod >= 1) {
        val label = when {
            unknown -> edge.edge.label.ifBlank { labels.unknown } + " ?"
            edge.edge.label.isNotBlank() -> edge.edge.label
            edge.edge.type.equals("TrueBranch", ignoreCase = true) -> labels.trueLabel
            edge.edge.type.equals("FalseBranch", ignoreCase = true) -> labels.falseLabel
            edge.edge.type.equals("Fallthrough", ignoreCase = true) -> labels.fallthrough
            edge.edge.type.equals("Unconditional", ignoreCase = true) -> labels.jump
            else -> edge.edge.type
        }
        if (label.isNotBlank()) {
            val style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = metrics.labelFontSizeSp.sp,
                color = color,
            )
            val measured = measurer.measure(label, style)
            val boxW = measured.size.width.toFloat() + 8f
            val boxH = measured.size.height.toFloat() + 4f
            val origin = Offset(edge.labelAt.x - boxW / 2f, edge.labelAt.y - boxH / 2f)
            drawRoundRect(
                color = colors.surfaceAlt.copy(alpha = 0.92f),
                topLeft = origin,
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(4f, 4f),
            )
            drawText(
                measurer,
                label,
                Offset(origin.x + 4f, origin.y + 2f),
                style,
                maxLines = 1,
                size = Size(boxW, boxH),
            )
        }
    }
}

private fun DrawScope.drawCfgBlock(
    lb: LaidOutBlock,
    metrics: CfgLayoutMetrics,
    colors: com.icode.asmviewer.ui.theme.AsmColors,
    surface: Color,
    onSurface: Color,
    selected: Long?,
    simPc: Long?,
    onPath: Boolean,
    lod: Int,
    measurer: TextMeasurer,
    labels: CfgDrawLabels,
) {
    val isCurrent = simPc != null && (
        lb.block.instructions.any { it.address == simPc } ||
            (simPc >= lb.block.start && simPc < lb.block.end)
        )
    val isSelected = selected != null && (
        lb.block.instructions.any { it.address == selected } ||
            (selected >= lb.block.start && selected < lb.block.end)
        )
    val fill = when {
        isCurrent -> colors.currentBlock.copy(alpha = 0.55f)
        isSelected -> colors.selection.copy(alpha = 0.22f)
        onPath -> colors.currentBlock.copy(alpha = 0.22f)
        else -> surface
    }
    val border = when {
        isSelected -> colors.selection
        lb.block.unknownTarget -> colors.unknownEdge
        else -> colors.muted
    }
    drawRoundRect(
        color = fill,
        topLeft = Offset(lb.rect.left, lb.rect.top),
        size = Size(lb.rect.width, lb.rect.height),
        cornerRadius = CornerRadius(8f, 8f),
    )
    drawRoundRect(
        color = border,
        topLeft = Offset(lb.rect.left, lb.rect.top),
        size = Size(lb.rect.width, lb.rect.height),
        cornerRadius = CornerRadius(8f, 8f),
        style = Stroke(width = if (isSelected) 2.4f else 1.2f),
    )
    if (lod == 0) return
    val header = if (lb.block.unknownTarget) {
        labels.unknownBlock(formatAddr(lb.block.start))
    } else {
        "${formatAddr(lb.block.start)}–${formatAddr(lb.block.end)}"
    }
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = metrics.fontSizeSp.sp,
        color = colors.address,
    )
    val pad = metrics.padInside
    var y = lb.rect.top + pad
    val textW = (lb.rect.width - pad * 2f).coerceAtLeast(8f)
    drawText(
        measurer,
        header,
        Offset(lb.rect.left + pad, y),
        style,
        maxLines = 1,
        size = Size(textW, metrics.headerHeight),
    )
    if (lod < 2) return
    y += metrics.headerHeight + pad * 0.5f
    val visible = lb.block.instructions.take(metrics.maxInsnLines)
    for (insn in visible) {
        val lineColor = when {
            insn.address == selected -> colors.selection
            insn.address == simPc -> colors.mnemonic
            else -> onSurface
        }
        val text = buildString {
            append(formatAddr(insn.address))
            append("  ")
            append(insn.mnemonic)
            if (insn.operands.isNotBlank()) {
                append(' ')
                append(insn.operands)
            }
        }
        drawText(
            measurer,
            text,
            Offset(lb.rect.left + pad, y),
            TextStyle(fontFamily = FontFamily.Monospace, fontSize = metrics.fontSizeSp.sp, color = lineColor),
            maxLines = 1,
            size = Size(textW, metrics.lineHeight),
        )
        y += metrics.lineHeight
    }
    if (lb.block.instructions.size > visible.size) {
        drawText(
            measurer,
            labels.moreInsns(lb.block.instructions.size),
            Offset(lb.rect.left + pad, y),
            TextStyle(fontFamily = FontFamily.Monospace, fontSize = metrics.fontSizeSp.sp, color = colors.muted),
            maxLines = 1,
            size = Size(textW, metrics.lineHeight),
        )
    }
}

private fun Rect.inflateWorld(delta: Float) =
    Rect(left - delta, top - delta, right + delta, bottom + delta)
