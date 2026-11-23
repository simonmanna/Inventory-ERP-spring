@file:OptIn(ExperimentalGetImage::class)

package com.poscafe.pos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import androidx.compose.ui.geometry.Size as GeoSize

/** What the caller made of the last scan, shown under the viewfinder (continuous mode). */
data class ScanFeedback(val message: String, val ok: Boolean, val at: Long = SystemClock.elapsedRealtime())

/** Remembered while the app runs: a cashier who switched to continuous scanning keeps it for the next open. */
private object ScannerPrefs {
    var continuous = false
}

/** Debounce state the analyzer callback reads and writes many times a second (deliberately not Compose state). */
private class ScanGate {
    var lastCode = ""
    var lastAt = 0L
    var done = false
}

/**
 * Full-screen camera scanner (EAN/UPC/Code 128/QR, …) with a viewfinder, torch, beep + haptic on every read and a
 * typed-code fallback for a damaged label.
 *
 * Single mode passes the first code to [onBarcodeDetected] and closes. With [allowContinuous] the cashier can switch
 * to continuous mode: the scanner stays open, the same code is ignored for 1.5 s (so one label is not read ten times)
 * and the caller's [feedback] ("Added Cola 500 ml", "No product for 600…") shows at the bottom.
 */
@Composable
fun BarcodeScannerDialog(
    onDismiss: () -> Unit,
    onBarcodeDetected: (String) -> Unit,
    feedback: ScanFeedback? = null,
    allowContinuous: Boolean = false,
    title: String = "Scan barcode",
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        asked = true
    }
    LaunchedEffect(Unit) { if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var continuous by remember { mutableStateOf(allowContinuous && ScannerPrefs.continuous) }
    var torch by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }

    val gate = remember { ScanGate() }
    var scanned by remember { mutableLongStateOf(0L) }
    val latestDetected by rememberUpdatedState(onBarcodeDetected)
    val latestDismiss by rememberUpdatedState(onDismiss)

    fun deliver(code: String, fromCamera: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (gate.done) return
        if (fromCamera && ((code == gate.lastCode && now - gate.lastAt < 1_500) || now - gate.lastAt < 600)) return
        gate.lastCode = code
        gate.lastAt = now
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scanned++
        if (continuous) {
            latestDetected(code)
        } else {
            gate.done = true
            latestDetected(code)
            latestDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (hasCameraPermission) {
                CameraPreview(
                    onCode = { deliver(it, fromCamera = true) },
                    torch = torch,
                    onTorchAvailable = { torchAvailable = it },
                    modifier = Modifier.fillMaxSize(),
                )
                Viewfinder(Modifier.fillMaxSize())
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("The camera is needed to scan barcodes.", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    if (asked) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    }
                    Text("You can also type the code.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Top bar: close, title, torch.
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close scanner", tint = Color.White) }
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                if (torchAvailable) {
                    RoundIcon(onClick = { torch = !torch }) {
                        Icon(
                            if (torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                            if (torch) "Torch off" else "Torch on",
                            tint = Color.White,
                        )
                    }
                }
            }

            // Bottom: feedback, mode switch, typed code.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (continuous && feedback != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (feedback.ok) Color(0xFF0F7B3F) else Color(0xFF9A5B00),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (feedback.ok) Icons.Filled.CheckCircle else Icons.Outlined.WarningAmber, null,
                                tint = Color.White, modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(feedback.message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    Text("Point the camera at a barcode", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                }
                if (typing) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = typed,
                                onValueChange = { typed = it.trim() },
                                placeholder = { Text("Barcode or SKU") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (typed.isNotEmpty()) {
                                        deliver(typed, fromCamera = false)
                                        typed = ""
                                    }
                                }),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(enabled = typed.isNotEmpty(), onClick = { deliver(typed, fromCamera = false); typed = "" }) { Text("Use") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val chipColors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Black.copy(alpha = 0.45f),
                        labelColor = Color.White,
                        iconColor = Color.White,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    if (allowContinuous) {
                        FilterChip(
                            selected = continuous,
                            onClick = { continuous = !continuous; ScannerPrefs.continuous = continuous },
                            label = { Text(if (continuous) "Continuous · $scanned" else "Continuous") },
                            colors = chipColors,
                        )
                    }
                    FilterChip(
                        selected = typing,
                        onClick = { typing = !typing },
                        label = { Text("Type code") },
                        leadingIcon = { Icon(Icons.Outlined.Keyboard, null, modifier = Modifier.size(18.dp)) },
                        colors = chipColors,
                    )
                    if (continuous) {
                        Button(onClick = onDismiss) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)) { content() }
}

/** Dimmed surround, a clear window with corner brackets and a moving scan line. */
@Composable
private fun Viewfinder(modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val sweep by rememberInfiniteTransition(label = "scanLine").animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Reverse),
        label = "scanLineY",
    )
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val w = size.width * 0.78f
        val h = (w * 0.62f).coerceAtMost(size.height * 0.4f)
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2.3f
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, Offset(left, top), GeoSize(w, h), CornerRadius(24f, 24f), blendMode = BlendMode.Clear)
        val len = 56f
        val stroke = 8f
        listOf(
            Offset(left, top) to Offset(1f, 1f),
            Offset(left + w, top) to Offset(-1f, 1f),
            Offset(left, top + h) to Offset(1f, -1f),
            Offset(left + w, top + h) to Offset(-1f, -1f),
        ).forEach { (c, d) ->
            drawLine(primary, c, Offset(c.x + d.x * len, c.y), stroke, StrokeCap.Round)
            drawLine(primary, c, Offset(c.x, c.y + d.y * len), stroke, StrokeCap.Round)
        }
        val y = top + h * sweep
        drawLine(primary.copy(alpha = 0.8f), Offset(left + 20f, y), Offset(left + w - 20f, y), 4f, StrokeCap.Round)
    }
}

@Composable
private fun CameraPreview(
    onCode: (String) -> Unit,
    torch: Boolean,
    onTorchAvailable: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val latestOnCode by rememberUpdatedState(onCode)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) {
                    proxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let { latestOnCode(it) } }
                    .addOnCompleteListener { proxy.close() }
            }
            try {
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                onTorchAvailable(cam.cameraInfo.hasFlashUnit())
            } catch (_: Exception) {
                // No back camera, or it is busy: the typed-code fallback still works.
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            runCatching { provider?.unbindAll() }
            scanner.close()
            executor.shutdown()
            camera = null
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    AndroidView(factory = { previewView }, modifier = modifier)
}
