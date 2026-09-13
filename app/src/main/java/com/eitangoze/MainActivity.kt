package com.eitangoze

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
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
import com.eitangoze.ui.screens.FoldScreen
import com.eitangoze.ui.screens.GridScreen
import com.eitangoze.ui.screens.LibraryScreen
import com.eitangoze.ui.screens.PassageScreen
import com.eitangoze.ui.screens.HomeScreen
import com.eitangoze.ui.screens.ImportScreen
import com.eitangoze.ui.screens.ReaderScreen
import com.eitangoze.ui.screens.SettingsScreen
import com.eitangoze.ui.screens.StatsScreen
import com.eitangoze.ui.screens.StudyScreen
import com.eitangoze.ui.theme.EitangozeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = sharedText(intent)
        setContent {
            EitangozeTheme {
                App(shared = shared, lookUp = intent?.action == Intent.ACTION_PROCESS_TEXT)
            }
        }
    }

    /** Text handed to us by another app, via Share or the text-selection menu. */
    private fun sharedText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT ->
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }
}

private enum class Screen(val title: String) {
    HOME("Eitangoze"),
    STUDY("学習"),
    READER("読めるか測る"),
    LIBRARY("英文を読む"),
    BROWSE("辞書"),
    IMPORT("単語帳の取り込み"),
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
private fun App(
    model: AppViewModel = viewModel(),
    shared: String? = null,
    lookUp: Boolean = false,
) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    val detail = model.detail
    val grid = model.grid
    val reading = model.reading
    val sentence = model.currentSentence()

    // Text arriving from another app: a short selection is a lookup, anything
    // longer is a passage to measure.
    LaunchedEffect(shared, model.loading) {
        if (shared == null || model.loading) return@LaunchedEffect
        if (lookUp || shared.split(Regex("\\s+")).size <= 3) {
            model.search(shared)
            screen = Screen.BROWSE
        } else {
            screen = Screen.READER
            model.analyze(shared, andRun = true)
        }
    }

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

    val showBack = screen != Screen.HOME || detail != null || grid != null || reading != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        detail?.entry?.lemma
                            ?: grid?.held
                            ?: reading?.passage?.title?.take(28)
                            ?: screen.title,
                    )
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = {
                            when {
                                detail != null -> model.closeEntry()
                                grid != null -> model.closeGrid()
                                sentence != null -> model.closeSentence()
                                reading != null -> model.closeReading()
                                else -> screen = Screen.HOME
                            }
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
                EntryScreen(
                    model, detail,
                    onOpenEntry = model::openEntry,
                    onOpenAffix = model::openAffix,
                )
                return@Box
            }
            if (reading != null) {
                if (sentence != null) {
                    FoldScreen(
                        reading = reading,
                        sentence = sentence,
                        collapsed = model.collapsed,
                        onToggleFold = model::toggleFold,
                        onSkeleton = model::foldToSkeleton,
                        onUnfold = model::unfoldAll,
                        onClose = model::closeSentence,
                    )
                } else {
                    PassageScreen(model, reading, onStudySentence = model::studySentence)
                }
                return@Box
            }
            if (grid != null) {
                GridScreen(
                    grid = grid,
                    onOpenEntry = model::openEntry,
                    onFlipToAffix = model::openAffix,
                    onFlipToStem = model::openStem,
                )
                return@Box
            }
            when (screen) {
                Screen.HOME -> HomeScreen(
                    model = model,
                    onStudy = {
                        model.startStudy()
                        screen = Screen.STUDY
                    },
                    onReader = { screen = Screen.READER },
                    onLibrary = { screen = Screen.LIBRARY },
                    onBrowse = { screen = Screen.BROWSE },
                    onImport = { screen = Screen.IMPORT },
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
                Screen.READER -> ReaderScreen(model, onOpenEntry = model::openEntry)
                Screen.LIBRARY -> LibraryScreen(model, onOpen = model::openPassage)
                Screen.BROWSE -> BrowseScreen(model, onOpenEntry = model::openEntry)
                Screen.IMPORT -> ImportScreen(model)
                Screen.STATS -> StatsScreen(model, onOpenEntry = model::openEntry)
                Screen.SETTINGS -> SettingsScreen(model)
            }
        }
    }
}
