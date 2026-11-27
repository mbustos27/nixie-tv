// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** tv-1.0.1: Settings › System's install link, only in the build that installs from it, and the same as the release's. */
class InstallLinkTest {
    @Test
    fun `only the GitHub build shows the install link`() {
        assertTrue(InstallLink.shownIn(""))
        assertFalse(InstallLink.shownIn("amazon"))
        assertFalse(InstallLink.shownIn("play"))
    }

    @Test
    fun `the link and the Downloader code are the ones the release notes give`() {
        val changelog = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "CHANGELOG.md") }
            .first { it.isFile }
            .readText()
        // The newest version's Install section (the header names "### Install" in a sentence first).
        val install = changelog.replace("\r\n", "\n").substringAfter("\n### Install\n").substringBefore("\n### ")
        assertTrue(install, InstallLink.URL in install)
        assertTrue(install, InstallLink.DOWNLOADER.substringAfterLast(' ') in install)
    }
}
