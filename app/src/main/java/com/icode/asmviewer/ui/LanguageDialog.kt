package com.icode.asmviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import com.icode.asmviewer.R
import com.icode.asmviewer.data.AppLanguage
import com.icode.asmviewer.data.AppLocale

@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppLocale.storedLanguage(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lang_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.lang_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LangRow(AppLanguage.System, selected, stringResource(R.string.lang_follow_system), "lang_system") { selected = it }
                LangRow(AppLanguage.Chinese, selected, stringResource(R.string.lang_zh), "lang_zh") { selected = it }
                LangRow(AppLanguage.English, selected, stringResource(R.string.lang_en), "lang_en") { selected = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AppLocale.setLanguage(context, selected)
                onDismiss()
            }, modifier = Modifier.testTag("lang_confirm")) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun LangRow(value: AppLanguage, selected: AppLanguage, label: String, tag: String, onSelect: (AppLanguage) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = value == selected, onClick = { onSelect(value) }, role = Role.RadioButton).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
