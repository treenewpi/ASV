package com.icode.asmviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.icode.asmviewer.R

@Composable
fun HomePane(
    onOpenApk: () -> Unit,
    onOpenApkAnyType: () -> Unit,
    onOpenInstalled: () -> Unit,
    onOpenElf: () -> Unit,
    onSample: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Brand name lives only in the top app bar. The content area leads with the
        // three open entries; the intro stays as context, not a second title.
        Text(
            stringResource(R.string.home_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        EntryCard(Icons.Filled.Android, stringResource(R.string.home_open_apk), stringResource(R.string.home_open_apk_sub), onOpenApk, Modifier.testTag("home_open_apk"))
        EntryCard(Icons.Filled.Apps, stringResource(R.string.home_installed), stringResource(R.string.home_installed_sub), onOpenInstalled, Modifier.testTag("home_installed"))
        EntryCard(Icons.Filled.Memory, stringResource(R.string.home_open_elf), stringResource(R.string.home_open_elf_sub), onOpenElf, Modifier.testTag("home_open_elf"))
        TextButton(onClick = onOpenApkAnyType, modifier = Modifier.align(Alignment.Start).testTag("home_open_apk_any")) {
            Text(stringResource(R.string.home_browse_all))
        }
        TextButton(onClick = onSample, modifier = Modifier.align(Alignment.Start).testTag("home_sample")) {
            Text(stringResource(R.string.home_sample))
        }
    }
}

@Composable
private fun EntryCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
