// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

/**
 * Nixie (tv-1.0-privacy): the first half of the log's anonymiser: the TV's servers (and those removed before) become
 * `server-1` and `server-1-name`, its profiles `user-1`. One combined pattern for each kind of place a name can stand,
 * not one pass over the log per name (a few hundred names made a few hundred passes):
 * - a server's host anywhere it stands as a whole; a host that is also a word of the app's or Jellyfin's own
 *   (`jellyfin`, `media`) only where a host stands: after `://`, `@`, `connect to`, `host=`, before `/203.0.113.7` or a port;
 * - a name of 4 characters or more with a letter in it, not such a word, anywhere as a whole word, but never inside a
 *   timestamp or a number;
 * - any other name (`TV`, `Bo`, `10`, `Media`) only in a name field (`user=`, `Name:`, "signed in as"): in free text
 *   it would take "Nixie for TV", `GOOGLE_TV` or a time.
 */
class NamesScrub(servers: List<Pair<String?, String>>, users: List<String>) {
    private val hosts = LinkedHashMap<String, String>()
    private val names = LinkedHashMap<String, String>()

    init {
        val distinct = servers.distinctBy { (host, name) -> host?.lowercase() ?: name.lowercase() }
        distinct.forEachIndexed { index, (host, name) ->
            val label = "server-${index + 1}"
            host?.takeIf { it.isNotBlank() }?.let { hosts.putIfAbsent(it.lowercase(), label) }
            if (usable(name)) names.putIfAbsent(name.lowercase(), "$label-name")
        }
        users.distinctBy { it.lowercase() }.filter(::usable).forEachIndexed { index, name ->
            names.putIfAbsent(name.lowercase(), "user-${index + 1}")
        }
    }

    private val hostsAnywhere = pattern(hosts.keys.filterNot(::ownWord)) {
        "(?<![\\p{L}\\p{N}.:-])(?:$it)(?![\\p{L}\\p{N}-]|\\.[\\p{L}\\p{N}]|:\\p{L})"
    }

    private val hostsWhereHosts = pattern(hosts.keys.filter(::ownWord)) {
        "(?:(?<=://|@|connect to |resolve host \"|host=|host: )(?:$it)(?=[:/\"\\s,;)\\]]|$)|" +
            "(?<![\\p{L}\\p{N}.:-])(?:$it)(?=/[\\d\\[]|:\\d))"
    }

    private val namesAnywhere = pattern(names.keys.filter(::freeName)) {
        "(?<![\\p{L}\\p{N}]|\\p{N}[.:,/-])(?:$it)(?![\\p{L}\\p{N}]|[.:,/-]\\p{N})"
    }

    private val namesInFields = pattern(names.keys.filterNot(::freeName)) { "($FIELD)($it)(?![\\p{L}\\p{N}])" }

    /** How many times [apply] goes over a text: one per kind of place, whatever the number of names. */
    internal val passes = listOfNotNull(hostsAnywhere, hostsWhereHosts, namesAnywhere, namesInFields).size

    fun apply(text: String): String {
        var out = text
        hostsAnywhere?.let { regex -> out = out.replace(regex) { label(hosts, it.value) } }
        hostsWhereHosts?.let { regex -> out = out.replace(regex) { label(hosts, it.value) } }
        namesAnywhere?.let { regex -> out = out.replace(regex) { label(names, it.value) } }
        namesInFields?.let { regex -> out = out.replace(regex) { it.groupValues[1] + label(names, it.groupValues[2]) } }
        return out
    }

    private companion object {
        /** Where a log names a profile or a server: `user=`, `UserName: `, `"Name":"`, "signed in as". */
        const val FIELD = "\\b(?:user|username|user_name|profile|account|name|servername|server_name|signed in as)" +
            "\\s*[=:]?\\s*[\"']?|\"(?:Name|UserName|ServerName)\"\\s*:\\s*\""

        /** From this length a name with a letter in it is no ordinary word or number, and is replaced anywhere. */
        const val FREE_LENGTH = 4

        /** Words of the app's and Jellyfin's own that a server or profile may be called too. */
        val OWN_WORDS = setOf(
            "jellyfin", "nixie", "emby", "kodi", "plex", "tv", "android", "google", "onn", "home", "media",
            "server", "servers", "movie", "movies", "show", "shows", "series", "music", "live", "library", "libraries",
            "collection", "collections", "favorites", "search", "settings", "player", "profile", "user", "admin",
        )

        fun usable(name: String) = name.isNotBlank() && !name.equals("jellyfin", ignoreCase = true)

        fun ownWord(text: String) = text.lowercase() in OWN_WORDS

        fun freeName(name: String) = name.length >= FREE_LENGTH && name.any { it.isLetter() } && !ownWord(name)

        /** One alternation, the longest first, so "Home Media" goes before "Home". */
        fun pattern(texts: Collection<String>, wrap: (String) -> String): Regex? = texts
            .takeIf { it.isNotEmpty() }
            ?.sortedByDescending { it.length }
            ?.joinToString("|") { Regex.escape(it) }
            ?.let { Regex(wrap(it), RegexOption.IGNORE_CASE) }

        fun label(labels: Map<String, String>, text: String): String =
            labels[text.lowercase()] ?: labels.entries.firstOrNull { it.key.equals(text, ignoreCase = true) }?.value
                ?: "name"
    }
}
