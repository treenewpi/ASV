package com.icode.asmviewer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AsmColors(
    val address: Color,
    val mnemonic: Color,
    val operand: Color,
    val trueEdge: Color,
    val falseEdge: Color,
    val unknownEdge: Color,
    val selection: Color,
    val currentBlock: Color,
    val surfaceAlt: Color,
    val muted: Color,
)

val LocalAsmColors = staticCompositionLocalOf {
    AsmColors(
        AddrColorDark, MnemonicColorDark, OperandColorDark,
        TrueEdgeDark, FalseEdgeDark, UnknownEdgeDark, SelectDark, CurrentBlockDark,
        DarkSurfaceAlt, DarkMuted,
    )
}

private val DarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = DarkText,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceAlt,
    onSurfaceVariant = DarkMuted,
)

private val LightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightText,
    onSurface = LightText,
    surfaceVariant = LightSurfaceAlt,
    onSurfaceVariant = LightMuted,
)

@Composable
fun AsmViewerTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val asm = if (darkTheme) {
        AsmColors(
            AddrColorDark, MnemonicColorDark, OperandColorDark,
            TrueEdgeDark, FalseEdgeDark, UnknownEdgeDark, SelectDark, CurrentBlockDark,
            DarkSurfaceAlt, DarkMuted,
        )
    } else {
        AsmColors(
            AddrColorLight, MnemonicColorLight, OperandColorLight,
            TrueEdgeLight, FalseEdgeLight, UnknownEdgeLight, SelectLight, CurrentBlockLight,
            LightSurfaceAlt, LightMuted,
        )
    }
    CompositionLocalProvider(LocalAsmColors provides asm) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = Typography,
            content = content,
        )
    }
}
