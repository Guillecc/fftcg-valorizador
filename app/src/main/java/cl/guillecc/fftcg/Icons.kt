package cl.guillecc.fftcg

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Íconos dibujados a mano (sin depender de material-icons). Se tiñen con el tint de Icon. */
object AppIcons {
    val Search: ImageVector by lazy { icon("M10.5,4a6.5,6.5 0 1,0 0,13a6.5,6.5 0 1,0 0,-13z M15.5,15.5L20,20") }
    val Close: ImageVector by lazy { icon("M6,6L18,18M18,6L6,18") }
    val Tune: ImageVector by lazy { icon("M4,7L20,7M7,12L17,12M10,17L14,17") }
    val Bolt: ImageVector by lazy { icon("M13,2L4,14h7l-1,8l9,-12h-7z", filled = true) }
    val MoreVert: ImageVector by lazy {
        icon(
            "M10.2,5a1.8,1.8 0 1,0 3.6,0a1.8,1.8 0 1,0 -3.6,0z" +
                "M10.2,12a1.8,1.8 0 1,0 3.6,0a1.8,1.8 0 1,0 -3.6,0z" +
                "M10.2,19a1.8,1.8 0 1,0 3.6,0a1.8,1.8 0 1,0 -3.6,0z",
            filled = true,
        )
    }

    private fun icon(path: String, filled: Boolean = false): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = if (filled) SolidColor(Color.White) else null,
                stroke = if (filled) null else SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}
