package com.skyler.pokedexbinder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFCC0000),
    onPrimary = Color.White,
    secondary = Color(0xFF3B5BA5),
    background = Color(0xFFF5F5F5)
)

@Composable
fun PokedexBinderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
