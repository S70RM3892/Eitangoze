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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eitangoze.data.Genre
import com.eitangoze.data.Passage
import com.eitangoze.ui.AppViewModel

/**
 * Every passage in the app, by genre.
 *
 * This is the escape hatch, not the front door. The plan is that the app hands
 * out a passage chosen against the reader's own coverage rather than asking
 * them to pick one (docs/DESIGN.md ⑧) — but somebody who wants to read about
 * 芸術 today should not have to fight the algorithm for it.
 */
@Composable
fun LibraryScreen(model: AppViewModel, onOpen: (Long) -> Unit) {
    val repo = model.repository
    var genre by remember { mutableStateOf<Genre?>(null) }
    var shelf by remember { mutableStateOf<List<Triple<Genre, String, Int>>>(emptyList()) }
    var passages by remember { mutableStateOf<List<Passage>>(emptyList()) }

    LaunchedEffect(repo) { shelf = repo?.shelf().orEmpty() }
    LaunchedEffect(repo, genre) {
        passages = repo?.passageList(genre, limit = 60).orEmpty()
    }

    val counts = shelf.groupBy { it.first }.mapValues { (_, rows) -> rows.sumOf { it.third } }
    val colors = MaterialTheme.colorScheme

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "読みたいジャンルから選べます",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "すべて",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (genre == null) FontWeight.Bold else FontWeight.Normal,
                        color = if (genre == null) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.clickable { genre = null }.padding(4.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Genre.entries.filter { counts[it].orEmpty0() > 0 }.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth()) {
                        pair.forEach { item ->
                            Text(
                                "${item.ja}  ${counts[item] ?: 0}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (genre == item) FontWeight.Bold
                                else FontWeight.Normal,
                                color = if (genre == item) colors.primary else colors.onSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { genre = item }
                                    .padding(vertical = 6.dp),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Divider()
        }

        items(passages) { passage ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(passage.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(passage.title, fontSize = 16.sp)
                Spacer(Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Chip(passage.genre.ja)
                    Chip(passage.cefr)
                    Text(
                        "${passage.words} 語 ・ ${passage.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Divider(color = colors.surfaceVariant)
        }

        if (passages.isEmpty()) {
            item {
                Text(
                    "このジャンルの英文はまだ入っていません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private fun Int?.orEmpty0(): Int = this ?: 0
