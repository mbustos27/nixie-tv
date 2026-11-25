// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

/**
 * Nixie: collection (box set) and library names as people read them, as desktop Nixie 1.6 has
 * them (`CollectionNames`): "[HUB] Marvel" shows as "Marvel", "!010_Bond" as "Bond",
 * "🎅 Christmas" as "Christmas". Films, shows, episodes and playlists are never touched, and
 * titles such as "[REC]", "+1" or "#Alive" stay: the tags taken off are a known list, never any
 * short capitals in brackets. The server's name comes back when nothing readable is left, or
 * when two names in one view would read the same ([libraries]).
 *
 * The rules, in order, again until nothing changes: invisible characters; known tags at the
 * start; known tags at the end; emoji at the edges; leaked sort keys ("!020_", "01. "); sort-forcing
 * punctuation ("!!Marvel", "~Misc"); "zz" prefixes; then " Collection" after a box set TMDb named,
 * and a separator left dangling at an edge.
 */
object TidyNames {

    /** A box set's name as shown; [fromTmdb]: TMDb named it, so a trailing " Collection" goes. */
    fun collection(name: String, fromTmdb: Boolean): String = clean(name, fromTmdb)

    /** Library names as shown, in their order: each tidied, unless two would read the same. */
    fun libraries(names: List<String>): List<String> {
        val shown = names.map { clean(it, fromTmdb = false) }.toMutableList()
        val counts = shown.groupingBy { it.lowercase() }.eachCount()
        shown.indices.forEach { i ->
            val taken = names.withIndex().any { (j, server) -> j != i && server.equals(shown[i], ignoreCase = true) }
            if (shown[i] != names[i] && (counts.getValue(shown[i].lowercase()) > 1 || taken)) shown[i] = names[i]
        }
        return shown
    }

    fun clean(name: String, fromTmdb: Boolean): String {
        if (name.isEmpty()) return name
        val server = collapse(withoutInvisible(name))
        var s = server
        for (pass in 0 until PASSES) {
            val before = s
            s = startTags.replaceFirst(s, "")
            s = endTags.replaceFirst(s, "")
            s = withoutEdgeEmoji(s)
            s = sortKey.replaceFirst(s, "")
            s = sortPunctuation.replaceFirst(s, "")
            s = zzPrefix.replaceFirst(s, "")
            s = collapse(s)
            if (s == before) break
        }
        if (fromTmdb) {
            collectionSuffix.find(s)?.groups?.get(1)?.value?.takeUnless(::isNothing)?.let { s = it }
        }
        if (s != server) s = collapse(danglingSeparator.replace(s, ""))
        return if (isNothing(s)) server.ifEmpty { name } else s
    }

    private fun isNothing(s: String) = s.isEmpty() || s.none(Char::isLetterOrDigit) || s.trim().lowercase() in articles

    private fun collapse(s: String) = spaces.replace(s, " ").trim()

    private fun withoutInvisible(s: String): String = buildString {
        s.forEachIndexed { i, c ->
            if (c in invisible) return@forEachIndexed
            // A zero-width joiner stays only inside an emoji sequence.
            if (c == ZERO_WIDTH_JOINER && !joinsEmoji(s, i)) return@forEachIndexed
            append(c)
        }
    }

    private fun joinsEmoji(s: String, joiner: Int): Boolean =
        joiner in 1 until s.lastIndex && isEmojiAt(s, joiner - 1) && isEmojiAt(s, joiner + 1)

    private fun isEmojiAt(s: String, index: Int): Boolean {
        val start = if (index > 0 && s[index].isLowSurrogate() && s[index - 1].isHighSurrogate()) index - 1 else index
        val code = s.codePointAt(start)
        return isEmojiPresentation(code) || s[index] == '\uFE0F' || code in SKIN_TONES
    }

    /** Emoji at the start followed by a space, or a space followed by emoji at the end, go. */
    private fun withoutEdgeEmoji(s: String): String {
        val elements = elements(s)
        var first = 0
        while (first < elements.size && isEmojiElement(elements[first])) first++
        if (first > 0 && first < elements.size && elements[first] == " ") repeat(first + 1) { elements.removeAt(0) }
        var last = elements.size
        while (last > 0 && isEmojiElement(elements[last - 1])) last--
        if (last < elements.size && last > 0 && elements[last - 1] == " ") {
            while (elements.size >= last) elements.removeAt(elements.lastIndex)
        }
        return elements.joinToString("")
    }

    /** The text split into characters as people see them: an emoji with its joiners and modifiers is one. */
    private fun elements(s: String): MutableList<String> {
        val list = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            var end = i + Character.charCount(s.codePointAt(i))
            while (end < s.length) {
                val next = s.codePointAt(end)
                val joined = next in JOINING || next in SKIN_TONES
                val afterJoiner = s.codePointBefore(end) == ZERO_WIDTH_JOINER.code
                if (!joined && !afterJoiner) break
                end += Character.charCount(next)
            }
            list += s.substring(i, end)
            i = end
        }
        return list
    }

    private fun isEmojiElement(element: String): Boolean = element.isNotEmpty() &&
        (isEmojiPresentation(element.codePointAt(0)) || '\uFE0F' in element || '\u20E3' in element)

    private fun isEmojiPresentation(code: Int): Boolean = presentation.any { (from, to) -> code in from..to }

    private const val PASSES = 4
    private const val ZERO_WIDTH_JOINER = '\u200D'
    private val SKIN_TONES = 0x1F3FB..0x1F3FF

    /** Zero-width joiner, emoji variation selector, keycap: they join the character before. */
    private val JOINING = setOf(0x200D, 0xFE0F, 0x20E3)
    private const val START_TAGS = "HUB|4K|UHD|HD|HDR|TV|MOVIES|COLLECTION|KOMETA|PMM|FRANCHISE|SET|BOX SET|SAGA"

    private val startTags = Regex(
        """^(?:\s*[\[{](?:$START_TAGS)[\]}](?:\s*[-:|–—·]\s*|\s+|(?=[^\w\s])))+""",
        RegexOption.IGNORE_CASE,
    )
    private val endTags = Regex(
        """\s*(?:\((?:4K|4K HDR|4K HDR DV|UHD|HD|SD|SD DVD|1080p|2160p|Box Set|Collection)\)|\[HUB])\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val sortKey = Regex("""^(?:!?\d+_\s*|0\d*\.\s+|0\d*\s+-\s+)(?=\S)""")
    private val sortPunctuation = Regex("""^(?:[!~+^_]{2,}\s*(?=\S)|[~^_](?=[^\s!~+^_])|[!~+^_](?:\s+|-\s*)(?=\S))""")
    private val zzPrefix = Regex("""^(?:zz |zz_|ZZ_)(?=\S)""")
    private val collectionSuffix = Regex("""^(.+?)\s+Collection$""", RegexOption.IGNORE_CASE)
    private val danglingSeparator = Regex("""^\s*[-:|–—·/]\s+|\s+[-:|–—·/]\s*$""")
    private val spaces = Regex("""\s{2,}""")
    private val invisible = setOf('\u200B', '\u200C', '\u2060', '\uFEFF', '\u00AD')
    private val articles = setOf("the", "a", "an", "le", "la", "les", "der", "die", "das", "el", "los", "las")

    /** Emoji_Presentation=Yes (Unicode 16.0 emoji-data.txt), as desktop Nixie's table. */
    private val presentation = listOf(
        0x231A to 0x231B, 0x23E9 to 0x23EC, 0x23F0 to 0x23F0, 0x23F3 to 0x23F3, 0x25FD to 0x25FE, 0x2614 to 0x2615,
        0x2648 to 0x2653, 0x267F to 0x267F, 0x2693 to 0x2693, 0x26A1 to 0x26A1, 0x26AA to 0x26AB, 0x26BD to 0x26BE,
        0x26C4 to 0x26C5, 0x26CE to 0x26CE, 0x26D4 to 0x26D4, 0x26EA to 0x26EA, 0x26F2 to 0x26F3, 0x26F5 to 0x26F5,
        0x26FA to 0x26FA, 0x26FD to 0x26FD, 0x2705 to 0x2705, 0x270A to 0x270B, 0x2728 to 0x2728, 0x274C to 0x274C,
        0x274E to 0x274E, 0x2753 to 0x2755, 0x2757 to 0x2757, 0x2795 to 0x2797, 0x27B0 to 0x27B0, 0x27BF to 0x27BF,
        0x2B1B to 0x2B1C, 0x2B50 to 0x2B50, 0x2B55 to 0x2B55, 0x1F004 to 0x1F004, 0x1F0CF to 0x1F0CF,
        0x1F18E to 0x1F18E, 0x1F191 to 0x1F19A, 0x1F1E6 to 0x1F1FF, 0x1F201 to 0x1F201, 0x1F21A to 0x1F21A,
        0x1F22F to 0x1F22F, 0x1F232 to 0x1F236, 0x1F238 to 0x1F23A, 0x1F250 to 0x1F251, 0x1F300 to 0x1F320,
        0x1F32D to 0x1F335, 0x1F337 to 0x1F37C, 0x1F37E to 0x1F393, 0x1F3A0 to 0x1F3CA, 0x1F3CF to 0x1F3D3,
        0x1F3E0 to 0x1F3F0, 0x1F3F4 to 0x1F3F4, 0x1F3F8 to 0x1F43E, 0x1F440 to 0x1F440, 0x1F442 to 0x1F4FC,
        0x1F4FF to 0x1F53D, 0x1F54B to 0x1F54E, 0x1F550 to 0x1F567, 0x1F57A to 0x1F57A, 0x1F595 to 0x1F596,
        0x1F5A4 to 0x1F5A4, 0x1F5FB to 0x1F64F, 0x1F680 to 0x1F6C5, 0x1F6CC to 0x1F6CC, 0x1F6D0 to 0x1F6D2,
        0x1F6D5 to 0x1F6D7, 0x1F6DC to 0x1F6DF, 0x1F6EB to 0x1F6EC, 0x1F6F4 to 0x1F6FC, 0x1F7E0 to 0x1F7EB,
        0x1F7F0 to 0x1F7F0, 0x1F90C to 0x1F93A, 0x1F93C to 0x1F945, 0x1F947 to 0x1F9FF, 0x1FA70 to 0x1FA7C,
        0x1FA80 to 0x1FA89, 0x1FA8F to 0x1FAC6, 0x1FACE to 0x1FADC, 0x1FADF to 0x1FAE9, 0x1FAF0 to 0x1FAF8,
    )
}

/** A box set's name as shown, see [TidyNames.collection]. */
internal fun tidyCollection(dto: org.jellyfin.sdk.model.api.BaseItemDto): String =
    TidyNames.collection(dto.name.orEmpty(), fromTmdb = dto.providerIds?.get("Tmdb").isNullOrEmpty().not())
