package com.drinkwatch.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.drinkwatch.app.data.HistoryPoint

/**
 * Price history, no chart library: one line for the daily cheapest price and a
 * dashed line for the median. The point is "is today a good day", not precision.
 */
@Composable
fun Sparkline(
    points: List<HistoryPoint>,
    medianCents: Int?,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) return
    val lineColor = MaterialTheme.colorScheme.primary
    val medianColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val prices = points.map { it.priceCents }.toMutableList()
    medianCents?.let { prices.add(it) }
    val min = prices.min().toFloat()
    val max = prices.max().toFloat()
    val span = (max - min).coerceAtLeast(1f)

    Canvas(modifier.fillMaxWidth().height(64.dp)) {
        val stepX = size.width / (points.size - 1)
        fun y(value: Float) = size.height - ((value - min) / span) * size.height

        medianCents?.let { median ->
            drawLine(
                color = medianColor,
                start = Offset(0f, y(median.toFloat())),
                end = Offset(size.width, y(median.toFloat())),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }

        val path = Path()
        points.forEachIndexed { index, point ->
            val x = index * stepX
            val py = y(point.priceCents.toFloat())
            if (index == 0) path.moveTo(x, py) else path.lineTo(x, py)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 3f))

        val last = points.last()
        drawCircle(
            color = lineColor,
            radius = 4.5f,
            center = Offset(size.width, y(last.priceCents.toFloat())),
        )
    }
}
