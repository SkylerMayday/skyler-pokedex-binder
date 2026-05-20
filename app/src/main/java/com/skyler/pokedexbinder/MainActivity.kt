package com.skyler.pokedexbinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.skyler.pokedexbinder.repository.SettingsRepository
import com.skyler.pokedexbinder.ui.navigation.AppNavigation
import com.skyler.pokedexbinder.ui.theme.PokedexBinderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settings.collectAsState(
                initial = com.skyler.pokedexbinder.repository.AppSettings()
            )
            PokedexBinderTheme(darkTheme = settings.darkMode) {
                AppNavigation()
            }
        }
    }
}
