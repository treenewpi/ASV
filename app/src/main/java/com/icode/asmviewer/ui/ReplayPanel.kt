package com.icode.asmviewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.icode.asmviewer.R
import com.icode.asmviewer.data.ReplayRegister
import com.icode.asmviewer.data.ReplayReport
import com.icode.asmviewer.data.ReplayStep
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.data.changedRegisters
import com.icode.asmviewer.data.formatHex
import com.icode.asmviewer.data.formatWideHex

internal val ReplayMono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 15.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

private val FlagNames = setOf("n", "z", "c", "v", "nzcv", "cpsr", "pstate")

/**
 * Compact 0x-prefixed hex for a register, flag, or initial value.
 * Uses two's complement of the value's width. Unknown stays [unknownLabel], never 0.
 */
fun replayValueWidthBits(name: String, sizeBytes: Int = 0): Int {
    val n = name.trim().lowercase()
    if (n in setOf("n", "z", "c", "v")) return 1
    if (n.matches(Regex("""w\d+""")) || n in setOf("nzcv", "cpsr", "pstate", "spsr")) return 32
    if (sizeBytes in 1..8) return sizeBytes * 8
    return 64
}

fun formatReplayScalar(
    known: Boolean,
    value: Long,
    unknownLabel: String,
    name: String = "",
    sizeBytes: Int = 0,
): String {
    if (!known) return unknownLabel
    val bits = replayValueWidthBits(name, sizeBytes).coerceIn(1, 64)
    val mask = if (bits >= 64) ULong.MAX_VALUE else (1UL shl bits) - 1UL
    val u = value.toULong() and mask
    return "0x" + u.toString(16)
}

/** One-step change, e.g. x0:0x5→0xc or z:0x0→0x1. */
fun formatReplayTransition(
    name: String,
    beforeKnown: Boolean,
    before: Long?,
    afterKnown: Boolean,
    after: Long,
    unknownLabel: String,
    sizeBytes: Int = 0,
): String {
    val b = formatReplayScalar(beforeKnown && before != null, before ?: 0L, unknownLabel, name, sizeBytes)
    val a = formatReplayScalar(afterKnown, after, unknownLabel, name, sizeBytes)
    return "$name:$b→$a"
}

fun formatReplayMemBytes(hex: String, unknownLabel: String): String {
    val raw = hex.trim()
    if (raw.isEmpty()) return unknownLabel
    val digits = if (raw.startsWith("0x") || raw.startsWith("0X")) raw.substring(2) else raw
    if (digits.isEmpty()) return unknownLabel
    return "0x" + digits.lowercase()
}

fun formatReplayInitial(name: String, value: Long, known: Boolean, unknownLabel: String, sizeBytes: Int = 0): String {
    return "$name:${formatReplayScalar(known, value, unknownLabel, name, sizeBytes)}"
}

fun formatReplayRegLine(name: String, known: Boolean, value: Long, unknownLabel: String): String {
    val v = if (known) formatWideHex(value) else unknownLabel
    return "$name  $v"
}

fun formatReplayStepInsn(step: ReplayStep): String =
    "${formatHex(step.pc, 8)}  ${step.mnemonic}  ${step.operands}".trimEnd()

fun ReplayStep.hasVisibleChange(): Boolean {
    if (skipped) return true
    if (registerWrites.any { !it.known || !it.beforeKnown || it.before == null || it.before != it.value }) return true
    if (memoryWrites.any { it.before != it.after }) return true
    return false
}

fun ReplayReport.authenticityIssues(): List<Int> = buildList {
    when (kind) {
        "infeasible" -> add(R.string.replay_fail_infeasible)
        "timeout" -> add(R.string.replay_fail_timeout)
        "cancelled" -> add(R.string.replay_fail_cancelled)
        "unsupported", "unavailable" -> add(R.string.replay_fail_unsupported)
        "not_reached" -> add(R.string.replay_fail_reach)
        else -> if (!ok) add(R.string.replay_fail_reach)
    }
    if (ok && steps.isEmpty()) add(R.string.replay_status_no_exec)
    if (unknownIndirect) add(R.string.replay_uncertain)
    if (skippedCalls) add(R.string.replay_skipped_calls)
}

fun ReplayReport.detailPathNotes(): List<Int> = buildList {
    if (pathAmbiguous || !uniquePath) add(R.string.replay_multi_path)
    if (containsLoopHeader) add(R.string.replay_loop_once)
}

@Composable
fun ReplayPanel(viewModel: ViewerViewModel, modifier: Modifier = Modifier) {
    val report = viewModel.replayReport ?: return
    val context = LocalContext.current
    val unknown = stringResource(R.string.replay_reg_unknown)
    val changed = remember(report) { report.changedRegisters() }
    val flags = changed.filter { it.name.lowercase() in FlagNames }
    val others = changed.filter { it.name.lowercase() !in FlagNames }
    val hasDetails = report.steps.isNotEmpty() || report.authenticityIssues().isNotEmpty()

    fun copyAndNotify(text: String) {
        copyText(context, text)
        viewModel.notifyCopied()
    }

    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("replay_panel"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            report.authenticityIssues().forEach { res ->
                Text(
                    stringResource(res),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("replay_issue"),
                )
            }
            if (report.initialAssignments.isNotEmpty()) {
                Text(
                    stringResource(R.string.replay_initial_used),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("replay_initial_used"),
                )
            }
            if (others.isEmpty() && flags.isEmpty()) {
                Text(
                    stringResource(R.string.replay_regs_none),
                    style = ReplayMono,
                    modifier = Modifier.testTag("replay_regs_none"),
                )
            } else {
                others.forEach { RegisterRow(it, unknown, onCopy = { copyAndNotify(it) }) }
                flags.forEach { RegisterRow(it, unknown, onCopy = { copyAndNotify(it) }) }
            }
        }
        val entryModifier = Modifier
            .testTag("replay_details_toggle")
            .then(
                if (hasDetails) {
                    Modifier.clickable { viewModel.openReplayDetails() }
                } else {
                    Modifier.alpha(0.4f)
                },
            )
            .padding(horizontal = 4.dp, vertical = 4.dp)
        Column(
            entryModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = stringResource(R.string.replay_details),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.replay_details),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RegisterRow(reg: ReplayRegister, unknown: String, onCopy: (String) -> Unit) {
    val line = formatReplayRegLine(reg.name, reg.known, reg.value, unknown)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("replay_reg_${reg.name}")
            .combinedClickable(onClick = {}, onLongClick = { onCopy(line) })
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(reg.name, style = ReplayMono, modifier = Modifier.width(36.dp), maxLines = 1)
        Text(
            if (reg.known) formatWideHex(reg.value) else unknown,
            style = ReplayMono,
            maxLines = 1,
        )
    }
}

internal fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("register", text))
}
