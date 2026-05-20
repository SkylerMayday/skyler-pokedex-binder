package com.skyler.pokedexbinder.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    var apiKeyText by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {

            // ---- Camera Scanner ----
            SectionHeader("Camera Scanner")

            SettingToggleItem(
                title = "Enable Camera Scanner",
                description = "Automatically identify cards using the camera. Requires a Gemini API Key.",
                checked = settings.useCameraScanner,
                onCheckedChange = { viewModel.setUseCameraScanner(it) }
            )
            HorizontalDivider()

            OutlinedTextField(
                value = apiKeyText,
                onValueChange = { apiKeyText = it; viewModel.setGeminiApiKey(it) },
                label = { Text("Gemini API Key") },
                supportingText = { Text("Free key at aistudio.google.com") },
                enabled = settings.useCameraScanner,
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(
                        onClick = { apiKeyVisible = !apiKeyVisible },
                        enabled = settings.useCameraScanner
                    ) {
                        Icon(
                            if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (apiKeyVisible) "Hide key" else "Show key"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ---- Binder Sections ----
            Spacer(Modifier.height(8.dp))
            SectionHeader("Binder Sections")

            SettingToggleItem(
                title = "Regional Variants",
                description = "Show Alolan, Galarian, Hisuian and other regional forms",
                checked = settings.showRegional,
                onCheckedChange = { viewModel.setShowRegional(it) }
            )
            HorizontalDivider()
            SettingToggleItem(
                title = "Mega Evolutions",
                description = "Show Mega Evolution slots",
                checked = settings.showMega,
                onCheckedChange = { viewModel.setShowMega(it) }
            )
            HorizontalDivider()
            SettingToggleItem(
                title = "V-Max",
                description = "Show Gigantamax / V-Max slots",
                checked = settings.showGmax,
                onCheckedChange = { viewModel.setShowGmax(it) }
            )

            // ---- Navigation ----
            Spacer(Modifier.height(8.dp))
            SectionHeader("Navigation")

            HorizontalDivider()
            SettingToggleItem(
                title = "Secondary Binder",
                description = "When enabled, cards replaced in the main binder are moved here instead of being deleted.",
                checked = settings.showSecondaryBinder,
                onCheckedChange = { viewModel.setShowSecondaryBinder(it) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}
