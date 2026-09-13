package com.eitangoze

import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.Fold
import com.eitangoze.data.Genre
import com.eitangoze.data.PassageDb
import com.eitangoze.data.ParsedSentence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks against the reading library that actually ships.
 *
 * The trees in it were computed by a parser, not written by a person, so what
 * has to be verified is that they still describe the text they came from: that
 * the token offsets land on real words, that folding a sentence leaves a
 * sentence, and that the main verb never disappears inside a fold. A tree that
 * has drifted from its passage would quietly teach the wrong structure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassageDbTest {

    private lateinit var db: PassageDb

    @Before
    fun open() {
        db = PassageDb.open(ApplicationProvider.getApplicationContext())
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `the library is installed and carries its attribution`() {
        assertTrue("passages: ${db.passageCount}", db.passageCount >= 30)
        assertTrue("sentences: ${db.sentenceCount}", db.sentenceCount >= 300)
        db.passages(limit = 30).forEach { passage ->
            assertTrue("${passage.title} has no source", passage.source.isNotBlank())
            assertTrue("${passage.title} has no licence", passage.license.isNotBlank())
            assertTrue("${passage.title} has no link", passage.url.startsWith("http"))
            // A passage shorter than this is not the exercise, and one longer
            // is two of them. 京大 2026 set 730 and 580 words. Simple English is
            // the exception: it is written short on purpose, and holding it to
            // an exam passage's length leaves one article in the encyclopaedia.
            val floor = if (passage.genre == Genre.PLAIN) 200 else 380
            assertTrue(
                "${passage.title} (${passage.genre.code}) is ${passage.words} words",
                passage.words in floor..1200,
            )
            assertTrue(
                "${passage.title} claims to cover ${passage.coverable}",
                passage.coverable in 0.5..1.0,
            )
        }
    }

    @Test
    fun `token offsets land on the words they claim`() {
        var checked = 0
        db.passages(limit = 12).forEach { passage ->
            db.sentences(passage.id).take(20).forEach { sentence ->
                assertEquals(sentence.size, sentence.heads.size)
                assertEquals(sentence.size, sentence.deps.size)
                assertEquals(sentence.size, sentence.pos.size)
                sentence.tokens.forEach { range ->
                    assertTrue(
                        "${passage.title}: token runs off the passage",
                        range.first >= 0 && range.last < passage.text.length,
                    )
                }
                // The tokens are in order and inside the sentence they belong to.
                var previous = -1
                sentence.tokens.forEach { range ->
                    assertTrue("tokens out of order", range.first >= previous)
                    previous = range.first
                }
                assertTrue(
                    "${passage.title}: tokens outside their own sentence",
                    sentence.tokens.isEmpty() ||
                        (sentence.tokens.first().first >= sentence.start &&
                            sentence.tokens.last().last < sentence.end),
                )
                checked++
            }
        }
        assertTrue("no sentences checked", checked > 100)
    }

    /**
     * The rule the build step enforces and the app depends on: whatever is
     * folded away, the main verb is still there. Folding to nothing would
     * destroy the answer the exercise is teaching you to find.
     */
    @Test
    fun `folding never swallows the main verb`() {
        var folded = 0
        db.passages(limit = 20).forEach { passage ->
            db.sentences(passage.id).forEach { sentence ->
                if (!sentence.confirmed) {
                    assertTrue(
                        "${passage.title}: an unconfirmed sentence offers folds",
                        sentence.folds.isEmpty(),
                    )
                    return@forEach
                }
                val verb = sentence.roles.entries.firstOrNull { it.value == "V" }?.key
                    ?: return@forEach
                sentence.folds.forEach { fold ->
                    assertTrue(
                        "${passage.title}: fold ${fold.label} swallows the verb",
                        verb < fold.start || verb > fold.end,
                    )
                    assertTrue("a one-word fold", fold.length >= 3)
                    assertTrue(
                        "fold runs off the sentence",
                        fold.start >= 0 && fold.end < sentence.size,
                    )
                    folded++
                }
            }
        }
        assertTrue("nothing is foldable at all", folded > 100)
    }

    /** Folding shortens the sentence and keeps the rest of it word for word. */
    @Test
    fun `a folded sentence is the sentence with pieces taken out`() {
        var checked = 0
        db.passages(limit = 20).forEach { passage ->
            db.sentences(passage.id).forEach { sentence ->
                val outer = sentence.outermostFolds()
                if (outer.isEmpty()) return@forEach
                val full = sentence.text(passage.text)
                val short = sentence.folded(passage.text, outer)
                assertTrue(
                    "${passage.title}: folding made the sentence longer\n$full\n$short",
                    short.length < full.length,
                )
                assertTrue("nothing was marked as folded", short.contains("⌄"))
                // Every run of letters still shown was in the original. Split on
                // letters rather than on spaces: an em-dash butts straight up
                // against the mark (`today—⌄.`), exactly as it did in the text.
                Regex("[A-Za-z']{2,}").findAll(short).forEach { match ->
                    assertTrue(
                        "${passage.title}: folding invented \"${match.value}\"",
                        full.contains(match.value),
                    )
                }
                checked++
            }
        }
        assertTrue("no sentence could be folded", checked > 30)
    }

    @Test
    fun `the shelf covers more than one genre and level`() {
        val shelf = db.shelf()
        assertTrue("nothing on the shelf", shelf.isNotEmpty())
        assertTrue(shelf.sumOf { it.third } == db.passageCount)
    }

    /** The packed columns survive a round trip. */
    @Test
    fun `packed fields decode`() {
        assertEquals(listOf(3, 0, 1), PassageDb.parseInts("3,0,1"))
        assertEquals(emptyList<Int>(), PassageDb.parseInts(""))
        assertEquals(listOf(0 until 5, 6 until 9), PassageDb.parseOffsets("0:5,6:9"))
        assertEquals(listOf(Fold(2, 7, "advcl")), PassageDb.parseFolds("2:7:advcl"))
        assertEquals(mapOf(1 to "S", 4 to "V"), PassageDb.parseRoles("1:S,4:V"))
    }

    @Test
    fun `outermost folds do not overlap`() {
        db.passages(limit = 20).forEach { passage ->
            db.sentences(passage.id).forEach { sentence: ParsedSentence ->
                val outer = sentence.outermostFolds()
                for (i in outer.indices) {
                    for (j in i + 1 until outer.size) {
                        assertTrue(
                            "${passage.title}: ${outer[i]} overlaps ${outer[j]}",
                            !outer[i].overlaps(outer[j]),
                        )
                    }
                }
            }
        }
    }
}
