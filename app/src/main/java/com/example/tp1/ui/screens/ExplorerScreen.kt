package com.example.tp1.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tp1.R
import com.example.tp1.ui.components.EmptyState
import com.example.tp1.ui.components.FileListItem
import com.example.tp1.ui.components.FileRow
import com.example.tp1.ui.components.SectionHeading
import com.example.tp1.ui.theme.shellTextFieldColors

@Composable
internal fun ExplorerScreen(
    query: String,
    files: List<FileListItem>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit = {},
    message: String? = null
) {
    val focusManager = LocalFocusManager.current
    val searchLabel = stringResource(R.string.search_label)
    LazyColumn(
        modifier = modifier.fillMaxSize().consumeWindowInsets(contentPadding),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { contentDescription = searchLabel },
                placeholder = {
                    Text(
                        stringResource(R.string.search_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                shape = CircleShape,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                colors = shellTextFieldColors()
            )
        }
        message?.let { text ->
            item {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        if (files.isNotEmpty()) {
            item {
                SectionHeading(
                    label = R.string.files,
                    count = files.size,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
        if (files.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.no_files_title),
                    description = stringResource(R.string.no_files_description),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp)
                )
            }
        }
        items(files) { file -> FileRow(file, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
}
