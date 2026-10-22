package com.example.tp1.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.tp1.R
import com.example.tp1.ui.components.EmptyState
import com.example.tp1.ui.components.FileListItem
import com.example.tp1.ui.components.FileRow
import com.example.tp1.ui.components.SectionHeading

@Composable
internal fun SourcesScreen(
    sources: List<FileListItem>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().consumeWindowInsets(contentPadding),
        contentPadding = contentPadding
    ) {
        if (sources.isNotEmpty()) {
            item {
                SectionHeading(
                    label = R.string.selected_sources,
                    count = sources.size,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                )
            }
        }
        if (sources.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.no_sources_title),
                    description = stringResource(R.string.source_selection_unavailable),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp)
                )
            }
        }
        items(sources) { source -> FileRow(source, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
}
