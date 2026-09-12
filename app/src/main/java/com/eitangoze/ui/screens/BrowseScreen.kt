package com.eitangoze.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.CardFactory
import com.eitangoze.data.Repository
import com.eitangoze.ui.AppViewModel

/** Search by English or by Japanese; 「延期」 finds `put off`. */
@Composable
fun BrowseScreen(model: AppViewModel, onOpenEntry: (Long) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = model.searchQuery,
            onValueChange = model::search,
            label = { Text("英語でも日本語でも検索できます") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Divider()
        LazyColumn(Modifier.weight(1f)) {
            items(model.searchResults) { entry ->
                EntryRow(entry) { onOpenEntry(entry.id) }
                Divider()
            }
            if (model.searchQuery.isNotBlank() && model.searchResults.isEmpty()) {
                item {
                    Text(
                        "見つかりませんでした",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * The dictionary page for one word: every meaning with its Japanese, English
 * definition, grammar labels and examples, plus what it combines with and what
 * it is easy to mix up with.
 */
@Composable
fun EntryScreen(model: AppViewModel, detail: Repository.EntryDetail, onOpenEntry: (Long) -> Unit) {
    val entry = detail.entry
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.lemma, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Chip(entry.pos.ja)
            LevelChip(entry)
            if (entry.isPhrase) Chip(entry.kind.ja)
        }
        if (entry.ipa.isNotBlank()) {
            Text(entry.ipa, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row {
            TextButton(onClick = { model.toggleStar() }) {
                Text(if (detail.starred) "★ 覚えておく" else "☆ 覚えておく")
            }
        }

        if (entry.cefrEstimated) {
            Text(
                "レベルは頻度からの推定です（CEFR-J は B2 まで）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.forms.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LabelValue("活用", entry.forms.joinToString(" / ") {
                "${CardFactory.formJa(it.first)} ${it.second}"
            })
        }
        if (entry.root.isNotBlank()) {
            LabelValue(
                "語根",
                "${entry.rootLang} ${entry.root}" +
                    if (entry.rootGloss.isNotBlank()) "「${entry.rootGloss}」" else "",
            )
        }
        if (entry.lists.isNotEmpty()) LabelValue("収録", entry.lists.joinToString(", "))

        Spacer(Modifier.height(10.dp))
        Divider()

        detail.senses.forEachIndexed { index, sense ->
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    sense.jaLine.ifBlank { "（和訳なし）" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // A meaning tagged often in a sense-annotated corpus is one that
                // actually turns up; it is worth saying so.
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

        if (detail.collocations.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Section("結びつく語") {
                Column {
                    detail.collocations.forEach { coll ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(coll.phrase, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            Text(
                                coll.patternJa,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (detail.sentences.isNotEmpty()) {
            Section("例文（対訳）") {
                Column {
                    detail.sentences.forEach { linked ->
                        Spacer(Modifier.height(4.dp))
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

        if (detail.relations.isNotEmpty()) {
            Section("関連") {
                Column {
                    detail.relations.groupBy { it.kind }.forEach { (kind, items) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                kind.ja,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Column {
                                items.take(6).forEach { relation ->
                                    TextButton(
                                        onClick = { onOpenEntry(relation.other.id) },
                                        contentPadding = androidx.compose.foundation.layout
                                            .PaddingValues(0.dp),
                                    ) {
                                        Text(
                                            "${relation.other.lemma}  " +
                                                relation.other.ja.firstOrNull().orEmpty(),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (detail.cards.isNotEmpty()) {
            Section("この語のカード") {
                Column {
                    detail.cards.forEach { card ->
                        LabelValue(card.kind.title, card.phase.name.lowercase())
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
