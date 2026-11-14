package com.icode.asmviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.icode.asmviewer.R
import com.icode.asmviewer.data.ViewerViewModel

@Composable
fun LibraryPickerPane(viewModel: ViewerViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Text(
            viewModel.pendingLibraryTitle.ifBlank { stringResource(R.string.libs_title_default) },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            stringResource(R.string.libs_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(modifier = Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(viewModel.pendingLibraries, key = { it.entryName + "@" + it.containerLabel }) { lib ->
                ListItem(
                    headlineContent = { Text(lib.soName, fontFamily = FontFamily.Monospace) },
                    supportingContent = {
                        val size = if (lib.size > 0) stringResource(R.string.libs_size_bytes, lib.size.toString()) else stringResource(R.string.libs_size_unknown)
                        Text("${lib.abi}  ·  ${lib.containerLabel}  ·  $size", style = MaterialTheme.typography.labelSmall)
                    },
                    modifier = Modifier.clickable { viewModel.openSelectedLibrary(lib) },
                )
            }
        }
    }
}
