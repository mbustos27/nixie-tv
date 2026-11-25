// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv

/**
 * YouTube trailers, as Nixie for Windows plays them since 1.4.0: the web view loads a small page the app makes
 * itself (nothing on disk, nothing hosted) at the app's own origin, YouTube's documented form for an app (the
 * reverse-DNS application id as an https origin). The embed is youtube-nocookie's, told that origin and sent it as
 * its Referer, so YouTube plays it (no Error 153) and Nixie never presents itself as another project's site.
 */
object NixieTrailers {
    /** The release application id as an https origin (debug builds use the same, so YouTube sees one app). */
    const val ORIGIN = "https://io.github.mbustos27.nixie.tv"

    /** Where the wrapper page claims to live; loaded from a string, never fetched. */
    const val BASE_URL = "$ORIGIN/trailer"

    /** The embed's host: no cookies until the viewer plays. */
    const val EMBED_HOST = "https://www.youtube-nocookie.com"
}
