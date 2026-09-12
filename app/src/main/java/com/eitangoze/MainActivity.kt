package com.eitangoze

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eitangoze.ui.AppViewModel
import com.eitangoze.ui.screens.BrowseScreen
import com.eitangoze.ui.screens.EntryScreen
import com.eitangoze.ui.screens.HomeScreen
import com.eitangoze.ui.screens.SettingsScreen
import com.eitangoze.ui.screens.StatsScreen
import com.eitangoze.ui.screens.StudyScreen
import com.eitangoze.ui.theme.EitangozeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EitangozeTheme {
                App()
            }
        }
    }
}

private enum class Screen(val title: String) {
    HOME("Eitangoze"),
    STUDY("学習"),
    BROWSE("辞書"),
    STATS("学習状況"),
    SETTINGS("設定"),
}

/**
 * A single back stack of screens, plus the entry sheet which can open over any
 * of them. Nothing here needs a navigation library: there are five screens and
 * one detail view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(model: AppViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    val detail = model.detail

    if (model.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    "語彙データベースを準備しています",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        return
    }
    model.loadError?.let { message ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(message, Modifier.padding(24.dp))
        }
        return
    }

    val showBack = screen != Screen.HOME || detail != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.entry?.lemma ?: screen.title) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = {
                            if (detail != null) model.closeEntry() else screen = Screen.HOME
                        }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (detail != null) {
                EntryScreen(model, detail, onOpenEntry = model::openEntry)
                return@Box
            }
            when (screen) {
                Screen.HOME -> HomeScreen(
                    model = model,
                    onStudy = {
                        model.startStudy()
                        screen = Screen.STUDY
                    },
                    onBrowse = { screen = Screen.BROWSE },
                    onStats = { screen = Screen.STATS },
                    onSettings = { screen = Screen.SETTINGS },
                )
                Screen.STUDY -> StudyScreen(
                    model = model,
                    onFinished = {
                        model.refresh()
                        screen = Screen.HOME
                    },
                    onOpenEntry = model::openEntry,
                )
                Screen.BROWSE -> BrowseScreen(model, onOpenEntry = model::openEntry)
                Screen.STATS -> StatsScreen(model, onOpenEntry = model::openEntry)
                Screen.SETTINGS -> SettingsScreen(model)
            }
        }
    }
}
