package com.icode.asmviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.icode.asmviewer.R
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.data.formatHex

@Composable
fun FunctionListPane(viewModel: ViewerViewModel, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier) {
        OutlinedTextField(
            value = viewModel.functionQuery,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            singleLine = true,
            label = { Text(stringResource(R.string.fn_search)) },
        )
        val list = viewModel.filteredFunctions
        Text(
            stringResource(R.string.fn_count, list.size),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(modifier = Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.start }) { fn ->
                ListItem(
                    headlineContent = { Text(fn.displayName, fontFamily = FontFamily.Monospace) },
                    supportingContent = {
                        Text(
                            stringResource(R.string.fn_row, formatHex(fn.start, 8), fn.size.toString(), sourceLabel(fn.source)),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.clickable { viewModel.selectFunction(fn) },
                )
            }
        }
    }
}

@Composable
fun sourceLabel(source: String): String = when (source) {
    "symbol" -> stringResource(R.string.source_symbol)
    "unwind" -> stringResource(R.string.source_unwind)
    "entry" -> stringResource(R.string.source_entry)
    "executable_view" -> stringResource(R.string.source_executable_view)
    else -> stringResource(R.string.source_heuristic)
}
