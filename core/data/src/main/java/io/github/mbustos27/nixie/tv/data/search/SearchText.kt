// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.search

import java.text.Normalizer

/**
 * Titles and queries in one comparable form (desktop Nixie 1.5's `SearchText`). Accents, case, apostrophes and other
 * punctuation count as equal: "It's" = "its", "Amélie" = "amelie", "WALL·E" = "wall-e" = "walle", "M*A*S*H" = "mash".
 * A leading "The", "A" or "An" can be set aside, so "matrix" is the whole of "The Matrix".
 */
object SearchText {
    private val small =
        setOf("the", "a", "an", "of", "and", "in", "on", "at", "to", "for", "or", "de", "la", "le", "el")
    private val articles = listOf("the ", "a ", "an ")
    private val paddedNumber = Regex("(?<!\\d)0{1,9}(?=\\d)")

    /**
     * Lower case, no accents, apostrophes dropped inside words ("it's" is "its"), every other mark a space, single
     * spaces. Letters without a decomposition are spelled out (ø is o, æ is ae, ß is ss). With [splitApostrophes] an
     * apostrophe parts words instead ("d'Amélie" is "d amelie").
     */
    fun fold(text: String?, splitApostrophes: Boolean = false): String {
        if (text.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        var space = true
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            val lower = Character.toLowerCase(cp)
            when {
                // Only the accents of Latin, Greek and Cyrillic go; a mark of the word's own script stays.
                isMark(cp) -> if (!isAccent(cp) && !space) out.appendCodePoint(cp)

                isApostrophe(cp) && !splitApostrophes -> Unit

                Character.isLetterOrDigit(lower) -> {
                    out.append(spelled(lower))
                    space = false
                }

                !space -> {
                    out.append(' ')
                    space = true
                }
            }
        }
        return Normalizer.normalize(out.toString().trimEnd(), Normalizer.Form.NFC)
    }

    fun words(text: String?): List<String> = fold(text).takeIf { it.isNotEmpty() }?.split(' ') ?: emptyList()

    /** The folded text without a leading "the", "a" or "an" (kept when it is all there is). */
    fun withoutArticle(folded: String): String {
        val article = articles.firstOrNull { folded.length > it.length && folded.startsWith(it) } ?: return folded
        return folded.substring(article.length)
    }

    /** A word a query can leave out of a title ("of", "the"). */
    fun isSmall(word: String): Boolean = word in small

    /** The server's sort name pads numbers to ten digits ("Se7en" sorts as "se0000000007en"): the padding goes. */
    fun unpad(sortName: String?): String = sortName?.replace(paddedNumber, "").orEmpty()

    private fun isMark(cp: Int): Boolean = Character.getType(cp).toByte().let {
        it == Character.NON_SPACING_MARK || it == Character.COMBINING_SPACING_MARK || it == Character.ENCLOSING_MARK
    }

    /** The apostrophes and their look-alikes (’ ‘ ʼ ` ´ ′). */
    @Suppress("MagicNumber") // Unicode code points
    internal fun isApostrophe(cp: Int): Boolean = cp == '\''.code || cp == 0x2019 || cp == 0x2018 || cp == 0x02BC ||
        cp == '`'.code || cp == 0x00B4 || cp == 0x2032

    /** The combining marks the accents of Latin, Greek and Cyrillic decompose into. */
    @Suppress("MagicNumber") // Unicode blocks
    private fun isAccent(cp: Int): Boolean = cp in 0x0300..0x036F || cp in 0x0483..0x0489 || cp in 0x1AB0..0x1AFF ||
        cp in 0x1DC0..0x1DFF || cp in 0xFE20..0xFE2F

    private fun spelled(cp: Int): String = when (cp) {
        'ø'.code -> "o"
        'æ'.code -> "ae"
        'œ'.code -> "oe"
        'ß'.code -> "ss"
        'ł'.code -> "l"
        'đ'.code, 'ð'.code -> "d"
        'þ'.code -> "th"
        'ı'.code -> "i"
        else -> String(Character.toChars(cp))
    }
}

/** How well a title answers a query, best first. */
enum class SearchTier {
    /** The whole title ("the matrix", "matrix" for The Matrix). */
    Whole,

    /** The title's start ("it's always sunny" for It's Always Sunny in Philadelphia). */
    Start,

    /** All the words, in any order ("night dead" for Night of the Living Dead). */
    AllWords,

    /** Some of the words, or the query inside a word ("tel" in Sintel). */
    SomeWords,

    /** None: the server found it for another reason (a person in it, its genre, its overview). */
    None,
}

/** A title's match: its tier, then how close it is within the tier (0 to 1, higher is closer). */
data class SearchMatch(val tier: SearchTier, val closeness: Double) : Comparable<SearchMatch> {
    /** Whole, start or all the words: shown at once. The rest wait behind "Show N more". */
    val strong: Boolean get() = tier <= SearchTier.AllWords

    /** Negative when this match is better than [other]. */
    override fun compareTo(other: SearchMatch): Int =
        if (tier != other.tier) tier.compareTo(other.tier) else other.closeness.compareTo(closeness)

    fun less(by: Double) = copy(closeness = (closeness - by).coerceAtLeast(0.0))

    companion object {
        val Nothing = SearchMatch(SearchTier.None, 0.0)

        fun best(a: SearchMatch, b: SearchMatch) = if (a <= b) a else b
    }
}

/**
 * A query, folded once, and its match against any title (desktop Nixie 1.5's `SearchQuery`): a whole title beats its
 * start, which beats all the words, which beats some of them. A leading article never blocks a match, and the sort
 * title counts too.
 */
class SearchQuery(text: String?) {
    val text: String = text?.trim().orEmpty()
    private val folded = SearchText.fold(this.text)
    private val squashed = folded.replace(" ", "")
    private val squashedWithoutArticle = SearchText.withoutArticle(folded).replace(" ", "")
    private val words = SearchText.words(SearchText.withoutArticle(folded))
    private val keyWords = words.filterNot(SearchText::isSmall).ifEmpty { words }

    val isEmpty: Boolean get() = squashed.isEmpty()

    /** The better of the [title] and the server's [sortName] (the title wins a tie). */
    fun match(title: String?, sortName: String?): SearchMatch {
        val own = match(title)
        if (sortName.isNullOrBlank()) return own
        return SearchMatch.best(own, match(SearchText.unpad(sortName)).less(SORT_NAME_PENALTY))
    }

    fun match(title: String?): SearchMatch {
        if (isEmpty) return SearchMatch.Nothing
        val foldedTitle = SearchText.fold(title)
        if (foldedTitle.isEmpty()) return SearchMatch.Nothing
        val squashedTitle = foldedTitle.replace(" ", "")
        val foldedWithoutArticle = SearchText.withoutArticle(foldedTitle)
        val titleWithoutArticle = foldedWithoutArticle.replace(" ", "")

        val titles = listOf(squashedTitle, titleWithoutArticle)
        val queries = listOf(squashed, squashedWithoutArticle)
        if (titles.any { t -> queries.any { it == t } }) return SearchMatch(SearchTier.Whole, 1.0)

        val start = startCloseness(listOf(squashedTitle to foldedTitle, titleWithoutArticle to foldedWithoutArticle))
        if (start > 0) return SearchMatch(SearchTier.Start, start)
        return wordsMatch(title!!, foldedTitle, squashedTitle)
    }

    /**
     * How close the query comes to the title's start, 0 when it is not its start: on whole words ("star" in Star Wars)
     * before inside a word ("star" in Starman), then the closer to the whole title, the better.
     */
    private fun startCloseness(titles: List<Pair<String, String>>): Double {
        var start = 0.0
        for ((t, spaced) in titles) {
            for (q in listOf(squashed, squashedWithoutArticle).filter { t.startsWith(it) }) {
                val onWord = if (endsOnWord(spaced, q.length)) HALF else 0.0
                start = maxOf(start, onWord + HALF * q.length / t.length)
            }
        }
        return start
    }

    /** All the words, in any order, or some of them; the title's elided words too ("d'Amélie" is "amelie"). */
    private fun wordsMatch(
        title: String,
        foldedTitle: String,
        squashedTitle: String,
    ): SearchMatch {
        var titleWords = foldedTitle.split(' ')
        if (title.codePoints().anyMatch(SearchText::isApostrophe)) {
            titleWords = (titleWords + SearchText.fold(title, splitApostrophes = true).split(' ')).distinct()
        }
        val phrase = squashedTitle.contains(squashedWithoutArticle)
        val keyMatched = keyWords.count { wordIn(it, titleWords) }
        if (keyMatched == keyWords.size) {
            val covered = minOf(squashedTitle.length, squashedWithoutArticle.length).toDouble() / squashedTitle.length
            return SearchMatch(SearchTier.AllWords, (if (phrase) HALF else 0.0) + HALF * covered)
        }
        val inside = keyWords.count { it.length >= MIN_INSIDE && squashedTitle.contains(it) }
        val some = maxOf(keyMatched, inside)
        if (some == 0 && !phrase) return SearchMatch.Nothing
        return SearchMatch(SearchTier.SomeWords, if (phrase) 1.0 else some.toDouble() / keyWords.size)
    }

    private companion object {
        const val SORT_NAME_PENALTY = 0.03
        const val HALF = 0.5

        /** A query word this long counts inside a title word ("tel" in Sintel). */
        const val MIN_INSIDE = 3

        /** Whether [length] letters of the title's words (spaces left out) end where a word ends. */
        fun endsOnWord(spaced: String, length: Int): Boolean {
            var at = 0
            for (word in spaced.split(' ')) {
                at += word.length
                if (at == length) return true
                if (at > length) return false
            }
            return false
        }

        /** A title word starts with the query word, or the words from one on run into it ("spiderman" in Spider-Man). */
        fun wordIn(word: String, titleWords: List<String>): Boolean {
            for (i in titleWords.indices) {
                if (titleWords[i].startsWith(word)) return true
                if (!word.startsWith(titleWords[i])) continue
                var run = titleWords[i]
                var j = i + 1
                while (j < titleWords.size && run.length < word.length) {
                    run += titleWords[j]
                    if (run.startsWith(word)) return true
                    if (!word.startsWith(run)) break
                    j++
                }
            }
            return false
        }
    }
}
