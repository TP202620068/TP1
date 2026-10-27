package com.example.tp1.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.tp1.R
import com.example.tp1.chat.ChatPhase
import com.example.tp1.chat.ChatPreparation
import com.example.tp1.chat.ChatProblem
import com.example.tp1.chat.ChatState
import com.example.tp1.chat.ChatTurn
import com.example.tp1.inference.FinishReason
import com.example.tp1.ui.theme.TP1Theme
import com.example.tp1.ui.theme.shellTextFieldColors
import kotlinx.coroutines.flow.collect

@Composable
internal fun ChatScreen(
    state: ChatState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    val list = rememberLazyListState()
    var followOutput by rememberSaveable { mutableStateOf(true) }
    var previousCount by remember { mutableIntStateOf(state.turns.size) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) followOutput = !list.canScrollForward
        }
    }
    LaunchedEffect(state.turns) {
        val added = state.turns.size > previousCount
        previousCount = state.turns.size
        if ((followOutput || added) && !list.isScrollInProgress) list.scrollToItem(state.turns.size + 1)
    }
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxSize().consumeWindowInsets(contentPadding),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "notice") {
            Text(stringResource(R.string.chat_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        items(state.turns, key = { it.id }) { turn ->
            ChatTurnContent(turn, retryEnabled = !state.busy && state.preparation == ChatPreparation.Ready &&
                turn.id == state.turns.last().id, onRetry = onRetry)
        }
        item(key = "tail") { Spacer(Modifier.height(1.dp)) }
    }
}

@Composable
private fun ChatTurnContent(turn: ChatTurn, retryEnabled: Boolean, modifier: Modifier = Modifier, onRetry: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large,
            modifier = Modifier.align(Alignment.End).fillMaxWidth(0.9f)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.chat_you), style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(turn.input, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.chat_assistant), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            if (turn.answer.isNotEmpty()) SelectionContainer {
                Text(turn.answer, style = MaterialTheme.typography.bodyLarge)
            }
            val status = when {
                turn.running -> R.string.chat_generating
                turn.failed -> R.string.chat_response_error
                turn.finish == FinishReason.LENGTH -> R.string.chat_length
                turn.finish == FinishReason.CANCELLED -> R.string.chat_cancelled
                turn.finish == FinishReason.DEADLINE -> R.string.chat_deadline
                turn.finish == FinishReason.NATIVE_FAILURE -> R.string.chat_response_error
                else -> null
            }
            if (status != null) Text(stringResource(status), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (turn.canRetry && retryEnabled) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
internal fun ChatComposer(
    state: ChatState,
    modifier: Modifier = Modifier,
    onDraftChange: (String) -> Unit = {},
    onPrepare: () -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {}
) {
    val inputLabel = stringResource(R.string.chat_input_label)
    Column(modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        if (state.preparation == ChatPreparation.Ready && state.phase == ChatPhase.Idle) {
            Text(stringResource(R.string.chat_ready), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (state.preparation != ChatPreparation.Ready) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val preparing = state.preparation in setOf(ChatPreparation.Acquiring, ChatPreparation.Loading, ChatPreparation.Closing)
                if (preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(when (state.preparation) {
                    ChatPreparation.Acquiring -> R.string.chat_acquiring
                    ChatPreparation.Loading -> R.string.chat_loading
                    ChatPreparation.Closing -> R.string.chat_closing
                    ChatPreparation.Error -> R.string.chat_prepare_error
                    else -> R.string.chat_prepare_description
                }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite })
                if (!preparing) TextButton(onClick = onPrepare, enabled = !state.busy) {
                    Text(stringResource(if (state.preparation == ChatPreparation.Error) R.string.retry else R.string.chat_prepare))
                }
            }
        }
        val problem = when {
            state.problem == ChatProblem.InvalidInput -> R.string.chat_invalid_input
            state.draft.isNotBlank() && !state.validDraft -> R.string.chat_invalid_input
            state.problem == ChatProblem.ContextLimit -> R.string.chat_context_limit
            state.problem == ChatProblem.Generation -> R.string.chat_response_error
            else -> null
        }
        TextField(
            value = state.draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = inputLabel },
            placeholder = { Text(stringResource(R.string.chat_hint)) },
            shape = MaterialTheme.shapes.extraLarge,
            maxLines = 3,
            isError = problem != null,
            supportingText = if (problem == null) null else ({ Text(stringResource(problem)) }),
            trailingIcon = {
                val stopping = state.phase == ChatPhase.Stopping
                if (state.phase != ChatPhase.Idle) IconButton(onClick = onStop, enabled = !stopping) {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(
                        if (stopping) R.string.chat_stopping else R.string.chat_stop))
                } else IconButton(onClick = onSend, enabled = state.canSend) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.send_question))
                }
            },
            colors = shellTextFieldColors()
        )
    }
}

@Preview(device = Devices.PIXEL_7, showBackground = true)
@Composable
private fun ChatScreenPreview() {
    TP1Theme {
        ChatScreen(ChatState(turns = listOf(ChatTurn(1, "Hola", "Hola. ¿En qué puedo ayudarte?", finish = FinishReason.EOG))),
            PaddingValues())
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatComposerPreview() {
    TP1Theme { Box { ChatComposer(ChatState()) } }
}
