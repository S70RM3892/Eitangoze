package com.eitangoze

import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.CardFactory
import com.eitangoze.data.CardKind
import com.eitangoze.srs.CardPhase
import com.eitangoze.data.CardSpec
import com.eitangoze.data.Deck
import com.eitangoze.data.Entry
import com.eitangoze.data.Repository
import com.eitangoze.data.StudyCard
import com.eitangoze.data.UserDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every question has to be answerable from what is on the screen, and only from
 * what is on the screen.
 *
 * Reported from the app, with a photograph. The 文脈判別 card for `measure`
 * showed "they held a public hearing on the bill" over 「彼らはその法案についての
 * 公聴会を催した」 and asked which meaning of `measure` the sentence used. The
 * word is not in the English, and the correct option — 法案 — is printed in the
 * Japanese underneath. Both halves of the question were broken at once: nothing
 * to reason from, and the answer given away.
 *
 * Neither was a stray row. WordNet files an example against a synset rather than
 * a word, so the sentence is written with whichever member of the set the
 * lexicographer reached for; 24.8% of the context cards in the shipped database
 * were built on a sentence that did not contain the word. And a translation of a
 * sentence is a translation of the word in it, so the Japanese gave the answer
 * away on every single one — visibly, character for character, on 18.8%.
 *
 * This sweeps the decks rather than checking `measure`, because the failure was
 * a property of how the cards were built and a spot check would not have caught
 * it either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerableTest {

    private lateinit var repo: Repository
    private lateinit var factory: CardFactory

    @Before
    fun setUp() {
        repo = Repository(ApplicationProvider.getApplicationContext())
        factory = CardFactory(repo.content)
    }

    @After
    fun tearDown() = repo.close()

    /** A spread of the taught vocabulary: common words and hard ones both. */
    private fun sample(perDeck: Int = 150): List<Entry> =
        listOf("A1", "A2", "B1", "B2", "C1", "C2").flatMap { id ->
            repo.content.deckEntries(Deck.byId(id)!!, exclude = emptySet(), limit = perDeck)
        }

    private fun cards(entry: Entry, kind: CardKind): List<StudyCard> =
        factory.specs(entry, "A1", setOf(kind)).mapNotNull { spec -> build(spec) }

    private fun build(spec: CardSpec): StudyCard? = factory.build(
        UserDb.DueCard(
            key = spec.key, kind = spec.kind, entryId = spec.entryId, senseId = spec.senseId,
            deck = "A1", extra = spec.extra, due = 0, stability = 0.0, lastReview = null,
            phase = CardPhase.NEW,
        )
    )

    /** The reported card, named. */
    @Test
    fun `the sentence on a context card contains the word it asks about`() {
        val words = sample()
        var checked = 0
        words.forEach { entry ->
            cards(entry, CardKind.CONTEXT).forEach { card ->
                checked++
                assertTrue(
                    "${entry.lemma}: 「${card.prompt}」 does not contain the word",
                    entry.appearsIn(card.prompt),
                )
            }
        }
        assertTrue("no context cards were built at all", checked >= 100)
    }

    /**
     * Nothing Japanese is printed with a question whose answer is Japanese. The
     * translation of the sentence is not a hint, it is the answer written out.
     */
    @Test
    fun `a context card does not print the answer under the question`() {
        val words = sample()
        var checked = 0
        words.forEach { entry ->
            cards(entry, CardKind.CONTEXT).forEach { card ->
                checked++
                assertEquals(
                    "${entry.lemma}: the translation is still on the question",
                    "", card.promptJa,
                )
                // The values only: 「品詞」「発音」「レベル」 are fixed furniture,
                // and `point` has 「レベル」 among its meanings.
                val shown = card.prompt + " " + card.promptNotes.joinToString(" ") { it.second }
                card.choices.forEach { choice ->
                    choice.split("、").forEach { gloss ->
                        assertFalse(
                            "${entry.lemma}: 「$gloss」 is visible in 「$shown」",
                            gloss.isNotBlank() && shown.contains(gloss),
                        )
                    }
                }
                // It is worth reading once the answer is in: it moves below.
                assertTrue(card.answerNotes.isNotEmpty())
            }
        }
        assertTrue("no context cards were built at all", checked >= 100)
    }

    /**
     * The blank falls on one spelling and a sentence can carry two: "Plums grow
     * on ______ trees", "The President ______ the bill, but Congress overrode
     * his veto". Blanking both would turn one question into two answers, so
     * these sentences are not asked about.
     */
    @Test
    fun `a cloze sentence does not leave another form of the word standing`() {
        val words = sample()
        var checked = 0
        words.forEach { entry ->
            cards(entry, CardKind.CLOZE).forEach { card ->
                checked++
                assertFalse(
                    "${entry.lemma}: 「${card.prompt}」 still shows the word",
                    entry.appearsIn(card.prompt),
                )
            }
        }
        assertTrue("no cloze cards were built at all", checked >= 100)
    }

    /** `wee wee`: blanking one half leaves the other one on the screen. */
    @Test
    fun `a collocation card does not show the word it is asking for`() {
        val words = sample()
        var checked = 0
        words.forEach { entry ->
            cards(entry, CardKind.COLLOCATION).forEach { card ->
                checked++
                val answer = card.correctChoice.ifBlank { card.accepted.firstOrNull().orEmpty() }
                assertFalse(
                    "${entry.lemma}: 「${card.prompt}」 already contains 「$answer」",
                    answer.isNotBlank() &&
                        Regex("\\b${Regex.escape(answer)}\\b", RegexOption.IGNORE_CASE)
                            .containsMatchIn(card.prompt),
                )
            }
        }
        assertTrue("no collocation cards were built at all", checked >= 50)
    }

    /**
     * The answer is not simply the longest button.
     *
     * The wrong meanings came from a different place than the right one — a
     * random word's headline, cut to its first gloss — while the right one was
     * the whole meaning. So the correct option was the only one with a 「、」 in
     * it on 933 of 1,200 cards, and the longest line on 1,002 of them. Nothing
     * about the English had to be read to score 84%.
     */
    @Test
    fun `the meaning card does not give itself away by the shape of the options`() {
        var cards = 0
        var onlyMulti = 0
        var longest = 0
        sample(400).forEach { entry ->
            cards(entry, CardKind.MEANING).forEach { card ->
                if (card.choices.size < 2) return@forEach
                cards++
                val counts = card.choices.map { it.split("、").size }
                assertEquals(
                    "${entry.lemma}: ${card.choices} are not the same shape",
                    1, counts.distinct().size,
                )
                if (counts.count { it > 1 } == 1 && card.correctChoice.contains("、")) onlyMulti++
                if (card.choices.maxByOrNull { it.length } == card.correctChoice) longest++
            }
        }
        assertTrue("no meaning cards were built at all", cards >= 300)
        assertEquals(0, onlyMulti)
        // One in four is chance. Japanese words are not all the same length, so
        // this will never be exactly a quarter; it must not be a strategy.
        assertTrue(
            "the longest option was right ${longest} times out of ${cards}",
            longest < cards * 0.4,
        )
    }

    /**
     * The wrong answers are words the app would teach.
     *
     * `if` was offered against 「ベリリウム」, which is the element — a true
     * thing about the spelling `be` and not a word anybody learns. The decks
     * stopped teaching those in v2.3; the distractors went on printing them.
     */
    @Test
    fun `the wrong answers are not words the app refuses to teach`() {
        // Every gloss that only a word the decks refuse to teach carries.
        val untaught = repo.content.untaught
        val banned = HashSet<String>()
        val allowed = HashSet<String>()
        repo.content.entries((untaught + (1L..20_000L)).toSet()).values.forEach { e ->
            (if (e.id in untaught) banned else allowed).addAll(e.ja.take(3))
        }
        banned.removeAll(allowed)
        assertTrue("nothing is off limits, so this proves nothing", banned.size > 100)
        assertTrue("ベリリウム" in banned)

        var checked = 0
        sample(200).forEach { entry ->
            cards(entry, CardKind.MEANING).forEach { card ->
                card.choices.forEachIndexed { i, choice ->
                    if (i == card.correctIndex) return@forEachIndexed
                    checked++
                    choice.split("、").forEach { gloss ->
                        assertFalse(
                            "${entry.lemma}: 「$gloss」 is only ever a word the app refuses " +
                                "to teach",
                            gloss in banned,
                        )
                    }
                }
            }
        }
        assertTrue(checked >= 300)
    }

    /**
     * A choice with the same word in it twice is one meaning pretending to be
     * several. `measure` offered 「量、クオンティティ、計測した大きさ、
     * クォンティティー、クォンティティ」 — five glosses, three words. WordNet has
     * the katakana from more than one source and spells it to taste.
     */
    @Test
    fun `the same word is not listed twice as a meaning`() {
        val small = "ァィゥェォャュョヮヵヶ"
        val large = "アイウエオヤユヨワカケ"
        fun fold(g: String) =
            if (!Regex("[ァ-ヴー・ｰ]+").matches(g)) g
            else g.filter { it != 'ー' && it != '・' && it != 'ｰ' }
                .map { small.indexOf(it).let { i -> if (i < 0) it else large[i] } }
                .joinToString("")

        var checked = 0
        sample().forEach { entry ->
            repo.content.senses(entry.id).forEach { sense ->
                checked++
                val folded = sense.ja.map(::fold)
                assertEquals(
                    "${entry.lemma}: ${sense.ja} says the same word twice",
                    folded.size, folded.distinct().size,
                )
            }
            val head = entry.ja.map(::fold)
            assertEquals("${entry.lemma}: ${entry.ja}", head.size, head.distinct().size)
        }
        assertTrue(checked >= 500)
    }
}
