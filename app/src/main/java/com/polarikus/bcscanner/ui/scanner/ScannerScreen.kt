package com.polarikus.bcscanner.ui.scanner

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polarikus.bcscanner.R
import com.polarikus.bcscanner.domain.model.ScannedCode
import com.polarikus.bcscanner.scanner.BarcodeScanner
import com.polarikus.bcscanner.ui.permissions.PermissionManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scanner screen: shows the CameraX preview, recognizes a barcode/QR code and sends it to the
 * FlipperZero (FR-006, FR-007). The camera permission is requested at the moment of use (FR-004)
 * and the preview is bound to the lifecycle so it stops in `onStop`.
 */
@Composable
fun ScannerScreen(
    viewModel: ScannerViewModel,
    scanner: BarcodeScanner,
    onReturnToMain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        denied = !result
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(PermissionManager.CAMERA)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event == ScannerEvent.ReturnToMain) onReturnToMain()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (granted) {
            CameraPreview(
                scanner = scanner,
                onCodeDetected = viewModel::onCodeDetected,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = stringResource(R.string.permission_camera_rationale),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (denied) {
                    Text(
                        text = stringResource(R.string.permission_denied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Text(
            text = scannerStatusText(state),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
        )
    }
}

@Composable
private fun scannerStatusText(state: ScannerUiState): String = when (state) {
    ScannerUiState.Scanning -> stringResource(R.string.scanner_hint)
    is ScannerUiState.Sending -> stringResource(R.string.delivery_status_pending)
    is ScannerUiState.Delivered -> stringResource(R.string.delivery_status_delivered)
    is ScannerUiState.Error -> stringResource(R.string.delivery_status_error)
}

@Composable
private fun CameraPreview(
    scanner: BarcodeScanner,
    onCodeDetected: (ScannedCode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCodeDetected by rememberUpdatedState(onCodeDetected)
    val previewView = remember { PreviewView(context) }
    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }

    DisposableEffect(lifecycleOwner, scanner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val cameraProvider = providerFuture.get()
            cameraProviderHolder[0] = cameraProvider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    val code = scanner.detect(image)
                    if (code != null) currentOnCodeDetected(code)
                } finally {
                    image.close()
                }
            }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProviderHolder[0]?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, PermissionManager.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
