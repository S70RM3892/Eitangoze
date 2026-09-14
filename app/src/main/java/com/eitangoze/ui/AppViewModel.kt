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
import com.eitangoze.data.EssayCheck
import com.eitangoze.data.EssayPart
import com.eitangoze.data.Fold
import com.eitangoze.data.Genre
import com.eitangoze.data.ParsedSentence
import com.eitangoze.data.Grade
import com.eitangoze.data.GradeResult
import com.eitangoze.data.Repository
import com.eitangoze.data.StudyCard
import com.eitangoze.data.TextReport
import com.eitangoze.data.WordMap
import com.eitangoze.data.WritingReview
import com.eitangoze.data.WritingTask
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

    // ---- reading a shipped passage -------------------------------------------

    var reading by mutableStateOf<Repository.Reading?>(null)
        private set

    /** Which sentence the fold view is working on, or -1. */
    var studiedSentence by mutableStateOf(-1)
        private set

    /** The subtrees currently collapsed in [studiedSentence]. */
    var collapsed by mutableStateOf<List<Fold>>(emptyList())
        private set

    // ---- reading fast ---------------------------------------------------------

    /** Coverage of the open passage, measured against this learner's memory. */
    var readingReport by mutableStateOf<TextReport?>(null)
        private set

    /** When the reader said they started, or 0. */
    var readingStartedAt by mutableStateOf(0L)
        private set

    var readingResult by mutableStateOf<Repository.ReadingResult?>(null)
        private set

    var pickingPassage by mutableStateOf(false)
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

    fun openPassage(id: Long) {
        val repo = repo ?: return
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { repo.reading(id) }
            reading = opened
            studiedSentence = -1
            collapsed = emptyList()
            readingResult = null
            readingStartedAt = 0L
            readingReport = null
            if (opened != null) {
                readingReport = withContext(Dispatchers.IO) { repo.analyze(opened.passage.text) }
            }
        }
    }

    /**
     * Ask for a passage instead of choosing one.
     *
     * Nobody can judge their own coverage of a text they have not read, so the
     * choice is made from the study database rather than put to the reader.
     */
    fun pickPassage(wantFast: Boolean, genre: Genre? = null) {
        val repo = repo ?: return
        viewModelScope.launch {
            pickingPassage = true
            val picked = withContext(Dispatchers.IO) { repo.pickPassage(wantFast, genre) }
            pickingPassage = false
            if (picked == null) return@launch
            val (passage, report) = picked
            reading = withContext(Dispatchers.IO) { repo.reading(passage.id) }
            readingReport = report
            studiedSentence = -1
            collapsed = emptyList()
            readingResult = null
            readingStartedAt = 0L
        }
    }

    fun startTimer(now: Long = System.currentTimeMillis()) {
        readingStartedAt = now
        readingResult = null
    }

    fun stopTimer(now: Long = System.currentTimeMillis()) {
        val repo = repo ?: return
        val open = reading ?: return
        val report = readingReport ?: return
        val started = readingStartedAt
        if (started <= 0L) return
        viewModelScope.launch {
            readingResult = withContext(Dispatchers.IO) {
                repo.finishReading(open.passage, report, now - started)
            }
            readingStartedAt = 0L
        }
    }

    fun dismissResult() {
        readingResult = null
    }

    /**
     * Put the words that block this passage into the deck, most blocking first.
     *
     * Only as many as it takes to reach the unassisted line. Adding every
     * unknown word would bury the useful ones under names and one-off jargon.
     */
    fun takeReadingGaps() {
        val repo = repo ?: return
        val measured = readingReport ?: return
        val ordered = measured.gaps.take(measured.gapsToThreshold).mapNotNull { it.entry?.id }
        if (ordered.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { repo.pick(ordered, source = "passage") }
            readerMessage = "$added 語を「自分の英文・単語帳から」に追加しました"
            refresh()
        }
    }

    fun closeReading() {
        reading = null
        studiedSentence = -1
        collapsed = emptyList()
        readingReport = null
        readingResult = null
        readingStartedAt = 0L
    }

    /** Answers given to the structure questions on the open sentence. */
    var syntaxAnswers by mutableStateOf<Map<Int, Int>>(emptyMap())
        private set

    fun studySentence(ord: Int) {
        studiedSentence = ord
        collapsed = emptyList()
        syntaxAnswers = emptyMap()
    }

    fun answerSyntax(question: Int, choice: Int) {
        if (question in syntaxAnswers) return
        syntaxAnswers = syntaxAnswers + (question to choice)
    }

    fun closeSentence() {
        studiedSentence = -1
        collapsed = emptyList()
        syntaxAnswers = emptyMap()
    }

    /**
     * Collapse a subtree, or open it again.
     *
     * Folding something that contains already-folded pieces absorbs them: the
     * bigger fold is the answer to the same question, and leaving the inner
     * marks inside it would show a `⌄` that can no longer be opened.
     */
    fun toggleFold(fold: Fold) {
        collapsed = if (collapsed.any { it == fold }) {
            collapsed - fold
        } else {
            collapsed.filterNot { it.start >= fold.start && it.end <= fold.end } + fold
        }
    }

    /** Straight to the skeleton: every widest fold at once. */
    fun foldToSkeleton() {
        val sentence = currentSentence() ?: return
        collapsed = sentence.outermostFolds()
    }

    fun unfoldAll() {
        collapsed = emptyList()
    }

    fun currentSentence(): ParsedSentence? =
        reading?.sentences?.firstOrNull { it.ord == studiedSentence }

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

    // ---- the map ------------------------------------------------------------

    /** The word whose neighbourhood is on screen, or null while it loads. */
    var wordMap by mutableStateOf<WordMap?>(null)
        private set

    var mapMessage by mutableStateOf<String?>(null)
        private set

    /** The word the map is centred on, or 0 when the map is closed. */
    var mapCenter by mutableStateOf(0L)
        private set

    val mapOpen: Boolean get() = mapCenter != 0L

    /** Open the map on a word, or walk it to a neighbour. */
    fun openMap(entryId: Long) {
        val repo = repo ?: return
        mapMessage = null
        mapCenter = entryId
        // The entry sheet draws over everything; opening the map from it has to
        // put the sheet away or nothing appears to happen.
        detail = null
        viewModelScope.launch {
            val drawn = withContext(Dispatchers.IO) { repo.wordMap(entryId) }
            // A tap that landed while another was loading must not redraw the
            // screen with the word the learner has already walked away from.
            if (mapCenter == entryId) wordMap = drawn
        }
    }

    fun closeMap() {
        wordMap = null
        mapCenter = 0L
        mapMessage = null
    }

    /** Take the words on the map that are not known yet into the study list. */
    fun takeMapGaps() {
        val repo = repo ?: return
        val map = wordMap ?: return
        val ids = map.nodes.filterNot { it.known }.map { it.entry.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { repo.pick(ids, source = "map") }
            mapMessage = "$added 語を「自分の英文・単語帳から」に追加しました"
            openMap(map.center.id)
            refresh()
        }
    }

    // ---- writing English ----------------------------------------------------

    /** The Japanese sentence being written, or null before one is asked for. */
    var writingTask by mutableStateOf<WritingTask?>(null)
        private set

    var writingText by mutableStateOf("")
        private set

    var writingReview by mutableStateOf<WritingReview?>(null)
        private set

    var writingLoading by mutableStateOf(false)
        private set

    var writingMessage by mutableStateOf<String?>(null)
        private set

    /** True when the corpus had nothing this learner is ready to write yet. */
    var writingEmpty by mutableStateOf(false)
        private set

    fun nextWriting() {
        val repo = repo ?: return
        if (writingLoading) return
        writingLoading = true
        writingText = ""
        writingReview = null
        writingMessage = null
        viewModelScope.launch {
            val task = withContext(Dispatchers.IO) { repo.writingTask() }
            writingTask = task
            writingEmpty = task == null
            writingLoading = false
        }
    }

    fun typeWriting(text: String) {
        writingText = text
    }

    fun checkWriting() {
        val repo = repo ?: return
        val task = writingTask ?: return
        if (writingText.isBlank()) return
        viewModelScope.launch {
            writingReview = withContext(Dispatchers.IO) { repo.reviewWriting(task, writingText) }
        }
    }

    /** Put the words that were readable but not writable back in the queue. */
    fun takeWritingGaps() {
        val repo = repo ?: return
        val review = writingReview ?: return
        val ids = review.missed.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val taken = withContext(Dispatchers.IO) { repo.takeWritingGaps(ids) }
            writingMessage = "$taken 語の「和→英」を今日の学習に入れました"
            refresh()
        }
    }

    fun closeWriting() {
        writingTask = null
        writingText = ""
        writingReview = null
        writingMessage = null
        writingEmpty = false
    }

    // ---- free composition ----------------------------------------------------

    /** The four moves of the argument, kept apart so the shape is visible. */
    var essayParts by mutableStateOf(EssayPart.entries.associateWith { "" })
        private set

    var essayCheck by mutableStateOf<EssayCheck?>(null)
        private set

    var essayChecking by mutableStateOf(false)
        private set

    /** The whole answer, in the order it will be read. */
    val essayText: String
        get() = EssayPart.entries
            .mapNotNull { essayParts[it]?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" ")

    fun typeEssay(part: EssayPart, text: String) {
        essayParts = essayParts + (part to text)
        // The measurement belongs to the text that produced it.
        essayCheck = null
    }

    fun checkEssay() {
        val repo = repo ?: return
        val text = essayText
        if (text.isBlank() || essayChecking) return
        essayChecking = true
        viewModelScope.launch {
            essayCheck = withContext(Dispatchers.IO) { repo.checkEssay(text) }
            essayChecking = false
        }
    }

    fun clearEssay() {
        essayParts = EssayPart.entries.associateWith { "" }
        essayCheck = null
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
