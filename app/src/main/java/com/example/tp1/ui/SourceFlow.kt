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
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourcesViewModel
import com.example.tp1.ui.screens.SourceSelectionScreen

@Composable
internal fun SourceFlow(
    viewModel: SourcesViewModel,
    browser: BrowserViewModel,
    chat: ChatViewModel,
    onPick: (SourceKind) -> Unit,
    onOpen: (BrowserEntry) -> Unit,
    onRefresh: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val browsing by browser.state.collectAsState()
    val chatting by chat.state.collectAsState()
    val shellState = rememberSaveableStateHolder()
    LaunchedEffect(state.sources, state.restoring) {
        if (!state.restoring) browser.setSources(state.sources)
    }
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
                    onRetry = { viewModel.refresh() }
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
                    onAddSource = viewModel::selectSources,
                    sourceMessage = state.message?.let { stringResource(it) }
                )
            }
        }
    }
}
