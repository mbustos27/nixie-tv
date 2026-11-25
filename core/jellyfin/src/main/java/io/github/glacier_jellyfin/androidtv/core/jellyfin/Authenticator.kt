package io.github.glacier_jellyfin.androidtv.core.jellyfin

import io.github.mbustos27.nixie.tv.jellyfin.DeviceIds
import io.github.mbustos27.nixie.tv.jellyfin.deviceFor
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto

/** A signed-in user: the access token is the credential kept on the device. */
data class SignedInUser(
    val serverId: String,
    val userId: UUID,
    val name: String,
    val primaryImageTag: String?,
    val accessToken: String,
    // Nixie: the device id the token belongs to (DeviceIds); null only from code that predates it.
    val deviceId: String? = null,
)

/** A user as listed on the server's login screen. */
data class PublicUser(
    val id: UUID,
    val name: String,
    val primaryImageTag: String?,
)

internal fun UserDto.toPublicUser() = PublicUser(
    id = id,
    name = name.orEmpty(),
    primaryImageTag = primaryImageTag,
)

sealed interface SignInResult {
    data class Success(val user: SignedInUser) : SignInResult
    data object InvalidCredentials : SignInResult
    data class Failed(val cause: Throwable) : SignInResult
}

sealed interface QuickConnectState {
    /** Quick Connect is disabled on the server. */
    data object Unavailable : QuickConnectState

    /** Show [code]; the user approves it in another signed-in Jellyfin client. */
    data class WaitingForApproval(val code: String) : QuickConnectState

    data class Authorized(val user: SignedInUser) : QuickConnectState
    data class Failed(val cause: Throwable) : QuickConnectState
}

@Singleton
class Authenticator @Inject constructor(private val jellyfin: Jellyfin) {

    suspend fun publicUsers(address: String): List<PublicUser> = withContext(Dispatchers.IO) {
        jellyfin.createApi(baseUrl = address).userApi.getPublicUsers().content.map { it.toPublicUser() }
    }

    suspend fun signIn(
        server: ServerInfo,
        username: String,
        password: String,
    ): SignInResult = withContext(Dispatchers.IO) { signInBlocking(server, username, password) }

    private suspend fun signInBlocking(
        server: ServerInfo,
        username: String,
        password: String,
    ): SignInResult = try {
        // Nixie: each sign-in has its own device id, so profiles on one TV do not share a session.
        val deviceId = DeviceIds.fresh()
        val api = jellyfin.createApi(baseUrl = server.address, deviceInfo = jellyfin.deviceFor(deviceId))
        val result = api.authenticationApi.authenticateUserByName(username, password).content
        result.toSignedInUser(server, deviceId)?.let(SignInResult::Success)
            ?: SignInResult.Failed(IllegalStateException("Server returned no access token"))
    } catch (e: InvalidStatusException) {
        if (e.status == HTTP_UNAUTHORIZED) SignInResult.InvalidCredentials else SignInResult.Failed(e)
    } catch (e: Exception) {
        SignInResult.Failed(e)
    }

    /**
     * Runs a Quick Connect session. A code expires on the server after a few
     * minutes; a fresh one is requested and emitted when that happens. The
     * flow completes after [QuickConnectState.Authorized], [QuickConnectState.Unavailable]
     * or [QuickConnectState.Failed].
     */

    /** Whether the server allows Quick Connect; false when it cannot be asked. */
    suspend fun isQuickConnectEnabled(server: ServerInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            jellyfin.createApi(baseUrl = server.address).authenticationApi.getQuickConnectEnabled().content
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    fun quickConnect(server: ServerInfo): Flow<QuickConnectState> = flow {
        // Nixie: one device id per attempt, fixed before Initiate: the server ties the code and its token to it.
        val deviceId = DeviceIds.fresh()
        val api = jellyfin.createApi(baseUrl = server.address, deviceInfo = jellyfin.deviceFor(deviceId))
            .authenticationApi
        try {
            if (!api.getQuickConnectEnabled().content) {
                emit(QuickConnectState.Unavailable)
                return@flow
            }
            var session = api.initiateQuickConnect().content
            emit(QuickConnectState.WaitingForApproval(session.code))
            while (true) {
                delay(QUICK_CONNECT_POLL_INTERVAL)
                val state = try {
                    api.getQuickConnectState(session.secret).content
                } catch (e: InvalidStatusException) {
                    if (e.status != HTTP_NOT_FOUND) throw e
                    // Expired: start over with a new code.
                    session = api.initiateQuickConnect().content
                    emit(QuickConnectState.WaitingForApproval(session.code))
                    continue
                }
                if (state.authenticated) {
                    val result = api.authenticateWithQuickConnect(session.secret).content
                    val user = result.toSignedInUser(server, deviceId)
                        ?: throw IllegalStateException("Server returned no access token")
                    emit(QuickConnectState.Authorized(user))
                    return@flow
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(QuickConnectState.Failed(e))
        }
    }.flowOn(Dispatchers.IO)

    // Nixie: [deviceId] in the three calls below is the profile's own (StoredUser.deviceId), sent with its token.

    /** Ends the session on the server, which invalidates the access token. */
    suspend fun signOut(
        address: String,
        accessToken: String,
        deviceId: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            profileApi(address, accessToken, deviceId).sessionApi.reportSessionEnded()
        }
    }

    /**
     * The id of the user a stored token signs in, as the server at [address] knows it;
     * null when the server does not accept the token. Throws when it cannot be reached.
     */
    suspend fun tokenUserId(
        address: String,
        accessToken: String,
        deviceId: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        try {
            profileApi(address, accessToken, deviceId).userApi.getCurrentUser().content.id.toString()
        } catch (e: InvalidStatusException) {
            if (e.status == HTTP_UNAUTHORIZED) null else throw e
        }
    }

    /** Checks whether a stored token is still accepted. */
    suspend fun isTokenValid(
        address: String,
        accessToken: String,
        deviceId: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            profileApi(address, accessToken, deviceId).userApi.getCurrentUser()
            true
        } catch (e: InvalidStatusException) {
            if (e.status == HTTP_UNAUTHORIZED) false else throw e
        }
    }

    fun userImageUrl(
        address: String,
        userId: UUID,
        imageTag: String?,
    ): String? =
        imageTag?.let { jellyfin.createApi(baseUrl = address).imageApi.getUserImageUrl(userId = userId, tag = it) }

    // Nixie: a profile's token goes out with the device id it belongs to.
    private fun profileApi(
        address: String,
        accessToken: String,
        deviceId: String?,
    ) = jellyfin.createApi(baseUrl = address, accessToken = accessToken, deviceInfo = jellyfin.deviceFor(deviceId))

    private fun AuthenticationResult.toSignedInUser(server: ServerInfo, deviceId: String): SignedInUser? {
        val token = accessToken ?: return null
        val dto = user ?: return null
        return SignedInUser(
            serverId = serverId ?: server.id,
            userId = dto.id,
            name = dto.name.orEmpty(),
            primaryImageTag = dto.primaryImageTag,
            accessToken = token,
            // Nixie: the device id the token was issued to.
            deviceId = deviceId,
        )
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        val QUICK_CONNECT_POLL_INTERVAL = 5.seconds
    }
}
