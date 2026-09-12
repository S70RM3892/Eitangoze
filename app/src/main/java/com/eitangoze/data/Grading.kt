package com.eitangoze.data

import com.eitangoze.srs.Rating
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** How a typed answer came out. [CLOSE] exists so a one-letter typo is not scored
 *  as a blank — the memory was there, the spelling was not. */
enum class Grade { CORRECT, CLOSE, WRONG }

data class GradeResult(val grade: Grade, val expected: String, val comment: String) {
    /** Pre-selected rating button; the learner can still override it. */
    val suggested: Rating
        get() = when (grade) {
            Grade.CORRECT -> Rating.GOOD
            Grade.CLOSE -> Rating.HARD
            Grade.WRONG -> Rating.AGAIN
        }
}

/**
 * Grade English typed into an answer box.
 *
 * Any of [accepted] counts. Case, surrounding punctuation and a leading article
 * or `to` are ignored, because none of them is what the card is testing. An
 * inflected form of the answer is accepted when [alsoAcceptForms] lists it, so a
 * cloze can be answered with the form the sentence actually needs *or* the
 * dictionary form.
 */
fun gradeEnglish(
    input: String,
    accepted: List<String>,
    alsoAcceptForms: List<String> = emptyList(),
): GradeResult {
    val shown = accepted.firstOrNull().orEmpty()
    val typed = input.trim()
    if (typed.isEmpty()) return GradeResult(Grade.WRONG, shown, "未入力")

    val all = (accepted + alsoAcceptForms).filter { it.isNotBlank() }
    if (all.isEmpty()) return GradeResult(Grade.WRONG, shown, "正解データなし")

    val normalized = normalizeEnglish(typed)
    for (candidate in all) {
        if (candidate == typed || normalizeEnglish(candidate) == normalized) {
            // Answering a cloze with the dictionary form is right, but the
            // sentence still needs a particular form, so say which.
            val note = if (candidate !in accepted && shown.isNotBlank() && shown != candidate) {
                "正解（文中の形は $shown）"
            } else {
                "正解"
            }
            return GradeResult(Grade.CORRECT, shown, note)
        }
    }

    val best = all.minOf { editDistance(normalized, normalizeEnglish(it)) }
    val tolerance = min(2, max(1, normalized.length / 5))
    return if (best <= tolerance) {
        GradeResult(Grade.CLOSE, shown, "惜しい（${best}文字違い）")
    } else {
        GradeResult(Grade.WRONG, shown, "不正解")
    }
}

internal fun normalizeEnglish(s: String): String {
    var t = Normalizer.normalize(s, Normalizer.Form.NFKC).trim().lowercase()
    t = t.replace("[.,!?;:\"'’”“()\\[\\]]".toRegex(), "")
        .replace("\\s+".toRegex(), " ")
        .trim()
    for (prefix in listOf("to ", "a ", "an ", "the ")) {
        if (t.startsWith(prefix)) {
            t = t.removePrefix(prefix)
            break
        }
    }
    return t
}

/**
 * Edit distance counting a swap of two neighbouring letters as one mistake.
 *
 * Plain Levenshtein charges two for `gian` / `gain`, which would score one of
 * the commonest typing slips the same as a different word.
 */
internal fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var best = min(min(d[i][j - 1] + 1, d[i - 1][j] + 1), d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                best = min(best, d[i - 2][j - 2] + 1)
            }
            d[i][j] = best
        }
    }
    return d[a.length][b.length]
}

/**
 * Replace a word with a blank, matching whatever form it takes in the sentence.
 *
 * The surface form is what was recorded when the sentence was linked, so this
 * hides `abandoned` in "Many baby girls have been abandoned" rather than
 * failing to find `abandon`. Multi-word phrases are blanked as a unit.
 */
fun blankOut(sentence: String, surface: String, blank: String = "______"): String {
    if (surface.isBlank()) return sentence
    val pattern = Regex("\\b" + Regex.escape(surface) + "\\b", RegexOption.IGNORE_CASE)
    val replaced = pattern.replace(sentence, blank)
    return if (replaced != sentence) replaced else sentence
}

/**
 * The first letter of each word plus one underscore per remaining letter.
 *
 * A hint, not the answer. Each word keeps its own initial so a phrasal verb
 * still shows which particle family it is (`p _ _   o _ _`).
 */
fun spellingHint(word: String): String =
    word.split(" ").joinToString("   ") { part ->
        part.mapIndexed { i, c -> if (i == 0 || c == '-') c else '_' }.joinToString(" ")
    }
