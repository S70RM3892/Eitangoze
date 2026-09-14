package com.eitangoze

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.EssayPart
import com.eitangoze.ui.AppViewModel
import com.eitangoze.ui.screens.ESSAY_TAG
import com.eitangoze.ui.screens.EssayScreen
import com.eitangoze.ui.screens.WRITING_TAG
import com.eitangoze.ui.screens.WritingScreen
import com.eitangoze.ui.theme.EitangozeTheme
import org.junit.Assert.assertFalse
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
 * The two writing screens, actually drawn, on a small phone.
 *
 * A screen that throws on first composition is the failure a data test never
 * sees, and neither of these has a shape a unit test can check: the drill has to
 * survive having no task yet, and the composition screen has to count words
 * while they are being typed. Both are laid out at 360×640 on the principle
 * that what composes there composes anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class WritingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun model(): AppViewModel {
        val model = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        waitFor("the content database to install") { !model.loading }
        assertTrue("load failed: ${model.loadError}", model.loadError == null)
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
    fun `the drill draws a sentence and compares what was written`() {
        val model = model()
        val repo = model.repository!!
        repo.enabledDecks = listOf("A1")
        repo.buildQueue()
        repo.baselineLevel = "B1"

        compose.setContent {
            EitangozeTheme(dark = false) { WritingScreen(model, onOpenEntry = {}) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(WRITING_TAG).assertExists()

        waitFor("a sentence to be picked") { !model.writingLoading }
        compose.waitForIdle()
        val task = model.writingTask
        assertNotNull("nothing was asked", task)

        // The Japanese is on screen, and so is the reason this sentence and not
        // another one: it is made of words this learner can read.
        compose.onNodeWithText(task!!.ja).assertExists()

        compose.onNodeWithText("英語で書く").performTextInput(task.shortest.en)
        compose.waitForIdle()
        compose.onNodeWithText("参照訳と照合する").performClick()
        waitFor("the comparison") { model.writingReview != null }
        compose.waitForIdle()

        val review = model.writingReview!!
        assertTrue("writing the reference back missed ${review.missed}", review.missed.isEmpty())
        // What the screen says it is: an overlap of words, not a mark.
        assertTrue(
            compose.onAllNodesWithText("参照訳の内容語と重なった数です。点数ではありません。")
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `the composition screen counts words as they are typed`() {
        val model = model()
        compose.setContent { EitangozeTheme(dark = false) { EssayScreen(model) } }
        compose.waitForIdle()
        compose.onNodeWithTag(ESSAY_TAG).assertExists()

        // Empty: nothing is measured and the target is still stated.
        compose.onAllNodesWithText("0", substring = true).fetchSemanticsNodes()

        val claim = "I believe that every student should read one book each week."
        compose.onNodeWithText("${EssayPart.CLAIM.ja} — ${EssayPart.CLAIM.hint}")
            .performTextInput(claim)
        compose.waitForIdle()

        assertTrue(model.essayText.startsWith("I believe"))
        // Eleven words in, the answer is a long way short of the range, and the
        // screen says so rather than only showing a number.
        assertFalse(model.repository!!.checkEssay(model.essayText).withinLength)
        assertTrue(
            compose.onAllNodesWithText("11", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
