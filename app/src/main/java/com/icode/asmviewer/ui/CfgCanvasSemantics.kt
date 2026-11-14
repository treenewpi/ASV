package com.icode.asmviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import java.util.Locale

/**
 * Read-only CFG viewport transform published for tests.
 * Values are in canvas pixels (the same space used by pointerInput).
 */
object CfgCanvasSemantics {
    const val TEST_TAG = "cfg_canvas"

    val Scale = SemanticsPropertyKey<Float>("CfgTransformScale")
    val PanX = SemanticsPropertyKey<Float>("CfgTransformPanX")
    val PanY = SemanticsPropertyKey<Float>("CfgTransformPanY")

    fun description(scale: Float, pan: Offset): String =
        String.format(Locale.US, "cfg_transform scale=%.4f panX=%.2f panY=%.2f", scale, pan.x, pan.y)
}

var SemanticsPropertyReceiver.cfgTransformScale by CfgCanvasSemantics.Scale
var SemanticsPropertyReceiver.cfgTransformPanX by CfgCanvasSemantics.PanX
var SemanticsPropertyReceiver.cfgTransformPanY by CfgCanvasSemantics.PanY

fun SemanticsPropertyReceiver.publishCfgTransform(scale: Float, pan: Offset) {
    cfgTransformScale = scale
    cfgTransformPanX = pan.x
    cfgTransformPanY = pan.y
    contentDescription = CfgCanvasSemantics.description(scale, pan)
}
