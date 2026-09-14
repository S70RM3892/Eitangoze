package com.eitangoze

import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.CardKind
import com.eitangoze.data.Deck
import com.eitangoze.data.EssayCheck
import com.eitangoze.data.Knowledge
import com.eitangoze.data.Repository
import com.eitangoze.data.WritingDrill
import com.eitangoze.data.WritingTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 和文英訳 and 自由英作文, against the shipped corpus.
 *
 * The claim being tested is narrow and worth stating: the drill only ever asks
 * for a sentence whose English this learner can already *read*, and what it
 * reports back is an overlap of content words against a translation somebody
 * else wrote — never a verdict on their English. Both halves of that are easy
 * to break by accident, and neither shows up in a screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WritingTest {

    private lateinit var repo: Repository

    @Before
    fun setUp() {
        repo = Repository(ApplicationProvider.getApplicationContext())
        repo.enabledDecks = listOf("A1")
        repo.newPerDay = 60
        // Cards have to exist before anything can be drilled: the sentences are
        // seeded from words the learner has met.
        repo.buildQueue()
        // Declaring a level is the quick way to a learner who can read: every
        // A1 and A2 word counts as known without a month of reviews.
        repo.baselineLevel = "B1"
    }

    @After
    fun tearDown() = repo.close()

    private fun drill() = WritingDrill(repo.content)

    @Test
    fun `the sentence asked for is one the learner could read`() {
        val task = repo.writingTask()
        assertNotNull("no writable sentence was found", task)
        task!!
        assertTrue(task.ja.isNotBlank())
        assertTrue("no reference translation", task.references.isNotEmpty())
        task.references.forEach { assertTrue(it.en.isNotBlank()) }

        // The whole point of seeding from the study database: the English is
        // made of words this learner is predicted to know by sight. Anything
        // less and a failure to write it would just mean "new word".
        assertTrue("readiness ${task.readiness}", task.readiness >= 0.8)
        if (task.clean) {
            // Every word the drill will hold the learner to is a word it
            // predicts they can read. Grammar is not among them: `be` and `to`
            // are structure, and the reference's content list already excludes
            // them.
            val wanted = task.shortest.content.map { it.id }.toSet()
            val shaky = repo.analyze(task.shortest.en).words.filter {
                it.entry?.id in wanted && it.knowledge != Knowledge.KNOWN
            }
            assertTrue("a clean task still contained ${shaky.map { it.surface }}", shaky.isEmpty())
        }
    }

    @Test
    fun `writing the reference back matches every content word`() {
        val task = repo.writingTask()!!
        val reference = task.shortest
        val review = repo.reviewWriting(task, reference.en)

        assertEquals(reference.content.map { it.lemma }, review.matched.map { it.lemma })
        assertTrue("missed ${review.missed.map { it.lemma }}", review.missed.isEmpty())
        assertTrue(review.substituted.isEmpty())
        assertEquals(1.0, review.overlap, 0.0001)
        assertTrue("extra ${review.extra.map { it.lemma }}", review.extra.isEmpty())
        // Nothing here is a claim about the English — only about the words in it.
        assertTrue(review.verdict.contains("時制"))
    }

    @Test
    fun `a content word left out is reported, and nothing else is`() {
        val task = repo.writingTask()!!
        val reference = task.shortest
        // A word whose dictionary form is the form in the sentence, so that
        // taking it out is a one-line edit rather than a conjugation exercise.
        val dropped = reference.content.lastOrNull {
            reference.en.contains(it.lemma, ignoreCase = true)
        } ?: return
        // Take the word out of the sentence, leaving everything else standing.
        val written = reference.en.replace(
            Regex("\\b${Regex.escape(dropped.lemma)}\\w*\\b", RegexOption.IGNORE_CASE), "",
        )
        val review = repo.reviewWriting(task, written)

        assertTrue(
            "${dropped.lemma} was not reported missing: ${review.missed.map { it.lemma }}",
            review.missed.any { it.id == dropped.id } || review.substituted.any {
                it.expected.id == dropped.id
            },
        )
        assertTrue("overlap ${review.overlap}", review.overlap < 1.0)
    }

    @Test
    fun `an empty attempt says so instead of scoring zero out of nothing`() {
        val task = repo.writingTask()!!
        val review = repo.reviewWriting(task, "   ")
        assertFalse(review.wrote)
        assertEquals("まだ何も書かれていません。", review.verdict)
        assertTrue(review.matched.isEmpty())
    }

    /**
     * Tatoeba pairs some Japanese with several English sentences, and all of
     * them are right. Marking against a fixed one would fail a learner for
     * having written the other translator's words.
     */
    @Test
    fun `the attempt is compared against whichever reference it came closest to`() {
        val multiple = repo.content.writingPool(limit = 400)
            .map { it.ja }.distinct().firstNotNullOfOrNull { ja ->
            drill().task(ja, readiness = 1.0) { repo.analyze(it) }
                ?.takeIf { it.references.size >= 2 }
        }
        // The corpus has plenty of these, but nothing guarantees one lands in a
        // random draw, so a miss skips rather than fails.
        if (multiple == null) return

        multiple.references.forEach { reference ->
            val review = repo.reviewWriting(multiple, reference.en)
            assertEquals(
                "writing reference ${reference.id} back was not matched against itself",
                reference.id, review.closest.id,
            )
            assertTrue(review.missed.isEmpty())
        }
    }

    /**
     * A word the dictionary itself calls related is not a miss. It is reported
     * on its own line, because only the writer can see whether this sentence was
     * a place where the two words are interchangeable.
     */
    @Test
    fun `a related word of your own is reported as a substitution, not a miss`() {
        val task = findTaskWithSynonym() ?: return
        val (built, expected, synonym) = task
        val written = built.shortest.en.replace(
            Regex("\\b${Regex.escape(expected)}\\b", RegexOption.IGNORE_CASE), synonym,
        )
        val review = repo.reviewWriting(built, written)
        assertTrue(
            "$expected → $synonym was called a miss",
            review.substituted.any { it.expected.lemma == expected } ||
                review.matched.any { it.lemma == expected },
        )
    }

    /** A task whose shortest reference has a content word with a listed relation. */
    private fun findTaskWithSynonym(): Triple<WritingTask, String, String>? {
        repeat(6) {
            val task = repo.writingTask() ?: return@repeat
            for (word in task.shortest.content) {
                if (!task.shortest.en.contains(word.lemma, ignoreCase = true)) continue
                val related = repo.content.relations(word.id, limit = 20).firstOrNull {
                    it.kind == com.eitangoze.data.RelationKind.SYNONYM &&
                        !it.other.lemma.contains(' ')
                } ?: continue
                return Triple(task, word.lemma, related.other.lemma)
            }
        }
        return null
    }

    @Test
    fun `words you could read but not write come back as production cards`() {
        val task = repo.writingTask()!!
        val missed = task.shortest.content.take(2)
        assertEquals(missed.size, repo.takeWritingGaps(missed.map { it.id }))

        // The card waiting afterwards is the production card — the one that
        // actually failed — and it is waiting now, not in three days.
        val queue = repo.buildQueue()
        missed.forEach { word ->
            assertTrue(
                "no 和→英 card waiting for ${word.lemma}",
                queue.any { it.kind == CardKind.PRODUCE && it.entry.id == word.id },
            )
        }
    }

    @Test
    fun `a word from no deck at all is taken into the picked list`() {
        val stranger = repo.content
            .deckEntries(Deck.byId("C1")!!, exclude = emptySet(), limit = 1)
            .first()
        assertEquals(1, repo.takeWritingGaps(listOf(stranger.id)))
        assertTrue("the picked deck was not switched on", Repository.MINE in repo.enabledDecks)
        assertTrue(
            "C1 word ${stranger.lemma} is not waiting",
            repo.buildQueue().any {
                it.kind == CardKind.PRODUCE && it.entry.id == stranger.id
            },
        )
    }

    // ---- 自由英作文 ---------------------------------------------------------

    private val essay = "I think students should read a book every week. First, reading " +
        "helps them learn a language faster, because a book gives them words they never " +
        "meet in class. Second, a book is cheap, so they can save money and still find " +
        "new ideas. Some people say a phone is enough, but a phone is full of short " +
        "messages. A book asks the reader to follow a long argument, and that is the " +
        "skill an exam tests. For these reasons, I believe every student should read a " +
        "book every week."

    @Test
    fun `a composition is counted, not marked`() {
        val check = repo.checkEssay(essay)
        assertEquals(89, check.words)
        assertEquals(6, check.sentences)
        assertTrue("89 words is inside 80–100", check.withinLength)
        assertTrue(check.lengthNote.contains("収まっています"))

        // The word carrying the whole essay is named, because a marker will see it.
        assertTrue(
            "book was not reported as repeated: ${check.repeated.map { it.surface }}",
            check.repeated.any { it.surface == "book" },
        )
    }

    @Test
    fun `length is reported against the exam's own range`() {
        assertTrue(repo.checkEssay("Too short.").lengthNote.contains("あと"))
        val long = repo.checkEssay(essay + " " + essay)
        assertFalse(long.withinLength)
        assertTrue(long.lengthNote.contains("超えています"))
        assertEquals(0, repo.checkEssay("").words)
        assertEquals(EssayCheck.MIN_WORDS, 80)
        assertEquals(EssayCheck.MAX_WORDS, 100)
    }

    /**
     * The one positive claim the collocation table can support. Nothing is ever
     * said about a pairing that is *missing* from it: 15,088 pairs is not all of
     * English, and an underline under good writing teaches distrust.
     */
    @Test
    fun `pairings a corpus attests are shown back`() {
        val check = repo.checkEssay(essay)
        val pairs = check.attested.map { "${it.head}+${it.collocate}" }
        assertTrue("read+book missing from $pairs", "read+book" in pairs)
        assertTrue("save+money missing from $pairs", "save+money" in pairs)
        assertTrue(check.attested.all { it.count > 0 })
    }
}
