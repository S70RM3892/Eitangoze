package com.eitangoze.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.CardKind
import com.eitangoze.data.Deck
import com.eitangoze.ui.AppViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(model: AppViewModel) {
    val repo = model.repository ?: return
    var newPerDay by remember { mutableStateOf(repo.newPerDay.toFloat()) }
    var reviewLimit by remember { mutableStateOf(repo.reviewLimit.toFloat()) }
    var retention by remember { mutableStateOf(repo.desiredRetention.toFloat()) }
    val enabledDecks = repo.enabledDecks
    val enabledKinds = repo.enabledKinds

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("設定", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        Section("出題する問題の種類") {
            Column {
                Text(
                    "同じ語でも「意味が分かる」「書ける」「文脈で見分けられる」は別の記憶です。" +
                        "それぞれ別々に出題し、別々に復習日を持ちます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                CardKind.entries.forEach { kind ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { model.setKindEnabled(kind, kind !in enabledKinds) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = kind in enabledKinds,
                            onCheckedChange = { model.setKindEnabled(kind, it) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(kind.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                kind.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Divider()
        Section("学習するデッキ") {
            Column {
                Deck.ALL.forEach { deck ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { model.setDeckEnabled(deck, deck.id !in enabledDecks) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = deck.id in enabledDecks,
                            onCheckedChange = { model.setDeckEnabled(deck, it) },
                        )
                        Spacer(Modifier.height(0.dp))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(deck.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                deck.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Divider()
        Section("1日の量") {
            Column {
                Text("新しく覚える枚数: ${newPerDay.roundToInt()} 枚/デッキ")
                Slider(
                    value = newPerDay,
                    onValueChange = { newPerDay = it },
                    onValueChangeFinished = { model.setNewPerDay(newPerDay.roundToInt()) },
                    valueRange = 0f..100f,
                )
                Text(
                    "増やすと数日後の復習が雪だるま式に増えます。20 前後から始めるのが無難です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("1日の復習上限: ${reviewLimit.roundToInt()} 枚")
                Slider(
                    value = reviewLimit,
                    onValueChange = { reviewLimit = it },
                    onValueChangeFinished = { model.setReviewLimit(reviewLimit.roundToInt()) },
                    valueRange = 20f..500f,
                )
            }
        }

        Divider()
        Section("目標定着率") {
            Column {
                Text("${(retention * 100).roundToInt()}%")
                Slider(
                    value = retention,
                    onValueChange = { retention = it },
                    onValueChangeFinished = { model.setRetention(retention.toDouble()) },
                    valueRange = 0.80f..0.97f,
                )
                Text(
                    "思い出せる確率がこの値まで下がった日に出題します。" +
                        "上げるほど忘れにくくなりますが、復習の回数は増えます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Divider()
        ExamDateSetting(model)

        Divider()
        Section("データについて") {
            Column {
                val content = repo.content
                Text(
                    "収録 ${content.entryCount} 項目 / ${content.senseCount} 語義 / " +
                        "${content.sentenceCount} 対訳例文",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "語義・例文・語法ラベル・発音・語源: English Wiktionary (CC BY-SA)\n" +
                        "語義ごとの日本語・対訳例文: 日本語 WordNet (CC BY 3.0)\n" +
                        "語義の並び順: Princeton WordNet 3.0 の SemCor 頻度\n" +
                        "対訳例文: Tatoeba Project (CC BY 2.0 FR)\n" +
                        "語彙レベル: CEFR-J Wordlist Version 1.6 " +
                        "（東京外国語大学 投野由紀夫研究室）\n" +
                        "頻度・学術語: NGSL / NAWL / TSL (CC BY-SA)、English Wikipedia 頻度\n" +
                        "日本語訳の並び順: JMdict の優先度タグ (CC BY-SA)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "「レベル」に * が付く語は、CEFR-J に載っていないため頻度から推定した値です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * The exam date, typed as yyyy-mm-dd.
 *
 * Setting it changes the question the scheduler answers: not "is this due
 * today" but "will this still be there on the day", and the target retention is
 * raised gradually over the last two months rather than all at once.
 */
@Composable
private fun ExamDateSetting(model: AppViewModel) {
    val repo = model.repository ?: return
    val format = remember { SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN) }
    var text by remember {
        mutableStateOf(
            repo.examDate.takeIf { it > 0 }?.let { format.format(it) }.orEmpty()
        )
    }
    var error by remember { mutableStateOf(false) }

    Section("試験日") {
        Column {
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    text = value
                    error = false
                    if (value.isBlank()) {
                        model.setExamDate(0L)
                        return@OutlinedTextField
                    }
                    val parsed = runCatching { format.parse(value) }.getOrNull()
                    if (parsed != null) model.setExamDate(parsed.time) else error = true
                },
                isError = error,
                singleLine = true,
                label = { Text("yyyy-mm-dd（空欄で解除）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "入れると、ホームに試験日時点の予測定着率が出ます。" +
                    "残り 60 日を切ると目標定着率を 97% まで少しずつ上げ、間隔を自動で詰めます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatsScreen(model: AppViewModel, onOpenEntry: (Long) -> Unit) {
    val stats = model.stats ?: return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("学習状況", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        Section("今日") {
            Column {
                val rate = if (stats.answeredToday == 0) 0
                else stats.correctToday * 100 / stats.answeredToday
                LabelValue("解答", "${stats.answeredToday} 問")
                LabelValue("正答率", "$rate%")
                LabelValue("連続", "${stats.streakDays} 日")
                LabelValue("総カード", "${stats.cardsTotal} 枚")
            }
        }

        Section("これから7日間の復習") {
            Column {
                val max = (stats.dueNextWeek.maxOrNull() ?: 0).coerceAtLeast(1)
                stats.dueNextWeek.forEachIndexed { day, count ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (day == 0) "今日" else "${day}日後",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Row(
                            Modifier
                                .weight(count.toFloat() / max)
                                .height(12.dp),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Divider(
                                Modifier.fillMaxWidth().height(12.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text("  $count", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        if (stats.trouble.isNotEmpty()) {
            Section("よく間違える語") {
                Column {
                    stats.trouble.forEach { (entry, misses) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenEntry(entry.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(entry.lemma, modifier = Modifier.weight(1f))
                            Text(
                                entry.ja.firstOrNull().orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text("$misses 回", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
