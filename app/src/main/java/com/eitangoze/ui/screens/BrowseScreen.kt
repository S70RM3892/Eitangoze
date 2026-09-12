package com.eitangoze.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
