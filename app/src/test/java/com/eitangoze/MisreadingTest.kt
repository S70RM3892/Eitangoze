package com.eitangoze

import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.CardKey
import com.eitangoze.data.CardKind
import com.eitangoze.data.ContentDb
import com.eitangoze.data.Deck
import com.eitangoze.data.Repository
import com.eitangoze.data.UserDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one thing on the results screen that reading again would never reveal.
 *
 * Unknown words announce themselves. A word you already know announces nothing:
 * read `abstract` as 抽象的な in a sentence that meant 要約 and there is no gap,
 * no hesitation, and no reason to look anything up. So the flag has to come from
 * outside the reader — and it has to be *honest*, because a false alarm on a word
 * they do know teaches them to ignore the whole feature.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MisreadingTest {

    private lateinit var db: ContentDb

    @Before
    fun open() {
        db = ContentDb.open(ApplicationProvider.getApplicationContext())
    }

    @After
    fun close() {
        db.close()
    }

    /**
     * The population the feature draws on: basic words carrying a second meaning
     * big enough to be worth warning about. Measured, not assumed — if a
     * database change collapsed this to a handful, the feature would quietly
     * stop existing and nothing else would fail.
     */
    @Test
    fun `enough basic words carry an unasked meaning to be worth flagging`() {
        var polysemous = 0
        var risky = 0
        for (deckId in listOf("A1", "A2", "B1")) {
            db.deckEntries(Deck.byId(deckId)!!, emptySet(), 600).forEach { entry ->
                val senses = db.senses(entry.id).filter { it.semcor > 0 }
                if (senses.size < 2) return@forEach
                polysemous++
                val total = senses.sumOf { it.semcor }.toDouble()
                val second = senses.sortedByDescending { it.semcor }[1]
                if (second.semcor / total >= 0.20) risky++
            }
        }
        assertTrue("only $polysemous polysemous basic words", polysemous > 300)
        assertTrue("only $risky would ever be flagged", risky > 150)
    }

    /**
     * A word whose meanings the learner has *all* been asked about is never
     * flagged. This is the false-alarm case, and it is the one that would make
     * the feature worth switching off.
     */
    @Test
    fun `a word you have been asked about in every meaning is not flagged`() {
        val repo = Repository(ApplicationProvider.getApplicationContext())
        val user = UserDb(ApplicationProvider.getApplicationContext())
        val entry = firstPolysemous() ?: return
        val senses = repo.content.senses(entry.id).filter { it.semcor > 0 }
        val now = System.currentTimeMillis()

        // Asked about every meaning: nothing is unstudied, so nothing to say.
        senses.forEach { sense ->
            user.introduce(
                CardKey.of(CardKind.MEANING, entry.id, sense.id.toString()),
                CardKind.MEANING, entry.id, sense.id, "A1", sense.id.toString(), now,
            )
        }
        val report = repo.analyze(entry.lemma, now)
        assertEquals(emptyList<Repository.MisreadingRisk>(), repo.misreadingRisks(report))
    }

    /** And a word never met at all is a gap, not a misreading. */
    @Test
    fun `a word you have never met is not flagged`() {
        val repo = Repository(ApplicationProvider.getApplicationContext())
        val entry = firstPolysemous() ?: return
        val report = repo.analyze(entry.lemma)
        assertTrue(
            "an unmet word was reported as a misreading risk",
            repo.misreadingRisks(report).isEmpty(),
        )
    }

    private fun firstPolysemous() =
        db.deckEntries(Deck.byId("A2")!!, emptySet(), 400).firstOrNull { entry ->
            val senses = db.senses(entry.id).filter { it.semcor > 0 }
            if (senses.size < 2) return@firstOrNull false
            val total = senses.sumOf { it.semcor }.toDouble()
            senses.sortedByDescending { it.semcor }[1].semcor / total >= 0.20
        }
}
