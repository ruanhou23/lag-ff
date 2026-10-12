package com.fakelag.tool.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FakeLagDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color.White,
    background = DarkBackground,
    surface = DarkSurface,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFAAAAAA),
    onBackground = Color.White,
)

@Composable
fun FakeLagTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FakeLagDarkColorScheme,
        content = content,
    )
}
