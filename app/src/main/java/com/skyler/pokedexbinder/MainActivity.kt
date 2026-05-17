package com.skyler.pokedexbinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.skyler.pokedexbinder.ui.navigation.AppNavigation
import com.skyler.pokedexbinder.ui.theme.PokedexBinderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PokedexBinderTheme { AppNavigation() }
        }
    }
}
