package io.github.hypercar.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.painterResource
import io.github.hypercar.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.StrokeGlass
import io.github.hypercar.ui.theme.SurfaceInset
import io.github.hypercar.ui.theme.LocalAppPalette
import java.util.Locale

/** Local vector badges: shared by settings and home, with a fallback for future remote profiles. */
@Composable
fun CarBrandBadge(profileId: String, label: String, modifier: Modifier = Modifier, size: Dp = 44.dp, framed: Boolean = true) {
    val identity = "$profileId $label".lowercase(Locale.ROOT)
    val brand = when {
        "bmw" in identity || "宝马" in identity -> "BMW"
        "mercedes" in identity || "奔驰" in identity -> "Mercedes-Benz"
        "audi" in identity || "奥迪" in identity -> "Audi"
        "volkswagen" in identity || "大众" in identity -> "Volkswagen"
        "winplay" in identity || "diplay" in identity -> "desktop"
        else -> "generic"
    }
    val dark = LocalAppPalette.current.dark
    val frame = if (framed) Modifier.background(
        if (brand == "generic" || brand == "desktop") SurfaceInset else Color(0xFF17202C), RoundedCornerShape(12.dp))
        .border(1.dp, StrokeGlass.copy(alpha = 0.65f), RoundedCornerShape(12.dp)).padding(6.dp) else Modifier
    Box(
        modifier.size(size).then(frame).semantics {
            contentDescription = if (brand == "generic" || brand == "desktop") label else "$brand 品牌标识"
        },
        contentAlignment = Alignment.Center,
    ) {
        if (brand == "generic" || brand == "desktop") {
            Icon(if (brand == "desktop") Icons.Rounded.DesktopWindows else Icons.Rounded.DirectionsCar,
                contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(size * 0.54f))
        } else if (brand == "BMW") {
            Image(painterResource(if (framed || dark) R.drawable.ic_brand_bmw_light else R.drawable.ic_brand_bmw),
                contentDescription = null, modifier = Modifier.size(size))
        } else {
            Canvas(Modifier.size(size)) {
                val chrome = if (framed || dark) listOf(Color(0xFFF9FAFC), Color(0xFF8E9AA9), Color(0xFFE6EBF2))
                    else listOf(Color(0xFF263748), Color(0xFF7B8D9D), Color(0xFF33485C))
                val silver = Brush.linearGradient(chrome, Offset.Zero, Offset(64f, 64f))
                withTransform({ scale(this@Canvas.size.width / 64f, this@Canvas.size.height / 64f, Offset.Zero) }) {
                    when (brand) {
                        "Mercedes-Benz" -> {
                            drawCircle(silver, 28f, Offset(32f, 32f), style = Stroke(3f))
                            listOf(Offset(32f, 5f), Offset(8.6f, 45.5f), Offset(55.4f, 45.5f)).forEach { tip ->
                                val dx = tip.x - 32f; val dy = tip.y - 32f
                                val blade = Path().apply {
                                    moveTo(tip.x, tip.y); lineTo(32f - dy * 0.095f, 32f + dx * 0.095f)
                                    lineTo(32f - dx * 0.12f, 32f - dy * 0.12f)
                                    lineTo(32f + dy * 0.095f, 32f - dx * 0.095f); close()
                                }
                                drawPath(blade, silver)
                            }
                        }
                        "Audi" -> {
                            for (x in listOf(12.5f, 25.5f, 38.5f, 51.5f)) {
                                drawCircle(Color(0xFF182331), 10.4f, Offset(x, 32f), style = Stroke(4f))
                                drawCircle(silver, 10.4f, Offset(x, 32f), style = Stroke(2.6f))
                            }
                        }
                        "Volkswagen" -> {
                            drawCircle(silver, 28f, Offset(32f, 32f), style = Stroke(3f))
                            val v = Path().apply { moveTo(20f, 10f); lineTo(32f, 32f); lineTo(44f, 10f) }
                            val w = Path().apply { moveTo(9f, 22f); lineTo(23f, 51f); lineTo(32f, 36f); lineTo(41f, 51f); lineTo(55f, 22f) }
                            drawPath(v, silver, style = Stroke(3.8f, cap = StrokeCap.Round))
                            drawPath(w, silver, style = Stroke(3.8f, cap = StrokeCap.Round))
                        }
                    }
                }
            }
        }
    }
}
