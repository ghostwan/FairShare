package com.fairshare.presentation.scan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Live camera preview that scans QR codes (and other 2D barcodes) using ML Kit.
 *
 * On the first valid `fairshare://` URL decoded, [onScanned] is invoked exactly once
 * with the raw URL. The host (NavGraph) is responsible for routing to ApplyChanges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanInvitationScreen(
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
) {
    ScanQrScreen(
        title = "Scanner un QR code",
        accept = { isFairshareInvitation(it) },
        onBack = onBack,
        onScanned = onScanned,
    )
}

/**
 * Generic camera + ML Kit barcode scanner reused by every screen that
 * needs to capture a typed QR payload (invitations, Gemini key
 * sharing, future flows). The [accept] predicate filters out QR codes
 * that don't match the expected format so a stray Wi-Fi or vCard QR
 * doesn't get fed to the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanQrScreen(
    title: String,
    accept: (String) -> Boolean,
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (hasPermission) {
                CameraScanner(accept = accept, onScanned = onScanned)
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "L'accès à la caméra est nécessaire pour scanner un QR code.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Autoriser la caméra")
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraScanner(
    accept: (String) -> Boolean,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    // Latch so we only dispatch the first valid URL and never re-enter onScanned.
    val dispatched = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = CameraPreview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // Higher resolution helps reading dense (version 40) QR codes
                    // when the receipt is displayed on another phone's screen.
                    .setTargetResolution(Size(1280, 720))
                    .build()
                analyzer.setAnalyzer(executor) { proxy ->
                    processFrame(proxy, scanner, dispatched, accept, onScanned)
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analyzer,
                    )
                } catch (_: Exception) {
                    // Camera binding failure is non-fatal here; user can retry by leaving the screen.
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private fun processFrame(
    proxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    dispatched: java.util.concurrent.atomic.AtomicBoolean,
    accept: (String) -> Boolean,
    onScanned: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            if (barcodes.isNotEmpty()) {
                Log.d(
                    TAG,
                    "Detected ${barcodes.size} barcode(s): " +
                        barcodes.joinToString { "${it.format}/${it.rawValue?.take(40)}" },
                )
            }
            val url = barcodes
                .mapNotNull { it.rawValue }
                .firstOrNull(accept)
            if (url != null && dispatched.compareAndSet(false, true)) {
                Log.i(TAG, "Dispatching scanned URL (len=${url.length})")
                onScanned(url)
            }
        }
        .addOnFailureListener { e -> Log.w(TAG, "Barcode scan failed", e) }
        .addOnCompleteListener { proxy.close() }
}

/**
 * Recognises both invitation URL flavours we emit (DESIGN.md §7):
 *
 *   - `fairshare://join?…` (legacy custom scheme, in-app deep link)
 *   - `https://<any-host>/join?…` (default since the webapp shipped,
 *     so iOS Camera and Android Lens open it natively)
 *
 * The actual host is intentionally not pinned — staging deployments
 * (`*.pages.dev` previews) and self-hosted mirrors must work without
 * a code change. The codec then validates the query params.
 */
private fun isFairshareInvitation(raw: String): Boolean {
    if (raw.startsWith("fairshare://join?")) return true
    return INVITATION_HTTPS_REGEX.containsMatchIn(raw)
}

private val INVITATION_HTTPS_REGEX = Regex("^https?://[^/?#]+/join\\?")

private const val TAG = "ScanInvitation"
