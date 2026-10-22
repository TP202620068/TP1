package com.example.tp1.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import com.example.tp1.ui.components.FileListItem
import com.example.tp1.ui.screens.ChatComposer
import com.example.tp1.ui.screens.ChatScreen
import com.example.tp1.ui.screens.ExplorerScreen

@Composable
internal fun TP1App(
    modifier: Modifier = Modifier,
    initialTab: AppTab = AppTab.Explorer,
    files: List<FileListItem> = emptyList(),
    onAddSource: () -> Unit = {},
    sourceMessage: String? = null
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    val screenState = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val selectTab: (AppTab) -> Unit = { tab ->
        focusManager.clearFocus()
        selectedTab = tab
    }

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { AppHeader(selectedTab, onAddSource = onAddSource) },
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
                    message = sourceMessage
                )
                AppTab.Chat -> ChatScreen(contentPadding = padding)
            }
        }
    }
}
