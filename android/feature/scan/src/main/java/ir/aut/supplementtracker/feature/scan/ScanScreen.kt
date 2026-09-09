package ir.aut.supplementtracker.feature.scan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.model.QrPayload
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanScreen(
    onDetected: (productId: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
        }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SupplementSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        Text(text = stringResource(R.string.scan_title))
        if (!hasPermission) {
            Text(text = stringResource(R.string.scan_permission_required))
            SupplementButton(
                text = stringResource(R.string.scan_grant_permission),
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                CameraBarcodePreview(
                    onBarcode = { raw ->
                        val productId = QrPayload.parse(raw)?.productId ?: raw.trim()
                        if (productId.isNotBlank()) {
                            onDetected(productId)
                        }
                    },
                )
            }
        }
        SupplementButton(
            text = stringResource(R.string.scan_close),
            onClick = onClose,
        )
    }
}

@Composable
private fun CameraBarcodePreview(
    onBarcode: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val handled = remember { AtomicBoolean(false) }
    val scanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val resolutionSelector =
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build()
                    val analysis =
                        ImageAnalysis.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null || handled.get()) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val image =
                            InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees,
                            )
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                val raw =
                                    barcodes.firstOrNull { barcode ->
                                        barcode.format == Barcode.FORMAT_QR_CODE ||
                                            barcode.rawValue != null
                                    }?.rawValue
                                if (!raw.isNullOrBlank() && handled.compareAndSet(false, true)) {
                                    onBarcode(raw)
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}
