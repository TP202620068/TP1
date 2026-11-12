package com.example.tp1.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.tp1.R
import com.example.tp1.ui.components.FileListItem
import com.example.tp1.browser.BrowserState
import com.example.tp1.browser.BrowserProblem
import com.example.tp1.chat.ChatState
import com.example.tp1.browser.DocumentReference
import com.example.tp1.chat.ChatOpenProblem
import com.example.tp1.indexing.IndexingState
import com.example.tp1.search.SearchState
import com.example.tp1.ui.screens.ChatComposer
import com.example.tp1.ui.screens.ChatScreen
import com.example.tp1.ui.screens.ExplorerScreen
import com.example.tp1.ui.screens.messageResource

@Composable
internal fun TP1App(
    modifier: Modifier = Modifier,
    initialTab: AppTab = AppTab.Explorer,
    files: List<FileListItem> = emptyList(),
    onAddSource: () -> Unit = {},
    sourceMessage: String? = null,
    browser: BrowserState = BrowserState(),
    onEntryClick: (String) -> Unit = {},
    onAncestor: (Int) -> Unit = {},
    onBrowserBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onMessageDismissed: (BrowserProblem) -> Unit = {},
    chat: ChatState = ChatState(),
    onChatSelected: (Boolean) -> Unit = {},
    onChatDraftChange: (String) -> Unit = {},
    onChatPrepare: () -> Unit = {},
    onChatSend: () -> Unit = {},
    onChatStop: () -> Unit = {},
    onChatRetry: () -> Unit = {},
    onChatOpenDocument: (DocumentReference) -> Unit = {},
    onChatOpenProblemDismissed: (ChatOpenProblem) -> Unit = {},
    indexing: IndexingState = IndexingState(),
    onRetryIndexing: (String) -> Unit = {},
    search: SearchState = SearchState(),
    onExplorerSelected: (Boolean) -> Unit = {},
    onSearchDraftChange: (String) -> Unit = {},
    onSearchSubmit: () -> Unit = {},
    onSearchClear: () -> Unit = {},
    onSearchBack: () -> Unit = {},
    onSearchRetry: () -> Unit = {},
    onSearchOpenDocument: (DocumentReference) -> Unit = {},
    onSearchOpenProblemDismissed: (BrowserProblem) -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    val screenState = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val problem = if (search.showingResults) search.openProblem else browser.message
    val problemText = problem?.let { stringResource(it.messageResource()) }
    val retry = stringResource(R.string.retry)
    val chatOpenText = chat.openProblem?.let { stringResource(when (it) {
        ChatOpenProblem.NoViewer -> R.string.browser_no_viewer
        ChatOpenProblem.Unavailable -> R.string.chat_document_unavailable
        ChatOpenProblem.Provider -> R.string.browser_provider_error
    }) }
    LaunchedEffect(chat.openProblem, selectedTab) {
        val problem = chat.openProblem ?: return@LaunchedEffect
        if (selectedTab != AppTab.Chat) return@LaunchedEffect
        try { snackbar.showSnackbar(requireNotNull(chatOpenText), duration = SnackbarDuration.Short) }
        finally { onChatOpenProblemDismissed(problem) }
    }
    LaunchedEffect(selectedTab) {
        onExplorerSelected(selectedTab == AppTab.Explorer)
        onChatSelected(selectedTab == AppTab.Chat)
    }
    LaunchedEffect(problem, selectedTab) {
        if (problem == null || selectedTab != AppTab.Explorer) return@LaunchedEffect
        try {
            if (problem != BrowserProblem.Access || sourceMessage == null) {
                val result = snackbar.showSnackbar(
                    message = requireNotNull(problemText),
                    actionLabel = retry.takeUnless { search.showingResults || problem == BrowserProblem.NoViewer },
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) onRetry()
            }
        } finally {
            if (search.showingResults) onSearchOpenProblemDismissed(problem) else onMessageDismissed(problem)
        }
    }
    val selectTab: (AppTab) -> Unit = { tab ->
        focusManager.clearFocus()
        selectedTab = tab
        onExplorerSelected(tab == AppTab.Explorer)
        onChatSelected(tab == AppTab.Chat)
    }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = selectedTab == AppTab.Explorer && !keyboardVisible && (search.showingResults || !browser.atRoots)) {
        focusManager.clearFocus()
        if (search.showingResults) onSearchBack() else onBrowserBack()
    }

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Box {
                AppHeader(selectedTab, onAddSource = onAddSource)
                if (selectedTab == AppTab.Explorer &&
                    (search.opening || !search.showingResults && (browser.opening || ((browser.loading || browser.pending) && files.isNotEmpty())))) {
                    val progressLabel = stringResource(if (search.opening || browser.opening) R.string.browser_opening else R.string.browser_loading)
                    LinearProgressIndicator(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .semantics { contentDescription = progressLabel })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    if (selectedTab == AppTab.Chat) {
                        ChatComposer(state = chat, onDraftChange = onChatDraftChange,
                            onPrepare = onChatPrepare, onSend = onChatSend, onStop = onChatStop)
                    }
                    AppNavigation(selectedTab = selectedTab, onTabSelected = selectTab)
                }
            }
        }
    ) { padding ->
        screenState.SaveableStateProvider(selectedTab.name) {
            when (selectedTab) {
                AppTab.Explorer -> ExplorerScreen(
                    search = search,
                    onSearchDraftChange = onSearchDraftChange,
                    onSearchSubmit = onSearchSubmit,
                    onSearchClear = onSearchClear,
                    onSearchRetry = onSearchRetry,
                    onSearchOpenDocument = onSearchOpenDocument,
                    files = files,
                    contentPadding = padding,
                    message = sourceMessage,
                    browser = browser,
                    onEntryClick = onEntryClick,
                    onAncestor = onAncestor,
                    onRetry = onRetry,
                    indexing = indexing,
                    onRetryIndexing = onRetryIndexing
                )
                AppTab.Chat -> ChatScreen(state = chat, contentPadding = padding, onRetry = onChatRetry,
                    onOpenDocument = onChatOpenDocument)
            }
        }
    }
}
