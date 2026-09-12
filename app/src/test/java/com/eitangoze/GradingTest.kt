package com.eitangoze

import com.eitangoze.data.Grade
import com.eitangoze.data.blankOut
import com.eitangoze.data.gradeEnglish
import com.eitangoze.data.spellingHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradingTest {

    @Test
    fun `exact answer is correct`() {
        assertEquals(Grade.CORRECT, gradeEnglish("abandon", listOf("abandon")).grade)
    }

    @Test
    fun `case, punctuation and a leading article are not what is tested`() {
        for (typed in listOf("Abandon", "abandon.", " abandon ", "to abandon")) {
            assertEquals(typed, Grade.CORRECT, gradeEnglish(typed, listOf("abandon")).grade)
        }
        assertEquals(Grade.CORRECT, gradeEnglish("the decision", listOf("decision")).grade)
    }

    @Test
    fun `one letter out is close, not wrong`() {
        val result = gradeEnglish("abandom", listOf("abandon"))
        assertEquals(Grade.CLOSE, result.grade)
        assertTrue(result.comment.contains("1文字違い"))
    }

    @Test
    fun `a different word is wrong`() {
        assertEquals(Grade.WRONG, gradeEnglish("abolish", listOf("abandon")).grade)
        assertEquals(Grade.WRONG, gradeEnglish("", listOf("abandon")).grade)
    }

    @Test
    fun `a short answer gets a tight tolerance`() {
        // Two letters out of four is a different word, not a slip.
        assertEquals(Grade.WRONG, gradeEnglish("mail", listOf("gain")).grade)
        assertEquals(Grade.CLOSE, gradeEnglish("main", listOf("gain")).grade)
    }

    @Test
    fun `swapped neighbouring letters count as one mistake`() {
        assertEquals(Grade.CLOSE, gradeEnglish("gian", listOf("gain")).grade)
        assertEquals(Grade.CLOSE, gradeEnglish("recieve", listOf("receive")).grade)
    }

    @Test
    fun `a cloze accepts the dictionary form as well as the inflected one`() {
        val result = gradeEnglish(
            input = "abandon",
            accepted = listOf("abandoned"),
            alsoAcceptForms = listOf("abandon", "abandons", "abandoning"),
        )
        assertEquals(Grade.CORRECT, result.grade)
        // Right answer, but the sentence needs a particular form and says so.
        assertTrue(result.comment, result.comment.contains("abandoned"))
    }

    @Test
    fun `blanking finds the form the sentence actually uses`() {
        assertEquals(
            "Many baby girls have been ______ on the streets.",
            blankOut("Many baby girls have been abandoned on the streets.", "abandoned"),
        )
        // Case-insensitive, and only whole words.
        assertEquals("______ me the sympathy.", blankOut("Spare me the sympathy.", "spare"))
        assertEquals(
            "He was spareribs.",
            blankOut("He was spareribs.", "spare"),
        )
    }

    @Test
    fun `blanking a phrase removes the whole phrase`() {
        assertEquals(
            "Don't ______ your homework.",
            blankOut("Don't put off your homework.", "put off"),
        )
    }

    @Test
    fun `a hint shows the first letter and the length`() {
        assertEquals("a _ _ _ _ _ _", spellingHint("abandon"))
        assertEquals("p _ _   o _ _", spellingHint("put off"))
        assertEquals("e - _ _ _ _ _ _ _ _", spellingHint("e-commerce"))
    }
}
