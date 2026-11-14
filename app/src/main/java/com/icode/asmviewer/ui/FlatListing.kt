package com.icode.asmviewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.icode.asmviewer.R
import com.icode.asmviewer.data.InstructionSnap
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.data.formatHex
import com.icode.asmviewer.ui.theme.LocalAsmColors

private val ListingTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 15.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/** Stable one-line copy format: address, bytes, mnemonic, operands. */
fun formatInstructionLine(insn: InstructionSnap): String {
    val bytes = insn.bytes.ifBlank { "--------" }
    return "${formatHex(insn.address, 8)}  $bytes  ${insn.mnemonic}  ${insn.operands}".trimEnd()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FlatListing(viewModel: ViewerViewModel, modifier: Modifier = Modifier) {
    val snap = viewModel.snapshot
    val colors = LocalAsmColors.current
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val insns = snap?.instructionsInOrder.orEmpty()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val col = remember(measurer, density) {
        val px = measurer.measure("0", style = ListingTextStyle).size.width
        { chars: Int -> with(density) { (px * chars).toDp() } }
    }

    LaunchedEffect(viewModel.selectedAddress, insns.size) {
        val addr = viewModel.selectedAddress ?: return@LaunchedEffect
        val index = insns.indexOfFirst { it.address == addr }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(modifier) {
        if (snap?.truncated == true) {
            Text(
                stringResource(R.string.cfg_truncated, snap.truncationReason ?: stringResource(R.string.cfg_truncated_default), snap.blockCount),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        val fn = viewModel.selectedFunction
        if (fn != null) {
            Text(
                stringResource(R.string.fn_meta, fn.displayName, formatHex(fn.start, 8), sourceLabel(fn.source), insns.size),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .testTag("listing_list"),
        ) {
            items(insns, key = { it.address }) { insn ->
                val selected = insn.address == viewModel.selectedAddress
                val addrText = formatHex(insn.address, 8)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) colors.selection.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface)
                        .testTag("listing_row_$addrText")
                        .combinedClickable(
                            onClick = { viewModel.selectAddress(insn.address) },
                            onLongClick = {
                                copyInstruction(context, insn)
                                viewModel.notifyCopied()
                            },
                        )
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        addrText,
                        color = colors.address,
                        style = ListingTextStyle,
                        maxLines = 1,
                        modifier = Modifier.width(col(10)),
                    )
                    Text(
                        insn.bytes.ifBlank { "--------" },
                        color = colors.muted,
                        style = ListingTextStyle,
                        maxLines = 1,
                        modifier = Modifier.widthIn(min = col(8)),
                    )
                    Text(
                        insn.mnemonic,
                        color = colors.mnemonic,
                        style = ListingTextStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                        modifier = Modifier.widthIn(min = col(8)),
                    )
                    Text(
                        insn.operands,
                        color = colors.operand,
                        style = ListingTextStyle,
                        maxLines = 1,
                    )
                    if (insn.branchTarget != null) {
                        val targetText = formatHex(insn.branchTarget, 8)
                        Text(
                            "→ $targetText",
                            color = colors.mnemonic,
                            style = ListingTextStyle,
                            maxLines = 1,
                            modifier = Modifier
                                .testTag("listing_branch_$addrText")
                                .clickable { viewModel.jumpToBranch(insn) },
                        )
                    }
                }
            }
        }
    }
}

fun copyInstruction(context: Context, insn: InstructionSnap) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("assembly", formatInstructionLine(insn)))
}
