// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import coil3.EventListener
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Tells once when a picture of one of a few titles has loaded, and from where ("network", "disk", "memory"). */
interface NixiePictureWatch {
    /** Calls [seen] once when a picture of one of [ids] has loaded; replaces the watch before. */
    fun watch(ids: Collection<UUID>, seen: (source: String) -> Unit)

    /** TV-S13: calls [done] once every one of [ids] has its picture (loaded or failed); replaces the watch before. */
    fun watchAll(ids: Collection<UUID>, done: () -> Unit) = Unit

    fun stop()
}

/**
 * TV-S1 (DESIGN-RULES 10): Coil's event listener (GlacierApplication's image loader) watching for the first picture of
 * a library's first rows, for the detailed log's "first picture" and so background work waits for it. One watch at a
 * time; any picture whose address names one of the titles counts.
 */
object NixieImageWatch : EventListener(), NixiePictureWatch {
    private class Watch(val ids: List<String>, val seen: (String) -> Unit)

    private val current = AtomicReference<Watch?>(null)

    override fun watch(ids: Collection<UUID>, seen: (source: String) -> Unit) {
        // The address names an id with or without its dashes, depending on who wrote it.
        current.set(Watch(ids.flatMap { listOf(it.toString(), it.toString().replace("-", "")) }, seen))
    }

    override fun stop() {
        current.set(null)
        row.set(null)
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        rowSeen(request)
        val watch = current.get() ?: return
        val url = request.data as? String ?: return
        if (watch.ids.none { url.contains(it, ignoreCase = true) }) return
        if (current.compareAndSet(watch, null)) watch.seen(result.dataSource.name.lowercase())
    }

    // TV-S13: the first row's pictures, all of them (a failed one counts: it is shown as failed).

    private class RowWatch(val left: MutableSet<UUID>, val done: () -> Unit)

    private val row = AtomicReference<RowWatch?>(null)

    override fun watchAll(ids: Collection<UUID>, done: () -> Unit) {
        row.set(if (ids.isEmpty()) null else RowWatch(ids.toMutableSet(), done))
        if (ids.isEmpty()) done()
    }

    override fun onError(request: ImageRequest, result: ErrorResult) = rowSeen(request)

    private fun rowSeen(request: ImageRequest) {
        val watch = row.get() ?: return
        val url = request.data as? String ?: return
        val finished = synchronized(watch) {
            watch.left.removeAll { id ->
                url.contains(id.toString(), ignoreCase = true) || url.contains(id.toString().replace("-", ""), true)
            }
            watch.left.isEmpty()
        }
        if (finished && row.compareAndSet(watch, null)) watch.done()
    }
}
