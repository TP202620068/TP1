package com.example.tp1.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tp1.R
import com.example.tp1.browser.BrowserProblem
import com.example.tp1.browser.BrowserState
import com.example.tp1.ui.components.EmptyState
import com.example.tp1.ui.components.BrowserBreadcrumbs
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
    message: String? = null,
    browser: BrowserState = BrowserState(),
    onEntryClick: (String) -> Unit = {},
    onAncestor: (Int) -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    val searchLabel = stringResource(R.string.search_label)
    val listState = rememberLazyListState()
    val location = browser.path.lastOrNull()?.uri
    var previousLocation by rememberSaveable { mutableStateOf(location) }
    // Keep the list position on refresh, tab/editor switches and viewer return. New folders start at the top.
    LaunchedEffect(location) {
        if (previousLocation != location) listState.scrollToItem(0)
        previousLocation = location
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().consumeWindowInsets(contentPadding).testTag("browser-list"),
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
        if (!browser.atRoots) {
            item(key = "breadcrumbs") {
                BrowserBreadcrumbs(browser.path, onAncestor)
            }
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
        if ((browser.loading || browser.pending) && files.isEmpty()) {
            item {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.browser_loading))
                    if (browser.pending) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
        }
        browser.problem?.let { problem ->
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(stringResource(R.string.browser_unavailable), stringResource(problem.messageResource()))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
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
        if (files.isEmpty() && !browser.loading && !browser.pending && browser.problem == null) {
            item {
                EmptyState(
                    title = stringResource(if (browser.atRoots) R.string.no_files_title else R.string.browser_empty_title),
                    description = stringResource(if (browser.atRoots) R.string.no_files_description else R.string.browser_empty_description),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp)
                )
            }
        }
        items(files, key = { it.id ?: it.name }) { file ->
            FileRow(file, modifier = Modifier.padding(horizontal = 16.dp).then(
                if (file.id != null) Modifier.clickable(enabled = !browser.opening && !browser.loading, role = Role.Button) {
                    onEntryClick(file.id)
                } else Modifier
            ))
        }
        if (browser.pending && files.isNotEmpty()) {
            item {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

internal fun BrowserProblem.messageResource(): Int = when (this) {
    BrowserProblem.Missing -> R.string.browser_missing
    BrowserProblem.Access -> R.string.browser_access
    BrowserProblem.Provider -> R.string.browser_provider_error
    BrowserProblem.NoViewer -> R.string.browser_no_viewer
}
