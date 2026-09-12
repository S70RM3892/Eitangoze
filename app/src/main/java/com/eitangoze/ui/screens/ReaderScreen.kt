package com.eitangoze.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.Knowledge
import com.eitangoze.data.TextReport
import com.eitangoze.ui.AppViewModel
import com.eitangoze.ui.theme.Marks
import kotlin.math.roundToInt

/**
 * Paste English, find out how much of it you can read.
 *
 * The number the screen leads with is coverage of the *running words*, because
 * that is the quantity the reading-threshold research is about: 98% is the point
 * at which a text can be read without stopping, and the gap list is the shortest
 * route from where the learner is to that line.
 */
@Composable
fun ReaderScreen(model: AppViewModel, onOpenEntry: (Long) -> Unit) {
    val report = model.report
    if (report == null) {
        ReaderInput(model)
        return
    }
    ReaderResult(model, report, onOpenEntry)
}

@Composable
private fun ReaderInput(model: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("英文を読めるか測る", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "過去問でも論文でもニュースでも、英文を貼ると「いまのあなたが何％読めるか」と、" +
                "読み切るために足りない単語だけが出ます。他のアプリから共有しても開けます。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = model.readerText,
            onValueChange = model::updateReaderText,
            label = { Text("英文を貼り付け") },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Spacer(Modifier.height(12.dp))
        if (model.analyzing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp))
                Text("  解析中", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Button(
                onClick = { model.analyze() },
                enabled = model.readerText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("読めるか測る") }
        }
    }
}

@Composable
private fun ReaderResult(model: AppViewModel, report: TextReport, onOpenEntry: (Long) -> Unit) {
    val selected = model.selectedGaps
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                CoverageHeadline(report, model.repository?.baselineLevel.orEmpty())
                Spacer(Modifier.height(16.dp))
                KnowledgeBars(report)
                Spacer(Modifier.height(14.dp))
                LabelValue("語数", "${report.tokens} 語（異なり ${report.types}）")
                LabelValue("語彙レベル", "${report.estimatedLevel} 相当")
                LabelValue(
                    "この英文の上限",
                    "全部覚えても ${(report.reachableCoverage * 100).roundToInt()}%" +
                        "（残りは固有名詞など辞書外）",
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.clearReport() }) { Text("別の英文") }
                    if (selected.isNotEmpty()) {
                        Button(onClick = { model.pickSelectedGaps() }) {
                            Text("${selected.size} 語を学習に追加")
                        }
                    }
                }
                model.readerMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Divider()
        }

        if (report.gaps.isNotEmpty()) {
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    Text(
                        "足りない語（出現回数の多い順）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "上から ${report.gapsToThreshold} 語で 98% に届きます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(report.gaps) { gap ->
                val entry = gap.entry ?: return@items
                val withinThreshold = report.gaps.indexOf(gap) < report.gapsToThreshold
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (withinThreshold) MaterialTheme.colorScheme.primaryContainer
                                .copy(alpha = 0.25f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = entry.id in model.selectedGaps,
                        onCheckedChange = { model.toggleGap(entry.id) },
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { onOpenEntry(entry.id) }
                            .padding(vertical = 8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.lemma, fontWeight = FontWeight.Medium)
                            LevelChip(entry)
                            if (gap.occurrences > 1) Chip("${gap.occurrences}回")
                            if (gap.knowledge == Knowledge.LEARNING) Chip("学習中")
                        }
                        Text(
                            entry.ja.joinToString("、"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Divider()
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun CoverageHeadline(report: TextReport, baseline: String) {
    val percent = report.coverage * 100
    val colors = MaterialTheme.colorScheme
    val colour = when {
        report.coverage >= TextReport.UNASSISTED -> Marks.correct
        report.coverage >= TextReport.ASSISTED -> colors.primary
        else -> Marks.wrong
    }
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "%.1f".format(percent),
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                color = colour,
            )
            Text("%", fontSize = 22.sp, color = colour,
                modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
            Text(
                "  読める",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        // The 98% line is drawn on the bar so the gap is visible, not just stated.
        Box(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { report.coverage.toFloat() },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = colour,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                report.coverage >= TextReport.UNASSISTED ->
                    "辞書なしで読める水準です（98% 以上）"
                report.coverage >= TextReport.ASSISTED ->
                    "辞書があれば読めますが、止まらず読むには 98% 要ります。あと ${report.gapsToThreshold} 語"
                else ->
                    "いまは読み通せません。98% までに必要なのは ${report.gapsToThreshold} 語"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "98% は「辞書を引かずに読める」境界（Hu & Nation 2000）。95% を切ると内容がつかめなくなります。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (baseline.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "いまは「このアプリで学習した語」だけを読めると数えています。" +
                    "すでに知っている層があれば、設定の「すでに知っている層」で申告すると実態に合います。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                "$baseline までは知っているものとして数えています",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KnowledgeBars(report: TextReport) {
    val total = report.tokens.coerceAtLeast(1)
    Column {
        Knowledge.entries.forEach { knowledge ->
            val count = report.byKnowledge[knowledge] ?: 0
            if (count == 0) return@forEach
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    knowledge.ja,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp),
                )
                LinearProgressIndicator(
                    progress = { count.toFloat() / total },
                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = when (knowledge) {
                        Knowledge.KNOWN -> Marks.correct
                        Knowledge.LEARNING -> MaterialTheme.colorScheme.primary
                        Knowledge.NEW -> MaterialTheme.colorScheme.secondary
                        Knowledge.UNLISTED -> MaterialTheme.colorScheme.outlineVariant
                    },
                )
                Text(
                    "  ${count * 100 / total}%",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Paste a headword list — the one from whatever book you are using. */
@Composable
fun ImportScreen(model: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("単語帳を取り込む", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "1行に1語、または TSV/CSV の1列目。その順番で出題します。" +
                "意味・例文・語法はこのアプリの収録データを使うので、貼るのは見出し語だけで足ります。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = model.importText,
            onValueChange = model::updateImportText,
            label = { Text("単語のリスト") },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        model.importResult?.let { result ->
            Spacer(Modifier.height(10.dp))
            Text("${result.matched.size} 語が見つかりました",
                style = MaterialTheme.typography.bodyMedium)
            if (result.missing.isNotEmpty()) {
                Text(
                    "未収録 ${result.missing.size} 語: " +
                        result.missing.take(8).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        model.readerMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { model.matchWordList() },
                enabled = model.importText.isNotBlank(),
            ) { Text("照合する") }
            Button(
                onClick = { model.pickImported() },
                enabled = model.importResult?.matched?.isNotEmpty() == true,
            ) { Text("学習に追加") }
        }
        TextButton(onClick = { model.clearImport() }) { Text("消す") }
    }
}
