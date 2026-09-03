package com.skyler.pokedexbinder

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.skyler.pokedexbinder.data.local.backup.PendingImportError
import com.skyler.pokedexbinder.ui.navigation.AppNavigation
import com.skyler.pokedexbinder.ui.settings.SettingsViewModel
import com.skyler.pokedexbinder.ui.theme.PokedexBinderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The .db import path force-restarts the process on both success and a post-close
        // failure (BackupImporter.applyDb) — a failure's message can't reach the old process's
        // UI through a StateFlow whose process just died, so it's shown here instead, once, on
        // the cold start that restart caused.
        PendingImportError.consume(this)?.let { message ->
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        setContent {
            val settingsVm: SettingsViewModel by viewModels()
            val settings by settingsVm.settings.collectAsState()
            PokedexBinderTheme(darkTheme = settings.darkMode) {
                AppNavigation()
            }
        }
    }
}
