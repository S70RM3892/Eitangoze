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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.Entry
import com.eitangoze.data.Substitution
import com.eitangoze.data.WritingReview
import com.eitangoze.data.WritingTask
import com.eitangoze.ui.AppViewModel

const val WRITING_TAG = "writing-screen"

/**
 * 和文英訳: write the English, then see what the corpus wrote.
 *
 * The screen is careful about one thing above all else. It never says correct or
 * wrong. It cannot: several English sentences translate the same Japanese, and
 * the two on the screen may both be right while sharing half their words. What
 * it does say is exactly what it can prove — these content words appear in a
 * translation a person wrote, and these ones do not appear in yours.
 *
 * The finding worth the screen is the missing ones. Every word here is a word
 * this learner is predicted to read on sight, so a word they could not produce
 * is the gap between recognising English and writing it, which is the gap the
 * exam charges for and the one a reading app never shows you.
 */
@Composable
fun WritingScreen(model: AppViewModel, onOpenEntry: (Long) -> Unit = {}) {
    LaunchedEffect(Unit) {
        if (model.writingTask == null && !model.writingLoading) model.nextWriting()
    }
    val task = model.writingTask
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp)
            .testTag(WRITING_TAG),
    ) {
        when {
            model.writingLoading && task == null -> Loading()
            task == null -> NothingToWrite(model)
            else -> Task(model, task, onOpenEntry)
        }
    }
}

@Composable
private fun Loading() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.height(20.dp))
        Text("  書ける文を選んでいます", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * There is nothing to ask yet, and saying why is the useful part: the drill
 * refuses to set a sentence whose words the learner has not met, so an empty
 * first run is the app working rather than failing.
 */
@Composable
private fun NothingToWrite(model: AppViewModel) {
    Text("まだ出せる文がありません", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(
        "和文英訳は、あなたが「読める」と判定された語だけでできた文から出します。" +
            "学習が少し進むと、その条件を満たす文が出てきます。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = model::nextWriting) { Text("もう一度探す") }
}

@Composable
private fun Task(model: AppViewModel, task: WritingTask, onOpenEntry: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val review = model.writingReview

    Text("日本語を英語にする", style = MaterialTheme.typography.labelLarge,
        color = colors.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Text(task.ja, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Text(
        if (task.clean) {
            "この文には、あなたが読める語だけでできた英訳があります。" +
                "書けなかった語があれば、それは「読めるのに書けない語」です。"
        } else {
            "一番近い英訳でも、内容語のうちあなたが読めるのは " +
                "${(task.readiness * 100).toInt()}% です。残りは未学習の語です。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )

    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = model.writingText,
        onValueChange = model::typeWriting,
        readOnly = review != null,
        label = { Text("英語で書く") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
    )

    Spacer(Modifier.height(12.dp))
    if (review == null) {
        Button(
            onClick = model::checkWriting,
            enabled = model.writingText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("参照訳と照合する") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = model::nextWriting, modifier = Modifier.fillMaxWidth()) {
            Text("別の文にする")
        }
    } else {
        Review(model, review, onOpenEntry)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun Review(model: AppViewModel, review: WritingReview, onOpenEntry: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme

    Divider()
    Spacer(Modifier.height(14.dp))
    Text("参照訳", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    review.task.references.forEach { reference ->
        Text(
            reference.en,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (reference.id == review.closest.id) FontWeight.Medium
            else FontWeight.Normal,
            color = if (reference.id == review.closest.id) colors.onSurface
            else colors.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 3.dp),
        )
    }
    if (review.task.references.size > 1) {
        Text(
            "コーパスにある英訳を全部出しています。どれも正解で、" +
                "照合はあなたの文に一番近いものに対して行っています。",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${review.matched.size}", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        Text(
            " / ${review.expected} 語",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    Text(
        "参照訳の内容語と重なった数です。点数ではありません。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.primaryContainer)
            .padding(14.dp),
    ) {
        Text(review.verdict, style = MaterialTheme.typography.bodyLarge,
            color = colors.onPrimaryContainer)
    }

    if (review.missed.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Section("読めるのに書けなかった語  ${review.missed.size}") {
            Column {
                review.missed.forEach { WordLine(it, onOpenEntry) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = model::takeWritingGaps, modifier = Modifier.fillMaxWidth()) {
                    Text("この ${review.missed.size} 語を「和→英」で出す")
                }
            }
        }
    }

    if (review.substituted.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Section("別の語で言い換えた") {
            Column { review.substituted.forEach { Substituted(it, onOpenEntry) } }
        }
    }

    if (review.extra.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Section("参照訳にない語") {
            Column {
                Text(
                    review.extra.joinToString("、") { it.lemma },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "誤りとは限りません。参照訳と違う言い方をしただけのこともあります。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }

    if (review.unlisted.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Section("辞書にない綴り") {
            Text(
                review.unlisted.joinToString("、") + "（固有名詞か、綴りの間違いです）",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    model.writingMessage?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
    }

    Spacer(Modifier.height(16.dp))
    Button(onClick = model::nextWriting, modifier = Modifier.fillMaxWidth()) {
        Text("次の文へ")
    }

    Spacer(Modifier.height(16.dp))
    Text(
        "時制・冠詞・語順が正しいかは判定していません。それを機械が判定するには" +
            "模範解答が要り、このアプリは模範解答を持っていないからです。" +
            "判定しているのは、参照訳と同じ内容語を出せたかどうかだけです。",
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
    )
}

@Composable
private fun WordLine(entry: Entry, onOpenEntry: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenEntry(entry.id) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(entry.lemma, style = MaterialTheme.typography.titleMedium)
        Chip(entry.pos.ja)
        Text(
            entry.ja.take(2).joinToString("、"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Substituted(substitution: Substitution, onOpenEntry: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenEntry(substitution.expected.id) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(substitution.used.lemma, style = MaterialTheme.typography.titleMedium)
        Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(substitution.expected.lemma, style = MaterialTheme.typography.titleMedium)
        Chip(substitution.kind.ja)
    }
}
