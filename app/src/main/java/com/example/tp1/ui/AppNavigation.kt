package com.example.tp1.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tp1.R

internal enum class AppTab(@param:StringRes val label: Int, val icon: ImageVector) {
    Explorer(R.string.tab_explorer, Icons.Outlined.Folder),
    Chat(R.string.tab_chat, Icons.AutoMirrored.Outlined.Chat)
}

@Composable
internal fun AppNavigation(
    selectedTab: AppTab,
    modifier: Modifier = Modifier,
    onTabSelected: (AppTab) -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    val labelHeight = with(LocalDensity.current) { 32.sp.toDp() }
    Column(modifier.background(colors.surfaceContainerLowest).windowInsetsPadding(insets)) {
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
        NavigationBar(
            modifier = Modifier.heightIn(min = 48.dp + labelHeight),
            containerColor = colors.surfaceContainerLowest,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0)
        ) {
            AppTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = {
                        Text(
                            text = stringResource(tab.label),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            textAlign = TextAlign.Center
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.primary,
                        selectedTextColor = colors.primary,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = colors.outline,
                        unselectedTextColor = colors.outline
                    )
                )
            }
        }
    }
}
