package cn.gxnu.campus.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/** App identity, not a school crest: a simple stroke path across two hills. */
@Composable
fun CampusMark(modifier: Modifier = Modifier) {
    val palette = LocalCampusPalette.current
    Surface(
        modifier = modifier.size(32.dp),
        shape = RoundedCornerShape(10.dp),
        color = palette.accentWash
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(20.dp)) {
                val ink = palette.accent
                val line = Path().apply {
                    moveTo(size.width * .03f, size.height * .58f)
                    lineTo(size.width * .30f, size.height * .16f)
                    lineTo(size.width * .55f, size.height * .52f)
                    lineTo(size.width * .72f, size.height * .30f)
                    lineTo(size.width * .97f, size.height * .58f)
                }
                drawPath(line, ink, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(
                    ink,
                    Offset(size.width * .18f, size.height * .84f),
                    Offset(size.width * .82f, size.height * .84f),
                    1.8.dp.toPx(),
                    StrokeCap.Round
                )
            }
        }
    }
}
