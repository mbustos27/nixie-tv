// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.diagnostics.QrImage
import java.net.URLEncoder

/*
 * tv-1.0-r1 (decided 2026-11-22 for TV 1.0; docs/PUBLISH-CHECKLIST.md item 6): Settings › System › Diagnostics ›
 * Report a problem. A QR code opens the public repository's Report a problem form (`.github/ISSUE_TEMPLATE/bug.yml`) on
 * a phone, with this app's and the server's version numbers filled in. The TV sends nothing: the phone opens the page
 * and the user sends the report there. The log goes with it the way it always does: Download log on the network (the
 * anonymised log, saved on the phone), then dragged into the form's log field.
 */

/** What Report a problem's page shows: the form's [url] and the two versions it fills in. */
data class NixieReport(
    val url: String,
    val appVersion: String?,
    val serverVersion: String?,
)

object NixieReportProblem {
    /**
     * The public repository's new-issue address. The repository (mbustos27/nixie-tv) is created at 1.0, so until then
     * the code leads to GitHub's "not found"; PUBLISH-CHECKLIST item 28 checks it once the repository is up.
     */
    const val FORM = "https://github.com/mbustos27/nixie-tv/issues/new"

    /** The Report a problem form's file in `.github/ISSUE_TEMPLATE`. */
    const val TEMPLATE = "bug.yml"

    /** The address to type where no camera can scan: the repository's issues, whose New issue offers the form. */
    const val TYPED = "github.com/mbustos27/nixie-tv/issues"

    /** The form's fields the code fills in, by their ids in bug.yml. */
    const val APP_FIELD = "version"
    const val SERVER_FIELD = "jellyfin"

    /** The report for [appVersion] (this build) and [serverVersion] (the one Settings › System shows, if known). */
    fun report(appVersion: String?, serverVersion: String?): NixieReport {
        val app = version(appVersion)
        val server = version(serverVersion)
        return NixieReport(url(app, server), app, server)
    }

    /**
     * The form's address with the versions filled in. Only a version number goes into it (see [version]): the form is
     * public, and nothing else about the TV, the server or its user belongs there.
     */
    fun url(appVersion: String?, serverVersion: String?): String = buildString {
        append(FORM).append("?template=").append(TEMPLATE)
        version(appVersion)?.let { append('&').append(APP_FIELD).append('=').append(encode(it)) }
        version(serverVersion)?.let { append('&').append(SERVER_FIELD).append('=').append(encode(it)) }
    }

    /**
     * [text] when it is a version number ("12.4.0", "1.0.1-beta.2": two or three numbers with dots, then perhaps a "-"
     * or "+" and a short suffix), else null: never an address (four numbers), a name or a token.
     */
    fun version(text: String?): String? = text?.trim()?.takeIf { VERSION.matches(it) }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private val VERSION = Regex("\\d{1,4}(\\.\\d{1,6}){1,2}([-+][0-9A-Za-z.+-]{1,24})?")
}

/** The test tags of Report a problem's page. */
object NixieReportTags {
    const val PAGE = "nixie-report"
    const val CODE = "nixie-report-code"
}

/**
 * Report a problem's page, laid out like Download log's: the steps on the left, the code on a glass panel on the right,
 * Close at the bottom with the focus. Back closes it.
 */
@Composable
fun NixieReportScreen(report: NixieReport, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val closeFocus = remember { FocusRequester() }
    GlacierBackground(
        Modifier
            .testTag(NixieReportTags.PAGE)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 130.dp, end = 130.dp, top = 90.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(100.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(36.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(R.string.settings_group_diagnostics).uppercase(),
                            style = GlacierText.label(18, 0.08),
                            color = LocalAccent.current.main,
                        )
                        Text(
                            stringResource(R.string.nixie_report),
                            style = GlacierText.display(64),
                            color = GlacierColors.Ice,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Step(1, stringResource(R.string.nixie_report_step_scan))
                        Step(2, stringResource(R.string.nixie_report_step_say))
                        Step(3, stringResource(R.string.nixie_report_step_log))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(GlacierColors.GlassBorder))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val unknown = stringResource(R.string.nixie_report_unknown)
                        Fact(stringResource(R.string.nixie_report_app), report.appVersion ?: unknown)
                        Fact(stringResource(R.string.nixie_report_server), report.serverVersion ?: unknown)
                    }
                }
                CodePanel(report.url, Modifier.width(620.dp).fillMaxHeight())
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.nixie_report_hint),
                    style = GlacierText.body(17),
                    color = GlacierColors.Mist,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    stringResource(R.string.diag_share_close),
                    onClick = onClose,
                    primary = true,
                    height = 68,
                    width = 260,
                    modifier = Modifier.focusRequester(closeFocus),
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
}

@Composable
private fun CodePanel(url: String, modifier: Modifier) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    Column(
        modifier
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, shape)
            .padding(44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.diag_share_scan), style = GlacierText.display(30), color = GlacierColors.Ice)
        QrImage(
            url,
            Modifier.testTag(NixieReportTags.CODE).size(400.dp).clip(RoundedCornerShape(GlacierShapes.RadiusMd)),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diag_share_or_open), style = GlacierText.body(17), color = GlacierColors.Mist)
            Text(
                NixieReportProblem.TYPED,
                style = GlacierText.mono(18),
                color = GlacierColors.Ice,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A numbered step: the number in an accent ring, the text beside it (as on Download log's page). */
@Composable
private fun Step(number: Int, text: String) {
    val accent = LocalAccent.current.main
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = GlacierText.mono(20).copy(fontWeight = FontWeight.SemiBold), color = accent)
        }
        Text(text, style = GlacierText.body(21), color = GlacierColors.Ice, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(label, style = GlacierText.body(19), color = GlacierColors.Mist, modifier = Modifier.weight(1f))
        Text(value, style = GlacierText.mono(19), color = GlacierColors.Ice)
    }
}
