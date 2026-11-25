package io.github.glacier_jellyfin.androidtv.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.ModalSheet
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.blockKeysWhile
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateCandidate
import io.github.glacier_jellyfin.androidtv.core.updater.WhatsNewPrompt
import io.github.glacier_jellyfin.androidtv.navigation.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.TopNav
import io.github.glacier_jellyfin.androidtv.parental.PinDialog
import io.github.glacier_jellyfin.androidtv.settings.RestoreOfferDialog
import io.github.glacier_jellyfin.androidtv.settings.RestoreViewModel
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.LocalToaster
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.yearText
import io.github.glacier_jellyfin.androidtv.update.UpdateDialog
import io.github.glacier_jellyfin.androidtv.update.UpdateViewModel
import io.github.glacier_jellyfin.androidtv.update.WhatsNewDialog
import io.github.mbustos27.nixie.tv.home.NixieHomePage
import io.github.mbustos27.nixie.tv.home.NixieKeepRowsFocus
import io.github.mbustos27.nixie.tv.home.NixieRemoveSheetHost
import io.github.mbustos27.nixie.tv.home.NixieRow
import io.github.mbustos27.nixie.tv.home.NixieRowsFocus
import io.github.mbustos27.nixie.tv.home.nixieExitKeys
import io.github.mbustos27.nixie.tv.home.rememberFocusHandoff
import io.github.mbustos27.nixie.tv.home.rememberReturns
import io.github.mbustos27.nixie.tv.home.requestFocusWhenReady
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    /** True once when the screen was opened through the navigation: the focus then stays on its bar. */
    takeFocusNav: () -> Boolean = { false },
    viewModel: HomeViewModel = hiltViewModel(),
    updates: UpdateViewModel = hiltViewModel(),
    restore: RestoreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    // Nixie (tv-1.0-fixes): nobody signed in: "Who's watching?" or the server list instead (HomeViewModel.nixieLeave).
    LaunchedEffect(state.nixieSignedOut) { if (state.nixieSignedOut) viewModel.nixieLeave() }

    val pinPrompt by viewModel.pin.prompt.collectAsStateWithLifecycle()
    // Nixie: Remove from Continue watching, with an Undo.
    val nixiePending by viewModel.nixieRemovals.pending.collectAsStateWithLifecycle()
    val nixieRemoveAsk by viewModel.nixieRemoveAsk.collectAsStateWithLifecycle()
    val listFocus = remember { FocusRequester() }
    // Home is the bottom of the stack: Back asks before it closes the app (declared here, Nixie TV-S10: the
    // focus moves below leave the dialog alone).
    var exitAsked by remember { mutableStateOf(false) }
    // Nixie (TV-S10): whether the focus is in "Quit Nixie?" yet; until then the dialog takes the keys (nixieExitKeys).
    var exitFocused by remember { mutableStateOf(false) }
    val nixieActivity = LocalActivity.current

    // Arrived through the navigation (Back from a page, OK on "Home"): the focus stays on the bar's entry, also
    // when the content comes later, so no jump into the page meets a key the user already pressed. Down goes in.
    var navArrival by rememberSaveable { mutableStateOf(takeFocusNav()) }
    val navFocus = remember { FocusRequester() }
    // Set by Back in the rows (and the top bar's Home back here): Down from the bar then starts at the first card, not
    // at the card left in the rows.
    var toTop by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The first focus goes into the rows once (not when returning later). Not on a navigation arrival.
    var initialFocusDone by rememberSaveable { mutableStateOf(navArrival) }
    // Back from another page the screen is composed anew; focus goes back to the element the
    // user left from (the lists keep their scroll), not to the nav on top.
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun Modifier.remembered(key: String, restoreTo: String = key): Modifier = this
        .focusRequester(requesters.getOrPut(key) { FocusRequester() })
        .onFocusChanged { if (it.isFocused) lastFocus = restoreTo }
    // Nixie: a card swapped in its place (Undo) takes the focus over (NixieHomeFocus).
    val nixieHandoff = rememberFocusHandoff { requesters[it] }
    val continueRow = rememberLazyListState()
    val upNextRow = rememberLazyListState() // Nixie (tv-home-two-rows)
    var inContinue by remember { mutableStateOf(false) }
    // Nixie: in every Nixie row the focus stays on the same title, or its neighbour, and the row is not
    // scrolled back (NixieKeepRowsFocus): an Undo card running out or a live update must not move it.
    val nixieRowsFocus = remember { NixieRowsFocus() }
    NixieKeepRowsFocus(
        state.content,
        inRow = { row -> if (row == NixieRow.ContinueWatching) inContinue else nixieRowsFocus.has(row) },
        lastFocus = { lastFocus },
        setLastFocus = { lastFocus = it },
        requester = { requesters[it] },
    )
    val hasContent = state.content != null
    // Nixie (tv-home-two-rows): whether the page has a card to focus; with none (both rows empty, or not in yet) the
    // focus stays on the top bar, and Down from it goes nowhere.
    val hasCards = state.content?.let { it.continueWatching.isNotEmpty() || it.upNext.isNotEmpty() } == true
    // Nixie (tv-home-start): the moments Home is on screen, for the start's timings and the background work.
    LaunchedEffect(hasContent) {
        if (!hasContent) return@LaunchedEffect
        withFrameNanos { }
        viewModel.nixieTimes.onScreen()
    }
    LaunchedEffect(state.nixieSettled) {
        if (!state.nixieSettled) return@LaunchedEffect
        withFrameNanos { }
        viewModel.nixieTimes.allOnScreen()
    }
    // Nixie (tv-home-two-rows): the first focus went to the top bar while no row had cards yet; until a key is
    // pressed, the first card takes it once the rows come.
    var nixieMayFocus by remember { mutableStateOf(false) }
    // Nixie: also on a return to the front with Home still composed (a quick Back), and asking for a few
    // frames, as a lazy row may draw the card a moment later (NixieHomeFocus).
    val nixieReturns = rememberReturns { lastFocus }
    LaunchedEffect(hasContent, nixieReturns.count) {
        // Nixie (tv-home-two-rows): the top bar's Home came back to this Home (nixieBackToHome): what it shows stays,
        // the focus goes to the bar as on an arrival, the rows start at their first card, and only they are asked again.
        if (takeFocusNav()) {
            navArrival = true
            toTop = true
            launch {
                continueRow.scrollToItem(0)
                upNextRow.scrollToItem(0)
            }
            viewModel.nixieBackHere()
        }
        if (!hasContent) return@LaunchedEffect
        if (exitAsked) return@LaunchedEffect // Nixie (TV-S10): rows coming in never take the Quit dialog's focus
        if (navArrival) {
            // The loader's key block is gone with the content; the focus goes to the bar's entry for this page.
            withFrameNanos { }
            runCatching { navFocus.requestFocus() }
            return@LaunchedEffect
        }
        if (!initialFocusDone) {
            // Nixie (tv-home-two-rows): the first card (Continue watching's, else Up next's), else the bar until one
            // comes. Wait one frame so the rows are attached before focusing them.
            withFrameNanos { }
            initialFocusDone = true
            nixieMayFocus = !hasCards
            if (!hasCards || !requestFocusWhenReady({ listFocus })) runCatching { navFocus.requestFocus() }
            return@LaunchedEffect
        }
        val back = nixieReturns.leftFrom.takeIf { nixieReturns.count > 0 } ?: lastFocus
        val restored = requestFocusWhenReady({ back?.let { requesters[it] } })
        if (!restored && hasCards) runCatching { listFocus.requestFocus() }
    }
    // Nixie (tv-home-two-rows): cards that come after the first focus went to the bar take it, while no key was pressed.
    LaunchedEffect(hasCards) {
        val elsewhere = exitAsked || navArrival
        if (!hasCards || !nixieMayFocus || elsewhere) return@LaunchedEffect
        withFrameNanos { }
        if (!nixieMayFocus) return@LaunchedEffect
        nixieMayFocus = false
        requestFocusWhenReady({ listFocus })
    }
    // A dismissed PIN dialog gives focus back to the card that asked for it.
    var pinWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pinPrompt == null) {
        if (pinPrompt == null && pinWasOpen) {
            withFrameNanos { }
            lastFocus?.let { requesters[it] }?.let { runCatching { it.requestFocus() } }
        }
        pinWasOpen = pinPrompt != null
    }

    // A profile new on this device: the settings of another device first, before any update news.
    val restoreOffer by restore.offer.collectAsStateWithLifecycle()
    val restoreShown = restoreOffer?.takeIf { hasContent && pinPrompt == null }

    // A new version found at start: shown once, a moment after the home screen has settled.
    val updatePrompt by updates.updates.prompt.collectAsStateWithLifecycle()
    var updateShown by remember { mutableStateOf<UpdateCandidate?>(null) }
    val toaster = LocalToaster.current
    val laterText = stringResource(R.string.update_later_toast)
    LaunchedEffect(updatePrompt, hasContent, pinPrompt == null, restoreOffer == null) {
        val candidate = updatePrompt ?: return@LaunchedEffect
        if (!hasContent || pinPrompt != null || restoreOffer != null) return@LaunchedEffect
        delay(1200)
        updateShown = candidate
        updates.updates.promptShown()
    }
    // The store builds' counterpart: the changes of a version the store installed, shown the same way.
    val whatsNewPrompt by updates.whatsNew.prompt.collectAsStateWithLifecycle()
    var whatsNewShown by remember { mutableStateOf<WhatsNewPrompt?>(null) }
    LaunchedEffect(whatsNewPrompt, hasContent, pinPrompt == null, restoreOffer == null) {
        val prompt = whatsNewPrompt ?: return@LaunchedEffect
        if (!hasContent || pinPrompt != null || restoreOffer != null) return@LaunchedEffect
        delay(1200)
        whatsNewShown = prompt
        updates.whatsNew.promptShown()
    }
    val dialogOpen = updateShown != null || whatsNewShown != null || restoreShown != null
    var updateWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) {
        if (!dialogOpen && updateWasOpen) {
            withFrameNanos { }
            val restored = lastFocus?.let { requesters[it] }?.let {
                runCatching { it.requestFocus() }.getOrDefault(false)
            } == true
            if (!restored) runCatching { if (hasCards) listFocus.requestFocus() else navFocus.requestFocus() }
        }
        updateWasOpen = dialogOpen
    }

    BackHandler(enabled = !exitAsked) { exitAsked = true }
    // Back among the rows goes up to the navigation bar, so the page shows where it is.
    var inList by remember { mutableStateOf(false) }
    BackHandler(enabled = !exitAsked && inList) {
        toTop = true
        runCatching { navFocus.requestFocus() }
        scope.launch {
            // The rows would stay shifted sideways otherwise.
            continueRow.scrollToItem(0)
            upNextRow.scrollToItem(0)
        }
    }
    var exitWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(exitAsked) {
        if (!exitAsked && exitWasOpen) {
            withFrameNanos { }
            val restored =
                !toTop && lastFocus?.let { requesters[it] }?.let {
                    runCatching { it.requestFocus() }.getOrDefault(false)
                } == true
            if (!restored) runCatching { navFocus.requestFocus() }
        }
        exitWasOpen = exitAsked
    }

    // A loader covers the page: the remote must not reach the navigation or anything else behind it.
    Box(
        Modifier
            .fillMaxSize()
            // Nixie (TV-S10): "Quit Nixie?" owns the remote from Back on, before its Quit has the focus.
            .nixieExitKeys(asked = { exitAsked }, inDialog = { exitFocused }, quit = { nixieActivity?.finish() })
            // Nixie (TV-S10): not while "Quit Nixie?" is open, whose buttons work whatever Home is doing.
            .blockKeysWhile(state.loading && state.content == null && !exitAsked, takeFocus = true)
            // Nixie (tv-home-two-rows): a key pressed keeps the focus where the user has it (nixieMayFocus).
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) nixieMayFocus = false
                false
            },
    ) {
        val content = state.content
        when {
            // Nixie (tv-1.0-fixes): no rows of a profile that is gone while Home gives way.
            state.nixieSignedOut -> Unit

            state.loading && content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SpinningDiamond(110)
            }

            state.failed && content == null -> ErrorState(onRetry = viewModel::load)

            // Nixie (tv-home-two-rows): Continue watching, then Up next, on one page under the hero (NixieHomePage), in
            // place of Glacier's scrolling spotlight and rows.
            content != null -> NixieHomePage(
                continueWatching = content.continueWatching,
                upNext = content.upNext,
                continueWatchingIn = state.nixieContinueWatchingIn,
                upNextIn = state.nixieUpNextIn,
                onOpen = viewModel::openContinueWatching,
                onAskRemove = viewModel::nixieAskRemove,
                onUndo = { item ->
                    viewModel.nixieRemovals.undo(item.id)
                    // The card comes back in the Undo card's place and takes its focus.
                    nixieHandoff.give("continue-${item.id}")
                },
                rowsModifier = Modifier
                    .onFocusChanged {
                        inList = it.hasFocus
                        if (it.hasFocus) {
                            toTop = false
                            navArrival = false
                        }
                    }
                    .focusRequester(listFocus)
                    .then(if (toTop) Modifier else Modifier.focusRestorer()),
                pending = nixiePending.keys,
                continueRow = continueRow,
                upNextRow = upNextRow,
                rowsFocus = nixieRowsFocus,
                onContinueFocus = { inContinue = it },
                cardModifier = { key -> Modifier.remembered(key) },
            )
        }

        TopNav(
            active = NavTarget.Home,
            kinds = LocalLibraryKinds.current,
            userName = state.userName,
            onSelect = viewModel::onNav,
            // Nixie (tv-home-two-rows): nowhere to go down to without a card.
            down = listFocus.takeIf { hasCards },
            focusRequester = navFocus,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 34.dp),
        )

        if (exitAsked) {
            val activity = LocalActivity.current
            ExitDialog(
                onCancel = {
                    exitAsked = false
                    exitFocused = false // Nixie (TV-S10)
                },
                onQuit = { activity?.finish() },
                onFocus = { exitFocused = it }, // Nixie (TV-S10)
            )
        }

        pinPrompt?.let { PinDialog(it, onKey = viewModel.pin::key, onDismiss = viewModel.pin::dismiss) }
        // Nixie: holding OK on a Continue watching card.
        NixieRemoveSheetHost(
            nixieRemoveAsk,
            onRemove = viewModel::nixieConfirmRemove,
            onCancel = { viewModel.nixieAskRemove(null) },
            // The card, or the Undo card in its place, takes the focus back.
            returnTo = { requesters["continue-${it.id}"] },
        )

        val installed = updates.updates.installed
        if (installed != null) {
            updateShown?.let { candidate ->
                UpdateDialog(
                    candidate,
                    installed,
                    onNow = {
                        updateShown = null
                        updates.updates.download(install = true)
                    },
                    onLater = {
                        updateShown = null
                        toaster.show(laterText)
                    },
                )
            }
        }
        whatsNewShown?.let { WhatsNewDialog(it, onClose = { whatsNewShown = null }) }
        restoreShown?.let { RestoreOfferDialog(it, onAnswer = restore::answer) }
    }
}

/** A Continue watching card's second line: the episode and the minutes left, or the minutes left, or the year. */
@Composable
internal fun continueSubtitle(item: MediaItem): String { // Nixie (tv-home-two-rows): internal, for NixieHomePage
    val season = item.seasonNumber
    val episode = item.episodeNumber
    val left = item.remainingMinutes
    return when {
        item.kind == ItemKind.Episode && season != null && episode != null && left != null ->
            stringResource(R.string.episode_minutes_left, season, episode, left)

        item.kind == ItemKind.Episode && season != null && episode != null ->
            listOfNotNull(stringResource(R.string.episode_short, season, episode), item.title).joinToString(" · ")

        left != null -> stringResource(R.string.minutes_left, left)

        else -> yearText(item).orEmpty()
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.home_error), style = GlacierText.display(30), color = GlacierColors.Ice)
        PillButton(
            stringResource(R.string.action_retry),
            onClick = onRetry,
            primary = true,
            modifier = Modifier.focusRequester(focus),
        )
    }
}

/**
 * "Quit Glacier?" on Back from the home screen. Quit is the primary action and has the
 * focus, so Back then OK leaves; a second Back closes the dialog instead.
 */
@Composable
private fun ExitDialog(
    onCancel: () -> Unit,
    onQuit: () -> Unit,
    onFocus: (Boolean) -> Unit = {}, // Nixie (TV-S10)
) {
    ModalSheet(onDismiss = onCancel) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_exit_title), style = GlacierText.display(28), color = GlacierColors.Ice)
            Text(stringResource(R.string.home_exit_body), style = GlacierText.body(19), color = GlacierColors.Mist)
        }
        val quitFocus = remember { FocusRequester() }
        // Nixie (TV-S10): tells Home when the focus is in the dialog (nixieExitKeys).
        Row(Modifier.onFocusChanged { onFocus(it.hasFocus) }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PillButton(stringResource(R.string.action_cancel), onClick = onCancel)
            PillButton(
                stringResource(R.string.home_exit_confirm),
                onClick = onQuit,
                primary = true,
                modifier = Modifier.focusRequester(quitFocus),
            )
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { quitFocus.requestFocus() }
        }
    }
}
