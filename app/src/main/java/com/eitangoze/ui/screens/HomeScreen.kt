package com.eitangoze.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.Repository
import com.eitangoze.ui.AppViewModel
import kotlin.math.roundToInt

/**
 * The first screen: what is waiting today, and one button to start.
 *
 * Deck rows show learned / total rather than a tick count, because the question
 * a learner actually has is "how far through B2 am I", not "how many cards did
 * I answer".
 */
@Composable
fun HomeScreen(
    model: AppViewModel,
    onStudy: () -> Unit,
    onReader: () -> Unit,
    onBrowse: () -> Unit,
    onImport: () -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
) {
    val repo = model.repository
    val stats = model.stats
    val enabled = repo?.enabledDecks.orEmpty()
    val waiting = model.decks.filter { it.deck.id in enabled }.sumOf { it.waiting }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("Eitangoze", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "英語だけの単語・熟語アプリ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                ExamPanel(model)

                Spacer(Modifier.height(12.dp))
                Button(onClick = onStudy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (waiting > 0) "学習する（$waiting 枚待ち）" else "学習する")
                }
                if (stats != null && stats.answeredToday > 0) {
                    Spacer(Modifier.height(8.dp))
                    val rate = stats.correctToday * 100 / stats.answeredToday
                    Text(
                        "今日 ${stats.answeredToday} 問・正答率 $rate%" +
                            if (stats.streakDays > 1) "・${stats.streakDays} 日連続" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
                ReaderInvite(onReader)

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBrowse, modifier = Modifier.weight(1f)) {
                        Text("辞書")
                    }
                    OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                        Text("取り込み")
                    }
                    OutlinedButton(onClick = onStats, modifier = Modifier.weight(1f)) {
                        Text("状況")
                    }
                    OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) {
                        Text("設定")
                    }
                }
            }
            Divider()
        }

        item {
            Text(
                "デッキ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
            )
        }

        items(model.decks) { status ->
            DeckRow(status, status.deck.id in enabled) {
                model.setDeckEnabled(status.deck, status.deck.id !in enabled)
            }
        }

        item {
            val content = repo?.content
            if (content != null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "収録 ${content.entryCount} 項目 / ${content.senseCount} 語義 / " +
                        "${content.sentenceCount} 例文",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/**
 * The thing this app does that a word list cannot: measure a text against the
 * learner. It sits on the home screen because it is also the best way to decide
 * what to study next.
 */
@Composable
private fun ReaderInvite(onReader: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onReader)
            .padding(14.dp),
    ) {
        Text(
            "この英文、いま何％読める？",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "過去問・論文・ニュースを貼ると、あなたの記憶に対する読解カバー率と、" +
                "98% に届くために足りない単語だけが出ます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun DeckRow(status: Repository.DeckStatus, enabled: Boolean, onToggle: () -> Unit) {
    val progress = if (status.total == 0) 0f else status.introduced.toFloat() / status.total
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                status.deck.name,
                fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (status.waiting > 0 && enabled) {
                Chip(
                    "${status.waiting}",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(0.dp))
            Text(
                "  ${status.introduced} / ${status.total}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            status.deck.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * The exam-day panel.
 *
 * The number that matters before an exam is not "how many cards are due" but
 * "how much of this will still be there on the day", so that is what is shown.
 */
@Composable
private fun ExamPanel(model: AppViewModel) {
    val repo = model.repository ?: return
    val exam = repo.examDate
    val stats = model.stats
    if (exam <= 0L) {
        Text(
            "設定で試験日を入れると、出題が「今日が期限か」ではなく" +
                "「その日に覚えていられるか」に切り替わります",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val daysLeft = ((exam - System.currentTimeMillis()) / Repository.DAY_MS).toInt()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            if (daysLeft >= 0) "試験まで $daysLeft 日" else "試験日を過ぎています",
            fontWeight = FontWeight.SemiBold,
        )
        val readiness = stats?.examReadiness
        if (readiness != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "試験日の予測定着率 ${(readiness * 100).roundToInt()}%" +
                    "・目標を下回る見込み ${stats.atRiskAtExam} 枚",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { model.startStudy(weakestForExam = true) }) {
                Text("試験日に弱い順で復習する")
            }
        }
    }
}
