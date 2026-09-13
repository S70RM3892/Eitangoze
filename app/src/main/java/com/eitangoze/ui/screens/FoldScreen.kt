package com.eitangoze.ui.screens

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.Fold
import com.eitangoze.data.ParsedSentence
import com.eitangoze.data.Repository
import com.eitangoze.data.SyntaxQuestion
import com.eitangoze.data.SyntaxQuiz
import com.eitangoze.ui.theme.Marks
import com.eitangoze.ui.AppViewModel

const val FOLD_SENTENCE_TAG = "fold-sentence"

/**
 * One sentence, folded down to its skeleton.
 *
 * This is the first step of 京大's 下線部和訳, and the one most people skip:
 * before translating anything, find out what the sentence is actually saying —
 * which noun is the subject, which verb is the main verb, and which of the
 * three clauses in front of you is the one the sentence is about.
 *
 * Paper cannot do this. A grammar book can print the sentence and print the
 * analysis underneath, but it cannot let the modifiers *leave*, and watching a
 * forty-word sentence shrink to five words is a different thing from being told
 * that it would. Every fold here is a real subtree that two parsers agreed on
 * (`tools/step8_syntax.py`); nothing is folded that contains the main verb, so
 * the skeleton always survives.
 */
@Composable
fun FoldScreen(
    reading: Repository.Reading,
    sentence: ParsedSentence,
    collapsed: List<Fold>,
    onToggleFold: (Fold) -> Unit,
    onSkeleton: () -> Unit,
    onUnfold: () -> Unit,
    onClose: () -> Unit,
    answers: Map<Int, Int> = emptyMap(),
    onAnswer: (Int, Int) -> Unit = { _, _ -> },
) {
    val colors = MaterialTheme.colorScheme
    val passage = reading.passage.text
    val available = remember(sentence, collapsed) {
        // A fold is offered only when nothing already folded covers it. Offering
        // one inside a `⌄` would point at text that is not on the screen.
        sentence.folds.filter { fold ->
            collapsed.none { it.start <= fold.start && fold.end <= it.end && it != fold }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                if (collapsed.isEmpty()) "この文の骨格を出す" else
                    "${collapsed.size} か所たたみました",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            FoldableSentence(
                passage = passage,
                sentence = sentence,
                collapsed = collapsed,
                offered = available,
                onToggleFold = onToggleFold,
            )

            if (collapsed.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Divider()
                Spacer(Modifier.height(12.dp))
                Skeleton(passage, sentence, colors.primary)
            }

            val questions = remember(sentence) {
                SyntaxQuiz.of(sentence, passage, seed = sentence.ord)
            }
            if (questions.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Divider()
                Spacer(Modifier.height(12.dp))
                Quiz(questions, sentence, passage, answers, onAnswer)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                if (sentence.confirmed) {
                    "下線のある語をタップすると、そこから始まる修飾がたたまれます。" +
                        "⌄ をタップすると戻ります。"
                } else {
                    "この文は2つの解析器の結果が一致しなかったので、たたむ操作は出していません。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "たたむ範囲は Wiktionary ではなく依存構造解析の結果で、" +
                    "2つの解析器が同じ範囲で括ったものだけを出しています。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }

        Divider()
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("本文に戻る") }
            Spacer(Modifier.weight(1f))
            if (collapsed.isNotEmpty()) {
                OutlinedButton(onClick = onUnfold) { Text("ひらく") }
            }
            OutlinedButton(
                onClick = onSkeleton,
                enabled = sentence.confirmed && sentence.folds.isNotEmpty(),
            ) { Text("骨まで畳む") }
        }
    }
}

/**
 * The sentence itself, with the folded pieces replaced by a mark.
 *
 * Rendered token by token rather than as one string so that each piece can be
 * its own tap target, and so that the spacing comes from the passage's own
 * character offsets — punctuation sits where the writer put it.
 */
@Composable
private fun FoldableSentence(
    passage: String,
    sentence: ParsedSentence,
    collapsed: List<Fold>,
    offered: List<Fold>,
    onToggleFold: (Fold) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Widest first, so tapping a word that begins several subtrees folds the
    // most it can: that is the move the reader is trying to make.
    val startsAt = remember(offered) {
        offered.sortedByDescending { it.length }.associateBy { it.start }
    }
    val foldedAt = remember(collapsed) { collapsed.associateBy { it.start } }

    Column(Modifier.animateContentSize().testTag(FOLD_SENTENCE_TAG)) {
        val pieces = ArrayList<Pair<String, Fold?>>()
        var index = 0
        var written = -1
        while (index < sentence.size) {
            val folded = foldedAt[index]
            if (folded != null && folded in collapsed) {
                val gap = if (pieces.isNotEmpty() &&
                    (written < 0 || sentence.tokens[index].first > written)
                ) " " else ""
                pieces.add("$gap⌄" to folded)
                written = sentence.tokens[folded.end].last + 1
                index = folded.end + 1
                continue
            }
            val range = sentence.tokens[index]
            val gap = if (pieces.isNotEmpty() && (written < 0 || range.first > written)) " " else ""
            pieces.add(gap + passage.substring(range.first, range.last + 1) to startsAt[index])
            written = range.last + 1
            index++
        }

        // One flowing text: a sentence broken into a grid of word chips stops
        // reading like a sentence, and reading it is the point.
        androidx.compose.foundation.text.ClickableText(
            text = buildAnnotatedString {
                pieces.forEach { (piece, handle) ->
                    val isMark = piece.trimStart().startsWith("⌄")
                    when {
                        isMark -> withStyle(
                            SpanStyle(
                                color = colors.primary,
                                fontWeight = FontWeight.Bold,
                                background = colors.primaryContainer,
                            ),
                        ) {
                            pushStringAnnotation("fold", "${handle?.start}")
                            append(piece)
                            pop()
                        }

                        handle != null -> withStyle(
                            SpanStyle(textDecoration = TextDecoration.Underline),
                        ) {
                            pushStringAnnotation("fold", "${handle.start}")
                            append(piece)
                            pop()
                        }

                        else -> append(piece)
                    }
                }
            },
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 19.sp,
                lineHeight = 33.sp,
                color = colors.onSurface,
            ),
            onClick = { offset ->
                val hit = buildAnnotatedString {
                    pieces.forEach { (piece, handle) ->
                        pushStringAnnotation("fold", "${handle?.start ?: -1}")
                        append(piece)
                        pop()
                    }
                }.getStringAnnotations("fold", offset, offset).firstOrNull()
                val start = hit?.item?.toIntOrNull() ?: return@ClickableText
                (foldedAt[start] ?: startsAt[start])?.let(onToggleFold)
            },
        )
    }
}

/**
 * Confirm the structure before translating anything.
 *
 * No translation is asked for and none is marked: several English sentences
 * render the same Japanese and the reverse holds too, so a string comparison
 * would fail most correct answers. What can be settled is where the marks are
 * actually lost — which verb the sentence belongs to, whose it is, and what the
 * relative clause hangs on — and every answer here comes from the tree rather
 * than from an opinion.
 */
@Composable
private fun Quiz(
    questions: List<SyntaxQuestion>,
    sentence: ParsedSentence,
    passage: String,
    answers: Map<Int, Int>,
    onAnswer: (Int, Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Text("訳す前に、構文を確定する", style = MaterialTheme.typography.labelLarge,
        color = colors.onSurfaceVariant)
    questions.forEachIndexed { index, question ->
        Spacer(Modifier.height(12.dp))
        Text(question.prompt, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        val chosen = answers[index]
        question.choices.forEachIndexed { slot, token ->
            val range = sentence.tokens.getOrNull(token)
            val label = if (range == null) "?" else
                passage.substring(range.first, range.last + 1)
            val right = slot == question.correct
            val background = when {
                chosen == null -> Color.Transparent
                right -> Marks.correct.copy(alpha = 0.14f)
                slot == chosen -> Marks.wrong.copy(alpha = 0.14f)
                else -> Color.Transparent
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(background)
                    .border(
                        1.dp,
                        if (chosen == null) colors.outlineVariant else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = chosen == null) { onAnswer(index, slot) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (chosen != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                (if (chosen == question.correct) "✓ " else "✗ ") + question.note,
                style = MaterialTheme.typography.bodySmall,
                color = if (chosen == question.correct) Marks.correct else colors.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "和訳そのものは採点しません。同じ日本語に正しい英文は何通りもあり、" +
            "文字列比較では正解の大半を不正解にしてしまうからです。",
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
    )
}

/** S / V / O under the words that carry them, once anything has been folded. */
@Composable
private fun Skeleton(passage: String, sentence: ParsedSentence, accent: androidx.compose.ui.graphics.Color) {
    if (sentence.roles.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Text(
        "骨格",
        style = MaterialTheme.typography.labelLarge,
        color = colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        sentence.roles.entries.sortedBy { it.key }.forEach { (index, role) ->
            val range = sentence.tokens.getOrNull(index) ?: return@forEach
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    passage.substring(range.first, range.last + 1),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent.copy(alpha = 0.16f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(role, style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }
        }
    }
}

/**
 * The passage, with every sentence a way in to the fold view.
 *
 * Sentences whose tree two parsers confirmed are marked; the rest read exactly
 * the same and simply do not offer to be taken apart.
 */
@Composable
fun PassageScreen(
    model: AppViewModel,
    reading: Repository.Reading,
    onStudySentence: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val passage = reading.passage
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(passage.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(passage.genre.ja, colors.primaryContainer, colors.onPrimaryContainer)
            Chip(passage.cefr)
            Chip("${passage.words} 語")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "辞書が説明できる語 ${(passage.coverable * 100).toInt()}%" +
                "（残りは固有名詞など）",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        reading.sentences.forEach { sentence ->
            val canFold = sentence.confirmed && sentence.folds.isNotEmpty()
            Text(
                sentence.text(passage.text),
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 30.sp,
                    textDecoration = if (canFold) TextDecoration.Underline else null,
                ),
                color = if (canFold) colors.onSurface else colors.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canFold) { onStudySentence(sentence.ord) }
                    .padding(vertical = 3.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text(
            "${reading.foldable} 文が構文を確認できます",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            passage.credit,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            passage.url,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}
