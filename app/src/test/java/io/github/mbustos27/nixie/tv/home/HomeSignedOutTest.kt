// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import android.app.Application
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunches
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.home.HomeViewModel
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.ServerListRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.mbustos27.nixie.tv.data.HomeViews
import io.github.mbustos27.nixie.tv.data.LiveUpdates
import io.github.mbustos27.nixie.tv.data.SavedHome
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * tv-1.0-fixes (the owner's TV log of 2026-11-25): after the signed-in profile's server was removed, Home asked for
 * Continue watching, Up next and the top bar's libraries with nobody signed in ("No profile is signed in" three
 * times). With no profile Home asks nothing, and gives way to "Who's watching?" or the server list. Glacier's real
 * HomeViewModel with the server faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeSignedOutTest {

    private val scheduler = TestCoroutineScheduler()
    private val server = StoredServer("s1", "Den", "https://jf.example.org", "10.11.0", 0)
    private val user = StoredUser("s1", "u1", "Ana", null, "token", lastUsedAt = 0)
    private val session = MutableStateFlow<Session?>(Session(server, user, mockk(relaxed = true), "install"))
    private var accounts = AccountState(servers = listOf(server), users = listOf(user))

    /** What Home asked the server, each kind counted. */
    private val asked = HashMap<String, AtomicInteger>()
    private fun ask(what: String) = asked.getOrPut(what) { AtomicInteger() }.incrementAndGet()
    private fun asked(what: String) = asked[what]?.get() ?: 0

    /** Continue watching answers when the test says so. */
    private var resumeAnswer = CompletableDeferred(emptyList<BaseItemDto>())
    private val stopped = MutableSharedFlow<UUID>(extraBufferCapacity = 1)

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun model(): HomeViewModel {
        val repository = mockk<HomeRepository>(relaxed = true) {
            coEvery { resume() } coAnswers {
                ask("continue watching")
                resumeAnswer.await()
            }
            coEvery { nextUp() } coAnswers {
                ask("up next")
                emptyList()
            }
            coEvery { items(any()) } coAnswers {
                ask("cards")
                emptyList()
            }
            coEvery { views() } coAnswers {
                ask("top bar")
                HomeViews(emptyList())
            }
            every { liveTv } returns MutableStateFlow(false)
        }
        val sessions = mockk<SessionManager>(relaxed = true) {
            every { session } returns this@HomeSignedOutTest.session
        }
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { settings } returns MutableStateFlow(ProfileSettings())
            coEvery { current() } returns ProfileSettings()
        }
        val playback = mockk<PlaybackRepository>(relaxed = true) {
            every { stopped } returns this@HomeSignedOutTest.stopped
        }
        val details = mockk<DetailRepository>(relaxed = true) { every { playedChanged } returns MutableSharedFlow() }
        val live = mockk<LiveUpdates>(relaxed = true) { every { live.changes } returns MutableSharedFlow() }
        val saved = mockk<SavedHome>(relaxed = true) { every { read(any()) } returns null }
        val accountRepository = mockk<AccountRepository>(relaxed = true) { coEvery { current() } answers { accounts } }
        return HomeViewModel(
            repository = repository,
            sessions = sessions,
            settings = settings,
            parental = mockk(relaxed = true),
            ageFilter = mockk(relaxed = true),
            playback = playback,
            details = details,
            launches = HomeLaunches(),
            continueWatchingServer = mockk(relaxed = true),
            nixieLive = live,
            nixieWatchNext = mockk(relaxed = true),
            nixieSaved = saved,
            nixieStartLine = mockk(relaxed = true),
            nixieAccounts = accountRepository,
        )
    }

    /** Runs what is queued, also what comes back from Dispatchers.IO, until [done] or the time is up. */
    private fun settle(ms: Long = 2_000, done: () -> Boolean = { false }) {
        val until = System.currentTimeMillis() + ms
        do {
            scheduler.advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        } while (System.currentTimeMillis() < until)
    }

    private fun HomeViewModel.firstEvent(): UiEvent {
        var event: UiEvent? = null
        val wait = CoroutineScope(Dispatchers.Main).launch { event = events.first() }
        settle { event != null }
        wait.cancel()
        return checkNotNull(event) { "Home sent no event" }
    }

    @Test
    fun `a load under way when the profile's session ends asks nothing more, and Home gives way to the server list`() {
        resumeAnswer = CompletableDeferred()
        val model = model()
        settle { asked("continue watching") == 1 && asked("up next") == 1 }
        assertEquals("Continue watching and Up next asked while signed in", 1, asked("continue watching"))

        // The server list above Home removes the signed-in profile's server: the session ends, the server goes.
        session.value = null
        accounts = AccountState()
        settle(300)
        resumeAnswer.complete(listOf(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)))
        settle(500)

        assertEquals("no cards asked for with nobody signed in", 0, asked("cards"))
        assertEquals("no top bar libraries asked for with nobody signed in", 0, asked("top bar"))
        assertTrue("Home knows nobody is signed in", model.state.value.nixieSignedOut)

        model.nixieLeave()
        assertEquals(UiEvent.Navigate(ServerListRoute, clearBackStack = true), model.firstEvent())
    }

    @Test
    fun `after a sign-out a refresh asks nothing, and Home gives way to that server's Who's watching`() {
        val model = model()
        settle { model.state.value.nixieSettled }
        val before = asked.mapValues { it.value.get() }

        session.value = null // signed out; the server stays on the TV
        stopped.tryEmit(UUID.randomUUID()) // back from the player: Home would refresh
        settle(500)

        assertEquals("nothing asked after the session ended", before, asked.mapValues { it.value.get() })
        assertTrue(model.state.value.nixieSignedOut)
        model.nixieLeave()
        assertEquals(UiEvent.Navigate(ProfilesRoute("s1"), clearBackStack = true), model.firstEvent())
    }

    @Test
    fun `a Home made with nobody signed in asks nothing`() {
        session.value = null
        val model = model()
        settle(500)

        assertEquals(emptyMap<String, Int>(), asked.mapValues { it.value.get() })
        assertTrue(model.state.value.nixieSignedOut)
        assertEquals(false, model.state.value.failed)
    }
}
