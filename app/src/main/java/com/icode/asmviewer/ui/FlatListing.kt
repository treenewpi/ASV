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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.icode.asmviewer.data.ListingKind
import com.icode.asmviewer.data.ListingRow
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

fun formatListingCopy(row: ListingRow): String {
    val insn = row.insn
    if (row.kind == ListingKind.Insn && insn != null) return formatInstructionLine(insn)
    if (row.kind == ListingKind.FunctionLabel) {
        return "${row.subtitle}  ${row.title}".trim()
    }
    val end = row.address + row.size.coerceAtLeast(0)
    return "${formatHex(row.address, 8)}..${formatHex(end, 8)}  ${row.kind.name.lowercase()}".trim()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FlatListing(viewModel: ViewerViewModel, modifier: Modifier = Modifier) {
    val colors = LocalAsmColors.current
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val rows = viewModel.listingRows
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val col = remember(measurer, density) {
        val px = measurer.measure("0", style = ListingTextStyle).size.width
        { chars: Int -> with(density) { (px * chars).toDp() } }
    }
    var pendingPrepend by remember { mutableStateOf<Pair<String, Int>?>(null) }

    LaunchedEffect(viewModel.listingAnchorSeq) {
        val va = viewModel.listingAnchorAddress ?: return@LaunchedEffect
        val rows = viewModel.listingRows
        val label = rows.indexOfFirst { it.kind == ListingKind.FunctionLabel && it.address == va }
        val covering = rows.indexOfFirst { it.kind != ListingKind.FunctionLabel && it.covers(va) }
        val index = when {
            label >= 0 -> label
            covering >= 0 -> covering
            else -> rows.indexOfFirst { it.address == va }
        }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
    }

    LaunchedEffect(rows, viewModel.listingLoadingBefore) {
        val pending = pendingPrepend
        if (pending != null) {
            val idx = rows.indexOfFirst { it.key == pending.first }
            if (idx >= 0) {
                listState.scrollToItem(idx, pending.second)
                pendingPrepend = null
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val current = viewModel.listingRows
            val firstInsn = visible.firstNotNullOfOrNull { info ->
                current.getOrNull(info.index)?.takeIf { it.kind == ListingKind.Insn }
            }?.address
            val firstContent = firstInsn ?: visible.firstNotNullOfOrNull { info ->
                current.getOrNull(info.index)?.takeIf { it.kind != ListingKind.FunctionLabel }
            }?.address
            val sel = viewModel.selectedAddress
            val selVisible = sel != null && visible.any { info ->
                val row = current.getOrNull(info.index)
                row != null && row.kind != ListingKind.FunctionLabel && row.covers(sel)
            }
            firstContent to selVisible
        }.collect { (anchor, selVisible) ->
            viewModel.updateListingViewport(anchor, selVisible)
        }
    }

    LaunchedEffect(rows.size, viewModel.listingReachedStart, viewModel.listingReachedEnd) {
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo
            Pair(info.firstOrNull(), info.lastOrNull())
        }.collect { (first, last) ->
            if (first != null && first.index <= 8 &&
                !viewModel.listingReachedStart &&
                !viewModel.listingLoadingBefore &&
                rows.isNotEmpty()
            ) {
                val key = rows.getOrNull(first.index)?.key
                if (key != null) pendingPrepend = key to first.offset
                viewModel.loadListingBefore()
            }
            if (last != null && last.index >= (rows.lastIndex - 8).coerceAtLeast(0) &&
                !viewModel.listingReachedEnd &&
                !viewModel.listingLoadingAfter &&
                rows.isNotEmpty()
            ) {
                viewModel.loadListingAfter()
            }
        }
    }

    Column(modifier) {
        val fn = viewModel.selectedFunction
        if (fn != null) {
            Text(
                stringResource(R.string.listing_selected_fn, fn.displayName, formatHex(fn.start, 8)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag("listing_selected_fn"),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .testTag("listing_list"),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row.kind) {
                    ListingKind.FunctionLabel -> FunctionBoundaryRow(row, colors, viewModel)
                    ListingKind.Insn -> InstructionRow(row, viewModel, colors, col, context)
                    else -> MarkerRow(row, viewModel, colors, context)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FunctionBoundaryRow(
    row: ListingRow,
    colors: com.icode.asmviewer.ui.theme.AsmColors,
    viewModel: ViewerViewModel,
) {
    val context = LocalContext.current
    val label = "${row.title}  ${row.subtitle}".trim()
    Text(
        label,
        color = colors.mnemonic,
        style = ListingTextStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("listing_fn_${row.title}")
            .combinedClickable(
                onClick = { viewModel.selectFunctionLabel(row.title, row.address) },
                onLongClick = {
                    copyText(context, formatListingCopy(row))
                    viewModel.notifyCopied()
                },
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkerRow(
    row: ListingRow,
    viewModel: ViewerViewModel,
    colors: com.icode.asmviewer.ui.theme.AsmColors,
    context: Context,
) {
    val selected = row.covers(viewModel.selectedAddress ?: Long.MIN_VALUE)
    val kindText = when (row.kind) {
        ListingKind.Unmapped -> stringResource(R.string.listing_marker_unmapped)
        ListingKind.Gap -> stringResource(R.string.listing_marker_gap)
        else -> stringResource(R.string.listing_marker_data)
    }
    val end = row.address + row.size.coerceAtLeast(0)
    val text = "${formatHex(row.address, 8)}..${formatHex(end, 8)}  $kindText"
    Text(
        text,
        color = colors.muted,
        style = ListingTextStyle,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.selection.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface)
            .testTag("listing_marker_${formatHex(row.address, 8)}")
            .combinedClickable(
                onClick = { viewModel.selectAddress(row.address) },
                onLongClick = {
                    copyText(context, formatListingCopy(row))
                    viewModel.notifyCopied()
                },
            )
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstructionRow(
    row: ListingRow,
    viewModel: ViewerViewModel,
    colors: com.icode.asmviewer.ui.theme.AsmColors,
    col: (Int) -> androidx.compose.ui.unit.Dp,
    context: Context,
) {
    val insn = row.insn ?: return
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

fun copyInstruction(context: Context, insn: InstructionSnap) {
    copyText(context, formatInstructionLine(insn))
}

