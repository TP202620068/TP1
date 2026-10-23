package com.example.tp1.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.tp1.R
import com.example.tp1.ui.components.FileListItem
import com.example.tp1.browser.BrowserState
import com.example.tp1.browser.BrowserProblem
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
    onMessageDismissed: (BrowserProblem) -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    val screenState = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val problem = browser.message
    val problemText = problem?.let { stringResource(it.messageResource()) }
    val retry = stringResource(R.string.retry)
    LaunchedEffect(problem, selectedTab) {
        if (problem == null || selectedTab != AppTab.Explorer) return@LaunchedEffect
        try {
            if (problem != BrowserProblem.Access || sourceMessage == null) {
                val result = snackbar.showSnackbar(
                    message = requireNotNull(problemText),
                    actionLabel = retry.takeUnless { problem == BrowserProblem.NoViewer },
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) onRetry()
            }
        } finally {
            onMessageDismissed(problem)
        }
    }
    val selectTab: (AppTab) -> Unit = { tab ->
        focusManager.clearFocus()
        selectedTab = tab
    }
    BackHandler(enabled = selectedTab == AppTab.Explorer && !browser.atRoots, onBack = onBrowserBack)

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Box {
                AppHeader(selectedTab, onAddSource = onAddSource)
                if (selectedTab == AppTab.Explorer &&
                    (browser.opening || ((browser.loading || browser.pending) && files.isNotEmpty()))) {
                    val progressLabel = stringResource(if (browser.opening) R.string.browser_opening else R.string.browser_loading)
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
                        ChatComposer(draft = draft, onDraftChange = { draft = it })
                    }
                    AppNavigation(selectedTab = selectedTab, onTabSelected = selectTab)
                }
            }
        }
    ) { padding ->
        screenState.SaveableStateProvider(selectedTab.name) {
            when (selectedTab) {
                AppTab.Explorer -> ExplorerScreen(
                    query = query,
                    onQueryChange = { query = it },
                    files = files,
                    contentPadding = padding,
                    message = sourceMessage,
                    browser = browser,
                    onEntryClick = onEntryClick,
                    onAncestor = onAncestor,
                    onRetry = onRetry
                )
                AppTab.Chat -> ChatScreen(contentPadding = padding)
            }
        }
    }
}
