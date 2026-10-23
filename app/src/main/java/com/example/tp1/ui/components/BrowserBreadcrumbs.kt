package com.example.tp1.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tp1.R
import com.example.tp1.browser.BrowserFolder

@Composable
internal fun BrowserBreadcrumbs(path: List<BrowserFolder>, onAncestor: (Int) -> Unit) {
    val scroll = rememberLazyListState()
    LaunchedEffect(path) { scroll.scrollToItem(path.size) }
    LazyRow(state = scroll, modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        item { TextButton(onClick = { onAncestor(-1) }) { Text(stringResource(R.string.browser_roots)) } }
        itemsIndexed(path, key = { _, folder -> folder.uri }) { index, folder ->
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (index == path.lastIndex) {
                Text(folder.name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 12.dp, vertical = 14.dp)
                        .semantics { selected = true })
            } else {
                TextButton(onClick = { onAncestor(index) }, modifier = Modifier.widthIn(max = 220.dp)) {
                    Text(folder.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
        }
    }
}
