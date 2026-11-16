package com.icode.asmviewer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.icode.asmviewer.R
import com.icode.asmviewer.data.ReplayStep
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.data.formatHex
import com.icode.asmviewer.data.formatWideHex

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReplayDetailsPage(viewModel: ViewerViewModel, modifier: Modifier = Modifier) {
    val report = viewModel.replayReport
    val context = LocalContext.current
    val unknown = stringResource(R.string.replay_reg_unknown)
    var changesOnly by remember { mutableStateOf(false) }

    fun copyAndNotify(text: String) {
        copyText(context, text)
        viewModel.notifyCopied()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("replay_details_page"),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.replay_details),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.closeReplayDetails() },
                        modifier = Modifier.testTag("replay_details_back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (report != null && report.steps.isNotEmpty()) {
                        Text(
                            stringResource(R.string.replay_details_changes_only),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (changesOnly) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .clickable { changesOnly = !changesOnly }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("replay_details_filter"),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (report == null || (report.steps.isEmpty() && report.authenticityIssues().isEmpty() && report.initialAssignments.isEmpty())) {
            Text(
                stringResource(R.string.replay_details_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(padding).padding(16.dp).testTag("replay_details_empty"),
            )
            return@Scaffold
        }
        val steps = if (changesOnly) report.steps.filter { it.hasVisibleChange() } else report.steps
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .testTag("replay_details_list"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            report.authenticityIssues().forEach { res ->
                item(key = "issue-$res") {
                    Text(
                        stringResource(res),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("replay_issue"),
                    )
                }
            }
            if (report.initialAssignments.isNotEmpty()) {
                item(key = "initial-title") {
                    Text(
                        stringResource(R.string.replay_initial_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .testTag("replay_initial_title"),
                    )
                }
                item(key = "initial-body") {
                    Text(
                        stringResource(R.string.replay_initial_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                report.initialAssignments.forEach { asg ->
                    item(key = "initial-${asg.name}") {
                        val line = formatReplayInitial(asg.name, asg.value, asg.known, unknown, asg.sizeBytes)
                        Text(
                            line,
                            style = ReplayMono,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("replay_initial_${asg.name}")
                                .combinedClickable(onClick = {}, onLongClick = { copyAndNotify(line) })
                                .padding(start = 12.dp, top = 1.dp),
                        )
                    }
                }
            }
            itemsIndexed(steps, key = { index, step -> "${step.pc}-$index" }) { _, step ->
                DetailStep(step = step, unknown = unknown, onCopy = { copyAndNotify(it) })
            }
            report.detailPathNotes().forEach { res ->
                item(key = "note-$res") {
                    Text(stringResource(res), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailStep(step: ReplayStep, unknown: String, onCopy: (String) -> Unit) {
    val insn = formatReplayStepInsn(step)
    Column(Modifier.fillMaxWidth().testTag("replay_step_${formatHex(step.pc, 8)}")) {
        val skipSuffix = if (step.skipped) {
            val reason = step.skipReason.trim()
            if (reason.isNotEmpty()) "  $reason" else ""
        } else {
            ""
        }
        Text(
            insn + skipSuffix,
            style = ReplayMono,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { onCopy((insn + skipSuffix).trim()) })
                .padding(top = 4.dp),
        )
        step.registerWrites.forEach { w ->
            val change = formatReplayTransition(w.name, w.beforeKnown, w.before, w.known, w.value, unknown, w.sizeBytes)
            Text(
                change,
                style = ReplayMono,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("replay_change_${formatHex(step.pc, 8)}_${w.name}")
                    .combinedClickable(onClick = {}, onLongClick = { onCopy(change) })
                    .padding(start = 12.dp, top = 1.dp),
            )
        }
        step.memoryWrites.forEach { m ->
            if (m.before == m.after) return@forEach
            val change = "${formatWideHex(m.address)}:${formatReplayMemBytes(m.before, unknown)}→${formatReplayMemBytes(m.after, unknown)}"
            Text(
                change,
                style = ReplayMono,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = { onCopy(change) })
                    .padding(start = 12.dp, top = 1.dp),
            )
        }
    }
}
