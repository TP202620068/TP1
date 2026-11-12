package com.example.tp1.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.tp1.R
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.browser.BrowserViewModel
import com.example.tp1.chat.ChatViewModel
import com.example.tp1.browser.DocumentReference
import com.example.tp1.search.SearchViewModel
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourcesViewModel
import com.example.tp1.ui.screens.SourceSelectionScreen

@Composable
internal fun SourceFlow(
    viewModel: SourcesViewModel,
    browser: BrowserViewModel,
    chat: ChatViewModel,
    search: SearchViewModel,
    onPick: (SourceKind) -> Unit,
    onOpen: (BrowserEntry) -> Unit,
    onOpenChatDocument: (DocumentReference) -> Unit,
    onOpenSearchDocument: (DocumentReference) -> Unit,
    onRefresh: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val browsing by browser.state.collectAsState()
    val chatting by chat.state.collectAsState()
    val searching by search.state.collectAsState()
    val indexing by viewModel.indexing.collectAsState()
    val shellState = rememberSaveableStateHolder()
    LaunchedEffect(state.sources, state.restoring) {
        if (!state.restoring) browser.setSources(state.sources)
    }
    LaunchedEffect(state.sources, state.restoring, state.storageReady, state.selecting) {
        search.setSources(state.sources, ready = !state.restoring && state.storageReady)
        if (state.selecting || state.restoring) search.setSelected(false)
    }
    LaunchedEffect(indexing) { search.setIndexing(indexing) }
    Surface(color = MaterialTheme.colorScheme.background) {
        when {
            state.restoring -> Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.selecting -> {
                BackHandler(onBack = viewModel::closeSelection)
                SourceSelectionScreen(
                    sources = state.sources,
                    busy = state.busy,
                    storageReady = state.storageReady,
                    canClose = state.canCloseSelection,
                    message = state.message?.let { stringResource(it) },
                    onPick = onPick,
                    onRemove = viewModel::remove,
                    onClose = viewModel::closeSelection,
                    onRetry = { viewModel.refresh() },
                    indexing = indexing,
                    onRetryIndexing = viewModel::retryIndexing
                )
            }
            else -> shellState.SaveableStateProvider("shell") {
                TP1App(
                    chat = chatting,
                    onChatSelected = chat::setSelected,
                    onChatDraftChange = chat::editDraft,
                    onChatPrepare = chat::prepare,
                    onChatSend = chat::send,
                    onChatStop = chat::stop,
                    onChatRetry = chat::retry,
                    onChatOpenDocument = onOpenChatDocument,
                    onChatOpenProblemDismissed = chat::dismissOpenProblem,
                    files = browsing.entries.map { it.toFileListItem() },
                    browser = browsing,
                    onEntryClick = { id ->
                        browsing.entries.firstOrNull { it.uri == id }?.let { entry ->
                            if (entry.directory) browser.enter(entry) else onOpen(entry)
                        }
                    },
                    onAncestor = browser::ancestor,
                    onBrowserBack = browser::back,
                    onRetry = onRefresh,
                    onMessageDismissed = browser::dismissMessage,
                    onAddSource = { search.setSelected(false); viewModel.selectSources() },
                    sourceMessage = state.message?.let { stringResource(it) },
                    indexing = indexing,
                    onRetryIndexing = viewModel::retryIndexing,
                    search = searching,
                    onExplorerSelected = search::setSelected,
                    onSearchDraftChange = search::editDraft,
                    onSearchSubmit = search::submit,
                    onSearchClear = search::clear,
                    onSearchBack = search::exitResults,
                    onSearchRetry = search::retry,
                    onSearchOpenDocument = onOpenSearchDocument,
                    onSearchOpenProblemDismissed = search::dismissOpenProblem
                )
            }
        }
    }
}
