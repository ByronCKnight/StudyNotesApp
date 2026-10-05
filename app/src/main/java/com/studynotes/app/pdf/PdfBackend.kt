package com.studynotes.app.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.graphics.pdf.models.selection.PageSelection
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
import kotlin.math.abs

/**
 * The platform renderer behind a [PdfDocumentHandle]. Implementations are not thread-safe; the
 * handle confines every call to its renderer thread and keeps at most one page open.
 */
internal interface PdfBackend : AutoCloseable {
    val pageCount: Int

    /** True when [BackendPage.selectText] is implemented. */
    val supportsTextSelection: Boolean

    /** Opens page [index]. Any previously opened page must be closed first. */
    fun openPage(index: Int): BackendPage

    override fun close()

    companion object {
        /**
         * True when PdfRendererPreV exists: built into API 35+, and delivered to API 31–34 devices
         * (e.g. the Lenovo Idea Tab Pro on Android 14) by the S extension 13 mainline update.
         */
        val isModernAvailable: Boolean by lazy {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= MODERN_EXTENSION_VERSION)
        }

        const val MODERN_EXTENSION_VERSION = 13
    }
}

/** An open page. Sizes are whole PDF points of the crop box with /Rotate applied. */
internal interface BackendPage : AutoCloseable {
    val width: Int
    val height: Int

    /** Renders into [bitmap] (mutable ARGB_8888) with [transform] mapping page points to bitmap pixels. */
    fun render(bitmap: Bitmap, transform: Matrix)

    /**
     * Raw bounds (page points) of the text selected between two page-point positions; empty when
     * nothing is selected. Only called when [PdfBackend.supportsTextSelection] is true.
     */
    fun selectText(startX: Int, startY: Int, stopX: Int, stopY: Int): List<TextBox>

    override fun close()
}

/** android.graphics.pdf.PdfRenderer: available everywhere, render only. Takes ownership of [descriptor]. */
internal class LegacyBackend(descriptor: ParcelFileDescriptor) : PdfBackend {
    private val renderer = PdfRenderer(descriptor)

    override val pageCount: Int get() = renderer.pageCount
    override val supportsTextSelection: Boolean get() = false

    override fun openPage(index: Int): BackendPage = Page(renderer.openPage(index))

    override fun close() = renderer.close()

    private class Page(private val page: PdfRenderer.Page) : BackendPage {
        override val width: Int get() = page.width
        override val height: Int get() = page.height

        override fun render(bitmap: Bitmap, transform: Matrix) =
            page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        override fun selectText(startX: Int, startY: Int, stopX: Int, stopY: Int): List<TextBox> = emptyList()

        override fun close() = page.close()
    }
}

/**
 * android.graphics.pdf.PdfRendererPreV: also draws highlight and comment annotations made in other
 * apps, and supports text selection (used by "Snap to text"). Takes ownership of [descriptor].
 */
@RequiresExtension(extension = Build.VERSION_CODES.S, version = PdfBackend.MODERN_EXTENSION_VERSION)
internal class ModernBackend(descriptor: ParcelFileDescriptor) : PdfBackend {
    private companion object {
        /**
         * Places checked when the selected text appears more than once on the page (a fragment like
         * "he" can occur dozens of times); each check is one quick selection by index.
         */
        const val MAX_CANDIDATES = 160
    }

    // Built before the renderer so a failure here cannot leave an open renderer behind.
    private val renderParams = RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY)
        .setRenderFlags(RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS or RenderParams.FLAG_RENDER_TEXT_ANNOTATIONS)
        .build()
    private val renderer = PdfRendererPreV(descriptor)

    override val pageCount: Int get() = renderer.pageCount
    override val supportsTextSelection: Boolean get() = true

    override fun openPage(index: Int): BackendPage = Page(renderer.openPage(index), renderParams)

    override fun close() = renderer.close()

    private class Page(private val page: PdfRendererPreV.Page, private val params: RenderParams) : BackendPage {
        override val width: Int get() = page.width
        override val height: Int get() = page.height

        override fun render(bitmap: Bitmap, transform: Matrix) = page.render(bitmap, null, transform, params)

        /** The page's text, which PDFium's character indices refer to; read on first use. */
        private var pageText: PageText? = null

        override fun selectText(startX: Int, startY: Int, stopX: Int, stopY: Int): List<TextBox> {
            val selection = page.selectContent(
                SelectionBoundary(Point(startX, startY)),
                SelectionBoundary(Point(stopX, stopY)),
            )?.takeIf { it.hasBounds() }
            val chosen = if (selection != null) {
                toWholeWords(selection) ?: selection
            } else {
                // A short stroke inside one word can select nothing (both ends round to the same
                // letter edge): take the word under its middle.
                wordAt((startX + stopX) / 2, (startY + stopY) / 2) ?: return emptyList()
            }
            return chosen.selectedTextContents
                .flatMap { it.bounds }
                .map { TextBox(it.left, it.top, it.right, it.bottom) }
        }

        /** The word at a page point (PDFium selects it when both boundaries are that point). */
        private fun wordAt(x: Int, y: Int): PageSelection? = try {
            page.selectContent(SelectionBoundary(Point(x, y)), SelectionBoundary(Point(x, y)))?.takeIf { it.hasBounds() }
        } catch (e: RuntimeException) {
            null
        }

        private fun PageSelection.hasBounds(): Boolean = selectedTextContents.any { it.bounds.isNotEmpty() }

        /**
         * [selection] grown to whole words ([PageText]); null to keep it as it is (already whole
         * words, or its place in the page text can't be pinned down).
         */
        private fun toWholeWords(selection: PageSelection): PageSelection? = try {
            val text = pageText ?: PageText(page.textContents.joinToString("") { it.text }).also { pageText = it }
            val bounds = selection.selectedTextContents.flatMap { it.bounds }
            val selected = selection.selectedTextContents.joinToString("") { it.text }
            // A stroke ending in the gap after a word selects that space too; it counts for the bounds.
            val spaceBefore = selected.firstOrNull()?.isWhitespace() == true
            val spaceAfter = selected.lastOrNull()?.isWhitespace() == true
            // The same words can appear several times on a page: the right place has the same bounds.
            val candidates = text.occurrences(selected, MAX_CANDIDATES)
                ?: return@toWholeWords byWordsAtEnds(bounds, spaceBefore, spaceAfter)
            val place = candidates.firstOrNull { (from, until) ->
                val (checkFrom, checkUntil) = text.withAdjacentWhitespace(from, until, spaceBefore, spaceAfter)
                val candidate = page.selectContent(SelectionBoundary(checkFrom), SelectionBoundary(checkUntil))
                candidate != null && sameArea(candidate.selectedTextContents.flatMap { it.bounds }, bounds)
            }
            place?.let { (from, until) ->
                // Whole words, and never the stray space at either end.
                text.wholeWords(from, until) ?: (from to until).takeIf { spaceBefore || spaceAfter }
            }?.let { (from, until) ->
                page.selectContent(SelectionBoundary(from), SelectionBoundary(until))?.takeIf { it.hasBounds() }
            }
        } catch (e: RuntimeException) {
            null
        }

        /**
         * For a fragment too common to place by its text (a letter or two): reselect from just inside
         * the word under its first character to just inside the word under its last. Horizontal text
         * only; an end that is a space is left as it is.
         */
        private fun byWordsAtEnds(bounds: List<RectF>, spaceBefore: Boolean, spaceAfter: Boolean): PageSelection? {
            val first = bounds.firstOrNull() ?: return null
            val last = bounds.last()
            val startY = first.centerY().toInt()
            val stopY = last.centerY().toInt()
            val startWord = if (spaceBefore) null else wordAt(first.left.toInt() + 1, startY)?.area()
            val stopWord = if (spaceAfter) null else wordAt(last.right.toInt() - 1, stopY)?.area()
            // Text running down or up the page: its words don't start and end at x edges.
            if (listOfNotNull(startWord, stopWord).any { it.height() > it.width() }) return null
            val startX = startWord?.left ?: first.left
            val stopX = stopWord?.right ?: last.right
            return page.selectContent(
                SelectionBoundary(Point(startX.toInt() + 1, startY)),
                SelectionBoundary(Point(stopX.toInt() - 1, stopY)),
            )?.takeIf { it.hasBounds() }
        }

        private fun PageSelection.area(): RectF? {
            val rects = selectedTextContents.flatMap { it.bounds }
            return rects.firstOrNull()?.let { RectF(it).apply { rects.forEach { r -> union(r) } } }
        }

        private fun sameArea(a: List<RectF>, b: List<RectF>): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            val ua = RectF(a[0]).apply { a.forEach { union(it) } }
            val ub = RectF(b[0]).apply { b.forEach { union(it) } }
            return abs(ua.left - ub.left) <= 1f && abs(ua.top - ub.top) <= 1f &&
                abs(ua.right - ub.right) <= 1f && abs(ua.bottom - ub.bottom) <= 1f
        }

        override fun close() = page.close()
    }
}
