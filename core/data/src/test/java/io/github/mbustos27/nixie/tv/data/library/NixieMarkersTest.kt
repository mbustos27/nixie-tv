// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.library

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review: every Nixie change inside Glacier's LibraryRepository carries a `// Nixie` mark (on its line or just
 * above it), so an upstream merge shows what is Nixie's. DIVERGENCE.md lists them.
 */
class NixieMarkersTest {

    private val repository = File(SOURCES, "io/github/glacier_jellyfin/androidtv/core/data/media/LibraryRepository.kt")

    /** Code that only Nixie's changes have, each to be marked. */
    private val nixieCode = listOf(
        "index(session, query, fresh, waited)",
        "fetchIndex(session, query, waitedFor = waited)",
        "keepIndex(key, Cached(key, newest.at, newest.entries))",
        "keepIndex(key, Cached(key, now, it))",
        "val pageSize = if (waitedFor)",
        "val counted = waitedFor && total > pageSize",
        "(pageSize until total step pageSize)",
        "                limit = pageSize,",
        "withTotal = false,",
        "val sortBy = serverSort.by",
        "val order = serverSort.order",
        "imageTypeLimit = 1.takeIf",
        "enableTotalRecordCount = withTotal",
        "if (serverOrder) return@withContext serverPage",
        "nameLessThan = nameLessThan",
        "nameStartsWithOrGreater = nameFrom",
        "IDS_PER_REQUEST = NixieLibraryPaging.IDS_PER_REQUEST",
        "letter != null -> null",
        "fromLetter?.let { letter ->",
    )

    @Test
    fun `every Nixie change in LibraryRepository is marked`() {
        val lines = repository.readLines()
        val unmarked = nixieCode.flatMap { code ->
            val at = lines.indices.filter { code in lines[it] }
            assertTrue("\"$code\" is no longer in LibraryRepository: update this list", at.isNotEmpty())
            at.filter { line -> (maxOf(0, line - 3)..line).none { "Nixie" in lines[it] } }.map { "${it + 1}: $code" }
        }
        assertTrue("unmarked: $unmarked", unmarked.isEmpty())
    }

    private companion object {
        /** The module's sources, from where its unit tests run. */
        const val SOURCES = "src/main/java"
    }
}
