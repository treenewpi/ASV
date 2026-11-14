package com.icode.asmviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.icode.asmviewer.R
import com.icode.asmviewer.data.InstalledAppEntry
import com.icode.asmviewer.data.InstalledApps
import com.icode.asmviewer.data.ViewerViewModel

@Composable
fun InstalledAppListPane(viewModel: ViewerViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val self = context.packageName
    val apps = remember(self) { InstalledApps.loadVisibleApps(context.packageManager, self) }
    val q = viewModel.installedAppQuery.trim()
    val filtered = remember(apps, q) {
        if (q.isEmpty()) apps
        else {
            val needle = q.lowercase()
            apps.filter { it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle) }
        }
    }
    Column(modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.installed_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = viewModel.installedAppQuery,
            onValueChange = viewModel::updateInstalledAppQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            singleLine = true,
            label = { Text(stringResource(R.string.installed_search)) },
        )
        Text(
            stringResource(R.string.installed_count, filtered.size),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyColumn(modifier = Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                InstalledRow(app) { viewModel.importInstalledApp(app) }
            }
        }
    }
}

@Composable
private fun InstalledRow(app: InstalledAppEntry, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(app.label) },
        supportingContent = { Text(app.packageName, style = MaterialTheme.typography.labelSmall) },
        leadingContent = {
            val bmp = app.icon
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
