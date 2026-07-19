package com.gemmark.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Chart primitives drawn with Canvas — no chart dependency, styled after the
 * reference design (smooth splines, soft gradient fills, rounded bars).
 */

/** Smooth polyline through normalized points using midpoint quadratics. */
private fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size == 1) return path
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val midX = (prev.x + curr.x) / 2
        path.quadraticTo(prev.x, prev.y, midX, (prev.y + curr.y) / 2)
        if (i == points.lastIndex) {
            path.quadraticTo(curr.x, curr.y, curr.x, curr.y)
        }
    }
    return path
}

private fun normalize(values: List<Float>, width: Float, height: Float, pad: Float): List<Offset> {
    if (values.isEmpty()) return emptyList()
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 1e-6f } ?: 1f
    val stepX = if (values.size > 1) (width - 2 * pad) / (values.size - 1) else 0f
    return values.mapIndexed { i, v ->
        Offset(
            x = pad + i * stepX,
            y = pad + (height - 2 * pad) * (1f - (v - min) / range),
        )
    }
}

/** Small live line for the run screen (thermal / power cards). */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val points = normalize(values, size.width, size.height, pad = 6f)
        val line = buildSmoothPath(points)

        val fill = Path().apply {
            addPath(line)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }
        drawPath(
            fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0f)),
            ),
        )
        drawPath(line, color = color, style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}

/**
 * Decode-decay chart: zero-baseline (a flat fast run reads flat — min/max
 * normalization would blow a 0.4 tok/s wobble up to full chart height),
 * gridlines + spline + gradient fill + x labels.
 */
@Composable
fun DecayLineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier) {
        if (values.isNotEmpty()) {
            Text(
                "0 – %.1f tok/s".format(values.max()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            if (values.size < 2) return@Canvas
            val pad = 8f
            repeat(3) { i ->
                val y = size.height * (0.25f + 0.25f * i)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
            val max = values.max().coerceAtLeast(1e-6f)
            val stepX = (size.width - 2 * pad) / (values.size - 1)
            val points = values.mapIndexed { i, v ->
                Offset(
                    x = pad + i * stepX,
                    y = pad + (size.height - 2 * pad) * (1f - v / max),
                )
            }
            val line = buildSmoothPath(points)
            val fill = Path().apply {
                addPath(line)
                lineTo(points.last().x, size.height)
                lineTo(points.first().x, size.height)
                close()
            }
            drawPath(
                fill,
                brush = Brush.verticalGradient(
                    listOf(lineColor.copy(alpha = 0.18f), lineColor.copy(alpha = 0f)),
                ),
            )
            drawPath(line, color = lineColor, style = Stroke(width = 6f, cap = StrokeCap.Round))
        }
        if (values.size >= 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val marks = listOf(1, values.size / 3, values.size * 2 / 3, values.size)
                    .distinct()
                    .filter { it >= 1 }
                marks.forEach {
                    Text(
                        "R$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Per-round bars (temperature peaks); the max bar is highlighted. */
@Composable
fun RoundBarsChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
    peakColor: Color = MaterialTheme.colorScheme.errorContainer,
    labelEvery: Int = 5,
) {
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        ) {
            if (values.isEmpty()) return@Canvas
            val min = values.min()
            val max = values.max()
            val range = (max - min).takeIf { it > 1e-6f } ?: 1f
            val gap = 6f
            val barWidth = (size.width - gap * (values.size - 1)) / values.size
            val peakIndex = values.indexOf(max)

            values.forEachIndexed { i, v ->
                // Keep short bars visible: floor at 15% height.
                val h = size.height * (0.15f + 0.85f * (v - min) / range)
                val left = i * (barWidth + gap)
                drawRoundRect(
                    color = if (i == peakIndex) peakColor else barColor,
                    topLeft = Offset(left, size.height - h),
                    size = androidx.compose.ui.geometry.Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 4, barWidth / 4),
                )
            }
        }
        if (values.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "R1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (values.size > labelEvery) {
                    Text(
                        "R${values.size / 2 + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "R${values.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

/** Determinate circular progress ring with center content. */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = ringColor.copy(alpha = 0.12f),
    strokeWidth: Float = 52f,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier,
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = strokeWidth / 2 + 2f
            val arcSize = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        content()
    }
}
