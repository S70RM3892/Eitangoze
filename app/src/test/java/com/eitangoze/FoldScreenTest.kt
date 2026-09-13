package com.eitangoze

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.Fold
import com.eitangoze.data.ParsedSentence
import com.eitangoze.data.PassageDb
import com.eitangoze.data.Repository
import com.eitangoze.ui.screens.FOLD_SENTENCE_TAG
import com.eitangoze.ui.screens.FoldScreen
import com.eitangoze.ui.theme.EitangozeTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fold view, drawn and then actually folded.
 *
 * What is being checked is the claim the screen makes: press 骨まで畳む and a
 * long sentence becomes a short one *with its main verb still in it*. That is
 * layout and state together, so only running it can tell.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class FoldScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: PassageDb

    @Before
    fun open() {
        db = PassageDb.open(ApplicationProvider.getApplicationContext())
    }

    @After
    fun close() {
        db.close()
    }

    /** A passage and one of its sentences that has something to fold. */
    private fun foldable(): Pair<Repository.Reading, ParsedSentence> {
        for (passage in db.passages(limit = 40)) {
            val sentences = db.sentences(passage.id)
            val found = sentences.firstOrNull {
                it.confirmed && it.outermostFolds().isNotEmpty() && it.size >= 15
            }
            if (found != null) return Repository.Reading(passage, sentences) to found
        }
        throw AssertionError("no sentence in the shipped library can be folded")
    }

    @Test
    fun `folding to the skeleton shortens the sentence and keeps the verb`() {
        val (reading, sentence) = foldable()
        var collapsed by mutableStateOf<List<Fold>>(emptyList())

        compose.setContent {
            EitangozeTheme(dark = false) {
                FoldScreen(
                    reading = reading,
                    sentence = sentence,
                    collapsed = collapsed,
                    onToggleFold = { fold ->
                        collapsed = if (fold in collapsed) collapsed - fold
                        else collapsed.filterNot { it.start >= fold.start && it.end <= fold.end } + fold
                    },
                    onSkeleton = { collapsed = sentence.outermostFolds() },
                    onUnfold = { collapsed = emptyList() },
                    onClose = {},
                )
            }
        }
        compose.waitForIdle()

        val full = compose.onNodeWithTag(FOLD_SENTENCE_TAG).fetchSemanticsNode().textOf()
        assertTrue("the sentence did not render", full.isNotBlank())
        assertTrue("nothing is folded yet, so no mark should be shown", !full.contains("⌄"))

        compose.onNodeWithText("骨まで畳む").performClick()
        compose.waitForIdle()

        val short = compose.onNodeWithTag(FOLD_SENTENCE_TAG).fetchSemanticsNode().textOf()
        assertTrue("folding did not shorten the sentence:\n$full\n$short", short.length < full.length)
        assertTrue("nothing was marked as folded", short.contains("⌄"))

        // The one invariant the whole exercise rests on.
        val verb = sentence.roles.entries.firstOrNull { it.value == "V" }?.key
        if (verb != null) {
            val word = reading.passage.text.let {
                val range = sentence.tokens[verb]
                it.substring(range.first, range.last + 1)
            }
            assertTrue("folding swallowed the main verb \"$word\":\n$short", short.contains(word))
        }

        // And it comes back.
        compose.onNodeWithText("ひらく").performClick()
        compose.waitForIdle()
        val again = compose.onNodeWithTag(FOLD_SENTENCE_TAG).fetchSemanticsNode().textOf()
        assertTrue("unfolding did not restore the sentence", !again.contains("⌄"))
    }
}

/**
 * Every piece of text under a node, joined.
 *
 * Recursive because the tag sits on the container: a plain Column does not
 * merge its descendants' semantics, so its own config holds no text at all.
 */
private fun SemanticsNode.textOf(): String = buildString {
    config.getOrNull(SemanticsProperties.Text)?.forEach { append(it.text).append(' ') }
    children.forEach { append(it.textOf()) }
}
