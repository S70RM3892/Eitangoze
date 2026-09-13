package com.eitangoze.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.Repository
import com.eitangoze.data.TextReport
import com.eitangoze.ui.theme.Marks

const val VERDICT_TAG = "reading-verdict"

/**
 * What a finished reading actually says.
 *
 * Every speed-reading course in existence assumes the problem is technique. For
 * most readers of English as a second language it is not: below about 98% known
 * words, reading breaks down no matter how the eyes move (Hu & Nation 2000), and
 * the stops are unknown words. Telling those two cases apart needs the coverage
 * of *this* text for *this* reader, word by word — which is the one thing this
 * app has and a stopwatch does not.
 *
 * So the result is a diagnosis, not a score. Three lines, in the order they
 * block each other: vocabulary, then syntax, then speed.
 */
@Composable
fun ReadingResultScreen(
    result: Repository.ReadingResult,
    onFoldSentences: () -> Unit,
    onStudyGaps: () -> Unit,
    onAgain: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val report = result.report
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(VERDICT_TAG),
    ) {
        Text(
            "読了  ${result.passage.words} 語 / ${humanTime(result.elapsedMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${result.wpm}", fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            Text("  wpm", style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }

        Spacer(Modifier.height(18.dp))
        Divider()
        Spacer(Modifier.height(14.dp))

        // The three things that can be wrong, in the order they block each other.
        Line(
            label = "語彙",
            value = "${(report.coverage * 100).toInt()}%",
            ok = result.vocabularyIsEnough,
            note = if (result.vocabularyIsEnough) {
                "辞書なしで読める線（98%）を超えています"
            } else {
                "98% に届いていません。あと ${report.gapsToThreshold} 語で届きます"
            },
        )
        Line(
            label = "構文",
            value = if (result.checkable > 0) "${result.checkable} 文" else "—",
            ok = null,
            note = if (result.checkable > 0) {
                "この英文で構文を確認できる文の数です"
            } else {
                "この英文には確認できる文がありません"
            },
        )
        Line(
            label = "速度",
            value = "${result.wpm} wpm",
            ok = result.fastEnough,
            note = "入試の目安は ${Repository.EXAM_WPM} wpm 前後と言われます",
        )

        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.primaryContainer)
                .padding(14.dp),
        ) {
            Text(
                result.verdict,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onPrimaryContainer,
            )
        }

        Spacer(Modifier.height(16.dp))
        // The next move follows from the diagnosis, so it is the button that is
        // offered first.
        if (!result.vocabularyIsEnough) {
            Button(onClick = onStudyGaps, modifier = Modifier.fillMaxWidth()) {
                Text("足りない ${report.gapsToThreshold} 語を学習に入れる")
            }
        } else if (result.checkable > 0) {
            Button(onClick = onFoldSentences, modifier = Modifier.fillMaxWidth()) {
                Text("構文を確認する（${result.checkable} 文）")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) {
                Text("次の英文")
            }
            TextButton(onClick = onClose) { Text("閉じる") }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "この英文の上限は ${(report.reachableCoverage * 100).toInt()}% です" +
                "（残りは固有名詞など、覚えようのない語）。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "98% は辞書なしで読める線、95% は辞書があっても理解が崩れる線です" +
                "（Hu & Nation 2000）。",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Line(label: String, value: String, ok: Boolean?, note: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.titleMedium)
                if (ok != null) {
                    Text(
                        if (ok) "  ✓" else "  ✗",
                        color = if (ok) Marks.correct else Marks.wrong,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(note, style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
        }
    }
}

/**
 * The bar above a passage while it is being read: what it is worth reading for,
 * and the clock.
 */
@Composable
fun ReadingBar(
    report: TextReport?,
    fit: Repository.Fit?,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (report == null) "測っています…" else
                        "いま読める語 ${(report.coverage * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (fit != null) {
                    Text(
                        fit.ja,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (fit) {
                            Repository.Fit.FAST -> Marks.correct
                            Repository.Fit.VOCABULARY -> colors.onSurfaceVariant
                            Repository.Fit.TOO_HARD -> Marks.wrong
                        },
                    )
                }
            }
            if (running) {
                Button(onClick = onStop) { Text("読み終わった") }
            } else {
                Button(onClick = onStart, enabled = report != null) { Text("計って読む") }
            }
        }
    }
}

private fun humanTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}秒" else "${seconds / 60}分${seconds % 60}秒"
}
