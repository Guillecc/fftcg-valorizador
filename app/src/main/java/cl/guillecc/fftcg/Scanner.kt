package cl.guillecc.fftcg

import android.os.Build
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Un código leído: [key] = "PR-158", [known] = existe en el índice, [text] = todo el texto OCR de ese cuadro. */
data class ScanHit(val key: String, val known: Boolean, val text: String)

/**
 * Lee el código impreso abajo en la carta ("1-001H", "PR-029", "Re-001H/12-002H").
 * Corrige confusiones típicas del OCR. Un código del índice se acepta tras dos cuadros seguidos;
 * uno con forma válida que no está en el índice, tras tres (más estricto para no reportar ruido).
 */
class CodeReader(
    private val exists: (String) -> Boolean,
    private val plausiblePrefix: (String) -> Boolean,
) {
    private val token = Regex("([A-Z0-9]{1,3})\\s?[-–—]+\\s?([0-9OIDLSBZ]{3})")
    private val digits = mapOf('O' to '0', 'D' to '0', 'I' to '1', 'L' to '1', 'S' to '5', 'B' to '8', 'Z' to '2')
    private var last: String? = null
    private var hits = 0
    private var lastReported: String? = null
    private var reportedAt = 0L

    private fun fix(s: String) = s.map { digits[it] ?: it }.joinToString("")

    /** Claves leídas en el texto (true = está en el índice); primero las conocidas. */
    fun codes(text: String): List<Pair<String, Boolean>> {
        val known = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (m in token.findAll(text.uppercase())) {
            val prefixes = listOf(m.groupValues[1], fix(m.groupValues[1])).distinct()
            val number = fix(m.groupValues[2]).toInt()
            val key = prefixes.map { "$it-$number" }.firstOrNull(exists)
            if (key != null) known += key
            else prefixes.firstOrNull(plausiblePrefix)?.let { unknown += "$it-$number" }
        }
        return known.distinct().map { it to true } + unknown.distinct().map { it to false }
    }

    /** Devuelve un código confirmado, o null. */
    fun accept(text: String): ScanHit? {
        val (key, known) = codes(text).firstOrNull() ?: return null
        // La misma carta sigue frente a la cámara tras reportarla: cada vez que se ve se renueva la espera,
        // así solo vuelve a contar si sale del cuadro (~2,5 s sin verla) y se escanea de nuevo
        val now = System.currentTimeMillis()
        if (key == lastReported && now - reportedAt < 2500) {
            reportedAt = now
            hits = 0
            return null
        }
        if (key == last) hits++ else { last = key; hits = 1 }
        if (hits < if (known) 2 else 3) return null
        hits = 0
        lastReported = key
        reportedAt = now
        return ScanHit(key, known, text)
    }
}

/**
 * Cámara con lector de códigos. [paused] deja de leer y muestra el marco en verde; cada cambio de [flash]
 * (modo Lote) muestra el verde ~700 ms sin pausar.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScanner(paused: Boolean, onCode: (ScanHit) -> Unit, modifier: Modifier = Modifier, flash: Int = 0) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentPaused by rememberUpdatedState(paused)
    val view = LocalView.current
    val previewView = remember { PreviewView(ctx) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(flash) {
        if (flash > 0) {
            flashing = true
            delay(700)
            flashing = false
        }
    }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val reader = CodeReader(CardIndex::exists, CardIndex::plausiblePrefix)
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        var disposed = false // listener y onDispose corren en el hilo principal
        providerFuture.addListener({
            if (disposed) return@addListener
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
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
                        val hit = reader.accept(text.text)
                        if (hit != null && !currentPaused) {
                            confirmHaptic(view)
                            currentOnCode(hit)
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))

        onDispose {
            disposed = true
            // Sin bloquear el hilo principal: si la cámara aún no abrió, el listener ya no la enlaza
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            recognizer.close()
            executor.shutdown()
        }
    }

    Box(modifier) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        CardFrame(found = paused || flashing, modifier = Modifier.fillMaxSize())
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

/** Un solo pulso corto al aceptar un código (no requiere permiso VIBRATE). */
private fun confirmHaptic(view: View) {
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
    else HapticFeedbackConstants.LONG_PRESS
    view.performHapticFeedback(type)
}

private val FoundGreen = Color(0xFF5BD47A)

/** Rectángulo de la carta (63×88) centrado en un área de [width]×[height] (px o dp, según quien llame). */
private fun frameRect(width: Float, height: Float): Rect {
    var h = height * 0.9f
    var w = h * 63f / 88f
    if (w > width * 0.82f) {
        w = width * 0.82f
        h = w * 88f / 63f
    }
    val left = (width - w) / 2
    val top = (height - h) / 2
    return Rect(left, top, left + w, top + h)
}

/**
 * Guía para encuadrar la carta (proporción 63×88 mm): oscurece el resto de la imagen, marca las
 * esquinas y con línea punteada dónde va el código (abajo a la izquierda). Mientras busca, una línea
 * recorre la carta; al reconocerla se pone verde con un pulso y muestra un check.
 */
@Composable
fun CardFrame(found: Boolean, modifier: Modifier = Modifier) {
    // El barrido solo existe (y consume) mientras se busca
    val sweep: State<Float>? = if (!found) {
        rememberInfiniteTransition(label = "sweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
            label = "sweepPos",
        )
    } else null
    val progress by animateFloatAsState(if (found) 1f else 0f, tween(250), label = "found")
    val badge by animateFloatAsState(
        if (found) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "badge",
    )
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(found) {
        if (found) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(250))
        } else {
            pulse.snapTo(0f)
        }
    }

    BoxWithConstraints(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val r = frameRect(size.width, size.height)
            val radius = CornerRadius(r.width * 0.045f)
            val color = lerp(Color.White, FoundGreen, progress)
            val bump = sin(pulse.value * PI.toFloat())

            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(r, radius))
            }
            drawPath(outside, Color.Black.copy(alpha = 0.5f))

            // Tinte verde suave dentro de la carta
            if (progress > 0f) drawRoundRect(FoundGreen.copy(alpha = 0.12f * progress), r.topLeft, r.size, radius)

            // Contorno tenue
            drawRoundRect(
                color.copy(alpha = 0.35f + 0.35f * progress), r.topLeft, r.size, radius,
                style = Stroke(1.dp.toPx()),
            )

            // Esquinas en L (siguen la curva del borde)
            val len = min(r.width, r.height) * 0.22f
            val rc = radius.x
            val k = 1f - 0.5523f
            val brackets = Path()
            fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
                brackets.moveTo(cx, cy + dy * len)
                brackets.lineTo(cx, cy + dy * rc)
                brackets.cubicTo(cx, cy + dy * rc * k, cx + dx * rc * k, cy, cx + dx * rc, cy)
                brackets.lineTo(cx + dx * len, cy)
            }
            corner(r.left, r.top, 1f, 1f)
            corner(r.right, r.top, -1f, 1f)
            corner(r.left, r.bottom, 1f, -1f)
            corner(r.right, r.bottom, -1f, -1f)
            scale(1f + 0.04f * bump, pivot = r.center) {
                drawPath(
                    brackets, color,
                    style = Stroke(4.dp.toPx() * (1f + 0.5f * bump), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // Línea de barrido
            if (sweep != null) {
                val t = sweep.value
                val inset = 8.dp.toPx()
                val y = r.top + inset + t * (r.height - 2 * inset)
                drawLine(
                    Color.White.copy(alpha = 0.35f * sin(t * PI.toFloat())),
                    Offset(r.left + inset, y), Offset(r.right - inset, y),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
            }

            // Zona del código: en las cartas del dueño va impreso abajo a la izquierda, así que el recuadro
            // queda dentro de la esquina en L inferior izquierda, con margen (grosor de la esquina + 4dp)
            // para no tocar su trazo. Se desvanece al detectar
            if (progress < 1f) {
                val cw = r.width * 0.36f
                val ch = r.height * 0.07f
                val clear = 8.dp.toPx()
                val cx = r.left + (r.width * 0.04f).coerceAtLeast(clear)
                val cy = r.bottom - (r.height * 0.02f).coerceAtLeast(clear) - ch
                drawRoundRect(
                    Color.White.copy(alpha = 1f - progress),
                    Offset(cx, cy),
                    GSize(cw, ch),
                    CornerRadius(4.dp.toPx()),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }

            // Check centrado
            val s = badge.coerceAtLeast(0f)
            if (s > 0.01f && progress > 0f) {
                val c = r.center
                drawCircle(FoundGreen.copy(alpha = progress), 28.dp.toPx() * s, c)
                val u = 1.dp.toPx() * s
                val check = Path().apply {
                    moveTo(c.x - 11 * u, c.y + 1 * u)
                    lineTo(c.x - 4 * u, c.y + 8 * u)
                    lineTo(c.x + 12 * u, c.y - 8 * u)
                }
                drawPath(
                    check, Color.White.copy(alpha = progress),
                    style = Stroke(4 * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        // Estado: debajo de la carta si hay espacio; si no, arriba a la izquierda (a la altura de la linterna)
        val fr = frameRect(maxWidth.value, maxHeight.value)
        val roomBelow = maxHeight.value - fr.bottom >= 40f
        val pillPlace = if (roomBelow) {
            Modifier.align(Alignment.TopCenter).padding(top = fr.bottom.dp + 8.dp)
        } else {
            Modifier.align(Alignment.TopStart).padding(8.dp).height(44.dp)
        }
        Box(pillPlace, contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(lerp(Color.Black.copy(alpha = 0.55f), FoundGreen, progress))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    if (found) "✓ Detectada" else "Buscando código…",
                    color = lerp(Color.White.copy(alpha = 0.85f), Color(0xFF0B2A14), progress),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (found) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
