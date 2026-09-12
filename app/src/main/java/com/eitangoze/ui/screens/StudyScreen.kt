package com.eitangoze.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.AnswerMode
import com.eitangoze.data.Grade
import com.eitangoze.data.Repository
import com.eitangoze.data.StudyCard
import com.eitangoze.srs.Rating
import com.eitangoze.ui.AppViewModel
import com.eitangoze.ui.theme.Marks

/**
 * One question at a time: prompt, answer, grade.
 *
 * The answer half is always shown *after* committing — typing then revealing,
 * or choosing then seeing — because the value of the whole exercise comes from
 * having tried to recall before being told.
 */
@Composable
fun StudyScreen(model: AppViewModel, onFinished: () -> Unit, onOpenEntry: (Long) -> Unit) {
    val card = model.current
    if (card == null) {
        SessionSummary(model, onFinished)
        return
    }
    val answer = model.answer
    val scroll = rememberScrollState()
    LaunchedEffect(model.position) { scroll.scrollTo(0) }

    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(
            progress = { (model.position.toFloat() / model.queue.size.coerceAtLeast(1)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(card.kind.title, MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer)
                    LevelChip(card.entry)
                }
                Text(
                    "${model.position + 1} / ${model.queue.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                card.instruction,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Prompt(card)

            Spacer(Modifier.height(16.dp))
            when (card.mode) {
                AnswerMode.CHOICE -> Choices(model, card)
                AnswerMode.TYPE -> TypeAnswer(model, card)
                AnswerMode.SELF_CHECK -> SelfCheck(model, card)
            }

            if (answer.revealed) {
                Spacer(Modifier.height(18.dp))
                Divider()
                Spacer(Modifier.height(12.dp))
                AnswerPanel(card, onOpenEntry)
            }
            Spacer(Modifier.height(24.dp))
        }
        Divider()
        Controls(model, card, onFinished)
    }
}

@Composable
private fun Prompt(card: StudyCard) {
    val big = card.prompt.length <= 24 && !card.prompt.contains(' ')
    Text(
        card.prompt,
        fontSize = if (big) 34.sp else 21.sp,
        fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal,
        lineHeight = if (big) 40.sp else 30.sp,
    )
    if (card.promptJa.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            card.promptJa,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (card.promptNotes.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        card.promptNotes.forEach { (label, value) -> LabelValue(label, value) }
    }
}

@Composable
private fun Choices(model: AppViewModel, card: StudyCard) {
    val answer = model.answer
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card.choices.forEachIndexed { index, text ->
            val isCorrect = index == card.correctIndex
            val picked = answer.chosen == index
            val colors = MaterialTheme.colorScheme
            val border = when {
                !answer.revealed -> colors.outlineVariant
                isCorrect -> Marks.correct
                picked -> Marks.wrong
                else -> colors.outlineVariant
            }
            val fill = when {
                !answer.revealed -> Color.Transparent
                isCorrect -> Marks.correct.copy(alpha = 0.12f)
                picked -> Marks.wrong.copy(alpha = 0.12f)
                else -> Color.Transparent
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(fill)
                    .border(1.dp, border, RoundedCornerShape(10.dp))
                    .clickable(enabled = !answer.revealed) { model.choose(index) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(0.dp))
                Text(
                    "  $text",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TypeAnswer(model: AppViewModel, card: StudyCard) {
    val answer = model.answer
    val focus = remember { FocusRequester() }
    LaunchedEffect(card.key) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = answer.typed,
        onValueChange = model::type,
        readOnly = answer.revealed,
        singleLine = true,
        label = { Text("英語を入力") },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { model.reveal() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    val result = answer.result
    if (result != null) {
        Spacer(Modifier.height(10.dp))
        val color = when (result.grade) {
            Grade.CORRECT -> Marks.correct
            Grade.CLOSE -> MaterialTheme.colorScheme.secondary
            Grade.WRONG -> Marks.wrong
        }
        Text(result.comment, color = color, fontWeight = FontWeight.Medium)
        if (result.grade != Grade.CORRECT) {
            Text("正解: ${result.expected}", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * 和文英訳: write freely, then tick what your sentence actually did.
 *
 * Nothing here is scored automatically. Several English sentences translate the
 * same Japanese, and a string comparison would mark most correct answers wrong,
 * which teaches the wrong lesson.
 */
@Composable
private fun SelfCheck(model: AppViewModel, card: StudyCard) {
    val answer = model.answer
    OutlinedTextField(
        value = answer.typed,
        onValueChange = model::type,
        readOnly = answer.revealed,
        label = { Text("英作文") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    if (answer.revealed) {
        Spacer(Modifier.height(14.dp))
        Text("模範解答", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(card.answerTitle, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text("自己採点", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        card.checklist.forEachIndexed { index, point ->
            Row(
                Modifier.fillMaxWidth().clickable { model.toggleCheck(index) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = index in answer.checked,
                    onCheckedChange = { model.toggleCheck(index) })
                Text(point, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AnswerPanel(card: StudyCard, onOpenEntry: (Long) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                card.entry.lemma,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onOpenEntry(card.entry.id) },
            )
            Chip(card.entry.pos.ja)
            if (card.entry.ipa.isNotBlank()) {
                Text(card.entry.ipa, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (card.answerTitle.isNotBlank() && card.mode != AnswerMode.SELF_CHECK) {
            Spacer(Modifier.height(6.dp))
            Text(card.answerTitle, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        card.answerNotes.forEach { (label, value) -> LabelValue(label, value) }
        if (card.examples.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Section("例文") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.examples.take(3).forEach { example ->
                        Column {
                            Text(example.en, style = MaterialTheme.typography.bodyMedium)
                            if (example.ja.isNotBlank()) {
                                Text(
                                    example.ja,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { onOpenEntry(card.entry.id) }) { Text("この語の詳細を見る") }
    }
}

@Composable
private fun Controls(model: AppViewModel, card: StudyCard, onFinished: () -> Unit) {
    val answer = model.answer
    Column(Modifier.padding(12.dp)) {
        if (!answer.revealed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onFinished) { Text("やめる") }
                Spacer(Modifier.weight(1f))
                if (card.mode != AnswerMode.CHOICE) {
                    Button(onClick = { model.reveal() }) { Text("答えを見る") }
                }
            }
        } else {
            val delays = remember(card.key, model.position) { model.previewDelays() }
            val suggested = model.suggestedRating()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Rating.entries.forEach { rating ->
                    val emphasised = rating == suggested
                    Button(
                        onClick = { model.rate(rating) },
                        modifier = Modifier.weight(1f),
                        colors = if (emphasised) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors(),
                        border = if (emphasised) null else
                            androidx.compose.foundation.BorderStroke(
                                1.dp, MaterialTheme.colorScheme.outlineVariant,
                            ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(rating.labelJa, fontSize = 13.sp)
                            delays[rating]?.let {
                                Text(
                                    Repository.humanDelay(it),
                                    fontSize = 10.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSummary(model: AppViewModel, onFinished: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (model.queue.isEmpty()) {
                Text("いまは出題できるカードがありません", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "設定でデッキを増やすか、1日の新規枚数を上げてください",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("おつかれさま", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                val rate = if (model.sessionAnswered == 0) 0
                else model.sessionCorrect * 100 / model.sessionAnswered
                Text("${model.sessionAnswered} 問 ・ 正答率 $rate%",
                    style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = onFinished) { Text("ホームに戻る") }
        }
    }
}
