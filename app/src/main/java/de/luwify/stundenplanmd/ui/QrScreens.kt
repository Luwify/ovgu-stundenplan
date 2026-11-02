package de.luwify.stundenplanmd.ui

import android.os.SystemClock
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.QRCode
import de.luwify.stundenplanmd.core.share.Base45
import de.luwify.stundenplanmd.core.share.ShareFrames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import de.luwify.stundenplanmd.R
import de.luwify.stundenplanmd.core.share.ShareCodec
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

/** Top row of a full-screen overlay: close button and title. */
/** Full brightness and the screen kept on while a QR code is shown. */
@Composable
internal fun FullBrightness() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness
        window?.let {
            it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
            it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.let {
                it.attributes = it.attributes.apply { screenBrightness = before ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

@Composable
internal fun OverlayHeader(title: String, onClose: () -> Unit) {
    Row(
        Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close)) }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * My plan as a QR code to scan with the other phone. Always dark modules on white with a quiet zone (whatever the
 * theme), at full brightness and with the screen kept on while shown. The code is as large as the smaller side of the
 * space left allows, never larger; in landscape name and "Stand" sit next to it. Up to version 20 one code (if ZXing's
 * detector reads it back, see [ShareQr.readable]), larger plans as an endless animation of small codes
 * ([ShareFrames], version 12 at [ShareQr.FPS]); [chosen] is the user's last choice of the two, null = by size.
 */
@Composable
fun QrShowScreen(qr: MyQr, name: String, stand: String, chosen: QrMode?, onMode: (QrMode) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    FullBrightness()
    // The smallest single code decides the default; computed off the main thread (large codes take a moment).
    var single by remember(qr) { mutableStateOf<QRCode?>(null) }
    var measured by remember(qr) { mutableStateOf(false) }
    LaunchedEffect(qr) {
        single = withContext(Dispatchers.Default) { ShareQr.code(qr.text) }
        measured = true
    }
    val mode = if (measured) ShareQr.mode(chosen, single) else null
    var matrix by remember(qr) { mutableStateOf<ByteMatrix?>(null) }
    LaunchedEffect(qr, mode) {
        when (mode) {
            null -> Unit
            QrMode.SINGLE -> matrix = withContext(Dispatchers.Default) { ShareQr.readable(qr.text, single!!).matrix }
            QrMode.ANIMATED -> {
                val frames = ShareFrames.Encoder(qr.payload)
                var seq = 0
                while (true) {
                    val started = SystemClock.uptimeMillis()
                    matrix = withContext(Dispatchers.Default) { ShareQr.code(frames.frame(seq))?.matrix }
                    seq++
                    delay((1000L / ShareQr.FPS - (SystemClock.uptimeMillis() - started)).coerceAtLeast(0))
                }
            }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OverlayHeader(stringResource(R.string.qr_title), onClose)
            BoxWithConstraints(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(16.dp),
            ) {
                val landscape = maxWidth > maxHeight
                val code: @Composable (Dp) -> Unit = { side ->
                    val m = matrix
                    if (m != null) QrCode(m, Modifier.size(side)) else Box(Modifier.size(side))
                }
                if (landscape) {
                    // Code on the left as tall as the space allows; the rest next to it.
                    val side = minOf(maxHeight, maxWidth * 0.6f)
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        code(side)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            QrInfo(name, stand, mode, canSingle = single != null, onMode = onMode)
                        }
                    }
                } else {
                    // Portrait: name and Stand above, the choice and hints below; keep room for them.
                    val side = minOf(maxWidth, maxHeight - 260.dp).coerceAtLeast(160.dp)
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(stand, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        code(side)
                        QrInfo(null, null, mode, canSingle = single != null, onMode = onMode, centered = true)
                    }
                }
            }
        }
    }
}

/** The modules on white, the quiet zone (4 modules) included in [modifier]'s size, snapped to whole pixels. */
@Composable
internal fun QrCode(matrix: ByteMatrix, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val modules = matrix.width + 8
        val cell = floor(size.minDimension / modules)
        val origin = (size.minDimension - cell * matrix.width) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y).toInt() == 1) drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), GSize(cell, cell))
        }
    }
}

/** Name and Stand (landscape), one code or animated, and how to scan. */
@Composable
private fun QrInfo(name: String?, stand: String?, mode: QrMode?, canSingle: Boolean, onMode: (QrMode) -> Unit, centered: Boolean = false) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    if (name != null) Text(name, style = MaterialTheme.typography.titleMedium)
    if (stand != null) Text(stand, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (mode != null && canSingle) {
        SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            QrMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, QrMode.entries.size),
                ) {
                    Text(
                        stringResource(if (m == QrMode.SINGLE) R.string.qr_mode_single else R.string.qr_mode_animated),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    Text(stringResource(R.string.qr_how_to), textAlign = align, style = MaterialTheme.typography.bodyMedium)
    if (mode == QrMode.ANIMATED) {
        Text(
            stringResource(R.string.qr_animated_hint),
            textAlign = align,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Camera view that reads a shared plan's QR code. Asks for the camera permission only here. */
@Composable
fun ScanScreen(onScanned: (String) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }
    // Frames of an animated share, and how far it is (null until the first frame).
    val frames = remember { ShareFrames.Decoder() }
    var progress by remember { mutableStateOf<Float?>(null) }
    var done by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OverlayHeader(stringResource(R.string.scan_title), onClose)
            Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                when {
                    granted -> {
                        CameraScanner(onText = { text ->
                            when {
                                done -> Unit
                                // An animated share: collect frames in any order until the plan is complete.
                                frames.add(text) || ShareFrames.isFrame(text) -> {
                                    progress = frames.progress
                                    frames.result?.let { done = true; onScanned(ShareCodec.QR_PREFIX + Base45.encode(it)) }
                                }
                                text.trim().startsWith(ShareCodec.QR_PREFIX) -> { done = true; onScanned(text) }
                                else -> other = true
                            }
                        })
                        Column(
                            Modifier.align(Alignment.BottomCenter).padding(24.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val p = progress
                            if (p != null) {
                                Text(stringResource(R.string.scan_progress, (p * 100).toInt()), textAlign = TextAlign.Center)
                                LinearProgressIndicator(progress = { p }, modifier = Modifier.width(200.dp))
                            } else {
                                Text(stringResource(if (other) R.string.scan_other_code else R.string.scan_hint), textAlign = TextAlign.Center)
                            }
                        }
                    }
                    denied -> Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.scan_no_permission), textAlign = TextAlign.Center)
                        Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.scan_allow)) }
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) { Text(stringResource(R.string.scan_app_settings)) }
                    }
                }
            }
        }
    }
}

/** Back camera preview + frame analysis with ZXing; [onText] gets every QR text found (on the main thread). */
@Composable
private fun CameraScanner(onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onText)
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    LaunchedEffect(owner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also { p -> p.setSurfaceProvider { request = it } }
        val main = ContextCompat.getMainExecutor(context)
        val executor = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder()
            // Dense codes (version 20+) need about 4 camera pixels per module: analyse in 1080p.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor, QrAnalyzer { text -> main.execute { latest(text) } })
        try {
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            awaitCancellation()
        } finally {
            provider.unbind(preview, analysis)
            executor.shutdown()
        }
    }
    request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
}

/** Decodes QR codes from the luminance plane of each frame; stops after the first share it found (frames of an animated share don't count). */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    private val found = AtomicBoolean(false)
    private var buffer = ByteArray(0)
    private var last: String? = null

    override fun analyze(image: ImageProxy) {
        image.use {
            if (found.get()) return
            val plane = it.planes[0]
            val stride = plane.rowStride
            val size = stride * it.height
            if (buffer.size != size) buffer = ByteArray(size)
            val data = plane.buffer.apply { rewind() }
            data.get(buffer, 0, minOf(data.remaining(), size))
            val source = PlanarYUVLuminanceSource(buffer, stride, it.height, 0, 0, it.width, it.height, false)
            val text = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (_: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            // The same code many times in a row (a frame stays up for several analyses): report it once.
            if (text != null && text != last) {
                last = text
                if (text.trim().startsWith(ShareCodec.QR_PREFIX) && !ShareFrames.isFrame(text)) found.set(true)
                onText(text)
            }
        }
    }
}
