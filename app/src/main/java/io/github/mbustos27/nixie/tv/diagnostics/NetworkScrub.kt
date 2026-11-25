// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

/**
 * Nixie (tv-1.0-fixes, tv-1.0-privacy): the second half of the log's anonymiser, after the known servers' hosts and
 * names became `server-1`, `server-1-name` (Anonymizer). Everything else that names a network, a machine or a person
 * goes too, wherever it stands in a line (exception messages and stack traces included): URL authorities whatever
 * their suffix, user info with them; the values of query strings (a search's typed text, an id looked up), only the
 * parameter names stay, and a technical parameter's plain value; IPv4 and IPv6 addresses, one number for every way one
 * address is written; certificate pins, distinguished names and subject alternative names (OkHttp's "Hostname … not
 * verified"); e-mail addresses; MAC addresses; `name:port`; host names where a line names a host ("Unable to resolve
 * host", "connect to …", `Host:`, `host=`). A bare dotted name elsewhere stays: in a minified build's stack trace it is
 * a class (`t3.c.ai`). They become `address-1`, `host-1`, `email-1`, `mac-1`, the same one each time in a log, so a
 * reader can still tell one from another; schemes and ports stay. Loopback stays, and so do the placeholders, public
 * services' hosts, versions (`Jellyfin 10.10.3.0`) and the code's own names.
 *
 * One instance per log: it numbers what it meets.
 */
class NetworkScrub {
    private val labels = HashMap<String, String>()
    private val counts = HashMap<String, Int>()

    fun apply(text: String): String = synchronized(this) {
        var out = text.replace(PIN) { "${it.groupValues[1]}/$HIDDEN" }
        out = out.replace(SAN_LIST) { it.groupValues[1] + sanList(it.groupValues[2]) + it.groupValues[3] }
        out = out.replace(DN_LINE) { line -> line.value.replace(DN_ATTRIBUTE) { "${it.groupValues[1]}=$HIDDEN" } }
        out = out.replace(URL_AUTHORITY) { urlAuthority(it) }
        out = out.replace(QUERY) { "?" + it.groupValues[1].split('&').joinToString("&") { p -> queryParameter(p) } }
        out = out.replace(EMAIL) { email(it) }
        out = out.replace(MAC) { label("mac", it.value.lowercase().replace('-', ':')) }
        val addressed = out
        out = addressed.replace(ADDRESS) { address(it, addressed) }
        out = out.replace(HOST_BEFORE_ADDRESS) { host(it.groupValues[1]) + "/" }
        out = HOST_IN_MESSAGE.fold(out) { result, form ->
            result.replace(form) { it.groupValues[1] + host(it.groupValues[2]) }
        }
        out.replace(NAME_PORT) { namePort(it) }
    }

    private fun urlAuthority(match: MatchResult): String {
        val scheme = match.groupValues[1]
        val rawHost = match.groupValues[3]
        if (scheme.removeSuffix("://").lowercase() in LOCAL_SCHEMES) return match.value
        // A user name or password before the host goes with it.
        return scheme + hostOrAddress(rawHost)
    }

    private fun hostOrAddress(raw: String): String = when {
        raw.startsWith("[") -> "[" + address(raw.removePrefix("[").removeSuffix("]")) + "]"
        IPV4.matches(raw) || IPV6.matches(raw) -> address(raw)
        raw.startsWith("*.") -> "*." + host(raw.removePrefix("*."))
        else -> host(raw)
    }

    /** `[*.example.org, nas, 203.0.113.7]`: each name or address on its own. */
    private fun sanList(entries: String): String = entries.split(',').joinToString(",") { entry ->
        val name = entry.trim()
        val lead = entry.substring(0, entry.indexOf(name).coerceAtLeast(0))
        if (name.isEmpty()) entry else lead + hostOrAddress(name)
    }

    /**
     * `searchTerm=lighthouse` -> `searchTerm=…`: which parameters a request had, not what they said. A technical one
     * ([TECHNICAL_PARAMETERS]: codecs, bitrates, containers, start ticks) keeps a plain value: it names no one.
     */
    private fun queryParameter(parameter: String): String {
        if ('=' !in parameter) return parameter
        val name = parameter.substringBefore('=')
        val value = parameter.substringAfter('=')
        val plain = name.lowercase() in TECHNICAL_PARAMETERS && PLAIN_VALUE.matches(value)
        return name + "=" + if (plain) value else ELLIPSIS
    }

    private fun email(match: MatchResult): String {
        val domain = match.groupValues[2]
        // Java's Object.toString, `LayoutNode@5f2a1c`, is no address.
        if (IDENTITY_HASH.matches(domain)) return match.value
        if (placeholder(match.groupValues[1]) && placeholder(domain)) return match.value
        return label("email", match.value.lowercase())
    }

    private fun address(match: MatchResult, text: String): String {
        val value = match.value
        // `Bad::add` is a Kotlin reference, not IPv6: an address has a digit.
        if (value.none { it.isDigit() }) return value
        // `Jellyfin 10.10.3.0`, `Version="10.10.3.0"`: a version, not IPv4.
        if (IPV4.matches(value)) {
            val before = text.substring((match.range.first - VERSION_LOOKBACK).coerceAtLeast(0), match.range.first)
            if (VERSION_BEFORE.containsMatchIn(before)) return value
        }
        return address(value)
    }

    private fun address(value: String): String {
        val canonical = canonical(value)
        return if (loopbackCanonical(canonical) || placeholder(value)) value else label("address", canonical)
    }

    /** A public service's host ([PUBLIC_HOSTS]) stays: it names the service, not the user. */
    private fun host(value: String): String {
        val keep = loopbackHost(value) || placeholder(value) || value.lowercase() in PUBLIC_HOSTS
        return if (keep) value else label("host", value.lowercase())
    }

    /** `nas:8096` -> `host-1:8096`, unless the name is a word of the code (`Unknown Source:19`, `Main.kt:47`). */
    private fun namePort(match: MatchResult): String {
        val name = match.groupValues[1]
        val code = name.lowercase() in NOT_HOSTS || CODE_FILE.containsMatchIn(name) || name.all { it.isDigit() }
        return if (code) match.value else host(name) + ":" + match.groupValues[2]
    }

    private fun label(kind: String, key: String): String = labels.getOrPut("$kind:$key") {
        val n = (counts[kind] ?: 0) + 1
        counts[kind] = n
        "$kind-$n"
    }

    companion object {
        private const val HIDDEN = "<hidden>"
        private const val ELLIPSIS = "…"
        private const val VERSION_LOOKBACK = 12
        private const val OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
        private const val IPV4_TEXT = "(?:$OCTET\\.){3}$OCTET"
        private const val H = "[0-9A-Fa-f]{1,4}"

        /** An IPv4 address on its own, not a piece of a longer dotted number. */
        private val IPV4 = Regex("(?<![\\w.])$IPV4_TEXT(?!\\w|\\.\\d)")

        /**
         * An IPv6 address in any of its written forms (embedded IPv4, `::` anywhere, a `%zone`). A time (21:04:35) has
         * neither eight groups nor `::`, and `Type::member` has a letter before the `::`, so neither is one.
         */
        private val IPV6 = Regex(
            "(?<![\\w:.%])(?:" +
                "(?:$H:){6}$IPV4_TEXT|(?:$H:){1,5}:(?:$H:){0,4}$IPV4_TEXT|::(?:$H:){0,5}$IPV4_TEXT|" +
                "(?:$H:){7}$H|(?:$H:){1,7}:|(?:$H:){1,6}:$H|(?:$H:){1,5}(?::$H){1,2}|(?:$H:){1,4}(?::$H){1,3}|" +
                "(?:$H:){1,3}(?::$H){1,4}|(?:$H:){1,2}(?::$H){1,5}|$H:(?::$H){1,6}|:(?::$H){1,7}" +
                ")(?:%[0-9A-Za-z._~-]+)?(?![\\w:]|\\.\\d)",
        )

        /** Either kind, in one pass, so the numbers follow the order the addresses come in. */
        private val ADDRESS = Regex("${IPV6.pattern}|${IPV4.pattern}")

        /**
         * `Jellyfin 10.10.3.0`, `version: `, `Version="`: what comes before a four-part version. Not a `/`:
         * `jellyfin/203.0.113.7` is a host called jellyfin and its address.
         */
        private val VERSION_BEFORE = Regex("(?i)(?:version|jellyfin)(?:\\s*[=:]\\s*[\"']?|\\s+v?)$")

        /** `scheme://[user[:password]@]host`: the host, an IPv6 one in brackets included; a password may hold a `/`. */
        private val URL_AUTHORITY = Regex(
            "\\b([A-Za-z][A-Za-z0-9+.-]*://)([^\\s/?#@:\"'<>\\[\\]]*(?::[^\\s?#@\"'<>\\[\\]]*)?@)?" +
                "(\\[[0-9A-Za-z:.%_~-]+]|[^\\s/?#:@\"'<>\\[\\]]+)",
        )

        /** A query string: `?name=value&name=value`, the values up to the end of the address. */
        private const val QUERY_NAME = "[A-Za-z_][\\w.\\-\\[\\]%]*"

        /** A value ends at `&`, a space or `, ` (a message going on); `h264,hevc` is one value. */
        private const val QUERY_VALUE = "(?:[^&\\s#\"',:)\\]]|,(?=[^\\s&]))*"
        private val QUERY = Regex("\\?($QUERY_NAME=$QUERY_VALUE(?:&$QUERY_NAME(?:=$QUERY_VALUE)?)*)")

        /** OkHttp's certificate pins, `sha256/AbC…=`: a pin finds the certificate, and with it the server. */
        private val PIN = Regex("(?i)\\b(sha(?:1|256))/[A-Za-z0-9+/]{8,}={0,2}")

        /** OkHttp's `subjectAltNames: [*.example.org, nas]`. */
        private val SAN_LIST = Regex("(?i)(subjectAltNames:\\s*\\[)([^\\]\\n]*)(])")

        /** A line with a distinguished name (`DN: CN=…,O=…`, a certificate's subject or issuer). */
        private val DN_LINE = Regex("(?im)^.*(?:\\bDN:|\\bCN=|\\bOU=|\\bsubject[:=]|\\bissuer[:=]).*$")

        /** One attribute of a distinguished name, its value up to the next unescaped comma. */
        private val DN_ATTRIBUTE = Regex(
            "(?i)(?<![\\w.-])(CN|OU|O|L|ST|C|DC|E|EMAILADDRESS|UID|STREET|SERIALNUMBER|SN|GIVENNAME|SURNAME|T|TITLE|" +
                "POSTALCODE)=(\"[^\"]*\"|(?:\\\\.|[^,;\\n\\]])*)",
        )

        /** `someone@example.org`, `someone@nas`. */
        private val EMAIL = Regex(
            "(?<![\\w.+%-])([A-Za-z0-9._%+-]+)@" +
                "([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9-]+)*)(?![\\w-])",
        )

        /** What follows `@` in Java's `Class@1a2b3c`. */
        private val IDENTITY_HASH = Regex("[0-9a-f]{1,16}")

        /** `aa:bb:cc:dd:ee:ff`, `AA-BB-CC-DD-EE-FF`. */
        private val MAC = Regex("(?<![\\w:-])[0-9A-Fa-f]{2}([:-])(?:[0-9A-Fa-f]{2}\\1){4}[0-9A-Fa-f]{2}(?![\\w:-])")

        /** Java's way of writing a socket address, `name/203.0.113.7`, once the address is a placeholder. */
        private val HOST_BEFORE_ADDRESS = Regex("(?<![\\w.-])([A-Za-z0-9][A-Za-z0-9.-]*)/(?=\\[?address-\\d+\\b)")

        private const val NAME = "([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)"

        /**
         * Where a line names a host, and only there (a bare dotted name is as likely a class, `t3.c.ai`, as a host):
         * `Unable to resolve host "nas"`, `Hostname nas not verified`, `failed to connect to nas/…`, `Failed to connect
         * to nas:8096`, `Host: nas`, `host=nas`, `UnknownHostException: nas`. URLs, `@`, a certificate's names and
         * `name:port` have passes of their own.
         */
        private val HOST_IN_MESSAGE = listOf(
            Regex("(?i)(resolve host \")$NAME(?=\")"),
            Regex("(?i)(\\bhostname )$NAME(?= not verified)"),
            Regex("(?i)(\\bconnect to )$NAME(?=/|:\\d| \\(port)"),
            Regex("(?im)(\\bhost: )$NAME(?=[\\s:;,]|$)"),
            Regex("(?i)(\\b(?:host|hostname|server|domain|authority)\\s*=\\s*\"?)$NAME(?=[\"\\s:/,;)\\]]|$)"),
            // Java's own message is the host ("nas: Name or service not known"); Android's starts "Unable to resolve host".
            Regex("(?im)(UnknownHostException: )$NAME(?=:|$)"),
            // OkHttp: "Dns.SYSTEM returned no addresses for nas", "... dns lookup of nas"; JSON: "host":"nas".
            Regex("(?i)(\\breturned no addresses for )$NAME(?=[\\s:;,)\"]|$)"),
            Regex("(?i)(\\blookup of )$NAME(?=[\\s:;,)\"]|$)"),
            Regex("(?i)(\"(?:host|hostname)\"\\s*:\\s*\")$NAME(?=\")"),
        )

        /** `nas:8096`, `media.example.pl:443`: a name and a port, not a time or a file's line. */
        private val NAME_PORT = Regex(
            "(?<![\\w.:/(@\\[$-])([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9-]+)*):(\\d{2,5})(?![\\w:.])",
        )

        /** Words a log puts before `:` and a number that are no hosts. */
        private val NOT_HOSTS = setOf(
            "source", "line", "port", "ports", "thread", "level", "id", "pid", "tid", "uid", "step", "frame", "code",
            "error", "status", "http", "https", "tcp", "udp", "index", "count", "size", "time", "ms", "at", "map",
            "track", "group", "window", "period", "item", "items", "page", "pages", "row", "rows", "part", "segment",
        )

        /** A source or library file before `:` and a line: `Main.kt:47`. */
        private val CODE_FILE = Regex("(?i)\\.(kt|kts|java|so|cpp|cc|c|h|js)$")

        /**
         * Query parameters whose plain values name no one (codecs, bitrates, containers, positions, the shape of a
         * page): a report needs them. Never an id, a token, a name or a search term.
         */
        private val TECHNICAL_PARAMETERS = setOf(
            "videocodec", "audiocodec", "subtitlecodec", "transcodereasons", "starttimeticks", "maxstreamingbitrate",
            "videobitrate", "audiobitrate", "maxaudiobitrate", "container", "segmentcontainer", "segmentlength",
            "minsegments", "maxwidth", "maxheight", "width", "height", "maxframerate", "framerate", "audiochannels",
            "maxaudiochannels", "transcodingmaxaudiochannels", "audiosamplerate", "maxaudiobitdepth", "videobitdepth",
            "subtitlemethod", "static", "context", "requireavc", "requirenonanamorphic", "deinterlace",
            "breakonnonkeyframes", "copytimestamps", "enableautostreamcopy", "allowvideostreamcopy",
            "allowaudiostreamcopy", "enablesubtitlesinmanifest", "level", "profile", "h264-profile", "h264-level",
            "hevc-profile", "hevc-level", "av1-profile", "av1-level", "audiostreamindex", "subtitlestreamindex",
            "videostreamindex", "startindex", "limit", "includeitemtypes", "excludeitemtypes", "sortby", "sortorder",
            "recursive", "fields", "filters", "enableimages", "enableimagetypes", "imagetypelimit", "enableuserdata",
            "enabletotalrecordcount", "mediatypes", "isplayed", "isfavorite", "page", "quality", "format",
            "fillwidth", "fillheight", "maxtime", "starttime", "isfolder",
        )

        /** A technical parameter's value as it may stay: short, letters, digits and `,._+-`. */
        private val PLAIN_VALUE = Regex("[A-Za-z0-9,._+-]{0,40}")

        /** Hosts of public services the app reaches: they name the service, not the user. */
        private val PUBLIC_HOSTS = setOf(
            "github.com", "api.github.com", "objects.githubusercontent.com", "raw.githubusercontent.com",
            "api.themoviedb.org", "image.tmdb.org", "www.themoviedb.org", "themoviedb.org", "www.youtube.com",
            "youtube.com", "m.youtube.com", "youtu.be", "www.youtube-nocookie.com", "i.ytimg.com",
            "api.theintrodb.org", "theintrodb.org", "introdb", "api.aniskip.com", "arm.haglund.dev",
            "repo.jellyfin.org", "jellyfin.org",
        )

        /** Schemes whose authority is no network host (an app's content provider, a file). */
        private val LOCAL_SCHEMES = setOf("content", "file", "android.resource", "android-app", "jar", "data", "about")

        private val PLACEHOLDER = Regex("(?i)(server|host|address|user|email|mac)-\\d+(-name)?")

        private fun placeholder(value: String) = PLACEHOLDER.matches(value)

        private fun loopbackHost(value: String): Boolean {
            val name = value.lowercase()
            return name == "localhost" || name.endsWith(".localhost") || name == "ip6-localhost" ||
                name == "ip6-loopback" || loopbackAddress(name)
        }

        internal fun loopbackAddress(value: String): Boolean = loopbackCanonical(canonical(value))

        private fun loopbackCanonical(canonical: String) =
            canonical.startsWith("127.") || canonical == "0:0:0:0:0:0:0:1"

        /** `::ffff:203.0.113.9` and `0:0:0:0:0:ffff:203.0.113.9` are 203.0.113.9 written as IPv6. */
        private val MAPPED = Regex("(?:(?:0{1,4}:){5}|::(?:0{1,4}:)?)ffff:((?:\\d{1,3}\\.){3}\\d{1,3})")

        /**
         * One way of writing each address, so its forms share a number: brackets, zone and case gone, an IPv4-mapped
         * address as its IPv4, `::` written out, leading zeros dropped.
         */
        internal fun canonical(value: String): String {
            val address = value.lowercase().removePrefix("[").removeSuffix("]").substringBefore('%')
            if (':' !in address) return address
            MAPPED.matchEntire(address)?.let { return it.groupValues[1] }
            val groups = if ("::" in address) {
                val left = address.substringBefore("::").split(':').filter { it.isNotEmpty() }
                val right = address.substringAfter("::").split(':').filter { it.isNotEmpty() }
                val used = (left + right).sumOf { if ('.' in it) 2 else 1 }
                left + List((IPV6_GROUPS - used).coerceAtLeast(0)) { "0" } + right
            } else {
                address.split(':')
            }
            return groups.joinToString(":") { if ('.' in it) it else it.trimStart('0').ifEmpty { "0" } }
        }

        private const val IPV6_GROUPS = 8
    }
}
