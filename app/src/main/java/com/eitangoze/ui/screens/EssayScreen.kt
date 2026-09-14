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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.EssayCheck
import com.eitangoze.data.EssayPart
import com.eitangoze.ui.AppViewModel
import com.eitangoze.ui.theme.Marks

const val ESSAY_TAG = "essay-screen"

private val WORD = Regex("[A-Za-z][A-Za-z'’-]*")

/**
 * 自由英作文: the 80–100 words, and the only things about them a machine can
 * honestly say.
 *
 * Whether the argument is any good is not one of them, and nothing on this
 * screen pretends otherwise — the app does not talk to a server and an offline
 * model of "is this persuasive" would be a guess wearing a number. What it does
 * is count, and counting is where the easy marks go: over the word limit, one
 * noun carrying the whole essay, four sentences of forty words each, a spelling
 * that is in no dictionary. All of those are visible before a marker sees them,
 * and all of them are fixable in a minute.
 *
 * The four boxes are the shape of the answer rather than decoration. 主張 →
 * 理由2つ → 総括 is what the question asks for, and splitting them stops the
 * commonest failure: two paragraphs of the same reason said twice.
 */
@Composable
fun EssayScreen(model: AppViewModel) {
    val colors = MaterialTheme.colorScheme
    val text = model.essayText
    val words = remember(text) { WORD.findAll(text).count() }
    val check = model.essayCheck

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp)
            .testTag(ESSAY_TAG),
    ) {
        Text("自由英作文", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "京大の第4問は ${EssayCheck.MIN_WORDS}〜${EssayCheck.MAX_WORDS} 語。" +
                "主張 → 理由2つ → 総括 の順に書きます。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        WordGauge(words)

        EssayPart.entries.forEach { part ->
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = model.essayParts[part].orEmpty(),
                onValueChange = { model.typeEssay(part, it) },
                label = { Text("${part.ja} — ${part.hint}") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = model::checkEssay,
            enabled = text.isNotBlank() && !model.essayChecking,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (model.essayChecking) "測っています…" else "測る") }

        if (check != null) {
            Spacer(Modifier.height(18.dp))
            Divider()
            Spacer(Modifier.height(14.dp))
            Result(check)
        }

        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = model::clearEssay, modifier = Modifier.fillMaxWidth()) {
            Text("消して書き直す")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The word count, live.
 *
 * It is the one number that decides marks on its own and the one nobody can
 * keep in their head while writing, so it updates on every keystroke rather
 * than waiting for a button.
 */
@Composable
private fun WordGauge(words: Int) {
    val colors = MaterialTheme.colorScheme
    val inside = words in EssayCheck.MIN_WORDS..EssayCheck.MAX_WORDS
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$words", fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                color = if (inside) colors.onSurface else colors.onSurfaceVariant)
            Text(
                " 語  / ${EssayCheck.MIN_WORDS}〜${EssayCheck.MAX_WORDS}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            if (inside) {
                Text("  ✓", color = Marks.correct, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { (words.toFloat() / EssayCheck.MAX_WORDS).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = if (inside) colors.primary else colors.outlineVariant,
        )
    }
}

@Composable
private fun Result(check: EssayCheck) {
    val colors = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.primaryContainer)
            .padding(14.dp),
    ) {
        Text(check.lengthNote, style = MaterialTheme.typography.bodyLarge,
            color = colors.onPrimaryContainer)
    }

    Spacer(Modifier.height(14.dp))
    LabelValue("文の数", "${check.sentences} 文・平均 ${check.averageSentence} 語")
    LabelValue("B1以上の語", "${check.aboveB1} 語 / ${check.words} 語")

    if (check.repeated.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Section("繰り返している語") {
            Column {
                Text(
                    check.repeated.take(6).joinToString("、") {
                        "${it.surface} ×${it.occurrences}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "同じ語で全部を運ぶと、語彙の幅が無いように読まれます。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }

    if (check.unlisted.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Section("辞書にない綴り") {
            Column {
                Text(
                    check.unlisted.take(10).joinToString("、") { it.surface },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "固有名詞なら問題ありません。そうでなければ綴りを確かめてください。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }

    if (check.attested.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Section("コーパスに裏付けのある結びつき  ${check.attested.size}") {
            Column {
                check.attested.take(8).forEach { collocation ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(collocation.phrase, style = MaterialTheme.typography.bodyMedium)
                        Chip(collocation.patternJa)
                        Text(
                            "${collocation.count} 回",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Text(
        "出していないものが2つあります。ひとつは内容と論理で、正しさを決める根拠が" +
            "端末内にありません。もうひとつは「この組み合わせは不自然だ」という指摘で、" +
            "同梱のコロケーションは 15,088 組しかなく、載っていないことは誤りの証拠に" +
            "ならないからです。出しているのは、載っていたものだけです。",
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
    )
}
