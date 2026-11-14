package com.icode.asmviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import com.icode.asmviewer.R
import com.icode.asmviewer.data.AddressKind
import com.icode.asmviewer.data.AddressResolve
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.data.formatWideHex
import com.icode.asmviewer.data.parseHexOffset

@Composable
fun JumpDialog(viewModel: ViewerViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(viewModel.addressKind) }
    val map = viewModel.image?.addressMap
    val parsed = parseHexOffset(text)
    val preview = if (parsed != null && map != null) {
        when (val r = map.resolve(kind, parsed, requireCode = false, requireAligned = false)) {
            is AddressResolve.Ok -> r
            is AddressResolve.Err -> null
        }
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jump_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KindRow(AddressKind.Rva, kind, stringResource(R.string.jump_kind_rva), stringResource(R.string.jump_kind_rva_hint), "jump_kind_rva") { kind = it }
                KindRow(AddressKind.FileOffset, kind, stringResource(R.string.jump_kind_file), stringResource(R.string.jump_kind_file_hint), "jump_kind_file") { kind = it }
                KindRow(AddressKind.Va, kind, stringResource(R.string.jump_kind_va), stringResource(R.string.jump_kind_va_hint), "jump_kind_va") { kind = it }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.jump_input_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("jump_input"),
                )
                if (preview != null) {
                    val fileText = preview.fileOffset?.let { formatWideHex(it) }
                    Text(
                        if (fileText != null) {
                            stringResource(R.string.jump_preview, formatWideHex(preview.rva), fileText, formatWideHex(preview.va))
                        } else {
                            stringResource(R.string.jump_preview_no_file, formatWideHex(preview.rva), formatWideHex(preview.va))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("jump_preview"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.updateAddressKind(kind)
                    viewModel.jumpToTypedAddress(text, kind)
                    onDismiss()
                },
                modifier = Modifier.testTag("jump_confirm"),
            ) { Text(stringResource(R.string.jump_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("jump_cancel")) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun KindRow(
    value: AddressKind,
    selected: AddressKind,
    title: String,
    hint: String,
    testTag: String,
    onSelect: (AddressKind) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = value == selected, onClick = { onSelect(value) }, role = Role.RadioButton)
            .testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
