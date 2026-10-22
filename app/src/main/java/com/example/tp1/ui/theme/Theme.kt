package com.example.tp1.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The approved Stitch palette stays stable across wallpaper and system theme changes.
private val LightColorScheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = PaleBlue,
    onPrimaryContainer = InkBlue,
    inversePrimary = PaleBlue,
    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = PaleSlate,
    onSecondaryContainer = InkSlate,
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = PaleTeal,
    onTertiaryContainer = InkTeal,
    background = Canvas,
    onBackground = Ink,
    surface = Canvas,
    onSurface = Ink,
    surfaceVariant = SurfaceHighest,
    onSurfaceVariant = SecondaryInk,
    surfaceTint = Blue,
    surfaceBright = Canvas,
    surfaceDim = SurfaceHighest,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = SurfaceLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHighest,
    inverseSurface = Ink,
    inverseOnSurface = Canvas,
    outline = Outline,
    outlineVariant = Divider,
    error = Red,
    onError = Color.White,
    errorContainer = PaleRed,
    onErrorContainer = InkRed
)

@Composable
fun TP1Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        shapes = Shapes(small = RoundedCornerShape(8.dp)),
        content = content
    )
}

@Composable
internal fun shellTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary
)
