package com.eitangoze

import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.PassageDb
import com.eitangoze.data.SyntaxQuestion
import com.eitangoze.data.SyntaxQuiz
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The structure questions, generated against the library that ships.
 *
 * Every one of these is answerable from the tree, which means every one of them
 * can be wrong in exactly one way: pointing at a word that is not there, or
 * offering the right answer twice. Both would be invisible in use — the learner
 * would simply be marked wrong for being right — so they are checked over the
 * whole library rather than on an example.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyntaxQuizTest {

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
    fun `questions point at real words and have exactly one answer`() {
        var built = 0
        val kinds = HashSet<SyntaxQuestion.Kind>()
        db.passages(limit = 25).forEach { passage ->
            db.sentences(passage.id).forEach { sentence ->
                SyntaxQuiz.of(sentence, passage.text, seed = sentence.ord).forEach { q ->
                    assertTrue("empty prompt", q.prompt.isNotBlank())
                    assertTrue("only ${q.choices.size} choices", q.choices.size >= 2)
                    assertEquals(
                        "the same word is offered twice",
                        q.choices.size, q.choices.toSet().size,
                    )
                    assertTrue("no answer marked", q.correct in q.choices.indices)
                    q.choices.forEach { token ->
                        assertTrue(
                            "${passage.title}: choice points outside the sentence",
                            token in sentence.tokens.indices,
                        )
                    }
                    kinds.add(q.kind)
                    built++
                }
            }
        }
        assertTrue("only $built questions in the whole library", built > 100)
        assertTrue("only the same kind of question is ever asked: $kinds", kinds.size >= 2)
    }

    /** A sentence the parsers disagreed about is never quizzed. */
    @Test
    fun `unconfirmed sentences are never asked about`() {
        var unconfirmed = 0
        db.passages(limit = 25).forEach { passage ->
            db.sentences(passage.id).filter { !it.confirmed }.forEach { sentence ->
                unconfirmed++
                assertEquals(
                    emptyList<SyntaxQuestion>(),
                    SyntaxQuiz.of(sentence, passage.text, seed = sentence.ord),
                )
            }
        }
        assertTrue("there were no unconfirmed sentences to check", unconfirmed > 0)
    }

    /** The order is stable: a choice that moves between redraws is unanswerable. */
    @Test
    fun `the same sentence always produces the same question`() {
        val passage = db.passages(limit = 6).first()
        db.sentences(passage.id).take(20).forEach { sentence ->
            val once = SyntaxQuiz.of(sentence, passage.text, seed = sentence.ord)
            val twice = SyntaxQuiz.of(sentence, passage.text, seed = sentence.ord)
            assertEquals(once, twice)
        }
    }

    /**
     * The answer is the tree's answer. Checked against the roles and heads
     * independently of the code that built the question.
     */
    @Test
    fun `the marked answer is the one the tree gives`() {
        var checked = 0
        db.passages(limit = 20).forEach { passage ->
            db.sentences(passage.id).forEach { sentence ->
                SyntaxQuiz.of(sentence, passage.text, seed = sentence.ord).forEach { q ->
                    when (q.kind) {
                        SyntaxQuestion.Kind.MAIN_VERB -> assertEquals(
                            sentence.roles.entries.first { it.value == "V" }.key,
                            q.correctChoice,
                        )

                        SyntaxQuestion.Kind.SUBJECT -> assertEquals(
                            sentence.roles.entries.first { it.value == "S" }.key,
                            q.correctChoice,
                        )

                        SyntaxQuestion.Kind.MODIFIED -> assertTrue(
                            "the clause is said to modify a non-noun",
                            sentence.pos[q.correctChoice] in setOf("NOUN", "PROPN", "PRON"),
                        )
                    }
                    checked++
                }
            }
        }
        assertTrue(checked > 100)
    }
}
