// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import coil3.annotation.ExperimentalCoilApi
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.NetworkFetcher
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * TV-S13's finding (stream M): at a cold Movies open 15 to 21 of about 50 picture requests were repeats. The grid
 * composes about two rows ahead and its cards ask for their posters, while [NixiePrefetchArt] fetches the same rows'
 * posters at the same moment; Coil does not join requests in flight, so rows 3 to 5 asked a slow server twice.
 *
 * The app's image loader takes its pictures from the network through [network]: a picture already coming is not asked
 * again; the second request waits for the first and takes the picture from the disk cache. Each picture is asked once,
 * whoever asks for it (the grid's cards, the rows fetched ahead, Home's rows). A request that fails or is cancelled
 * (a row no longer ahead) lets the next one ask the server itself.
 */
object NixieImageRequests {

    /** The network fetcher for the app's image loader; [callFactory] is the HTTP client (a fake server in tests). */
    @OptIn(ExperimentalCoilApi::class)
    fun network(callFactory: () -> Call.Factory = { OkHttpClient() }): NetworkFetcher.Factory {
        val joined = DeDupeConcurrentRequestStrategy()
        return OkHttpNetworkFetcherFactory(callFactory = callFactory, concurrentRequestStrategy = { joined })
    }
}
