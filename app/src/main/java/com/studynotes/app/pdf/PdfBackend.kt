package com.studynotes.app.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension

/**
 * The platform renderer behind a [PdfDocumentHandle]. Implementations are not thread-safe; the
 * handle confines every call to its renderer thread and keeps at most one page open.
 */
internal interface PdfBackend : AutoCloseable {
    val pageCount: Int

    /** True when [BackendPage.textLayout] can describe a page's text. */
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
     * The page's text as lines of measured words (page points of the displayed page). Only called
     * when [PdfBackend.supportsTextSelection] is true; empty when the page has no text.
     */
    fun textLayout(): PageTextLayout

    /**
     * The page's text as the engine extracts it; the words of [textLayout] index into it. One
     * quick engine call (no words are measured), so a whole document can be searched. Only called
     * when [PdfBackend.supportsTextSelection] is true; empty when the page has no text.
     */
    fun text(): String

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

        override fun textLayout(): PageTextLayout = PageTextLayout(emptyList())

        override fun text(): String = ""

        override fun close() = page.close()
    }
}

/**
 * android.graphics.pdf.PdfRendererPreV: also draws highlight and comment annotations made in other
 * apps, and supports text selection (used by "Snap to text"). Takes ownership of [descriptor].
 */
@RequiresExtension(extension = Build.VERSION_CODES.S, version = PdfBackend.MODERN_EXTENSION_VERSION)
internal class ModernBackend(descriptor: ParcelFileDescriptor) : PdfBackend {
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

        /**
         * Measures every word on the page. PDFium reports no character geometry directly, but a
         * selection by character index (stop exclusive) returns the bounds of exactly that text,
         * so each word is selected once by its place in the page text and its box kept. The page
         * text splits into the engine's lines at its line breaks; words are then grouped into lines
         * by their boxes, so a line the engine did not split still comes out as two.
         */
        override fun text(): String = page.textContents.joinToString("") { it.text }

        override fun textLayout(): PageTextLayout {
            val text = text()
            val engineLines = ArrayList<List<TextWord>>()
            for (range in TextLayoutBuilder.lineRanges(text)) {
                val words = ArrayList<TextWord>()
                for (w in TextLayoutBuilder.wordRanges(text, range)) {
                    val box = boxOf(w.first, w.last + 1) ?: continue
                    words += TextWord(box, w.first, w.last + 1)
                }
                if (words.isNotEmpty()) engineLines += words
            }
            // Lines of several words decide whether the text runs across or down the page.
            var vote = 0
            for (words in engineLines) {
                val box = words.map { it.box }.reduce(TextBox::union)
                vote += if (box.height > box.width) words.size else -words.size
            }
            val vertical = vote > 0
            return PageTextLayout(engineLines.flatMap { TextLayoutBuilder.linesOf(it, vertical) })
        }

        /** The box (displayed page points) of the text from character [from] until [until], or null when it has none. */
        private fun boxOf(from: Int, until: Int): TextBox? = try {
            page.selectContent(SelectionBoundary(from), SelectionBoundary(until))
                ?.selectedTextContents
                ?.flatMap { it.bounds }
                ?.map { TextBox(it.left, it.top, it.right, it.bottom) }
                ?.filter { it.isValid }
                ?.takeIf { it.isNotEmpty() }
                ?.reduce(TextBox::union)
        } catch (e: RuntimeException) {
            null
        }

        override fun close() = page.close()
    }
}
