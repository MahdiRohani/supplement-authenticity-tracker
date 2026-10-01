package ir.aut.supplementtracker.feature.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.IconBadge
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementButtonVariant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ScanScreen"

/** Which of the two label layers the user is asked to scan. */
enum class ScanMode {
    /** The open label: verify URL, or a legacy v1 code. */
    PublicLabel,

    /** The code under the scratch-off, used once to record consumption. */
    HiddenLabel,
}

/** Reports the raw QR text; callers decide which label generations they accept. */
@Composable
fun ScanScreen(
    onDetected: (raw: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    mode: ScanMode = ScanMode.PublicLabel,
) {
    val context = LocalContext.current
    fun granted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(granted()) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
            hasPermission = result
        }

    LaunchedEffect(Unit) {
        if (!hasPermission && !askedOnce) {
            askedOnce = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    // Picks up a grant made in system settings while the app was in the background.
    LifecycleResumeEffect(Unit) {
        hasPermission = granted()
        onPauseOrDispose { }
    }

    when {
        !hasPermission -> ScanMessage(
            icon = SupplementIcons.Camera,
            title = stringResource(R.string.scan_permission_title),
            message = stringResource(R.string.scan_permission_required),
            onClose = onClose,
            modifier = modifier,
        ) {
            SupplementButton(
                text = stringResource(R.string.scan_grant_permission),
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                leadingIcon = SupplementIcons.Camera,
            )
            if (askedOnce) {
                SupplementButton(
                    text = stringResource(R.string.scan_open_settings),
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    variant = SupplementButtonVariant.Tonal,
                )
            }
        }
        cameraFailed -> ScanMessage(
            icon = SupplementIcons.Error,
            title = stringResource(R.string.scan_error_title),
            message = stringResource(R.string.scan_error_message),
            onClose = onClose,
            modifier = modifier,
        )
        else -> CameraScanner(
            mode = mode,
            onBarcode = { raw ->
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) onDetected(trimmed)
            },
            onCameraError = { cameraFailed = true },
            onClose = onClose,
            modifier = modifier,
        )
    }
}

@Composable
private fun CameraScanner(
    mode: ScanMode,
    onBarcode: (String) -> Unit,
    onCameraError: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (titleRes, hintRes, hintIcon) =
        when (mode) {
            ScanMode.PublicLabel -> Triple(R.string.scan_title, R.string.scan_hint, SupplementIcons.QrCode)
            ScanMode.HiddenLabel -> Triple(R.string.scan_hidden_title, R.string.scan_hidden_hint, SupplementIcons.Secret)
        }
    var torchOn by rememberSaveable { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    DarkSystemBarsEffect()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        CameraBarcodePreview(
            onBarcode = onBarcode,
            onError = onCameraError,
            torchOn = torchOn,
            onTorchAvailable = { hasTorch = it },
        )
        ViewfinderOverlay(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(SupplementSpacing.Md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayIconButton(
                    icon = SupplementIcons.Close,
                    contentDescription = stringResource(R.string.scan_close),
                    onClick = onClose,
                )
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                if (hasTorch) {
                    OverlayIconButton(
                        icon = if (torchOn) SupplementIcons.TorchOn else SupplementIcons.TorchOff,
                        contentDescription = stringResource(
                            if (torchOn) R.string.scan_torch_off else R.string.scan_torch_on,
                        ),
                        onClick = { torchOn = !torchOn },
                        highlighted = torchOn,
                    )
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = SupplementSpacing.Md, vertical = SupplementSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Xs),
                ) {
                    Icon(hintIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(text = stringResource(hintRes), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.size(SupplementSpacing.Lg))
        }
    }
}

@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else Color.Black.copy(alpha = 0.45f),
        contentColor = if (highlighted) MaterialTheme.colorScheme.onTertiaryContainer else Color.White,
    ) {
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}

/** Dims everything except a rounded square window, with brand-colored corners and a sweeping line. */
@Composable
private fun ViewfinderOverlay(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primaryContainer
    val sweep by rememberInfiniteTransition(label = "scanSweep").animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    Canvas(modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val side = size.minDimension * 0.68f
        val window = Rect(
            offset = Offset((size.width - side) / 2f, (size.height - side) / 2f - size.height * 0.04f),
            size = GeometrySize(side, side),
        )
        val radius = 28.dp.toPx()
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(
            color = Color.Transparent,
            topLeft = window.topLeft,
            size = window.size,
            cornerRadius = CornerRadius(radius),
            blendMode = BlendMode.Clear,
        )

        val arm = side * 0.16f
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        val corners = Path().apply {
            moveTo(window.left, window.top + arm)
            lineTo(window.left, window.top + radius)
            quadraticTo(window.left, window.top, window.left + radius, window.top)
            lineTo(window.left + arm, window.top)

            moveTo(window.right - arm, window.top)
            lineTo(window.right - radius, window.top)
            quadraticTo(window.right, window.top, window.right, window.top + radius)
            lineTo(window.right, window.top + arm)

            moveTo(window.right, window.bottom - arm)
            lineTo(window.right, window.bottom - radius)
            quadraticTo(window.right, window.bottom, window.right - radius, window.bottom)
            lineTo(window.right - arm, window.bottom)

            moveTo(window.left + arm, window.bottom)
            lineTo(window.left + radius, window.bottom)
            quadraticTo(window.left, window.bottom, window.left, window.bottom - radius)
            lineTo(window.left, window.bottom - arm)
        }
        drawPath(corners, color = accent, style = stroke)

        val y = window.top + side * sweep
        val inset = side * 0.08f
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, accent, Color.Transparent),
                startX = window.left + inset,
                endX = window.right - inset,
            ),
            start = Offset(window.left + inset, y),
            end = Offset(window.right - inset, y),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Light status/navigation bar icons while the camera feed is behind them. */
@Composable
private fun DarkSystemBarsEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            previousStatus?.let { controller.isAppearanceLightStatusBars = it }
            previousNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
}

@Composable
private fun ScanMessage(
    icon: ImageVector,
    title: String,
    message: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(SupplementSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Md, Alignment.CenterVertically),
    ) {
        IconBadge(icon = icon, size = 88.dp)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(SupplementSpacing.Xs))
        actions()
        SupplementButton(
            text = stringResource(R.string.scan_close),
            onClick = onClose,
            variant = SupplementButtonVariant.Outlined,
        )
    }
}

@Composable
private fun CameraBarcodePreview(
    onBarcode: (String) -> Unit,
    onError: () -> Unit,
    torchOn: Boolean,
    onTorchAvailable: (Boolean) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = remember { ScanSession.create() }
    var camera by remember { mutableStateOf<Camera?>(null) }

    if (session == null) {
        LaunchedEffect(Unit) { onError() }
        return
    }

    LaunchedEffect(camera, torchOn) {
        camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl?.enableTorch(torchOn)
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener(
                {
                    try {
                        val provider = providerFuture.get()
                        session.provider = provider
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = session.buildAnalysis(onBarcode)
                        provider.unbindAll()
                        val bound = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                        camera = bound
                        onTorchAvailable(bound.cameraInfo.hasFlashUnit())
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera could not be started", e)
                        onError()
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

/** Owns the scanner, analysis executor and camera binding so they are released together. */
private class ScanSession(
    private val scanner: BarcodeScanner,
    private val executor: ExecutorService,
) {
    private val handled = AtomicBoolean(false)
    private var analysis: ImageAnalysis? = null
    var provider: ProcessCameraProvider? = null

    fun buildAnalysis(onBarcode: (String) -> Unit): ImageAnalysis {
        val resolutionSelector =
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
        return ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null || handled.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    try {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                val raw = barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                                if (raw != null && handled.compareAndSet(false, true)) {
                                    onBarcode(raw)
                                }
                            }
                            .addOnFailureListener { Log.w(TAG, "Barcode analysis failed", it) }
                            .addOnCompleteListener { imageProxy.close() }
                    } catch (e: Exception) {
                        Log.w(TAG, "Frame skipped", e)
                        imageProxy.close()
                    }
                }
                analysis = useCase
            }
    }

    fun close() {
        handled.set(true)
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        executor.shutdown()
        scanner.close()
    }

    companion object {
        fun create(): ScanSession? =
            try {
                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                ScanSession(BarcodeScanning.getClient(options), Executors.newSingleThreadExecutor())
            } catch (e: Exception) {
                Log.e(TAG, "Barcode scanner unavailable", e)
                null
            }
    }
}
