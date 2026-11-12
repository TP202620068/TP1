package com.example.tp1.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.tp1.browser.DocumentReference
import com.example.tp1.search.SearchPhase
import com.example.tp1.search.SearchProblem
import com.example.tp1.search.SearchState
import com.example.tp1.ui.components.EmptyState
import com.example.tp1.ui.components.BrowserBreadcrumbs
import com.example.tp1.ui.components.FileListItem
import com.example.tp1.ui.components.FileKind
import com.example.tp1.ui.components.FileRow
import com.example.tp1.ui.components.SectionHeading
import com.example.tp1.ui.components.IndexingStatus
import com.example.tp1.indexing.IndexingState
import com.example.tp1.ui.theme.shellTextFieldColors

@Composable
internal fun ExplorerScreen(
    search: SearchState,
    files: List<FileListItem>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onSearchDraftChange: (String) -> Unit = {},
    onSearchSubmit: () -> Unit = {},
    onSearchClear: () -> Unit = {},
    onSearchRetry: () -> Unit = {},
    onSearchOpenDocument: (DocumentReference) -> Unit = {},
    message: String? = null,
    browser: BrowserState = BrowserState(),
    onEntryClick: (String) -> Unit = {},
    onAncestor: (Int) -> Unit = {},
    onRetry: () -> Unit = {},
    indexing: IndexingState = IndexingState(),
    onRetryIndexing: (String) -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    val searchLabel = stringResource(R.string.search_label)
    val browserListState = rememberLazyListState()
    val resultListState = rememberLazyListState()
    val location = browser.path.lastOrNull()?.uri
    var previousLocation by rememberSaveable { mutableStateOf(location) }
    // Keep the list position on refresh, tab/editor switches and viewer return. New folders start at the top.
    LaunchedEffect(location) {
        if (previousLocation != location) browserListState.scrollToItem(0)
        previousLocation = location
    }
    var previousRequest by rememberSaveable { mutableStateOf(search.request) }
    LaunchedEffect(search.request) {
        if (previousRequest != search.request && search.showingResults) resultListState.scrollToItem(0)
        previousRequest = search.request
    }
    val submit = { focusManager.clearFocus(); onSearchSubmit() }
    LazyColumn(
        state = if (search.showingResults) resultListState else browserListState,
        modifier = modifier.fillMaxSize().consumeWindowInsets(contentPadding)
            .testTag(if (search.showingResults) "search-list" else "browser-list"),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item(key = "search-field") {
            TextField(
                value = search.draft,
                onValueChange = onSearchDraftChange,
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
                leadingIcon = {
                    IconButton(onClick = submit, enabled = search.canSubmit,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search_submit))
                    }
                },
                trailingIcon = {
                    if (search.draft.isNotEmpty() || search.showingResults) IconButton(
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), onClick = {
                        focusManager.clearFocus(); onSearchClear()
                    }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_clear)) }
                },
                isError = search.inputInvalid,
                supportingText = if (search.inputInvalid) { { Text(stringResource(R.string.search_invalid_input)) } } else null,
                shape = CircleShape,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                colors = shellTextFieldColors()
            )
        }
        if (!search.showingResults && !browser.atRoots) {
            item(key = "breadcrumbs") {
                BrowserBreadcrumbs(browser.path, onAncestor)
            }
        }
        if (indexing.sources.isNotEmpty()) {
            item(key = "indexing-status") {
                IndexingStatus(indexing, onRetryIndexing, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
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
        if (search.showingResults) {
            item(key = "submitted-query") {
                Text(stringResource(R.string.search_submitted, requireNotNull(search.submitted)),
                    style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
            }
            if (search.phase != SearchPhase.Idle) {
                item(key = "search-progress") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(if (search.phase == SearchPhase.Searching) R.string.search_loading else R.string.search_checking))
                    }
                }
            } else if (search.problem != null) {
                item(key = "search-error") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(search.problem.messageResource()), color = MaterialTheme.colorScheme.error)
                        if (search.problem != SearchProblem.QueryLimit) {
                            TextButton(onClick = onSearchRetry) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
            } else {
                if (search.rows.isEmpty()) item(key = "search-empty") {
                    EmptyState(stringResource(R.string.search_empty_title), stringResource(R.string.search_empty_description),
                        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp))
                }
                if (search.rows.isNotEmpty()) item(key = "search-heading") {
                    SectionHeading(R.string.search_results, search.rows.size,
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
                }
                items(search.rows, key = { it.document.authority + "/" + it.document.providerId }) { row ->
                    FileRow(FileListItem(row.document.name, FileKind.Document, row.excerpt), metadataMaxLines = 3,
                        modifier = Modifier.padding(horizontal = 16.dp).clickable(enabled = !search.opening, role = Role.Button) {
                            onSearchOpenDocument(row.document)
                        })
                }
            }
        } else {
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
}

internal fun SearchProblem.messageResource(): Int = when (this) {
    SearchProblem.Busy -> R.string.search_busy
    SearchProblem.QueryLimit -> R.string.search_query_limit
    SearchProblem.Retrieval -> R.string.search_failed
    SearchProblem.Changed -> R.string.search_changed
    SearchProblem.Interrupted -> R.string.search_interrupted
}

internal fun BrowserProblem.messageResource(): Int = when (this) {
    BrowserProblem.Missing -> R.string.browser_missing
    BrowserProblem.Access -> R.string.browser_access
    BrowserProblem.Provider -> R.string.browser_provider_error
    BrowserProblem.NoViewer -> R.string.browser_no_viewer
}
