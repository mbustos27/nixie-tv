package io.github.glacier_jellyfin.androidtv.diagnostics

import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.mbustos27.nixie.tv.diagnostics.NamesScrub
import io.github.mbustos27.nixie.tv.diagnostics.NetworkScrub

/**
 * Replaces what identifies the user in a log before it leaves the device:
 * server hosts become `server-1`, `server-2` (scheme and port stay, they
 * matter for connection problems), server and user names `server-1-name`,
 * `user-1`. Item and user ids stay: they only mean something on the server.
 * Nixie (tv-1.0-fixes): then every other address and host name ([NetworkScrub]).
 * Nixie (tv-1.0-privacy): the names in one combined pattern per kind of place ([NamesScrub]).
 */
class Anonymizer(servers: List<Pair<String, String>>, users: List<String>) {

    // Nixie (tv-1.0-privacy): hosts before names, so the user "demo" does not break the host "demo.jellyfin.org".
    private val names = NamesScrub(servers.map { (address, name) -> hostOf(address) to name }, users)

    // Nixie (tv-1.0-fixes): one per log, so each address keeps its number throughout.
    private val network = NetworkScrub()

    fun apply(text: String): String = network.apply(names.apply(text))

    companion object {
        /**
         * The profiles and servers on the TV first (their numbers stay as they were), then, Nixie (tv-1.0-privacy),
         * those removed before ([AccountState.nixieSeen]): older lines of the log still name them.
         */
        fun of(state: AccountState) = Anonymizer(
            servers = state.servers.map { it.address to it.name } +
                state.nixieSeen.servers.map { it.address to it.name },
            users = state.users.map { it.name } + state.nixieSeen.users,
        )

        /** "https://jf.example.org:8920/jellyfin" -> "jf.example.org"; "http://[2001:db8::7]:8096" -> "2001:db8::7". */
        internal fun hostOf(address: String): String? {
            val authority = address.substringAfter("://").substringBefore('/').substringAfterLast('@')
            // Nixie (tv-1.0-fixes): an IPv6 host is written in brackets, its colons are not the port's.
            val host = if (authority.startsWith("[")) {
                authority.substringAfter('[').substringBefore(']')
            } else {
                authority.substringBefore(':')
            }
            return host.takeIf { it.isNotBlank() }
        }
    }
}
