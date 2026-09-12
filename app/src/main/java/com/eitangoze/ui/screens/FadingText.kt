package com.eitangoze.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.Repository
import com.eitangoze.data.TextReport
import com.eitangoze.data.TextSpan
import com.eitangoze.ui.theme.Marks

/**
 * The passage, drawn as the learner will see it on a chosen date.
 *
 * How much you can read *today* is something you find out by reading. What you
 * cannot find out by reading is what will still be there in three months, and
 * that is the only question this view answers: drag the slider forward and the
 * words whose predicted recall has dropped below the readable line fade out of
 * the page, one by one, in the order your own memory will lose them.
 *
 * Nothing here is a metaphor for forgetting. Each word's opacity *is* its FSRS
 * recall probability at that date.
 */
@Composable
fun FadingPassage(
    report: TextReport,
    at: Long,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val annotated = remember(report.text, at) {
        buildFadedText(report, at, colors.onSurface, colors.primary)
    }
    Text(
        annotated,
        modifier = modifier,
        fontSize = 17.sp,
        lineHeight = 30.sp,
    )
}

/**
 * Ink for what survives, paper for what does not.
 *
 * Words that were never studied are drawn faint with a dotted underline: they
 * are not being forgotten, they were never there, and the difference matters
 * when deciding what to do about them.
 */
private fun buildFadedText(
    report: TextReport,
    at: Long,
    ink: Color,
    accent: Color,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val dotted = TextDecoration.Underline
    for (span in report.spans) {
        if (span.start > cursor) append(report.text.substring(cursor, span.start))
        val word = report.text.substring(span.start, span.end)
        val recall = span.recallAt(at)
        when {
            span.permanent || recall >= TextSpan.READABLE ->
                withStyleAppend(word, SpanStyle(color = ink))

            span.lastReview != null -> {
                // Studied, and on its way out: fade in proportion to recall so a
                // word at 0.79 is barely dimmed and one at 0.1 is nearly gone.
                val alpha = (0.12f + 0.88f * recall.toFloat()).coerceIn(0.12f, 1f)
                withStyleAppend(word, SpanStyle(color = ink.copy(alpha = alpha)))
            }

            else -> withStyleAppend(
                word,
                SpanStyle(
                    color = accent.copy(alpha = 0.55f),
                    textDecoration = dotted,
                ),
            )
        }
        cursor = span.end
    }
    if (cursor < report.text.length) append(report.text.substring(cursor))
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleAppend(
    text: String,
    style: SpanStyle,
) {
    pushStyle(style)
    append(text)
    pop()
}

/** The dates the slider stops at, chosen so the curve's shape is visible. */
data class Horizon(val label: String, val days: Int)

fun horizons(examDate: Long, now: Long): List<Horizon> {
    val base = listOf(
        Horizon("今", 0),
        Horizon("1週間後", 7),
        Horizon("1か月後", 30),
        Horizon("3か月後", 90),
        Horizon("半年後", 180),
    )
    if (examDate <= now) return base
    val daysToExam = ((examDate - now) / Repository.DAY_MS).toInt()
    return (base.filter { it.days < daysToExam } + Horizon("試験日", daysToExam))
        .sortedBy { it.days }
}

/**
 * The slider, and the sentence that says what the current position means.
 *
 * The number beside it is the share of the passage still readable on that date —
 * the one figure in the app that could not be obtained by simply reading.
 */
@Composable
fun HorizonControl(
    report: TextReport,
    horizonList: List<Horizon>,
    index: Int,
    now: Long,
    onIndex: (Int) -> Unit,
) {
    val horizon = horizonList.getOrElse(index) { horizonList.first() }
    val at = now + horizon.days.toLong() * Repository.DAY_MS
    val coverage = report.coverageAt(at)
    val today = report.coverageAt(now)
    val lost = report.fadingBy(at, now).size

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(horizon.label, fontWeight = FontWeight.SemiBold)
            Text(
                "%.0f%% 読める".format(coverage * 100),
                fontWeight = FontWeight.SemiBold,
                color = if (coverage >= TextReport.ASSISTED) Marks.correct else Marks.wrong,
            )
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { onIndex(it.toInt()) },
            valueRange = 0f..(horizonList.size - 1).coerceAtLeast(1).toFloat(),
            steps = (horizonList.size - 2).coerceAtLeast(0),
        )
        Text(
            if (horizon.days == 0) {
                "スライダーを動かすと、何もしなかった場合に読めなくなる語から消えます"
            } else {
                "何もしなければ ${lost} 語が抜け落ち、${(today * 100).toInt()}% → " +
                    "${(coverage * 100).toInt()}% になります"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Coverage from today to the far horizon, with the 98% line drawn across it.
 *
 * The shape is the point: it falls fastest in the first weeks, which is what
 * makes "I read it fine last month" and "I cannot read it now" both true.
 */
@Composable
fun DecayCurve(report: TextReport, days: Int, now: Long, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val threshold = MaterialTheme.colorScheme.error
    val samples = remember(report.text, days) {
        val steps = 40
        (0..steps).map { step ->
            val at = now + (days.toLong() * step / steps) * Repository.DAY_MS
            report.coverageAt(at).toFloat()
        }
    }
    Canvas(modifier.fillMaxWidth().height(84.dp)) {
        val w = size.width
        val h = size.height
        fun y(value: Float) = h - value * h
        drawLine(grid, Offset(0f, y(0f)), Offset(w, y(0f)), strokeWidth = 1f)
        drawLine(
            threshold.copy(alpha = 0.7f),
            Offset(0f, y(TextReport.UNASSISTED.toFloat())),
            Offset(w, y(TextReport.UNASSISTED.toFloat())),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
        val path = Path()
        samples.forEachIndexed { i, value ->
            val x = w * i / (samples.size - 1).toFloat()
            if (i == 0) path.moveTo(x, y(value)) else path.lineTo(x, y(value))
        }
        drawPath(path, line, style = Stroke(width = 3f))
    }
}

/** Legend for the two reasons a word is not black. */
@Composable
fun FadingLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendItem("読める", MaterialTheme.colorScheme.onSurface)
        LegendItem("薄い = 忘れかけ", MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        LegendItem("下線 = 未学習", MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
    }
}

@Composable
private fun LegendItem(label: String, colour: Color) {
    Row {
        Spacer(
            Modifier
                .height(12.dp)
                .padding(end = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colour),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = colour)
    }
}
