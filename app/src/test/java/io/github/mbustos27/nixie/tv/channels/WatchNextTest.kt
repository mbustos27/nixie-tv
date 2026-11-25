// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.channels

import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import io.github.glacier_jellyfin.androidtv.MainActivity
import io.github.glacier_jellyfin.androidtv.channels.HomeChannels
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunch
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunches
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeScreenContent
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeScreenTitle
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.mbustos27.nixie.tv.data.watchnext.NixieWatchNextSettings
import io.github.mbustos27.nixie.tv.data.watchnext.WatchNextOwner
import io.github.mbustos27.nixie.tv.data.watchnext.WatchNextProfiles
import io.github.mbustos27.nixie.tv.data.watchnext.WatchNextProgress
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Stream W: Continue watching on the TV's home screen (Android's Watch Next), through Glacier's own writer
 * (HomeChannels) and Nixie's rules (NixieWatchNext, WatchNextStore), against a fake of the system's TV provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchNextTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: FakeTvProvider
    private val profiles = FakeProfiles()
    private var now = 1_800_000_000_000L
    private var refreshes = 0
    private val scheduler = TestCoroutineScheduler()

    private val server = "server-1"
    private val alice = UUID.nameUUIDFromBytes("alice".toByteArray()).toString()
    private val bob = UUID.nameUUIDFromBytes("bob".toByteArray()).toString()
    private val movie = UUID.nameUUIDFromBytes("movie".toByteArray())
    private val episode = UUID.nameUUIDFromBytes("episode".toByteArray())
    private val other = UUID.nameUUIDFromBytes("other".toByteArray())

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(FakeTvProvider::class.java).create("android.media.tv").get()
        profiles.state = AccountState(
            servers = listOf(StoredServer(server, "Home", "http://127.0.0.1:1", "12.4.0", 0)),
            users = listOf(user(alice, "token-a"), user(bob, "token-b")),
        )
        profiles.recorded = WatchNextOwner.key(server, alice)
    }

    private fun watchNext(): NixieWatchNext = NixieWatchNext(
        context,
        profiles,
        clock = { now + scheduler.currentTime },
        provider = { true },
        refresh = { refreshes++ },
        dispatcher = StandardTestDispatcher(scheduler),
    )

    private fun publish(nixie: NixieWatchNext, content: HomeScreenContent?) = runBlocking {
        nixie.publish(content) { HomeChannels(context).write(it) }
    }

    private fun rows() = provider.rows("watch_next_program")

    private fun internalId(row: ContentValues): String? = row.getAsString(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)

    private fun ownerOf(row: ContentValues): String? = WatchNextIds.ownerOf(internalId(row))

    private fun rowOf(item: UUID, owner: String = alice) =
        rows().singleOrNull { internalId(it) == WatchNextIds.of(key(owner), item.toString()) }

    @Test
    fun insert_oneRowPerTitle_withTheTitlesFacts_andAnIntentThatResumes() {
        publish(watchNext(), content(alice))

        assertEquals(2, rows().size)
        val film = checkNotNull(rowOf(movie)) { "the film's row" }
        assertEquals("A Quiet Harbor", film.getAsString(WatchNextPrograms.COLUMN_TITLE))
        assertEquals(
            WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
            film.getAsInteger(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE),
        )
        assertEquals(600_000, film.getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
        assertEquals(5_400_000, film.getAsInteger(WatchNextPrograms.COLUMN_DURATION_MILLIS))
        assertEquals(now - 60_000, film.getAsLong(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS))
        assertEquals(
            "http://127.0.0.1:1/Items/$movie/Images/Thumb",
            film.getAsString(WatchNextPrograms.COLUMN_POSTER_ART_URI),
        )
        val launch = HomeLaunch.from(
            Intent.parseUri(film.getAsString(WatchNextPrograms.COLUMN_INTENT_URI), Intent.URI_INTENT_SCHEME),
        )
        assertEquals(HomeLaunch(server, alice, movie.toString(), play = true), launch)

        val next = checkNotNull(rowOf(episode)) { "the episode's row" }
        assertEquals(
            WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
            next.getAsInteger(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE),
        )
        assertEquals("Harbor Lights", next.getAsString(WatchNextPrograms.COLUMN_TITLE))
        assertEquals("The Tide", next.getAsString(WatchNextPrograms.COLUMN_EPISODE_TITLE))
        assertEquals("2", next.getAsString(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER))
        assertEquals("5", next.getAsString(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER))
        // An episode not started comes after the started titles.
        assertTrue(next.getAsLong(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS) < now - 60_000)
    }

    @Test
    fun aTapOnARow_opensNixieOnThatTitle_andPlaysIt_forItsOwnProfileOnly() {
        publish(watchNext(), content(alice))
        val intent = Intent.parseUri(
            rowOf(movie)!!.getAsString(WatchNextPrograms.COLUMN_INTENT_URI),
            Intent.URI_INTENT_SCHEME,
        )

        assertEquals(ComponentName(context.packageName, MainActivity::class.java.name), intent.component)
        val launch = checkNotNull(HomeLaunch.from(intent)) { "Nixie's launch" }
        assertTrue("plays from where it was left", launch.play)
        // Home takes it for Alice's profile only: Bob's (or another server's) opens nothing.
        assertNull(HomeLaunches().apply { set(launch) }.take(server, bob))
        assertNull(HomeLaunches().apply { set(launch) }.take("server-2", alice))
        assertEquals(movie.toString(), HomeLaunches().apply { set(launch) }.take(server, alice)?.itemId)
    }

    @Test
    fun anotherAppsRows_areNeverTouched() = runBlocking {
        val youtube = "com.google.android.youtube.tv"
        provider.seed("watch_next_program", ContentValues().apply { put("title", "Someone else's") }, youtube)
        val nixie = watchNext()

        publish(nixie, content(alice))
        nixie.removed(movie)
        scheduler.runCurrent()
        nixie.reconcileNow(key(bob))
        nixie.switchedOffNow()
        nixie.ownerChangedNow(null)

        assertEquals(0, rows().size)
        assertEquals(
            listOf("Someone else's"),
            provider.rows("watch_next_program", youtube).map { it.getAsString("title") },
        )
    }

    @Test
    fun theWiring_followsSessions_owners_positions_andTheSetting() = runBlocking {
        val nixie = watchNext()
        val sessions = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
        val positions = MutableSharedFlow<WatchNextProgress>(extraBufferCapacity = 4)
        val shows = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
        publish(nixie, content(alice))
        nixie.follow(sessions, profiles.ownerKeys, positions, shows)
        scheduler.runCurrent()
        // The first owner seen (Alice, at app start) keeps her rows and loads nothing.
        assertEquals(2, rows().size)
        assertEquals(0, refreshes)

        // Bob opens Nixie: he owns the rows from now on; Alice's go at once, his come with a load.
        sessions.tryEmit(server to bob)
        scheduler.runCurrent()
        assertEquals(key(bob), profiles.ownerKeys.value)
        assertEquals(0, rows().size)
        assertEquals(1, refreshes)
        publish(nixie, content(bob))

        // A position the server took reaches Bob's row.
        positions.tryEmit(WatchNextProgress(movie, 42_000))
        scheduler.runCurrent()
        assertEquals(
            42_000,
            rowOf(movie, owner = bob)!!.getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS),
        )

        // The setting as Bob's profile opens (on), then switched off: every row goes.
        shows.tryEmit(true)
        scheduler.runCurrent()
        assertEquals(2, rows().size)
        assertEquals("the setting's first value loads nothing", 1, refreshes)
        shows.tryEmit(false)
        scheduler.runCurrent()
        assertEquals(0, rows().size)
    }

    @Test
    fun update_inPlace_andNoDuplicateRowsAfterARestart() {
        publish(watchNext(), content(alice))
        val ids = rows().map { it.getAsLong("_id") }.toSet()
        // A second row of one title (from a crash between two writes, say) goes too.
        provider.seed("watch_next_program", rowOf(movie)!!.apply { remove("_id") })
        assertEquals(3, rows().size)

        // The app started again: a new instance writes the same titles, one further on.
        publish(watchNext(), content(alice, moviePositionMs = 1_200_000))

        assertEquals(2, rows().size)
        assertEquals(ids, rows().map { it.getAsLong("_id") }.toSet())
        assertEquals(1_200_000, rowOf(movie)!!.getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
    }

    @Test
    fun aRowTheUserHidOnTheHomeScreen_staysHidden_untilTheTitleIsWatchedFurther() {
        publish(watchNext(), content(alice))
        val hiddenId = rowOf(movie)!!.getAsLong("_id")
        // The user removed it from the home screen's row: the system marks it not browsable.
        provider.systemSet("watch_next_program", hiddenId, "browsable", 0)

        publish(watchNext(), content(alice))
        assertEquals(0, rowOf(movie)!!.getAsInteger("browsable"))

        publish(watchNext(), content(alice, moviePositionMs = 1_500_000))
        val shown = rowOf(movie)!!
        assertEquals(1, shown.getAsInteger("browsable"))
        assertEquals(1_500_000, shown.getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
        assertEquals(2, rows().size)
    }

    @Test
    fun progress_writesThePlayingTitlesRowByItsId_andNeverLoadsTheRows() = runBlocking {
        val nixie = watchNext()
        publish(nixie, content(alice))
        now += 30_000
        provider.ops.clear()

        nixie.progressNow(WatchNextProgress(movie, 900_000))
        val film = rowOf(movie)!!
        assertEquals(900_000, film.getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
        assertEquals(now, film.getAsLong(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS))
        // The next episode starts: its row is under way now.
        nixie.progressNow(WatchNextProgress(episode, 20_000))
        assertEquals(
            WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
            rowOf(episode)!!.getAsInteger(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE),
        )
        // A title without a row: nothing is written and the rows are not loaded.
        nixie.progressNow(WatchNextProgress(other, 10_000))
        // A stale id that now names another title's row writes nothing.
        WatchNextRowIds[WatchNextIds.of(key(alice), other.toString())] = rowOf(episode)!!.getAsLong("_id")
        now += 61_000
        nixie.progressNow(WatchNextProgress(other, 99_000))
        assertEquals(20_000, rowOf(episode)!!.getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))

        assertEquals(2, rows().size)
        assertEquals(0, refreshes)
        assertEquals(listOf<String>(), provider.ops.filter { it == "query watch_next_program" })
    }

    @Test
    fun playback_writesAtMostOnceAMinute_plusPauseAndStop_withSeeksCoalesced() {
        val nixie = watchNext()
        publish(nixie, content(alice))
        provider.ops.clear()
        // Ten minutes of play as the player reports it: every 10 s while playing, every seek, a pause from 5:05 to
        // 5:30, a moment of buffering at 7:31, and the stop at 10:00.
        val events = sortedMapOf<Long, WatchNextProgress>()
        fun at(
            second: Long,
            paused: Boolean = false,
            final: Boolean = false,
            positionMs: Long = 600_000 + second * 1_000,
        ) {
            events[second * 1_000] = WatchNextProgress(movie, positionMs, paused, final)
        }
        for (second in 0L until 600L step 10) if (second !in 305L..329L) at(second)
        for (second in listOf(125L, 126L, 127L, 400L)) at(second, positionMs = 2_000_000 + second * 1_000)
        at(305, paused = true)
        at(330)
        at(451, paused = true)
        at(453)
        at(600, final = true, positionMs = 1_234_000)

        val positions = MutableSharedFlow<WatchNextProgress>(extraBufferCapacity = 64)
        nixie.follow(emptyFlow(), emptyFlow(), positions, emptyFlow())
        scheduler.runCurrent()
        for ((time, change) in events) {
            scheduler.advanceTimeBy(time - scheduler.currentTime)
            scheduler.runCurrent()
            positions.tryEmit(change)
            scheduler.runCurrent()
        }
        scheduler.advanceUntilIdle()

        val writes = provider.ops.count { it.startsWith("update watch_next_program/") }
        assertTrue("$writes writes for ${events.size} reports", writes in 10..13)
        assertEquals(1_234_000, rowOf(movie)!!.getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
        assertEquals(listOf<String>(), provider.ops.filter { it == "query watch_next_program" })
        assertEquals(0, refreshes)
    }

    @Test
    fun remove_takesTheRowAtOnce_andAWriteFromBeforeTheRemovalKeepsItOut() = runBlocking {
        val nixie = watchNext()
        publish(nixie, content(alice))

        nixie.removed(movie)
        scheduler.runCurrent()
        assertNull(rowOf(movie))
        assertNotNull(rowOf(episode))
        assertEquals(1, refreshes)

        // Rows loaded before the server took the removal still list it.
        publish(nixie, content(alice))
        assertNull(rowOf(movie))

        // A removal the server never took: the title shows again once the hold is over.
        now += 61_000
        publish(nixie, content(alice))
        assertNotNull(rowOf(movie))
    }

    @Test
    fun removeAndUndo_asTwoLaunches_putTheRowBackAsItWas() = runBlocking {
        val nixie = watchNext()
        publish(nixie, content(alice))
        val before = rowOf(movie)!!

        nixie.removed(movie)
        scheduler.runCurrent()
        assertNull(rowOf(movie))
        nixie.restored(movie)
        scheduler.runCurrent()

        val back = checkNotNull(rowOf(movie)) { "the row put back" }
        for (column in listOf(
            WatchNextPrograms.COLUMN_TITLE,
            WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            WatchNextPrograms.COLUMN_INTENT_URI,
        )) {
            assertEquals(column, before.get(column), back.get(column))
        }
        // The next write keeps it, once.
        publish(nixie, content(alice))
        assertEquals(2, rows().size)
    }

    @Test
    fun anUndoArrivingAfterALaterRemoval_neverBringsTheRowBack() = runBlocking {
        val nixie = watchNext()
        publish(nixie, content(alice))
        nixie.removed(movie)
        scheduler.runCurrent()
        // Removed again (after the first removal's Undo card), then the first removal's Undo arrives late.
        nixie.removed(movie)
        scheduler.runCurrent()
        nixie.restoredNow(movie, generation = 1)
        assertNull(rowOf(movie))

        // The later removal's own Undo puts it back.
        nixie.restored(movie)
        scheduler.runCurrent()
        assertNotNull(rowOf(movie))
    }

    @Test
    fun signOut_emptiesEveryRow_andRowsLoadedBeforeStayOut() = runBlocking {
        val nixie = watchNext()
        publish(nixie, content(alice))
        assertTrue(provider.rows("preview_program").isNotEmpty())

        profiles.state = profiles.state.copy(users = listOf(user(alice, token = null), user(bob, "token-b")))
        nixie.ownerChangedNow(null)

        assertEquals(0, rows().size)
        assertEquals(0, provider.rows("preview_program").size)
        // Loaded for Alice before she signed out; and nothing of Bob's, who did not open Nixie.
        publish(nixie, content(alice))
        publish(nixie, content(bob))
        assertEquals(0, rows().size)
    }

    @Test
    fun signOut_emptiesWatchNext_evenWhenTheChannelsFail() = runBlocking {
        val nixie = watchNext()
        publish(nixie, content(alice))
        provider.failing = setOf("channel", "preview_program")

        profiles.state = profiles.state.copy(users = listOf(user(alice, token = null), user(bob, "token-b")))
        nixie.ownerChangedNow(null)
        assertEquals(0, rows().size)

        // The background work's empty write, the same way.
        provider.failing = emptySet()
        publish(watchNext(), null)
        provider.seed("watch_next_program", ContentValues().apply { put("title", "left over") })
        provider.failing = setOf("channel", "preview_program")
        runCatching { publish(nixie, null) }
        assertEquals(0, rows().size)
    }

    @Test
    fun userSwitch_replacesEveryRow_andNothingOfTheOtherProfileStays() = runBlocking {
        val nixie = watchNext()
        publish(nixie, content(alice))

        profiles.record(server, bob)
        nixie.ownerChangedNow(key(bob))
        assertEquals(0, rows().size)

        publish(nixie, content(alice)) // loaded for Alice before the switch
        assertEquals(0, rows().size)
        publish(nixie, content(bob))
        assertEquals(2, rows().size)
        assertTrue(rows().all { ownerOf(it) == key(bob) })
        assertNull(rowOf(movie, owner = alice))
    }

    @Test
    fun aWriteCutShort_neverLeavesTheOldProfilesRows() {
        publish(watchNext(), content(alice))
        profiles.record(server, bob)
        // The provider fails on the second insert (or the app dies there).
        provider.failWatchNextInsertsAfter = 1

        runCatching { publish(watchNext(), content(bob)) }

        assertEquals(1, rows().size)
        assertTrue(rows().none { ownerOf(it) == key(alice) })
    }

    @Test
    fun appStart_reconcilesOnce_rowsOfNoCurrentProfileGo_theOwnersStay() {
        publish(watchNext(), content(alice))
        // The app started again with Alice still the owner: her rows stay, and nothing is loaded.
        watchNext().follow(emptyFlow(), flowOf(key(alice)), emptyFlow(), emptyFlow())
        scheduler.runCurrent()
        assertEquals(2, rows().size)

        // Bob became the owner, and the app died before Alice's rows went: they go at the next start.
        profiles.record(server, bob)
        watchNext().follow(emptyFlow(), flowOf(key(bob)), emptyFlow(), emptyFlow())
        scheduler.runCurrent()
        assertEquals(0, rows().size)

        // The recorded owner signed out and the app died before her rows went: they go at the next start, as her
        // profile is known to be gone (tv-home-start: only then; "no profile yet" removes nothing).
        profiles.record(server, alice)
        publish(watchNext(), content(alice))
        profiles.state = profiles.state.copy(users = listOf(user(alice, null), user(bob, "token-b")))
        watchNext().follow(emptyFlow(), flowOf(null), emptyFlow(), emptyFlow())
        scheduler.runCurrent()
        assertEquals(0, rows().size)
        assertEquals(0, refreshes)
    }

    @Test
    fun appStart_withTheSameProfile_keepsTheRows_whileNoProfileIsKnownYet_andAfter() {
        publish(watchNext(), content(alice))
        // A start with nothing recorded yet (the first start after an update): no owner is known.
        profiles.recorded = null
        val nixie = watchNext()
        val sessions = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
        nixie.follow(sessions, profiles.ownerKeys, emptyFlow(), emptyFlow())
        scheduler.runCurrent()
        assertEquals("\"no profile yet\" is not a decision", 2, rows().size)

        // The same profile opens: it owns the rows now, and they stay (it is no other profile's).
        sessions.tryEmit(server to alice)
        scheduler.runCurrent()
        assertEquals(key(alice), profiles.ownerKeys.value)
        assertEquals(2, rows().size)
        assertEquals("nothing emptied, so nothing to load at once", 0, refreshes)
        // Glacier's channels were not emptied either.
        assertTrue(provider.rows("preview_program").isNotEmpty())
    }

    @Test
    fun appStart_waitsForHome_beforeItTouchesTheRows() {
        publish(watchNext(), content(alice))
        profiles.record(server, bob)
        val home = kotlinx.coroutines.CompletableDeferred<Unit>()
        watchNext().follow(emptyFlow(), flowOf(key(bob)), emptyFlow(), emptyFlow(), ready = { home.await() })
        scheduler.runCurrent()
        assertEquals("Home is not drawn yet: nothing happens", 2, rows().size)

        home.complete(Unit)
        scheduler.runCurrent()
        assertEquals("then another profile's rows go", 0, rows().size)
    }

    @Test
    fun aWriteForOneProfile_removesRowsOfAnyOther_evenWithoutTheSwitch() {
        publish(watchNext(), content(alice))
        // Bob owns the rows; Alice's are still in the table (the switch's emptying failed, say).
        profiles.record(server, bob)

        publish(watchNext(), content(bob))

        assertEquals(2, rows().size)
        assertTrue(rows().none { ownerOf(it) == key(alice) })
    }

    @Test
    fun providerErrors_neverEscape_andTheWorkGoesOnAfter() = runBlocking {
        val nixie = watchNext()
        publish(nixie, content(alice))
        val positions = MutableSharedFlow<WatchNextProgress>(extraBufferCapacity = 8)
        nixie.follow(emptyFlow(), emptyFlow(), positions, emptyFlow())
        scheduler.runCurrent()
        provider.failing = setOf("watch_next_program", "channel", "preview_program")

        // Every path, with the provider failing: nothing is thrown.
        nixie.reconcileNow(key(alice))
        nixie.removedNow(movie)
        nixie.restoredNow(movie, generation = 0)
        nixie.progressNow(WatchNextProgress(episode, 1_000))
        nixie.switchedOffNow()
        nixie.ownerChangedNow(key(alice))
        positions.tryEmit(WatchNextProgress(movie, 2_000))
        scheduler.runCurrent()
        scheduler.advanceTimeBy(61_000)
        scheduler.runCurrent()

        // The provider is back: positions still reach the row.
        provider.failing = emptySet()
        positions.tryEmit(WatchNextProgress(movie, 3_000))
        scheduler.runCurrent()
        assertEquals(3_000, rowOf(movie)!!.getAsInteger(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
    }

    @Test
    fun theDebugDump_onlyASenderWithTheDumpPermissionReachesIt_andItListsTheRows() {
        val nixie = watchNext()
        publish(nixie, content(alice))
        nixie.registerDebugDump()

        val receivers = shadowOf(context).registeredReceivers
        val dump = receivers.single { it.intentFilter.hasAction(NixieWatchNext.DUMP_ACTION) }
        assertEquals(android.Manifest.permission.DUMP, dump.broadcastPermission)
        ShadowLog.clear()
        dump.broadcastReceiver.onReceive(context, Intent(NixieWatchNext.DUMP_ACTION))
        scheduler.runCurrent()
        assertTrue(ShadowLog.getLogsForTag(NixieWatchNext.TAG).any { it.msg == "Dump: 2 rows" })
    }

    @Test
    fun settingOff_removesEveryRow_andNothingIsWrittenWhileOff() = runBlocking {
        val nixie = watchNext()
        publish(nixie, content(alice))

        val off = ProfileSettings(nixieWatchNext = NixieWatchNextSettings(showOnHomeScreen = false))
        profiles.settings[key(alice)] = off
        nixie.switchedOffNow()
        assertEquals(0, rows().size)

        publish(nixie, content(alice))
        nixie.progressNow(WatchNextProgress(movie, 1_000))
        nixie.removed(movie)
        nixie.restored(movie)
        scheduler.runCurrent()
        assertEquals(0, rows().size)
        // Nor in the Continue watching channel.
        val channels = provider.rows("channel")
        val continueChannel = channels.single { it.getAsString("internal_provider_id") == "continue" }.getAsLong("_id")
        assertTrue(provider.rows("preview_program").none { it.getAsLong("channel_id") == continueChannel })

        profiles.settings.clear()
        publish(nixie, content(alice))
        assertEquals(2, rows().size)
    }

    // ---- Fixtures ----

    private fun key(userId: String) = WatchNextOwner.key(server, userId)

    private fun user(id: String, token: String?) = StoredUser(
        serverId = server,
        userId = id,
        name = id.take(4),
        primaryImageTag = null,
        accessToken = token,
        lastUsedAt = 0,
    )

    private fun content(userId: String, moviePositionMs: Long = 600_000) = HomeScreenContent(
        serverId = server,
        userId = userId,
        continueWatching = listOf(
            HomeScreenTitle(
                item(movie, ItemKind.Movie, "A Quiet Harbor", resumeMs = moviePositionMs),
                durationMs = 5_400_000,
                lastPlayedAt = now - 60_000,
            ),
            HomeScreenTitle(
                item(episode, ItemKind.Episode, "The Tide", show = "Harbor Lights", season = 2, number = 5),
                durationMs = 2_700_000,
                lastPlayedAt = null,
                nextUp = true,
            ),
        ),
        recentlyAdded = listOf(
            HomeScreenTitle(item(other, ItemKind.Movie, "Another"), durationMs = null, lastPlayedAt = null),
        ),
        recentReleases = emptyList(),
    )

    private fun item(
        id: UUID,
        kind: ItemKind,
        title: String,
        resumeMs: Long = 0,
        show: String? = null,
        season: Int? = null,
        number: Int? = null,
    ) = MediaItem(
        id = id,
        kind = kind,
        title = title,
        sortName = null,
        year = 2024,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 90,
        genres = emptyList(),
        overview = "About $title",
        parentTitle = show,
        seasonNumber = season,
        episodeNumber = number,
        progress = null,
        remainingMinutes = null,
        resumePositionMs = resumeMs,
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = "http://127.0.0.1:1/Items/$id/Images/Primary",
        thumbUrl = "http://127.0.0.1:1/Items/$id/Images/Thumb",
        backdropUrl = null,
    )

    private class FakeProfiles : WatchNextProfiles {
        var state = AccountState()
            set(value) {
                field = value
                emitOwner()
            }
        var recorded: String? = null
            set(value) {
                field = value
                emitOwner()
            }
        val settings = HashMap<String, ProfileSettings>()

        /** The owner's key whenever the accounts or the record change, as DeviceWatchNextProfiles does. */
        override val ownerKeys = MutableStateFlow<String?>(null)

        private fun emitOwner() {
            ownerKeys.value = WatchNextOwner.of(state, recorded)?.let(WatchNextOwner::key)
        }

        override suspend fun owner(): StoredUser? = WatchNextOwner.of(state, recorded)

        override suspend fun settingsOf(user: StoredUser): ProfileSettings =
            settings[WatchNextOwner.key(user)] ?: ProfileSettings()

        override fun record(serverId: String, userId: String) {
            recorded = WatchNextOwner.key(serverId, userId)
        }

        override suspend fun gone(): String? = recorded?.takeIf { WatchNextOwner.of(state, it) == null }
    }
}

/**
 * The system's TV provider (`android.media.tv`) as far as Glacier's writer and Nixie's store use it: channels,
 * preview programs and Watch Next rows, each a table of [ContentValues] by id. As the real one: each row belongs to
 * the package that inserted it, a caller sees and changes only its own package's rows, inserted rows are browsable,
 * and the columns only the system writes (the id, the package, the browsable flag) are refused.
 */
class FakeTvProvider : ContentProvider() {
    private val tables = HashMap<String, MutableMap<Long, ContentValues>>()
    private var next = 1L

    /** The calling package (the app under test). */
    var caller = APP

    /** Tables that fail every call, as a provider in trouble does. */
    var failing = setOf<String>()

    /** Watch Next inserts that succeed before the next fails; null: all succeed. */
    var failWatchNextInsertsAfter: Int? = null

    private fun check(uri: Uri) = check(uri.pathSegments[0] !in failing) { "provider down: $uri" }

    /** Every call, for counting: `query <table>` (the whole table), `query <table>/<id>` (one row), `update <table>/<id>`. */
    val ops = mutableListOf<String>()

    /** As the system's provider does for apps without full access. */
    private fun refuseSelection(uri: Uri, selection: String?) {
        if (selection != null) throw SecurityException("Selection not allowed for $uri")
    }

    private fun ContentValues.mine() = getAsString("package_name") == caller

    private fun refuseProtected(values: ContentValues?) {
        for (column in listOf("_id", "package_name", "browsable")) {
            if (values?.containsKey(column) == true) throw SecurityException("Not allowed to write $column")
        }
    }

    /** A table's rows of [pkg] (the app's by default), as stored. */
    fun rows(table: String, pkg: String = APP): List<ContentValues> =
        tables[table].orEmpty().values.filter { it.getAsString("package_name") == pkg }.map(::ContentValues)

    /** A row as another app or the system left it, written as is. */
    fun seed(
        table: String,
        values: ContentValues,
        pkg: String = APP,
    ): Long {
        val id = next++
        tables.getOrPut(table) { LinkedHashMap() }[id] = ContentValues(values).apply {
            put("_id", id)
            put("package_name", pkg)
            if (!containsKey("browsable")) put("browsable", 1)
        }
        return id
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        check(uri)
        refuseProtected(values)
        if (uri.pathSegments[0] == "watch_next_program") {
            failWatchNextInsertsAfter?.let { left ->
                check(left > 0) { "provider down: insert" }
                failWatchNextInsertsAfter = left - 1
            }
        }
        val id = seed(uri.pathSegments[0], values ?: ContentValues(), caller)
        return ContentUris.withAppendedId(Uri.parse("content://android.media.tv/${uri.pathSegments[0]}"), id)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        check(uri)
        refuseSelection(uri, selection)
        ops += "query ${uri.pathSegments.joinToString("/")}"
        val table = tables[uri.pathSegments[0]].orEmpty()
        val id = uri.pathSegments.getOrNull(1)?.toLongOrNull()
        val channel = uri.getQueryParameter("channel")?.toLongOrNull()
        val rows = table.values.filter { row ->
            row.mine() && (id == null || row.getAsLong("_id") == id) &&
                (channel == null || row.getAsLong("channel_id") == channel)
        }
        val columns = projection ?: rows.flatMap { it.keySet() }.distinct().toTypedArray()
        return MatrixCursor(columns).apply { rows.forEach { row -> addRow(columns.map { row.get(it) }) } }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        check(uri)
        refuseSelection(uri, selection)
        refuseProtected(values)
        val id = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: return 0
        val row = tables[uri.pathSegments[0]]?.get(id)?.takeIf { it.mine() } ?: return 0
        ops += "update ${uri.pathSegments[0]}/$id"
        values?.let(row::putAll)
        return 1
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        check(uri)
        refuseSelection(uri, selection)
        val id = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: return 0
        val table = tables[uri.pathSegments[0]] ?: return 0
        if (table[id]?.mine() != true) return 0
        table.remove(id)
        return 1
    }

    /** What only the system may write (the browsable flag, say). */
    fun systemSet(
        table: String,
        id: Long,
        column: String,
        value: Int,
    ) {
        tables.getValue(table).getValue(id).put(column, value)
    }

    /** Channel logos. */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
        File.createTempFile("logo", ".png").apply { deleteOnExit() },
        ParcelFileDescriptor.MODE_READ_WRITE,
    )

    companion object {
        const val APP = "io.github.mbustos27.nixie.tv.debug"
    }
}
