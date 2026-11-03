package com.poscafe.pos.ui.screens.report

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poscafe.pos.ui.components.Money

/** Chart palette: the till's green, the sidebar's sapphire and champagne, then supporting hues. */
internal val ChartColors = listOf(
    Color(0xFF10A06C), Color(0xFF0C5A9A), Color(0xFFD9B872), Color(0xFF38BDF8),
    Color(0xFFF97316), Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF64748B),
)

internal fun chartColor(i: Int) = ChartColors[i % ChartColors.size]

/** Compact money for axis labels: 12.5k, 1.2M. */
internal fun shortMoney(v: Double): String = when {
    kotlin.math.abs(v) < 0.5 -> "0"
    kotlin.math.abs(v) >= 1_000_000 -> "%.1fM".format(v / 1_000_000).replace(".0M", "M")
    kotlin.math.abs(v) >= 1_000 -> "%.1fk".format(v / 1_000).replace(".0k", "k")
    else -> "%.0f".format(v)
}

/** Grows from 0 to 1 once per [key], so charts draw in when data changes. */
@Composable
private fun rememberReveal(key: Any?): Float {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(key) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    return anim.value
}

/**
 * Smooth area trend with a tap-to-inspect marker. [labels] align with [values]; the selected
 * point (default: the peak) shows its label and amount above the chart.
 */
@Composable
internal fun TrendChart(
    values: List<Double>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    onColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (values.isEmpty()) return
    val reveal = rememberReveal(values)
    val peak = values.indices.maxByOrNull { values[it] } ?: 0
    var selected by remember(values) { mutableIntStateOf(peak) }
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(labels.getOrElse(selected) { "" }, style = MaterialTheme.typography.labelMedium, color = onColor.copy(alpha = 0.7f))
            Spacer(Modifier.width(8.dp))
            Text(Money.format(values[selected]), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = onColor)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(values) {
                    detectTapGestures { o ->
                        val step = if (values.size > 1) size.width / (values.size - 1f) else 0f
                        selected = if (step == 0f) 0 else (o.x / step).let { kotlin.math.round(it).toInt() }.coerceIn(0, values.lastIndex)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val step = if (values.size > 1) w / (values.size - 1) else 0f
            fun pt(i: Int) = Offset(if (values.size == 1) w / 2 else i * step, h - (values[i] / max).toFloat() * h * 0.9f * reveal)
            // Baseline grid.
            for (g in 1..3) {
                val y = h * g / 4f
                drawLine(onColor.copy(alpha = 0.08f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            val line = Path()
            values.indices.forEach { i ->
                val p = pt(i)
                if (i == 0) line.moveTo(p.x, p.y)
                else {
                    val prev = pt(i - 1)
                    val mx = (prev.x + p.x) / 2
                    line.cubicTo(mx, prev.y, mx, p.y, p.x, p.y)
                }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(pt(values.lastIndex).x, h)
                lineTo(pt(0).x, h)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            val s = pt(selected)
            drawLine(color.copy(alpha = 0.4f), Offset(s.x, 0f), Offset(s.x, h), strokeWidth = 1.5f)
            drawCircle(Color.White, 6.dp.toPx(), s)
            drawCircle(color, 4.dp.toPx(), s)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(labels.firstOrNull() ?: "", style = MaterialTheme.typography.labelSmall, color = onColor.copy(alpha = 0.6f))
            Text(labels.lastOrNull() ?: "", style = MaterialTheme.typography.labelSmall, color = onColor.copy(alpha = 0.6f))
        }
    }
}

/** Ring chart with the total in its hole. */
@Composable
internal fun Donut(amounts: List<Double>, center: String, caption: String, modifier: Modifier = Modifier) {
    val reveal = rememberReveal(amounts)
    val total = amounts.sum().coerceAtLeast(0.0001)
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val stroke = size.minDimension * 0.16f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            var start = -90f
            amounts.forEachIndexed { i, a ->
                val sweep = (a / total * 360f).toFloat() * reveal
                drawArc(chartColor(i), start, if (amounts.size > 1) (sweep - 1.5f).coerceAtLeast(0.5f) else sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 24 hourly bars; the busiest hour is highlighted and named. */
@Composable
internal fun HourBars(amounts: List<Double>, color: Color, modifier: Modifier = Modifier) {
    val reveal = rememberReveal(amounts)
    val max = (amounts.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val peak = amounts.indices.maxByOrNull { amounts[it] } ?: 0
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val gap = 3.dp.toPx()
            val bw = (size.width - gap * 23) / 24
            amounts.forEachIndexed { i, a ->
                val bh = (a / max).toFloat() * size.height * reveal
                drawRoundRect(
                    color = if (i == peak && a > 0) color else color.copy(alpha = if (a > 0) 0.45f else 0.08f),
                    topLeft = Offset(i * (bw + gap), size.height - bh.coerceAtLeast(2f)),
                    size = Size(bw, bh.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(bw / 2.5f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "23").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}

/** Day-of-week × hour intensity grid (only hours that ever sold, to stay readable on a phone). */
@Composable
internal fun Heatmap(heat: List<List<Double>>, color: Color, modifier: Modifier = Modifier) {
    val activeHours = (0..23).filter { h -> heat.any { it[h] > 0 } }
    if (activeHours.isEmpty()) return
    val first = activeHours.first()
    val last = activeHours.last()
    val hours = (first..last).toList()
    val max = heat.flatten().maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        days.forEachIndexed { d, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.width(32.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    hours.forEach { h ->
                        val v = heat[d][h]
                        Box(
                            Modifier
                                .weight(1f)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (v <= 0) empty else color.copy(alpha = (0.18f + 0.82f * (v / max).toFloat()))),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 32.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("%02d:00".format(first), style = MaterialTheme.typography.labelSmall, color = muted)
            Text("%02d:00".format(last), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

internal data class WaterStep(val label: String, val delta: Double, val total: Boolean = false)

/**
 * Cash waterfall: each step floats from the running balance; totals stand on the axis.
 * Inflows green, outflows coral.
 */
@Composable
internal fun Waterfall(steps: List<WaterStep>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    val reveal = rememberReveal(steps)
    var running = 0.0
    val spans = steps.map { s ->
        if (s.total) { running = s.delta; 0.0 to s.delta }
        else { val a = running; running += s.delta; a to running }
    }
    val lo = minOf(0.0, spans.minOf { minOf(it.first, it.second) })
    val hi = spans.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(lo + 1)
    val up = ChartColors[0]
    val down = ChartColors[4]
    val total = ChartColors[1]
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val gap = 10.dp.toPx()
            val bw = (size.width - gap * (steps.size - 1)) / steps.size
            fun y(v: Double) = (size.height - ((v - lo) / (hi - lo)).toFloat() * size.height)
            steps.forEachIndexed { i, s ->
                val (a, b) = spans[i]
                val top = y(maxOf(a, b))
                val bottom = y(minOf(a, b))
                val h = ((bottom - top) * reveal).coerceAtLeast(2f)
                val c = when { s.total -> total; s.delta >= 0 -> up; else -> down }
                drawRoundRect(c, Offset(i * (bw + gap), bottom - h), Size(bw, h), CornerRadius(6.dp.toPx()))
                if (i < steps.lastIndex) {
                    val yy = y(b)
                    drawLine(onSurface.copy(alpha = 0.25f), Offset(i * (bw + gap) + bw, yy), Offset((i + 1) * (bw + gap), yy), strokeWidth = 1.5f)
                }
            }
            drawLine(onSurface.copy(alpha = 0.2f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1f)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEach { s ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        (if (!s.total && s.delta > 0) "+" else "") + shortMoney(s.delta),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(s.label, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 2, fontSize = 10.sp, lineHeight = 12.sp)
                }
            }
        }
    }
}

/** Thin animated share bar (0..1). */
@Composable
internal fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "share")
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Legend swatch. */
@Composable
internal fun Swatch(color: Color) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
}
