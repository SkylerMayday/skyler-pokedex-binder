package com.skyler.pokedexbinder.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.skyler.pokedexbinder.ui.publish.PublishDialog
import com.skyler.pokedexbinder.ui.publish.PublishViewModel
import com.skyler.pokedexbinder.ui.restore.RestoreDialog
import com.skyler.pokedexbinder.ui.restore.RestoreViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val publishConfig by viewModel.publishConfig.collectAsState()
    var apiKeyText by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    var githubOwnerText by remember(publishConfig.githubOwner) { mutableStateOf(publishConfig.githubOwner) }
    var githubRepoText by remember(publishConfig.githubRepo) { mutableStateOf(publishConfig.githubRepo) }
    var githubPatText by remember(publishConfig.githubPat) { mutableStateOf(publishConfig.githubPat) }
    var discordWebhookText by remember(publishConfig.discordWebhookUrl) { mutableStateOf(publishConfig.discordWebhookUrl) }
    var githubPatVisible by remember { mutableStateOf(false) }
    var discordWebhookVisible by remember { mutableStateOf(false) }

    var showPublish by remember { mutableStateOf(false) }
    val publishVm: PublishViewModel = hiltViewModel()
    val publishState by publishVm.state.collectAsState()
    val pageUrl = "https://${publishConfig.githubOwner}.github.io/${publishConfig.githubRepo}/"

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    val restoreVm: RestoreViewModel = hiltViewModel()
    val restoreState by restoreVm.state.collectAsState()

    val context = LocalContext.current
    val backupVm: BackupViewModel = hiltViewModel()
    val backupState by backupVm.state.collectAsState()

    val importVm: ImportViewModel = hiltViewModel()
    val importState by importVm.state.collectAsState()
    val importFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> importVm.onFilesPicked(uris) }

    LaunchedEffect(backupState) {
        val current = backupState
        if (current is BackupUiState.Success) {
            context.startActivity(current.shareIntent)
            backupVm.dismiss()
        }
    }

    BackupDialog(state = backupState, onDismiss = { backupVm.dismiss() })

    ImportDialog(
        state = importState,
        onConfirm = { importVm.confirmImport() },
        onDismiss = { importVm.dismiss() }
    )

    if (showPublish) {
        LaunchedEffect(Unit) { publishVm.startPublish() }
        PublishDialog(
            state = publishState,
            pageUrl = pageUrl,
            onDismiss = { showPublish = false; publishVm.dismiss() }
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restore from snapshot?") },
            text = { Text("This overwrites your local card assignments with the ones from the " +
                "published snapshot on GitHub. Slots you've filled in since the last publish will be " +
                "lost. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showRestoreConfirm = false; showRestore = true }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showRestore) {
        LaunchedEffect(Unit) { restoreVm.startRestore() }
        RestoreDialog(
            state = restoreState,
            onDismiss = { showRestore = false; restoreVm.dismiss() }
        )
    }

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

            // ---- Appearance ----
            SectionHeader("Appearance")

            SettingToggleItem(
                title = "Dark Mode",
                description = "Use a dark colour scheme",
                checked = settings.darkMode,
                onCheckedChange = { viewModel.setDarkMode(it) }
            )
            HorizontalDivider()

            // ---- Camera Scanner ----
            Spacer(Modifier.height(8.dp))
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
                title = "Alternate Forms",
                description = "Show form differences like Giratina Origin, Rotom forms, Kyurem fusions, etc.",
                checked = settings.showAlternateForms,
                onCheckedChange = { viewModel.setShowAlternateForms(it) }
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
                title = "VMax",
                description = "Show Gigantamax / VMax slots",
                checked = settings.showGmax,
                onCheckedChange = { viewModel.setShowGmax(it) }
            )

            // ---- Public Binder / Sharing ----
            Spacer(Modifier.height(8.dp))
            SectionHeader("Public Binder / Sharing")

            OutlinedTextField(
                value = githubOwnerText,
                onValueChange = { githubOwnerText = it; viewModel.setGithubOwner(it) },
                label = { Text("GitHub Owner") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            OutlinedTextField(
                value = githubRepoText,
                onValueChange = { githubRepoText = it; viewModel.setGithubRepo(it) },
                label = { Text("GitHub Repo") },
                placeholder = { Text("binders-pokedex-binder") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            OutlinedTextField(
                value = githubPatText,
                onValueChange = { githubPatText = it; viewModel.setGithubPat(it) },
                label = { Text("GitHub PAT") },
                supportingText = { Text("Fine-grained token, Contents read/write on the repo only") },
                singleLine = true,
                visualTransformation = if (githubPatVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { githubPatVisible = !githubPatVisible }) {
                        Icon(
                            if (githubPatVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (githubPatVisible) "Hide PAT" else "Show PAT"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            OutlinedTextField(
                value = discordWebhookText,
                onValueChange = { discordWebhookText = it; viewModel.setDiscordWebhookUrl(it) },
                label = { Text("Discord Webhook URL") },
                supportingText = { Text("Server Settings → Integrations → Webhooks") },
                singleLine = true,
                visualTransformation = if (discordWebhookVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { discordWebhookVisible = !discordWebhookVisible }) {
                        Icon(
                            if (discordWebhookVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (discordWebhookVisible) "Hide webhook URL" else "Show webhook URL"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            SettingToggleItem(
                title = "Publish Pokédex",
                description = "Include the main Pokédex binder in the public page",
                checked = publishConfig.publishPokedex,
                onCheckedChange = { viewModel.setPublishPokedex(it) }
            )
            HorizontalDivider()
            SettingToggleItem(
                title = "Publish Card History",
                description = "Include the secondary (card history) binder in the public page",
                checked = publishConfig.publishCardHistory,
                onCheckedChange = { viewModel.setPublishCardHistory(it) }
            )
            HorizontalDivider()

            Button(
                onClick = { showPublish = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Publish now")
            }

            OutlinedButton(
                onClick = { showRestoreConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Restore from published snapshot")
            }

            // ---- Local Backup / Restore (no GitHub account needed) ----
            Spacer(Modifier.height(8.dp))
            SectionHeader("Local Backup")

            OutlinedButton(
                onClick = { backupVm.startBackup() },
                enabled = backupState !is BackupUiState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(if (backupState is BackupUiState.Loading) "Backing up…" else "Backup")
            }

            OutlinedButton(
                onClick = {
                    importFilePicker.launch(
                        arrayOf("application/json", "application/octet-stream", "*/*")
                    )
                },
                enabled = importState is ImportUiState.Idle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Restore from file")
            }

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
