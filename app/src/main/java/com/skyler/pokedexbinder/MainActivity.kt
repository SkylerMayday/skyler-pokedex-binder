package com.skyler.pokedexbinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.skyler.pokedexbinder.ui.navigation.AppNavigation
import com.skyler.pokedexbinder.ui.settings.SettingsViewModel
import com.skyler.pokedexbinder.ui.theme.PokedexBinderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsVm: SettingsViewModel by viewModels()
            val settings by settingsVm.settings.collectAsState()
            PokedexBinderTheme(darkTheme = settings.darkMode) {
                AppNavigation()
            }
        }
    }
}
