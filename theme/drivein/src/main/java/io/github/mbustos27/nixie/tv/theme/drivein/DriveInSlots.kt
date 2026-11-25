// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.drivein

import io.github.mbustos27.nixie.tv.designsystem.NixieSlots

/**
 * Drive-in's layout slots for phase 1: the page ground (night sky), sign-in (the lot at dusk, Quick Connect on nixie
 * tubes), the top bar (the sky bar with marquee bulbs, an underline for the page you are on) and Home (the outdoor
 * screen at dusk as the hero, marquee row headers).
 */
val DriveInSlots = NixieSlots(
    pageGround = { with(Dusk) { ground() } },
    topBarChrome = { driveInBar() },
    topBarEntry = ::driveInEntry,
    rowHeader = { title, subtitle, modifier -> DriveInRowHeader(title, subtitle, modifier) },
    heroBackdrop = { imageUrl, artwork -> DriveInHero(imageUrl, artwork) },
    quickConnectCode = { code, length -> DriveInQuickConnectCode(code, length) },
    setupBackdrop = { DriveInLot() },
    browse = DriveInBrowse,
    item = DriveInItem,
    player = DriveInPlayer,
)
