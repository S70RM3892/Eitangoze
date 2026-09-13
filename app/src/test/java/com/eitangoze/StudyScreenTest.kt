package com.eitangoze

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.AnswerMode
import com.eitangoze.ui.AppViewModel
import com.eitangoze.ui.screens.ANSWER_SHEET_TAG
import com.eitangoze.ui.screens.StudyScreen
import com.eitangoze.ui.theme.EitangozeTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The study screen actually drawn, on a small phone.
 *
 * The screen's whole claim is that a card fits without scrolling and that the
 * answer arrives by rising into place rather than by being scrolled to. Both of
 * those are layout, so nothing but laying it out can check them — and a screen
 * that throws on first composition is the one failure a data test never sees.
 * The narrow qualifier is deliberate: if it composes at 360×640 it composes
 * anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class StudyScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A view model with a queue, waited for *outside* the compose clock.
     *
     * Installing the content database runs on `Dispatchers.IO`, which the
     * compose test clock knows nothing about, so the wait has to be real time
     * plus a main-looper drain for the continuations that come back.
     */
    private fun studying(): AppViewModel {
        val model = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        waitFor("the content database to install") { !model.loading }
        assertTrue("load failed: ${model.loadError}", model.loadError == null)
        model.startStudy()
        waitFor("a card to be queued") { model.current != null }
        return model
    }

    private fun waitFor(what: String, limitMs: Long = 120_000, ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + limitMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ready()) return
            Thread.sleep(20)
        }
        fail("timed out waiting for $what")
    }

    @Test
    fun `a card draws, and answering raises the sheet instead of scrolling`() {
        val model = studying()
        val card = model.current
        assertNotNull("no card was queued to draw", card)
        card!!

        compose.setContent {
            EitangozeTheme(dark = false) {
                StudyScreen(model, onFinished = {}, onOpenEntry = {})
            }
        }
        compose.waitForIdle()

        // Before answering: the question is up and nothing has been revealed.
        compose.onNodeWithText(card.instruction).assertExists()
        assertTrue("revealed before answering", !model.answer.revealed)

        // Commit. A choice card is answered by picking, anything else by asking.
        if (card.mode == AnswerMode.CHOICE) {
            compose.onNodeWithText("  ${card.choices.first()}").performClick()
        } else {
            compose.onNodeWithText("答えを見る").performClick()
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        assertTrue("answering did not reveal", model.answer.revealed)

        // The rating row has replaced the commit row.
        compose.onNodeWithText("もう一度").assertExists()

        // The question is still on screen. This is the whole point of the sheet
        // covering the lower half rather than pushing the question off the top:
        // the sentence you just judged stays readable beside the answer.
        val instruction = compose.onAllNodesWithText(card.instruction).fetchSemanticsNodes()
        assertTrue("the question left the screen when the sheet rose", instruction.isNotEmpty())

        // The sheet rose from the bottom: it starts below the question and
        // runs down to the rating buttons, rather than appearing inline where
        // the options were.
        val screen = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        val sheet = compose.onNodeWithTag(ANSWER_SHEET_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the sheet starts at ${sheet.top} on a ${screen}px screen, not below the question",
            instruction.all { it.boundsInRoot.top < sheet.top },
        )
        assertTrue(
            "the sheet ends at ${sheet.bottom}, so it is not anchored to the bottom",
            sheet.bottom > screen * 0.75f,
        )

        // And the answer itself is on it.
        assertTrue(
            "the word is not on the sheet",
            compose.onAllNodesWithText(card.entry.lemma).fetchSemanticsNodes()
                .any { it.boundsInRoot.top >= sheet.top },
        )
    }
}
