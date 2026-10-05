package com.studynotes.app.pdf

/**
 * Holds the single page a renderer may have open. Repeated access to the same page (detail tiles,
 * text selection) reuses it instead of re-parsing; switching pages closes the old one first,
 * because the legacy PdfRenderer throws "Current page not closed" otherwise.
 *
 * Not thread-safe: confined to the handle's renderer thread.
 */
internal class OpenPageSlot<P : AutoCloseable>(private val openPage: (index: Int) -> P) {
    private var index = -1
    private var page: P? = null

    /** The open page [index], opening it (and closing any other open page) if needed. */
    operator fun get(index: Int): P {
        val current = page
        if (current != null && this.index == index) return current
        clear()
        val opened = openPage(index)
        page = opened
        this.index = index
        return opened
    }

    /** Closes the open page, if any. Safe to call repeatedly. */
    fun clear() {
        val current = page ?: return
        // Forget the page before closing it so a failing close() can't leave a dead page cached.
        page = null
        index = -1
        current.close()
    }
}
