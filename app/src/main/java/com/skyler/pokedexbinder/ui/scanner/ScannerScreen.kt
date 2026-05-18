package com.skyler.pokedexbinder.ui.scanner

import android.Manifest
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.skyler.pokedexbinder.data.model.TcgCard

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    pokemonId: String,
    onCardSelected: (TcgCard) -> Unit,
    onBack: () -> Unit,
    viewModel: ScannerViewModel = hiltViewModel()
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val state by viewModel.state.collectAsState()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Card") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is ScannerState.Idle -> {
                    if (cameraPermission.status.isGranted) {
                        CameraPreview(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onImageCaptureReady = { imageCapture = it }
                        )
                        Button(
                            onClick = {
                                imageCapture?.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            viewModel.processImage(image)
                                        }
                                        override fun onError(exc: ImageCaptureException) {
                                            viewModel.onCaptureError(exc.message ?: "Capture failed")
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)
                        ) { Text("Capture") }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Camera permission required", modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                is ScannerState.Scanning -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is ScannerState.HighConfidence -> {
                    CardConfirmation(
                        card = s.card,
                        onConfirm = { onCardSelected(s.card) },
                        onDismiss = { viewModel.reset() }
                    )
                }
                is ScannerState.LowConfidence -> {
                    CardSelectionList(
                        cards = s.cards,
                        onSelect = { onCardSelected(it) },
                        onBack = { viewModel.reset() }
                    )
                }
                is ScannerState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.reset() }) { Text("Try Again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    onImageCaptureReady: (ImageCapture) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder().build()
                onImageCaptureReady(capture)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = modifier
    )
}

@Composable
private fun CardConfirmation(card: TcgCard, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Is this the right card?", style = MaterialTheme.typography.titleMedium)
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.name,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Text("${card.name} · ${card.setName} · #${card.number}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Wrong card") }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Confirm") }
        }
    }
}

@Composable
private fun CardSelectionList(cards: List<TcgCard>, onSelect: (TcgCard) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (cards.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No cards found")
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(cards) { card ->
                    ListItem(
                        headlineContent = { Text(card.name) },
                        supportingContent = { Text("${card.setName} · #${card.number}") },
                        leadingContent = {
                            AsyncImage(
                                model = card.imageUrl,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp)
                            )
                        },
                        modifier = Modifier.clickable { onSelect(card) }
                    )
                    HorizontalDivider()
                }
            }
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Back") }
    }
}
