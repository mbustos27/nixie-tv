// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.PlaybackSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferencesRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsState
import io.github.glacier_jellyfin.androidtv.core.data.settings.profileKey
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceCapabilities
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.MediaCapabilityDetector
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.JellyfinOptions
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.ApiClientFactory
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue

/** One video file on the fake server: its picture and its one audio track. */
internal data class FakeTitle(
    val width: Int,
    val height: Int,
    val videoCodec: String = "hevc",
    val videoProfile: String = "Main 10",
    val level: Int = 153,
    val bitDepth: Int = 10,
    /** The server's VideoRangeType: "SDR", "HDR10", "DOVI", "DOVIWithHDR10", … */
    val range: String = "HDR10",
    val dolbyVisionProfile: Int? = null,
    val audioCodec: String = "eac3",
    val audioProfile: String? = null,
    val channels: Int = 6,
    val container: String = "mkv",
    val videoBitrate: Int = 60_000_000,
) {
    /** The file's bitrate: the picture and its audio track (1 Mbit/s). */
    val bitrate: Int get() = videoBitrate + 1_000_000
}

/** What the fake server chose for one PlaybackInfo request. */
internal sealed interface Choice {
    data object DirectPlay : Choice

    /** Nixie (tv-1.0.1): the file repackaged as it is (a container the device doesn't play). */
    data object DirectStream : Choice

    /** Only the audio is converted; the picture is copied as it is. */
    data class CopyVideo(val reasons: List<String>) : Choice

    /** The picture is converted, at most [maxWidth] x [maxHeight] (the profile's limits). */
    data class EncodeVideo(
        val maxWidth: Int?,
        val maxHeight: Int?,
        val reasons: List<String>,
    ) : Choice

    /** Nixie (tv-1.0.1): what the server would have made ([wanted]), refused with NoCompatibleStream. */
    data class Refused(val wanted: Choice) : Choice
}

/** Nixie (tv-1.0.1): a server where this user may not transcode or remux: everything but direct play is refused. */
internal val NO_TRANSCODES: (PlaybackInfoDto, Choice) -> Boolean = { _, choice -> choice != Choice.DirectPlay }

/**
 * A server that decides like Jellyfin's StreamBuilder, cut down to what a video file needs: the direct play profiles
 * (container, codecs) and the codec profiles' conditions (size, level, profile, bit depth, range, audio). Every posted
 * device profile is kept in [asked] and every choice in [choices]. Nothing goes over a network.
 */
internal class FakePlaybackServer(
    private val title: FakeTitle,
    // Nixie (tv-1.0.1): where the bandwidth test goes (a FakeLink); the API itself never leaves this process.
    private val address: String = "http://home.invalid",
    // Nixie (tv-1.0.1): the answers the server refuses with NoCompatibleStream (see NO_TRANSCODES).
    private val refuses: (PlaybackInfoDto, Choice) -> Boolean = { _, _ -> false },
) {
    val asked: MutableList<PlaybackInfoDto> = java.util.Collections.synchronizedList(mutableListOf())
    val choices: MutableList<Choice> = java.util.Collections.synchronizedList(mutableListOf())

    /** Nixie (tv-1.0.1): the app's settings file, kept from one [repository] to the next (as across a restart). */
    private var settingsStore: MemoryStore<SettingsState>? = null

    /** Nixie (tv-1.0.1): the sessions of the last [repository], to sign out. */
    lateinit var sessions: SessionManager

    /**
     * A signed-in [PlaybackRepository] on this server for a device with [capabilities] whose panel is [mode] and whose
     * UI is [ui], with the bitrate setting at 120 Mbit/s (a cap: no link measurement, no ladder), or [maxBitrate].
     * [deviceId]: the sign-in's own device id (a new sign-in has a new one).
     */
    fun repository(
        capabilities: DeviceCapabilities,
        mode: Pair<Int, Int>?,
        ui: Pair<Int, Int>,
        hdAudio: Boolean = false,
        maxBitrate: io.github.glacier_jellyfin.androidtv.core.data.settings.MaxBitrate =
            io.github.glacier_jellyfin.androidtv.core.data.settings.MaxBitrate.M120,
        deviceId: String = "d",
    ): PlaybackRepository {
        val server = StoredServer("s1", "Home", address, "12.4.0", lastUsedAt = 0)
        val user = StoredUser("s1", USER.toString(), "anna", null, "t", lastUsedAt = 0, deviceId = deviceId)
        val accounts = AccountRepository(MemoryStore(AccountState(servers = listOf(server), users = listOf(user))))
        val jellyfin = Jellyfin(
            JellyfinOptions(
                context = ContextWrapper(null),
                clientInfo = ClientInfo("Nixie for TV", "test"),
                deviceInfo = DeviceInfo(id = "install", name = "Test TV"),
                apiClientFactory = Factory(),
                socketConnectionFactory = SocketConnectionFactory { _, _ -> error("no sockets here") },
                minimumServerVersion = Jellyfin.minimumVersion,
            ),
        )
        val sessions = SessionManager(jellyfin, accounts, Authenticator(jellyfin))
        this.sessions = sessions
        runBlocking { check(sessions.open("s1", USER.toString())) }
        val playback = PlaybackSettings(
            maxBitrate = maxBitrate,
            hdAudio = if (hdAudio) {
                io.github.glacier_jellyfin.androidtv.core.data.settings.HdAudioOutput.ServerEac3
            } else {
                io.github.glacier_jellyfin.androidtv.core.data.settings.HdAudioOutput.Glacier
            },
        )
        val profiles = mapOf(profileKey("s1", USER.toString()) to ProfileSettings(playback = playback))
        val store = settingsStore?.also { it.data.value = it.data.value.copy(profiles = profiles) }
            ?: MemoryStore(SettingsState(profiles = profiles)).also { settingsStore = it }
        val settings = SettingsRepository(store, sessions)
        val detector = mockk<MediaCapabilityDetector> {
            every { this@mockk.capabilities } returns capabilities
            every { iec61937() } returns false
        }
        return PlaybackRepository(
            sessions,
            detector,
            settings,
            ServerPreferencesRepository(sessions),
            jellyfin,
            DisplayPanel(modeSize = { mode }, uiSize = { ui }),
        )
    }

    private fun answer(dto: PlaybackInfoDto): String {
        asked += dto
        val profile = checkNotNull(dto.deviceProfile) { "PlaybackInfo without a device profile" }
        val video = videoReasons(profile, dto.maxStreamingBitrate)
        val audio = buildList {
            val direct = profile.directPlayProfiles.any {
                it.type == DlnaProfileType.VIDEO && title.audioCodec in it.audioCodec.orEmpty().split(',')
            }
            if (!direct) add("AudioCodecNotSupported")
            val failing = profile.applying(CodecType.VIDEO_AUDIO, title.audioCodec).filterNot(::holds)
            failing.forEach { add(reason(it.property)) }
        }.distinct()
        val choice = when {
            video.isEmpty() && audio.isEmpty() -> Choice.DirectPlay

            video == listOf(CONTAINER) && audio.isEmpty() -> Choice.DirectStream

            video.isEmpty() -> Choice.CopyVideo(audio)

            else -> {
                val limits = profile.applying(CodecType.VIDEO, "hevc")
                Choice.EncodeVideo(
                    limits.limit(ProfileConditionValue.WIDTH),
                    limits.limit(ProfileConditionValue.HEIGHT),
                    video + audio,
                )
            }
        }.let { if (refuses(dto, it)) Choice.Refused(it) else it }
        choices += choice
        return playbackInfo(choice)
    }

    /** Why the picture can't play as it is with [profile] and a limit of [maxBitrate]; empty when it can. */
    private fun videoReasons(profile: DeviceProfile, maxBitrate: Int?): List<String> = buildList {
        val video = profile.directPlayProfiles.filter {
            it.type == DlnaProfileType.VIDEO && title.videoCodec in it.videoCodec.orEmpty().split(',')
        }
        when {
            video.isEmpty() -> add("VideoCodecNotSupported")
            video.none { title.container in it.container.split(',') } -> add(CONTAINER)
        }
        profile.applying(CodecType.VIDEO, title.videoCodec).filterNot(::holds).forEach { add(reason(it.property)) }
        // Against the file's bitrate, as Jellyfin does (Nixie tv-1.0.1: it was the picture's alone).
        if ((maxBitrate ?: Int.MAX_VALUE) < title.bitrate) add("ContainerBitrateExceedsLimit")
    }.distinct()

    /** The conditions of the codec profiles of [type] for [codec] whose apply-conditions hold. */
    private fun DeviceProfile.applying(type: CodecType, codec: String): List<ProfileCondition> = codecProfiles
        .filter { it.type == type && it.codec?.split(',')?.contains(codec) != false }
        .filter { it.applyConditions.all(::holds) }
        .flatMap { it.conditions }

    private fun List<ProfileCondition>.limit(property: ProfileConditionValue): Int? =
        filter { it.property == property && it.condition == ProfileConditionType.LESS_THAN_EQUAL }
            .mapNotNull { it.value?.toIntOrNull() }.minOrNull()

    private fun value(property: ProfileConditionValue): String? = when (property) {
        ProfileConditionValue.WIDTH -> title.width.toString()
        ProfileConditionValue.HEIGHT -> title.height.toString()
        ProfileConditionValue.VIDEO_LEVEL -> title.level.toString()
        ProfileConditionValue.VIDEO_PROFILE -> title.videoProfile
        ProfileConditionValue.VIDEO_BIT_DEPTH -> title.bitDepth.toString()
        ProfileConditionValue.VIDEO_RANGE_TYPE -> title.range
        ProfileConditionValue.AUDIO_CHANNELS -> title.channels.toString()
        ProfileConditionValue.AUDIO_PROFILE -> title.audioProfile
        else -> null
    }

    private fun holds(condition: ProfileCondition): Boolean {
        val actual = value(condition.property) ?: return true
        val wanted = condition.value.orEmpty()
        return when (condition.condition) {
            ProfileConditionType.EQUALS -> actual.equals(wanted, ignoreCase = true)
            ProfileConditionType.NOT_EQUALS -> !actual.equals(wanted, ignoreCase = true)
            ProfileConditionType.EQUALS_ANY -> wanted.split('|').any { it.equals(actual, ignoreCase = true) }
            ProfileConditionType.LESS_THAN_EQUAL -> actual.toDouble() <= wanted.toDouble()
            ProfileConditionType.GREATER_THAN_EQUAL -> actual.toDouble() >= wanted.toDouble()
        }
    }

    private fun reason(property: ProfileConditionValue): String = when (property) {
        ProfileConditionValue.WIDTH, ProfileConditionValue.HEIGHT -> "VideoResolutionNotSupported"
        ProfileConditionValue.VIDEO_LEVEL -> "VideoLevelNotSupported"
        ProfileConditionValue.VIDEO_PROFILE -> "VideoProfileNotSupported"
        ProfileConditionValue.VIDEO_BIT_DEPTH -> "VideoBitDepthNotSupported"
        ProfileConditionValue.VIDEO_RANGE_TYPE -> "VideoRangeTypeNotSupported"
        ProfileConditionValue.AUDIO_CHANNELS -> "AudioChannelsNotSupported"
        ProfileConditionValue.AUDIO_PROFILE -> "AudioProfileNotSupported"
        else -> "UnknownVideoStreamInfo"
    }

    private fun transcodingUrl(choice: Choice): String? = when (choice) {
        Choice.DirectPlay, is Choice.Refused -> null

        Choice.DirectStream ->
            "/videos/$ITEM/master.m3u8?MediaSourceId=$SOURCE&VideoCodec=${title.videoCodec}" +
                "&AudioCodec=${title.audioCodec}&AudioStreamIndex=1&VideoBitrate=${title.videoBitrate}" +
                "&SegmentContainer=ts&TranscodeReasons=$CONTAINER"

        is Choice.CopyVideo ->
            "/videos/$ITEM/master.m3u8?MediaSourceId=$SOURCE&VideoCodec=${title.videoCodec}&AudioCodec=eac3" +
                "&AudioStreamIndex=1&VideoBitrate=${title.videoBitrate}&SegmentContainer=ts" +
                "&TranscodeReasons=${choice.reasons.joinToString(",")}"

        is Choice.EncodeVideo ->
            "/videos/$ITEM/master.m3u8?MediaSourceId=$SOURCE&VideoCodec=hevc&AudioCodec=eac3&AudioStreamIndex=1" +
                "&VideoBitrate=20000000&SegmentContainer=ts" +
                (choice.maxWidth?.let { "&MaxWidth=$it" } ?: "") +
                (choice.maxHeight?.let { "&MaxHeight=$it" } ?: "") +
                "&TranscodeReasons=${choice.reasons.joinToString(",")}"
    }

    private fun playbackInfo(choice: Choice): String {
        val direct = choice == Choice.DirectPlay
        val stream = direct || choice == Choice.DirectStream
        val url = transcodingUrl(choice)
        // Nixie (tv-1.0.1): a refusal as Jellyfin answers it: the source with nothing allowed, and the error code.
        val refused = choice is Choice.Refused
        val dolbyVision = title.dolbyVisionProfile?.let { ""","DvProfile":$it,"DvLevel":6""" } ?: ""
        return """
            {"MediaSources":[{"Protocol":"File","Id":"$SOURCE","Path":"/media/film.mkv","Type":"Default",
             "Container":"${title.container}","Size":1000000000,"Name":"Film","IsRemote":false,"ETag":"e",
             "RunTimeTicks":72000000000,"ReadAtNativeFramerate":false,"IgnoreDts":false,"IgnoreIndex":false,
             "GenPtsInput":false,"SupportsTranscoding":${!refused},"SupportsDirectStream":$stream,"SupportsDirectPlay":$direct,
             "IsInfiniteStream":false,"UseMostCompatibleTranscodingProfile":false,"RequiresOpening":false,
             "RequiresClosing":false,"RequiresLooping":false,"SupportsProbing":true,"VideoType":"VideoFile",
             "MediaAttachments":[],"Formats":[],"Bitrate":${title.bitrate},"RequiredHttpHeaders":{},
             "TranscodingSubProtocol":"${if (url == null) "http" else "hls"}",
             ${url?.let { "\"TranscodingUrl\":\"$it\",\"TranscodingContainer\":\"ts\"," } ?: ""}
             "HasSegments":false,"DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":-1,
             "MediaStreams":[
              {"Codec":"${title.videoCodec}","Profile":"${title.videoProfile}","Level":${title.level},
               "Width":${title.width},"Height":${title.height},"BitDepth":${title.bitDepth},
               "VideoRangeType":"${title.range}"$dolbyVision,"BitRate":${title.videoBitrate},
               "AverageFrameRate":23.976,"RealFrameRate":23.976,"IsInterlaced":false,"IsDefault":true,"IsForced":false,
               "IsHearingImpaired":false,"IsOriginal":false,"IsAVC":false,"AudioSpatialFormat":"None",
               "VideoRange":"${if (title.range == "SDR") "SDR" else "HDR"}","Type":"Video","Index":0,"IsExternal":false,
               "IsTextSubtitleStream":false,"SupportsExternalStream":false},
              {"Codec":"${title.audioCodec}"${title.audioProfile?.let { ",\"Profile\":\"$it\"" } ?: ""},
               "Language":"eng","Channels":${title.channels},"BitRate":1000000,"SampleRate":48000,"IsInterlaced":false,
               "IsDefault":true,"IsForced":false,"IsHearingImpaired":false,"IsOriginal":false,"IsAVC":false,
               "AudioSpatialFormat":"None","VideoRange":"Unknown","VideoRangeType":"Unknown","Type":"Audio","Index":1,
               "IsExternal":false,"IsTextSubtitleStream":false,"SupportsExternalStream":false,"Level":0}
             ]}],
             ${if (refused) "\"ErrorCode\":\"NoCompatibleStream\"," else ""}
             "PlaySessionId":"p${asked.size}"}
        """.trimIndent()
    }

    private inner class Factory : ApiClientFactory {
        override fun create(
            baseUrl: String?,
            accessToken: String?,
            clientInfo: ClientInfo,
            deviceInfo: DeviceInfo,
            httpClientOptions: HttpClientOptions,
            socketConnectionFactory: SocketConnectionFactory,
        ): ApiClient = Client(baseUrl, accessToken, clientInfo, deviceInfo, httpClientOptions)
    }

    private inner class Client(
        override var baseUrl: String?,
        override var accessToken: String?,
        override var clientInfo: ClientInfo,
        override var deviceInfo: DeviceInfo,
        override val httpClientOptions: HttpClientOptions,
    ) : ApiClient() {
        override val webSocket: SocketApi get() = error("no sockets here")

        override fun update(
            baseUrl: String?,
            accessToken: String?,
            clientInfo: ClientInfo,
            deviceInfo: DeviceInfo,
        ) = Unit

        override suspend fun request(
            method: HttpMethod,
            pathTemplate: String,
            pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>,
            requestBody: Any?,
        ): RawResponse {
            // The user's configuration (subtitle mode) is not part of these tests: the app goes on without it.
            check(pathTemplate == "/Items/{itemId}/PlaybackInfo") { "not on this server: $pathTemplate" }
            val body = answer(requestBody as PlaybackInfoDto)
            return RawResponse(body.toByteArray(), 200, mapOf("content-type" to listOf("application/json")))
        }
    }

    private class MemoryStore<T>(start: T) : DataStore<T> {
        override val data = MutableStateFlow(start)

        override suspend fun updateData(transform: suspend (t: T) -> T): T =
            transform(data.value).also { data.value = it }
    }

    companion object {
        val USER: UUID = UUID.nameUUIDFromBytes("anna".toByteArray())
        val ITEM: UUID = UUID.nameUUIDFromBytes("film".toByteArray())
        val SOURCE: String = ITEM.toString().replace("-", "")

        /** Nixie (tv-1.0.1): the reason a file is repackaged (direct stream). */
        const val CONTAINER = "ContainerNotSupported"
    }
}
