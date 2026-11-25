package io.github.glacier_jellyfin.androidtv.core.jellyfin

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.mbustos27.nixie.tv.jellyfin.NixieTimedApiClient
import javax.inject.Singleton
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo

/**
 * Provides the SDK entry point. The app module supplies [ClientInfo], since
 * only it knows the app's name and version.
 *
 * The SDK reads response bodies on the calling thread, so every API call must
 * run on Dispatchers.IO; large responses otherwise fail with
 * NetworkOnMainThreadException. Repositories switch dispatchers themselves.
 */
@Module
@InstallIn(SingletonComponent::class)
object JellyfinModule {

    @Provides
    @Singleton
    fun provideJellyfin(@ApplicationContext context: Context, clientInfo: ClientInfo): Jellyfin = createJellyfin {
        this.context = context
        this.clientInfo = clientInfo
        // Nixie (TV-S1): each answer's arrival marked for the detailed log's timings (NixieRequestClock); one OkHttp
        // client for requests and sockets, as the SDK's default.
        val okHttp = OkHttpFactory()
        apiClientFactory = NixieTimedApiClient.wrap(okHttp)
        socketConnectionFactory = okHttp
    }
}
