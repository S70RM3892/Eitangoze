package com.eitangoze.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eitangoze.data.AnswerMode
import com.eitangoze.data.CardKind
import com.eitangoze.data.Deck
import com.eitangoze.data.Entry
import com.eitangoze.data.Grade
import com.eitangoze.data.GradeResult
import com.eitangoze.data.Repository
import com.eitangoze.data.StudyCard
import com.eitangoze.data.TextReport
import com.eitangoze.data.gradeEnglish
import com.eitangoze.srs.Rating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the answer of the current card stands. */
data class AnswerState(
    val revealed: Boolean = false,
    val typed: String = "",
    val chosen: Int? = null,
    val result: GradeResult? = null,
    val checked: Set<Int> = emptySet(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private var repo: Repository? = null

    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    var decks by mutableStateOf<List<Repository.DeckStatus>>(emptyList())
        private set
    var stats by mutableStateOf<Repository.Stats?>(null)
        private set

    var queue by mutableStateOf<List<StudyCard>>(emptyList())
        private set
    var position by mutableStateOf(0)
        private set
    var answer by mutableStateOf(AnswerState())
        private set
    var sessionAnswered by mutableStateOf(0)
        private set
    var sessionCorrect by mutableStateOf(0)
        private set

    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<Entry>>(emptyList())
        private set
    var detail by mutableStateOf<Repository.EntryDetail?>(null)

    /** The morpheme grid, open over everything else when it is not null. */
    var grid by mutableStateOf<Repository.Grid?>(null)
        private set

    // ---- reading your own English -------------------------------------------

    var readerText by mutableStateOf("")
        private set
    var report by mutableStateOf<TextReport?>(null)
        private set
    var analyzing by mutableStateOf(false)
        private set
    var selectedGaps by mutableStateOf<Set<Long>>(emptySet())
        private set
    var readerMessage by mutableStateOf<String?>(null)
        private set
    var importText by mutableStateOf("")
        private set
    var importResult by mutableStateOf<Repository.ImportResult?>(null)
        private set

    /** Which point on the forgetting timeline the passage is drawn at. */
    var horizon by mutableStateOf(0)
        private set

    val repository: Repository? get() = repo

    init {
        viewModelScope.launch {
            // Installing the content database is a one-off multi-second job the
            // first time the app runs, so it never touches the main thread.
            val result = withContext(Dispatchers.IO) {
                runCatching { Repository(getApplication()) }
            }
            result.onSuccess {
                repo = it
                refresh()
            }.onFailure {
                loadError = it.message ?: "語彙データベースを開けませんでした"
            }
            loading = false
        }
    }

    fun refresh() {
        val repo = repo ?: return
        viewModelScope.launch {
            val (d, s) = withContext(Dispatchers.IO) { repo.deckStatuses() to repo.stats() }
            decks = d
            stats = s
        }
    }

    // ---- studying -----------------------------------------------------------

    fun startStudy(weakestForExam: Boolean = false) {
        val repo = repo ?: return
        viewModelScope.launch {
            val cards = withContext(Dispatchers.IO) {
                if (weakestForExam) repo.weakestAtExam() else repo.buildQueue()
            }
            queue = cards
            position = 0
            answer = AnswerState()
            sessionAnswered = 0
            sessionCorrect = 0
        }
    }

    val current: StudyCard? get() = queue.getOrNull(position)

    fun type(text: String) {
        if (!answer.revealed) answer = answer.copy(typed = text)
    }

    fun choose(index: Int) {
        val card = current ?: return
        if (answer.revealed) return
        val correct = index == card.correctIndex
        answer = answer.copy(
            chosen = index,
            revealed = true,
            result = GradeResult(
                if (correct) Grade.CORRECT else Grade.WRONG,
                card.correctChoice,
                if (correct) "正解" else "不正解",
            ),
        )
    }

    fun toggleCheck(index: Int) {
        val checked = answer.checked.toMutableSet()
        if (!checked.add(index)) checked.remove(index)
        answer = answer.copy(checked = checked)
    }

    fun reveal() {
        val card = current ?: return
        if (answer.revealed) return
        answer = when (card.mode) {
            AnswerMode.TYPE -> answer.copy(
                revealed = true,
                result = gradeEnglish(answer.typed, card.accepted, card.alsoAccepted),
            )
            else -> answer.copy(revealed = true)
        }
    }

    /**
     * For a self-checked composition, how many points the learner ticked decides
     * which rating is suggested — the same reasoning as a marked answer, just
     * with the marking done by the person who wrote it.
     */
    fun suggestedRating(): Rating {
        val card = current ?: return Rating.GOOD
        answer.result?.let { return it.suggested }
        if (card.mode == AnswerMode.SELF_CHECK && card.checklist.isNotEmpty()) {
            val ratio = answer.checked.size.toDouble() / card.checklist.size
            return when {
                ratio >= 0.99 -> Rating.EASY
                ratio >= 0.75 -> Rating.GOOD
                ratio >= 0.4 -> Rating.HARD
                else -> Rating.AGAIN
            }
        }
        return Rating.GOOD
    }

    fun previewDelays(): Map<Rating, Long> {
        val repo = repo ?: return emptyMap()
        val card = current ?: return emptyMap()
        return repo.previewDelays(card)
    }

    fun rate(rating: Rating) {
        val repo = repo ?: return
        val card = current ?: return
        val correct = when {
            answer.result != null -> answer.result!!.grade != Grade.WRONG
            else -> rating != Rating.AGAIN
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.answer(card, rating, correct) }
            sessionAnswered++
            if (correct) sessionCorrect++
            // A card answered "again" comes back at the end of the session
            // rather than disappearing until tomorrow.
            queue = if (rating == Rating.AGAIN) queue + card else queue
            position++
            answer = AnswerState()
            if (position >= queue.size) refresh()
        }
    }

    fun skip() {
        position++
        answer = AnswerState()
    }

    // ---- browsing -----------------------------------------------------------

    fun search(query: String) {
        searchQuery = query
        val repo = repo ?: return
        viewModelScope.launch {
            searchResults = withContext(Dispatchers.IO) { repo.search(query) }
        }
    }

    fun openEntry(id: Long) {
        val repo = repo ?: return
        viewModelScope.launch {
            detail = withContext(Dispatchers.IO) { repo.detail(id) }
        }
    }

    fun closeEntry() {
        detail = null
    }

    fun openAffix(affixId: Long) {
        val repo = repo ?: return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { repo.gridForAffix(affixId) }
            if (found != null) {
                grid = found
                detail = null
            }
        }
    }

    fun openStem(stem: String) {
        val repo = repo ?: return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { repo.gridForStem(stem) }
            if (found != null) {
                grid = found
                detail = null
            }
        }
    }

    fun closeGrid() {
        grid = null
    }

    fun toggleStar() {
        val repo = repo ?: return
        val current = detail ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.setStarred(current.entry.id, !current.starred) }
            detail = withContext(Dispatchers.IO) { repo.detail(current.entry.id) }
        }
    }

    // ---- reading -------------------------------------------------------------

    fun updateReaderText(text: String) {
        readerText = text
    }

    /** Analyse pasted or shared English; [andRun] is set when text arrived from
     *  another app, where the learner has already asked for this. */
    fun analyze(text: String? = null, andRun: Boolean = false) {
        val repo = repo ?: return
        text?.let { readerText = it }
        if (readerText.isBlank()) return
        if (!andRun && analyzing) return
        analyzing = true
        readerMessage = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repo.analyze(readerText) }
            report = result
            // Everything that would take the reader to 98% starts ticked; the
            // rest is there to add deliberately.
            selectedGaps = result.gaps.take(result.gapsToThreshold)
                .mapNotNull { it.entry?.id }.toSet()
            horizon = 0
            analyzing = false
        }
    }

    fun moveHorizon(index: Int) {
        horizon = index
    }

    fun clearReport() {
        report = null
        selectedGaps = emptySet()
        readerMessage = null
    }

    fun toggleGap(entryId: Long) {
        selectedGaps = if (entryId in selectedGaps) selectedGaps - entryId
        else selectedGaps + entryId
    }

    fun pickSelectedGaps() {
        val repo = repo ?: return
        val report = report ?: return
        // Keep the "most blocking first" order the analysis worked out.
        val ordered = report.gaps.mapNotNull { it.entry?.id }.filter { it in selectedGaps }
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { repo.pick(ordered, source = "text") }
            readerMessage = "$added 語を「自分の英文・単語帳から」に追加しました"
            selectedGaps = emptySet()
            refresh()
        }
    }

    // ---- importing a word list ----------------------------------------------

    fun updateImportText(text: String) {
        importText = text
    }

    fun matchWordList() {
        val repo = repo ?: return
        viewModelScope.launch {
            importResult = withContext(Dispatchers.IO) { repo.importWordList(importText) }
            readerMessage = null
        }
    }

    fun pickImported() {
        val repo = repo ?: return
        val matched = importResult?.matched ?: return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                repo.pick(matched.map { it.id }, source = "list")
            }
            readerMessage = "$added 語を追加しました"
            refresh()
        }
    }

    fun clearImport() {
        importText = ""
        importResult = null
        readerMessage = null
    }

    // ---- settings -----------------------------------------------------------

    fun setDeckEnabled(deck: Deck, enabled: Boolean) {
        val repo = repo ?: return
        val current = repo.enabledDecks.toMutableList()
        if (enabled) {
            if (deck.id !in current) current.add(deck.id)
        } else {
            current.remove(deck.id)
        }
        repo.enabledDecks = current
        refresh()
    }

    fun setKindEnabled(kind: CardKind, enabled: Boolean) {
        val repo = repo ?: return
        val current = repo.enabledKinds.toMutableSet()
        if (enabled) current.add(kind) else current.remove(kind)
        // Leaving no question types on would make the app unable to ask anything.
        if (current.isNotEmpty()) repo.enabledKinds = current
        refresh()
    }

    fun setNewPerDay(value: Int) {
        repo?.newPerDay = value
        refresh()
    }

    fun setReviewLimit(value: Int) {
        repo?.reviewLimit = value
        refresh()
    }

    fun setRetention(value: Double) {
        repo?.desiredRetention = value
        refresh()
    }

    fun setBaselineLevel(level: String) {
        repo?.baselineLevel = level
        report = null
        refresh()
    }

    fun setExamDate(value: Long) {
        repo?.examDate = value
        refresh()
    }

    override fun onCleared() {
        repo?.close()
        repo = null
    }
}
