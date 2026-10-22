package com.example.tp1.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.tp1.R
import com.example.tp1.ui.theme.shellTextFieldColors

@Composable
internal fun ChatScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().consumeWindowInsets(contentPadding),
        contentPadding = contentPadding
    ) {
        item {
            Text(
                text = stringResource(R.string.chat_preview_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            )
        }
    }
}

@Composable
internal fun ChatComposer(
    draft: String,
    modifier: Modifier = Modifier,
    onDraftChange: (String) -> Unit = {}
) {
    val inputLabel = stringResource(R.string.chat_input_label)
    TextField(
        value = draft,
        onValueChange = onDraftChange,
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = inputLabel },
        placeholder = { Text(stringResource(R.string.chat_hint)) },
        shape = MaterialTheme.shapes.extraLarge,
        maxLines = 3,
        trailingIcon = {
            IconButton(onClick = {}, enabled = false) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.send_question))
            }
        },
        colors = shellTextFieldColors()
    )
}
