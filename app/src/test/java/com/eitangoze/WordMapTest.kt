package com.eitangoze

import androidx.test.core.app.ApplicationProvider
import com.eitangoze.data.Knowledge
import com.eitangoze.data.RelationKind
import com.eitangoze.data.Repository
import com.eitangoze.data.WordMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * つながりの地図, against the shipped dictionary.
 *
 * The map's whole claim is that it is a picture of *this learner's* English
 * rather than of English: the connections come from rows in the database, and
 * the colouring comes from the same memory model the reading screens use. Both
 * halves are easy to break quietly — a duplicated row draws the same word
 * twice, and a knowledge lookup that misses leaves every node grey.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordMapTest {

    private lateinit var repo: Repository

    @Before
    fun setUp() {
        repo = Repository(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = repo.close()

    private fun entryOf(lemma: String) =
        repo.search(lemma).first { it.lemma == lemma }

    private fun mapOf(lemma: String) = repo.wordMap(entryOf(lemma).id)

    @Test
    fun `a word with a shared root has its family on the map`() {
        val map = mapOf("reduce")
        assertNotNull("no map for reduce", map)
        map!!
        assertEquals("reduce", map.center.lemma)
        assertNotNull("reduce has a root family in the dictionary", map.family)

        val roots = map.nodes.filter { it.kind == RelationKind.ROOT }.map { it.entry.lemma }
        assertTrue("no same-root words: ${map.nodes.map { it.entry.lemma }}", roots.isNotEmpty())
        assertTrue("introduce shares duc with reduce", "introduce" in roots)
        // The word in the middle is not also on the rim.
        assertTrue(map.nodes.none { it.entry.id == map.center.id })
    }

    /**
     * The relation table stores a pair once per source that claimed it, so
     * `embrace` arrives twice as an opposite of `abandon` and `heavy` twice as a
     * derivation of `difficult`. Drawn as they come, the map has two nodes with
     * the same name sitting on top of each other.
     */
    @Test
    fun `a word that the dictionary lists twice appears once`() {
        listOf("abandon", "difficult", "produce", "reduce").forEach { lemma ->
            val map = mapOf(lemma) ?: return@forEach
            val ids = map.nodes.map { it.entry.id }
            assertEquals("$lemma has a repeated node", ids.size, ids.distinct().size)
            val names = map.nodes.map { it.entry.lemma }
            assertEquals("$lemma has a repeated word", names.size, names.distinct().size)
        }
    }

    @Test
    fun `the kinds are kept apart and drawn in a fixed order`() {
        val map = mapOf("reduce")!!
        val kinds = map.nodes.map { it.kind }
        // Same root, then derivation, then meaning, then opposition, then the
        // warning — the rings have to mean the same thing on every visit.
        val order = listOf(
            RelationKind.ROOT, RelationKind.FAMILY, RelationKind.SYNONYM,
            RelationKind.ANTONYM, RelationKind.CONFUSE,
        )
        val positions = kinds.map { order.indexOf(it) }
        assertEquals(positions.sorted(), positions)
        assertTrue("the map is one enormous ring", map.nodes.size <= WordMap.LIMIT)
    }

    @Test
    fun `every node carries what this learner knows about it`() {
        val plain = mapOf("produce")!!
        assertTrue(
            "a fresh learner already knows something",
            plain.nodes.all { it.knowledge == Knowledge.NEW },
        )
        assertEquals(0, plain.known)

        // Declaring a level is the quick way to a learner who can read.
        repo.baselineLevel = "C2"
        val after = mapOf("produce")!!
        assertTrue("declaring C2 changed nothing", after.known > 0)
        assertTrue(after.nodes.any { it.knowledge == Knowledge.KNOWN })
    }

    /** The line under the picture is about the gap, and has to match the count. */
    @Test
    fun `the verdict says what the picture shows`() {
        listOf("", "A2", "C2").forEach { level ->
            repo.baselineLevel = level
            listOf("produce", "difficult", "reduce", "abandon").forEach { lemma ->
                val map = mapOf(lemma) ?: return@forEach
                val missing = map.nodes.size - map.known
                when {
                    map.nodes.isEmpty() -> assertTrue(map.verdict.contains("ありません"))
                    missing == 0 -> assertTrue(
                        "$lemma at $level: ${map.verdict}", map.verdict.contains("すべて"),
                    )
                    missing == 1 -> assertTrue(
                        "$lemma at $level: ${map.verdict}", map.verdict.contains("あと1語"),
                    )
                    else -> assertTrue(
                        "$lemma at $level: ${map.verdict}",
                        map.verdict.contains("${map.known} 語"),
                    )
                }
            }
        }
    }

    @Test
    fun `a word with no connections says so instead of drawing nothing`() {
        // 1,275 of the 9,260 words in the taught levels have no relation row at
        // all; finding one is not a corner case.
        val lonely = repo.content
            .deckEntries(com.eitangoze.data.Deck.byId("C2")!!, exclude = emptySet(), limit = 400)
            .firstNotNullOfOrNull { entry ->
                repo.wordMap(entry.id)?.takeIf { it.isEmpty }
            }
        if (lonely == null) return
        assertTrue(lonely.verdict.contains("辞書の中にありません"))
        assertEquals(0, lonely.known)
    }

    @Test
    fun `a word that is not in the dictionary has no map`() {
        assertNull(repo.wordMap(-1))
    }
}
