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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.CardFactory
import com.eitangoze.data.Repository
import com.eitangoze.data.Sense
import com.eitangoze.ui.AppViewModel

/**
 * One word, with everything the database knows about it.
 *
 * The part no other free dictionary shows is at the top: how the word's meanings
 * divide up in practice. A dictionary lists senses; it does not tell you that
 * three quarters of the time `run` means 走る and that four of its twelve senses
 * are ones you will meet once a decade. Those proportions come from SemCor, a
 * corpus where a person marked which sense each word carried, so the bars are
 * counts, not an editor's ordering.
 */
@Composable
fun EntryScreen(
    model: AppViewModel,
    detail: Repository.EntryDetail,
    onOpenEntry: (Long) -> Unit,
    onOpenAffix: (Long) -> Unit = {},
) {
    val entry = detail.entry
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.lemma, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Chip(entry.pos.ja)
            LevelChip(entry)
            if (entry.isPhrase) Chip(entry.kind.ja)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (entry.ipa.isNotBlank()) {
                Text(entry.ipa, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.lists.isNotEmpty()) {
                Text(entry.lists.joinToString(" / ").uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = { model.toggleStar() }) {
            Text(if (detail.starred) "★ 覚えておく" else "☆ 覚えておく")
        }

        Decomposition(detail, onOpenAffix)

        SenseShare(detail.senses)

        Spacer(Modifier.height(6.dp))
        Divider()

        detail.senses.forEachIndexed { index, sense ->
            SenseBlock(index, sense, detail)
        }

        WordFamily(model, detail, onOpenEntry)

        if (detail.collocations.isNotEmpty()) {
            Section("結びつく語（コーパス出現回数）") {
                Column {
                    val top = detail.collocations.maxOf { it.count }.coerceAtLeast(1)
                    detail.collocations.forEach { coll ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(coll.phrase, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(150.dp))
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(coll.count.toFloat() / top)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                            Text("  ${coll.count}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (entry.forms.isNotEmpty()) {
            Section("活用") {
                Text(
                    entry.forms.joinToString("　") {
                        "${CardFactory.formJa(it.first)} ${it.second}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (detail.sentences.isNotEmpty()) {
            Section("対訳例文") {
                Column {
                    detail.sentences.forEach { linked ->
                        Spacer(Modifier.height(6.dp))
                        Text(linked.example.en, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            linked.example.ja,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val related = detail.relations.filter { it.kind.code != "root" }
        if (related.isNotEmpty()) {
            Section("関連") {
                Column {
                    related.groupBy { it.kind }.forEach { (kind, items) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                kind.ja,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(64.dp),
                            )
                            Column {
                                items.take(6).forEach { relation ->
                                    Text(
                                        "${relation.other.lemma}　" +
                                            relation.other.ja.firstOrNull().orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .clickable { onOpenEntry(relation.other.id) }
                                            .padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (entry.cefrEstimated) {
            Text(
                "レベルは頻度からの推定です（CEFR-J は B2 まで）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * The proportions of a word's meanings, as one bar.
 *
 * Only drawn when the corpus actually has counts for this word. Inventing a
 * split for a word nobody tagged would be the most misleading thing on the page.
 */
/**
 * The word cut into its pieces, laid side by side above everything else.
 *
 * `introduction` is intro- ＋ duc ＋ -tion, and each piece is worth its own
 * page: the stem carries the meaning, the affixes operate on it. Reading the
 * row left to right is reading how the word was assembled, which is the only
 * reliable way to guess at the next word built the same way.
 *
 * The cut is Wiktionary's own, never a guess: a rule that strips letters off
 * the front would break `region` into re- ＋ gion and teach a lie.
 */
@Composable
private fun Decomposition(detail: Repository.EntryDetail, onOpenAffix: (Long) -> Unit) {
    val parts = detail.morphemes
    if (parts.size < 2) return
    val colors = MaterialTheme.colorScheme
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        parts.forEach { part ->
            val affix = detail.affixes[part.affixId]
            // An affix with a page is tinted and tappable; a stem is quiet, and
            // so is an affix Wiktionary never defined well enough to explain.
            val background =
                if (affix != null) colors.primaryContainer else colors.surfaceVariant
            val label = affix?.jaLine?.takeIf { it.isNotBlank() }
                ?: affix?.glossLine?.takeIf { it.isNotBlank() }
                ?: part.gloss
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(background)
                    .then(
                        if (affix != null) Modifier.clickable { onOpenAffix(affix.id) }
                        else Modifier,
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    part.form,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (affix != null) colors.onPrimaryContainer else colors.onSurface,
                )
                if (label.isNotBlank()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    val readable = parts.mapNotNull { part ->
        detail.affixes[part.affixId]?.ja?.firstOrNull() ?: part.gloss.takeIf { it.isNotBlank() }
    }
    if (readable.size == parts.size) {
        Spacer(Modifier.height(4.dp))
        Text(
            readable.joinToString(" ＋ "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun SenseShare(senses: List<Sense>) {
    val counted = senses.filter { it.semcor > 0 }
    if (counted.size < 2) return
    val total = counted.sumOf { it.semcor }.toFloat()
    val colors = MaterialTheme.colorScheme
    val shades = listOf(
        colors.primary,
        colors.primary.copy(alpha = 0.72f),
        colors.primary.copy(alpha = 0.52f),
        colors.primary.copy(alpha = 0.36f),
        colors.primary.copy(alpha = 0.24f),
        colors.primary.copy(alpha = 0.16f),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "意味の使われ方",
        style = MaterialTheme.typography.labelLarge,
        color = colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        counted.forEachIndexed { index, sense ->
            Box(
                Modifier
                    .fillMaxWidth(sense.semcor / total)
                    .height(22.dp)
                    .background(shades.getOrElse(index) { shades.last() }),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    counted.take(4).forEachIndexed { index, sense ->
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 1.dp)) {
            Box(
                Modifier
                    .width(10.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(shades.getOrElse(index) { shades.last() }),
            )
            Text(
                "  ${(sense.semcor / total * 100).toInt()}%  ${sense.jaLine}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Text(
        "語義タグ付きコーパス（SemCor）での出現比率",
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
    )
}

@Composable
private fun SenseBlock(index: Int, sense: Sense, detail: Repository.EntryDetail) {
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            sense.jaLine.ifBlank { "（和訳なし・英英のみ）" },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (sense.semcor >= 3) Chip("よく出る")
    }
    if (sense.tags.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            sense.tags.take(4).forEach { Chip(CardFactory.tagJa(it)) }
        }
    }
    if (sense.definition.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(sense.definition, style = MaterialTheme.typography.bodyMedium)
    }
    if (sense.jaDefinition.isNotBlank()) {
        Text(
            sense.jaDefinition,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    detail.examplesBySense[sense.id].orEmpty().take(3).forEach { example ->
        Spacer(Modifier.height(6.dp))
        Text("・${example.en}", style = MaterialTheme.typography.bodyMedium)
        if (example.ja.isNotBlank()) {
            Text(
                "　${example.ja}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (sense.synonyms.isNotEmpty()) LabelValue("類義", sense.synonyms.joinToString(", "))
    if (sense.antonyms.isNotEmpty()) LabelValue("対義", sense.antonyms.joinToString(", "))
}

/** The stem this word shares with a group of others, and that group. */
@Composable
private fun WordFamily(
    model: AppViewModel,
    detail: Repository.EntryDetail,
    onOpenEntry: (Long) -> Unit,
) {
    val family = detail.family ?: return
    val members = detail.familyMembers.filter { it.lemma != detail.entry.lemma }
    if (members.isEmpty()) return
    Section("同語根 ${family.pattern}") {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            if (family.form.isNotBlank()) {
                Text(
                    "${family.lang} ${family.form}" +
                        if (family.gloss.isNotBlank()) "「${family.gloss}」" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
            }
            members.take(14).forEach { member ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenEntry(member.id) }
                        .padding(vertical = 3.dp),
                ) {
                    StemText(member.lemma, family.pattern)
                    Text(
                        "  " + member.ja.firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The word with its shared stem picked out, so the pattern is visible at a glance. */
@Composable
fun StemText(lemma: String, pattern: String) {
    val at = lemma.indexOf(pattern)
    if (at < 0) {
        Text(lemma, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row {
        Text(lemma.substring(0, at), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            pattern,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(lemma.substring(at + pattern.length), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
