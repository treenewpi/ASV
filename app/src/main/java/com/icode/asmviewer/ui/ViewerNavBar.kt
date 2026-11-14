package com.icode.asmviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.icode.asmviewer.R
import com.icode.asmviewer.data.ViewMode
import com.icode.asmviewer.data.ViewerViewModel

private enum class ViewerNavId { Functions, Listing, Cfg, Replay }

@Composable
fun ViewerNavBar(viewModel: ViewerViewModel, modifier: Modifier = Modifier) {
    val onFunctionList = viewModel.selectedFunction == null
    val listingSelected = !onFunctionList && viewModel.viewMode == ViewMode.Flat
    val cfgSelected = !onFunctionList && viewModel.viewMode == ViewMode.Cfg
    val replaySelected = viewModel.replayPanelExpanded && viewModel.replayReport != null
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(
            selected = onFunctionList,
            icon = Icons.AutoMirrored.Filled.List,
            label = stringResource(R.string.nav_functions),
            contentDescription = stringResource(R.string.cd_nav_functions),
            testTag = "nav_functions",
            onClick = { viewModel.clearFunctionSelection() },
            modifier = Modifier.weight(1f),
        )
        NavItem(
            selected = listingSelected,
            icon = Icons.Filled.Code,
            label = stringResource(R.string.nav_listing),
            contentDescription = stringResource(R.string.cd_nav_listing),
            testTag = "nav_listing",
            onClick = {
                if (viewModel.selectedFunction != null) viewModel.updateViewMode(ViewMode.Flat)
            },
            modifier = Modifier.weight(1f),
        )
        NavItem(
            selected = cfgSelected,
            icon = Icons.Filled.AccountTree,
            label = stringResource(R.string.nav_cfg),
            contentDescription = stringResource(R.string.cd_nav_cfg),
            testTag = "nav_cfg",
            onClick = {
                if (viewModel.selectedFunction != null) viewModel.updateViewMode(ViewMode.Cfg)
            },
            modifier = Modifier.weight(1f),
        )
        NavItem(
            selected = replaySelected,
            icon = Icons.Filled.Replay,
            label = stringResource(R.string.nav_replay),
            contentDescription = stringResource(R.string.cd_nav_replay),
            testTag = "nav_replay",
            onClick = { viewModel.replayToSelected() },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NavItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(22.dp))
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
