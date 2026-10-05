package com.studynotes.app.pdf

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SizeF
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

class PdfOpenException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { NOT_FOUND, PASSWORD_PROTECTED, CORRUPT, IO }
}

/**
 * An open PDF backed by android.graphics.pdf.PdfRenderer.
 *
 * CONTRACT (frozen — other modules depend on these signatures):
 * - All PdfRenderer access is confined to ONE dedicated background thread owned by this handle
 *   (PdfRenderer is not thread-safe and allows one open page at a time). Every suspend function
 *   hops to that thread; callers may invoke them from any thread/coroutine. Work queued for a
 *   coroutine that was cancelled before it started must be skipped (cooperative cancellation).
 * - Page sizes are in PDF points as reported by PdfRenderer.Page.getWidth()/getHeight() (crop box,
 *   rotation applied). This is "page space" used by the annotation model.
 * - Rendered bitmaps are ARGB_8888 with an opaque WHITE background.
 * - Two backends: MODERN = android.graphics.pdf.PdfRendererPreV, used when SDK_INT >= 35 or
 *   (SDK_INT >= 31 && SdkExtensions.getExtensionVersion(S) >= 13); LEGACY = android.graphics.pdf.PdfRenderer.
 *   MODERN renders with RenderParams FLAG_RENDER_HIGHLIGHT_ANNOTATIONS | FLAG_RENDER_TEXT_ANNOTATIONS
 *   so highlights/comments made in other apps are visible, and supports text selection.
 * - The most recently used page stays open (closed when another page is needed or on close), so
 *   repeated tile renders / text selection on one page don't re-parse it.
 * - [close] is idempotent; after close, suspend calls throw IllegalStateException.
 *
 * Implementation notes: the renderer thread is a single worker that may retire after a period of
 * inactivity and be recreated on demand, so an idle (or leaked) handle holds no thread. Work is
 * still strictly serial. If MODERN fails to open a file for a reason other than a password, LEGACY
 * is tried before giving up, so a misbehaving mainline renderer can never make a PDF unopenable.
 */
class PdfDocumentHandle private constructor(
    /** The PDF this handle reads. */
    val file: File,
    private val executor: ExecutorService,
    private val dispatcher: CoroutineDispatcher,
    private val descriptor: ParcelFileDescriptor,
    private val backend: PdfBackend,
    private val dimensions: List<PageDimensions>,
) : Closeable {

    val pageCount: Int get() = dimensions.size

    /** Size of every page in page points, indexed by page. Computed once at open. */
    val pageSizes: List<SizeF> = dimensions.map { SizeF(it.width.toFloat(), it.height.toFloat()) }

    /** True when [selectTextRects] can return results on this device. */
    val supportsTextSelection: Boolean get() = backend.supportsTextSelection

    val isClosed: Boolean get() = closed.get()

    private val closed = AtomicBoolean(false)

    /** Renderer thread only. */
    private val openPage = OpenPageSlot(backend::openPage)

    /**
     * Renders the whole page into a new bitmap [widthPx] wide (height follows the page aspect ratio,
     * rounded, min 1px).
     *
     * @throws IllegalArgumentException for a page index out of range or a non-positive width.
     */
    suspend fun renderPage(pageIndex: Int, widthPx: Int): Bitmap {
        checkOpen()
        requirePageIndex(pageIndex)
        require(widthPx > 0) { "widthPx must be positive, was $widthPx" }
        return onRendererThread {
            val page = dimensions[pageIndex]
            val size = RenderGeometry.pageBitmapSize(widthPx, page)
            val transform = Matrix().apply {
                setScale(size.width.toFloat() / page.width, size.height.toFloat() / page.height)
            }
            renderIntoNewBitmap(pageIndex, size, transform)
        }
    }

    /**
     * Renders [region] (page points) of the page at [pxPerPt] pixels per point. The result is
     * ceil(region.width()*pxPerPt) x ceil(region.height()*pxPerPt) pixels. If [reuse] is non-null,
     * mutable, ARGB_8888 and exactly that size it is drawn into and returned; otherwise a new bitmap
     * is allocated. Used for sharp "detail" rendering when zoomed in.
     *
     * Float noise below 0.01 px is ignored when rounding up (a 512.00006 px wide tile is 512 px).
     * Parts of [region] outside the page are white. A region that is empty or doesn't overlap the
     * page at all yields a new 1x1 white bitmap (nothing to render).
     *
     * @throws IllegalArgumentException for a page index out of range or a non-positive [pxPerPt].
     */
    suspend fun renderRegion(pageIndex: Int, region: RectF, pxPerPt: Float, reuse: Bitmap? = null): Bitmap {
        checkOpen()
        requirePageIndex(pageIndex)
        require(pxPerPt.isFinite() && pxPerPt > 0f) { "pxPerPt must be positive, was $pxPerPt" }
        // Copied now: the caller owns the RectF and may reuse it while this call waits for the thread.
        val left = region.left
        val top = region.top
        val right = region.right
        val bottom = region.bottom
        return onRendererThread {
            val size = RenderGeometry.regionBitmapSize(left, top, right, bottom, pxPerPt, dimensions[pageIndex])
                ?: return@onRendererThread whiteBitmap(PixelSize(1, 1))
            val transform = Matrix().apply {
                setScale(pxPerPt, pxPerPt)
                postTranslate(-left * pxPerPt, -top * pxPerPt)
            }
            if (reuse != null && reuse.canBeReusedAs(size)) {
                renderInto(pageIndex, reuse, transform)
                reuse
            } else {
                renderIntoNewBitmap(pageIndex, size, transform)
            }
        }
    }

    /**
     * Text-snapping support: returns the bounding rectangles (page points, one per text line,
     * top-to-bottom) of the text a user would select by dragging from [start] to [end] (page points),
     * or null when text selection is unsupported, the page has no text there, or anything fails.
     * Never throws (except CancellationException).
     *
     * Also returns null for a page index out of range or after [close]. Positions are rounded to
     * whole points and clamped to the page; rectangles PDFium reports per font run are merged into
     * one per line.
     */
    suspend fun selectTextRects(pageIndex: Int, start: PointF, end: PointF): List<RectF>? {
        if (!backend.supportsTextSelection || pageIndex !in dimensions.indices || closed.get()) return null
        val startX = start.x
        val startY = start.y
        val endX = end.x
        val endY = end.y
        return try {
            withContext(dispatcher) {
                if (closed.get()) return@withContext null
                ensureActive()
                val page = dimensions[pageIndex]
                val boxes = openPage[pageIndex].selectText(
                    RenderGeometry.selectionCoordinate(startX, page.width) ?: return@withContext null,
                    RenderGeometry.selectionCoordinate(startY, page.height) ?: return@withContext null,
                    RenderGeometry.selectionCoordinate(endX, page.width) ?: return@withContext null,
                    RenderGeometry.selectionCoordinate(endY, page.height) ?: return@withContext null,
                )
                TextLineMerger.merge(boxes)
                    .takeIf { it.isNotEmpty() }
                    ?.map { RectF(it.left, it.top, it.right, it.bottom) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Text selection failed on page $pageIndex", t)
            null
        }
    }

    /**
     * Releases the renderer, the open page and the file descriptor. Idempotent and non-blocking, so
     * it is safe on the main thread even while a render is running: the release is queued behind
     * the work already on the renderer thread, and anything queued after it fails the closed check.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            executor.execute {
                releaseResources()
                executor.shutdown()
            }
        } catch (e: RejectedExecutionException) {
            // Only possible if the executor is already shut down, i.e. nothing can be running.
            releaseResources()
        }
    }

    private fun releaseResources() {
        runCatching { openPage.clear() }
        runCatching { backend.close() }
        // The renderer owns and closes the descriptor; closing it again is a harmless no-op that
        // guarantees the fd is released even if the renderer's close failed.
        runCatching { descriptor.close() }
    }

    private suspend fun <T> onRendererThread(block: () -> T): T = withContext(dispatcher) {
        checkOpen()
        ensureActive()
        block()
    }

    /** Renderer thread only. Allocates, renders, and frees the bitmap again if rendering fails. */
    private fun renderIntoNewBitmap(pageIndex: Int, size: PixelSize, transform: Matrix): Bitmap {
        val bitmap = createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            renderInto(pageIndex, bitmap, transform)
        } catch (t: Throwable) {
            bitmap.recycle()
            throw t
        }
        return bitmap
    }

    /** Renderer thread only. PdfRenderer paints no background, so the bitmap is cleared to white first. */
    private fun renderInto(pageIndex: Int, bitmap: Bitmap, transform: Matrix) {
        bitmap.eraseColor(Color.WHITE)
        openPage[pageIndex].render(bitmap, transform)
    }

    private fun checkOpen() = check(!closed.get()) { "PdfDocumentHandle for ${file.name} is closed" }

    private fun requirePageIndex(pageIndex: Int) =
        require(pageIndex in dimensions.indices) { "Page $pageIndex out of range 0..${dimensions.size - 1}" }

    companion object {
        private const val TAG = "PdfDocumentHandle"

        /** An idle renderer thread retires after this long; the next call starts a new one. */
        private const val THREAD_KEEP_ALIVE_SECONDS = 30L

        private val threadIds = AtomicInteger()

        /**
         * Opens [file] and measures every page. Throws [PdfOpenException] (NOT_FOUND, PASSWORD_PROTECTED,
         * CORRUPT, IO). Runs on the handle's own thread; safe to call from the main thread.
         * Measuring parses every page, so when [sizeCacheDir] is given the page-size list is cached
         * there (keyed by path + length + lastModified) and reused on later opens.
         *
         * Cancellation is honoured between pages while measuring; a cancelled open releases everything.
         */
        suspend fun open(file: File, sizeCacheDir: File? = null): PdfDocumentHandle {
            val executor = newRendererExecutor()
            val dispatcher = executor.asCoroutineDispatcher()
            val opened = AtomicReference<PdfDocumentHandle?>()
            try {
                return withContext(dispatcher) {
                    openOnRendererThread(file, sizeCacheDir, executor, dispatcher).also(opened::set)
                }
            } catch (t: Throwable) {
                // Either opening failed (its resources are already released) or the caller was
                // cancelled after the handle was built and withContext dropped it: don't leak it.
                val handle = opened.get()
                if (handle != null) handle.close() else executor.shutdown()
                throw t
            }
        }

        /**
         * Renders the top of page 1 of [file] for a preview without measuring the other pages (which
         * [open] does): [widthPx] wide, page 1's aspect ratio, but at most [maxHeightPx] tall (a
         * taller page is cut off at the bottom). ARGB_8888, mutable, opaque white background.
         * Returns the bitmap and page 1's size in page points (page space -> bitmap px is a scale of
         * bitmap.width / size.width). Opens and closes the file on its own temporary renderer thread.
         * Throws [PdfOpenException] like [open]; safe to call from the main thread.
         */
        suspend fun renderFirstPage(file: File, widthPx: Int, maxHeightPx: Int): Pair<Bitmap, SizeF> {
            require(widthPx > 0 && maxHeightPx > 0) { "Sizes must be positive, were $widthPx x $maxHeightPx" }
            val executor = newRendererExecutor()
            try {
                return withContext(executor.asCoroutineDispatcher()) {
                    if (!file.isFile || !file.canRead()) {
                        throw PdfOpenException(PdfOpenException.Reason.NOT_FOUND, "The file could not be found.")
                    }
                    val (descriptor, backend) = openBackend(file)
                    try {
                        if (backend.pageCount <= 0) throw corrupt(null)
                        ensureActive()
                        val page = try {
                            backend.openPage(0)
                        } catch (e: Exception) {
                            throw corrupt(e)
                        }
                        page.use {
                            val dimensions = PageDimensions(page.width.coerceAtLeast(1), page.height.coerceAtLeast(1))
                            val fullSize = RenderGeometry.pageBitmapSize(widthPx, dimensions)
                            // Same transform as renderPage; a cut-off page just has a shorter bitmap.
                            val transform = Matrix().apply {
                                setScale(fullSize.width.toFloat() / dimensions.width, fullSize.height.toFloat() / dimensions.height)
                            }
                            val bitmap = createBitmap(widthPx, fullSize.height.coerceAtMost(maxHeightPx), Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, transform)
                            } catch (t: Throwable) {
                                bitmap.recycle()
                                throw t
                            }
                            bitmap to SizeF(dimensions.width.toFloat(), dimensions.height.toFloat())
                        }
                    } finally {
                        runCatching { backend.close() }
                        runCatching { descriptor.close() }
                    }
                }
            } finally {
                executor.shutdown()
            }
        }

        private fun newRendererExecutor(): ExecutorService =
            ThreadPoolExecutor(
                1, 1, THREAD_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(),
            ) { runnable ->
                Thread(runnable, "PdfRenderer-${threadIds.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }.apply { allowCoreThreadTimeOut(true) }

        private fun CoroutineScope.openOnRendererThread(
            file: File,
            sizeCacheDir: File?,
            executor: ExecutorService,
            dispatcher: CoroutineDispatcher,
        ): PdfDocumentHandle {
            if (!file.isFile || !file.canRead()) {
                throw PdfOpenException(PdfOpenException.Reason.NOT_FOUND, "The file could not be found.")
            }
            // Stamped before reading: if the file changes while we measure, the next open re-measures.
            val stamp = FileStamp.of(file)
            val (descriptor, backend) = openBackend(file)
            try {
                val pageCount = backend.pageCount
                if (pageCount <= 0) throw corrupt(null)
                val cache = sizeCacheDir?.let { PageSizeCache(it) }
                val dimensions = cache?.read(file.absolutePath, stamp, pageCount)
                    ?: measurePages(backend, pageCount).also { measured ->
                        if (cache?.write(file.absolutePath, stamp, measured) == false) {
                            Log.w(TAG, "Could not cache page sizes in $sizeCacheDir")
                        }
                    }
                return PdfDocumentHandle(file, executor, dispatcher, descriptor, backend, dimensions)
            } catch (t: Throwable) {
                runCatching { backend.close() }
                runCatching { descriptor.close() }
                throw t
            }
        }

        /** Opens every page once to read its size; the slow part of opening a large uncached PDF. */
        private fun CoroutineScope.measurePages(backend: PdfBackend, pageCount: Int): List<PageDimensions> {
            val sizes = ArrayList<PageDimensions>(pageCount)
            for (index in 0 until pageCount) {
                ensureActive()
                try {
                    backend.openPage(index).use { page ->
                        // Degenerate (zero-size) pages are clamped so page-space maths never divides by zero.
                        sizes += PageDimensions(page.width.coerceAtLeast(1), page.height.coerceAtLeast(1))
                    }
                } catch (e: Exception) {
                    throw corrupt(e)
                }
            }
            return sizes
        }

        /**
         * Opens a descriptor and a backend for [file]: MODERN when available, falling back to LEGACY if
         * MODERN fails for any reason other than encryption. The returned backend owns the descriptor.
         */
        @SuppressLint("NewApi") // ModernBackend is only constructed when PdfBackend.isModernAvailable.
        private fun openBackend(file: File): Pair<ParcelFileDescriptor, PdfBackend> {
            if (PdfBackend.isModernAvailable) {
                val descriptor = openDescriptor(file)
                try {
                    return descriptor to ModernBackend(descriptor)
                } catch (e: SecurityException) {
                    descriptor.closeQuietly()
                    throw passwordProtected(e)
                } catch (e: Exception) {
                    descriptor.closeQuietly()
                    Log.w(TAG, "PdfRendererPreV could not open ${file.name}; retrying with PdfRenderer", e)
                } catch (e: LinkageError) {
                    descriptor.closeQuietly()
                    Log.w(TAG, "PdfRendererPreV unavailable despite the SDK extension check", e)
                }
            }
            val descriptor = openDescriptor(file)
            try {
                return descriptor to LegacyBackend(descriptor)
            } catch (e: SecurityException) {
                descriptor.closeQuietly()
                throw passwordProtected(e)
            } catch (e: Exception) {
                // IOException ("not in PDF format or corrupted"), IllegalArgument/IllegalState and the
                // checked java.lang.Exception("unknown error") the legacy constructor can throw.
                descriptor.closeQuietly()
                throw corrupt(e)
            }
        }

        private fun openDescriptor(file: File): ParcelFileDescriptor =
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: FileNotFoundException) {
                if (file.exists()) {
                    throw PdfOpenException(PdfOpenException.Reason.IO, "The file could not be read.", e)
                }
                throw PdfOpenException(PdfOpenException.Reason.NOT_FOUND, "The file could not be found.", e)
            } catch (e: Exception) {
                throw PdfOpenException(PdfOpenException.Reason.IO, "The file could not be read.", e)
            }

        private fun passwordProtected(cause: Throwable) =
            PdfOpenException(PdfOpenException.Reason.PASSWORD_PROTECTED, "This PDF is password-protected.", cause)

        private fun corrupt(cause: Throwable?) =
            PdfOpenException(PdfOpenException.Reason.CORRUPT, "This PDF is damaged or uses an unsupported format.", cause)

        private fun ParcelFileDescriptor.closeQuietly() {
            runCatching { close() }
        }

        /** Whether [this] can be rendered into in place of a new bitmap of [size]. */
        private fun Bitmap.canBeReusedAs(size: PixelSize): Boolean =
            !isRecycled && isMutable && config == Bitmap.Config.ARGB_8888 &&
                width == size.width && height == size.height

        private fun whiteBitmap(size: PixelSize): Bitmap =
            createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    }
}
