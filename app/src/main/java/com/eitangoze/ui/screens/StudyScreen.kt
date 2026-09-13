package com.eitangoze.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.AnswerMode
import com.eitangoze.data.Grade
import com.eitangoze.data.Repository
import com.eitangoze.data.StudyCard
import com.eitangoze.srs.Rating
import com.eitangoze.ui.AppViewModel
import com.eitangoze.ui.theme.Marks

/** How much of the card area the answer sheet takes when it is up. */
private const val SHEET_HEIGHT = 0.62f

/** Names the answer sheet so a test can measure where it actually landed. */
const val ANSWER_SHEET_TAG = "answer-sheet"

/**
 * One question, one screen.
 *
 * The screen is three fixed bands and never scrolls as a whole: the question
 * holds the space above, the rating buttons stay pinned under the thumb, and
 * the answer *rises* into the gap between them when you commit. Scrolling to
 * find out whether you were right costs a gesture on every single card, and a
 * session is a hundred cards; the answer has to arrive where the eye already
 * is.
 *
 * The answer half is still shown only *after* committing — typing then
 * revealing, or choosing then seeing — because the value of the whole exercise
 * comes from having tried to recall before being told.
 */
@Composable
fun StudyScreen(model: AppViewModel, onFinished: () -> Unit, onOpenEntry: (Long) -> Unit) {
    val card = model.current
    if (card == null) {
        SessionSummary(model, onFinished)
        return
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LinearProgressIndicator(
            progress = { (model.position.toFloat() / model.queue.size.coerceAtLeast(1)) },
            modifier = Modifier.fillMaxWidth(),
        )

        CardArea(model, card, onOpenEntry, Modifier.weight(1f))

        Divider()
        Controls(model, card, onFinished)
    }
}

/**
 * The question, with the answer sheet able to rise over its lower half.
 *
 * Its own function so that the only implicit receiver here is the Box: inside
 * a Column, `AnimatedVisibility` resolves to the ColumnScope overload, which
 * cannot be aligned to the bottom of anything.
 */
@Composable
private fun CardArea(
    model: AppViewModel,
    card: StudyCard,
    onOpenEntry: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The question can be scrolled clear of the sheet, so it needs exactly
        // the sheet's height as bottom room — a fixed number would leave the
        // last option unreachable on a short screen.
        Question(model, card, bottomInset = maxHeight * SHEET_HEIGHT)

        // The sheet covers the lower part of the question rather than pushing
        // it off the top, so the sentence you just judged is still on screen
        // while you read what it meant.
        AnimatedVisibility(
            visible = model.answer.revealed,
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(140)) { it } + fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            AnswerSheet(model, card, onOpenEntry)
        }
    }
}

/** The question, laid out to fit. It scrolls only when it genuinely cannot. */
@Composable
private fun Question(model: AppViewModel, card: StudyCard, bottomInset: Dp) {
    val scroll = rememberScrollState()
    LaunchedEffect(model.position) { scroll.scrollTo(0) }
    Column(
        Modifier
            .fillMaxSize()
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
        // Room for the sheet to rise into without hiding the last option.
        Spacer(Modifier.height(if (model.answer.revealed) bottomInset else 24.dp))
    }
}

@Composable
private fun Prompt(card: StudyCard) {
    val big = card.prompt.length <= 24 && !card.prompt.contains(' ')
    // A long prompt is a sentence to read, not a word to stare at: it gets the
    // smaller size so that the whole card still fits on one screen.
    val size = when {
        big -> 34.sp
        card.prompt.length > 120 -> 17.sp
        else -> 21.sp
    }
    Text(
        card.prompt,
        fontSize = size,
        fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal,
        lineHeight = size * 1.35f,
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
}

/**
 * What the answer was, risen into place over the question.
 *
 * Everything the learner might want *after* committing lives here and nowhere
 * else — the word, the meaning, the grammar labels, the examples — so the
 * question above it never has to make room for any of it.
 */
@Composable
private fun AnswerSheet(model: AppViewModel, card: StudyCard, onOpenEntry: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val answer = model.answer
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    Column(
        Modifier
            .testTag(ANSWER_SHEET_TAG)
            .fillMaxWidth()
            .fillMaxHeight(SHEET_HEIGHT)
            .shadow(14.dp, shape)
            .clip(shape)
            .background(colors.surface),
    ) {
        // A short bar at the top, so the sheet reads as a thing that arrived
        // rather than as more page.
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.outlineVariant),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
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
                        color = colors.onSurfaceVariant)
                }
            }
            if (card.answerTitle.isNotBlank() && card.mode != AnswerMode.SELF_CHECK) {
                Spacer(Modifier.height(6.dp))
                Text(card.answerTitle, style = MaterialTheme.typography.titleMedium)
            }

            if (card.mode == AnswerMode.SELF_CHECK) {
                Spacer(Modifier.height(8.dp))
                Text("模範解答", style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant)
                Text(card.answerTitle, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Text("自己採点", style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant)
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

            AnsweredShare(card)

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
                                        color = colors.onSurfaceVariant,
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
}

/**
 * The proportions of this word's meanings, opened at the moment of answering.
 *
 * Answering is when the word is most alive, and it is the only moment at which
 * "and here is the other meaning" is a discovery rather than a footnote. A
 * dictionary lists senses; it never says that the one you just answered is the
 * 22% one and that the 62% one is still coming — which, for 下線部和訳, is the
 * part that decides the mark.
 *
 * The counts are from SemCor, a corpus a person hand-tagged sense by sense, so
 * the bars are measurements and not an editor's ordering. They are only drawn
 * for a word that has more than one counted meaning, because a single full bar
 * would say nothing while looking as though it said something.
 */
@Composable
private fun AnsweredShare(card: StudyCard) {
    val senses = card.senseShare
    if (senses.size < 2) return
    val total = senses.sumOf { it.semcor }.toFloat()
    if (total <= 0f) return
    val colors = MaterialTheme.colorScheme
    val answered = card.sense?.id

    Spacer(Modifier.height(14.dp))
    Text(
        "意味の使われ方",
        style = MaterialTheme.typography.labelLarge,
        color = colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceVariant),
    ) {
        senses.forEach { sense ->
            Box(
                Modifier
                    .weight(sense.semcor / total)
                    .fillMaxHeight()
                    .background(
                        // The meaning just answered is the solid one; the rest
                        // are there to show what is still unclaimed.
                        if (sense.id == answered) colors.primary
                        else colors.primary.copy(alpha = 0.22f),
                    ),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    senses.take(4).forEach { sense ->
        val percent = (sense.semcor / total * 100).toInt()
        val mine = sense.id == answered
        Text(
            (if (mine) "▸ " else "  ") + "$percent%  ${sense.jaLine}" +
                if (mine) "  ← いま答えた意味" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (mine) colors.primary else colors.onSurfaceVariant,
            fontWeight = if (mine) FontWeight.Medium else FontWeight.Normal,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "語義タグ付きコーパス（SemCor）での出現比率",
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
    )
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
