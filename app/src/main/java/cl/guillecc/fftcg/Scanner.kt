package cl.guillecc.fftcg

import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/**
 * Lee el código impreso abajo a la derecha de la carta ("1-001H", "PR-029", "Re-001H/12-002H").
 * Corrige confusiones típicas del OCR y solo acepta códigos que existen en el índice,
 * vistos en dos cuadros seguidos.
 */
class CodeReader(private val exists: (String) -> Boolean) {
    private val token = Regex("([A-Z0-9]{1,3})\\s?[-–—]+\\s?([0-9OIDLSBZ]{3})")
    private val digits = mapOf('O' to '0', 'D' to '0', 'I' to '1', 'L' to '1', 'S' to '5', 'B' to '8', 'Z' to '2')
    private var last: String? = null
    private var hits = 0
    private var lastReported: String? = null
    private var reportedAt = 0L

    private fun fix(s: String) = s.map { digits[it] ?: it }.joinToString("")

    fun codes(text: String): List<String> =
        token.findAll(text.uppercase()).flatMap { m ->
            val prefix = m.groupValues[1]
            val number = fix(m.groupValues[2]).toInt()
            listOf(prefix, fix(prefix)).distinct().map { "$it-$number" }
        }.filter(exists).distinct().toList()

    /** Devuelve una clave confirmada, o null. */
    fun accept(text: String): String? {
        val key = codes(text).firstOrNull() ?: return null
        if (key == last) hits++ else { last = key; hits = 1 }
        if (hits < 2) return null
        hits = 0
        // La misma carta sigue frente a la cámara tras agregarla: no repetir por unos segundos
        val now = System.currentTimeMillis()
        if (key == lastReported && now - reportedAt < 4000) return null
        lastReported = key
        reportedAt = now
        return key
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScanner(paused: Boolean, onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentPaused by rememberUpdatedState(paused)
    val previewView = remember { PreviewView(ctx) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val reader = CodeReader(CardIndex::exists)
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || currentPaused) {
                    proxy.close()
                    return@setAnalyzer
                }
                recognizer.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { text ->
                        // Corre en el hilo principal
                        val key = reader.accept(text.text)
                        if (key != null && !currentPaused) currentOnCode(key)
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            recognizer.close()
            executor.shutdown()
        }
    }

    Box(modifier) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        CardFrame(found = paused, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(44.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Bolt, contentDescription = "Linterna",
                tint = if (torch) Color(0xFFF59E0B) else Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Guía para encuadrar la carta (proporción 63×88 mm): oscurece el resto de la imagen y marca
 * con línea punteada dónde va el código (abajo a la derecha). Se pone verde al reconocer una carta.
 */
@Composable
fun CardFrame(found: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        var h = size.height * 0.9f
        var w = h * 63f / 88f
        if (w > size.width * 0.82f) {
            w = size.width * 0.82f
            h = w * 88f / 63f
        }
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        val radius = CornerRadius(w * 0.045f)
        val color = if (found) Color(0xFF5BD47A) else Color.White

        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(left, top, left + w, top + h, radius))
        }
        drawPath(outside, Color.Black.copy(alpha = 0.5f))
        drawRoundRect(color, Offset(left, top), GSize(w, h), radius, style = Stroke(3.dp.toPx()))

        // Zona del código
        val cw = w * 0.34f
        val ch = h * 0.08f
        drawRoundRect(
            color,
            Offset(left + w - cw - w * 0.03f, top + h - ch - h * 0.02f),
            GSize(cw, ch),
            CornerRadius(4.dp.toPx()),
            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
        )
    }
}
