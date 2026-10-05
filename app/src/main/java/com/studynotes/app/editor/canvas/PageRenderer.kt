package com.studynotes.app.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.LongSparseArray
import com.studynotes.app.pdf.PdfDocumentHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Scroll/zoom state shared between [DocumentCanvasView] (writer) and [PageRenderer] (reader). Main thread only. */
internal class Viewport {
    var layout: PageLayout? = null
    var scrollX = 0f
    var scrollY = 0f
    var zoom = 1f

    /** True while a pinch or an animated zoom runs: detail tiles wait until it ends. */
    var scaling = false

    /** Uptime of the last scroll/zoom change; detail tiles wait until the viewport settles. */
    var lastChangeUptime = 0L
}

/**
 * Byte-budgeted LRU of bitmaps keyed by a long, without boxing (lookups happen in onDraw).
 * Evicted bitmaps are handed to [onEvict]. Main thread only.
 */
internal class BitmapLru(private val maxBytes: Long, private val onEvict: (Bitmap) -> Unit) {
    private class Entry(val bitmap: Bitmap, val bytes: Int, val tag: Int, var lastUse: Long)

    private val map = LongSparseArray<Entry>()
    private var clock = 0L

    var bytes = 0L
        private set

    val capacityBytes: Long get() = maxBytes

    /** Bitmap for [key] (marks it recently used) or null. */
    fun get(key: Long): Bitmap? {
        val e = map.get(key) ?: return null
        e.lastUse = ++clock
        return e.bitmap
    }

    /** The tag stored with [key] (e.g. the width a page was rendered for), or -1. */
    fun tagOf(key: Long): Int = map.get(key)?.tag ?: -1

    fun contains(key: Long): Boolean = map.indexOfKey(key) >= 0

    fun put(key: Long, bitmap: Bitmap, tag: Int) {
        val old = map.get(key)
        if (old != null) {
            map.remove(key)
            bytes -= old.bytes
            if (old.bitmap !== bitmap) onEvict(old.bitmap)
        }
        val entry = Entry(bitmap, bitmap.allocationByteCount, tag, ++clock)
        map.put(key, entry)
        bytes += entry.bytes
        trimTo(maxBytes, keep = key)
    }

    /** Evicts least recently used entries (never [keep]) until at most [limit] bytes remain. */
    fun trimTo(limit: Long, keep: Long = Long.MIN_VALUE) {
        while (bytes > limit && map.size() > 0) {
            var oldestIndex = -1
            var oldest = Long.MAX_VALUE
            for (i in 0 until map.size()) {
                if (map.keyAt(i) == keep) continue
                val e = map.valueAt(i)
                if (e.lastUse < oldest) {
                    oldest = e.lastUse
                    oldestIndex = i
                }
            }
            if (oldestIndex < 0) return
            val e = map.valueAt(oldestIndex)
            map.removeAt(oldestIndex)
            bytes -= e.bytes
            onEvict(e.bitmap)
        }
    }

    fun clear() = trimTo(-1L)
}

/**
 * Page bitmaps for [DocumentCanvasView]: fit-width base bitmaps for every visible page (plus
 * neighbours) and 512 px detail tiles at quantized √2 scale steps when zoomed in. Rendering runs in
 * two long-lived coroutines (main dispatcher; the PDF work itself hops to the handle's thread) that
 * always pick the most useful missing bitmap for the *current* viewport, so scrolling never waits
 * for rendering and stale work is naturally skipped.
 */
internal class PageRenderer(
    private val viewport: Viewport,
    private val onBitmapReady: () -> Unit,
) {
    private val baseCache: BitmapLru
    private val tileCache: BitmapLru
    private val tilePool = ArrayList<Bitmap>(TILE_POOL_SIZE)

    init {
        val maxMemory = Runtime.getRuntime().maxMemory()
        baseCache = BitmapLru((maxMemory / 5).coerceIn(32L * MB, 256L * MB)) { it.recycle() }
        tileCache = BitmapLru((maxMemory / 6).coerceIn(24L * MB, 192L * MB)) { recycleTile(it) }
    }

    private var handle: PdfDocumentHandle? = null
    private var scope: CoroutineScope? = null
    private var baseJob: Job? = null
    private var tileJob: Job? = null
    private var baseSignal = Channel<Unit>(Channel.CONFLATED)
    private var tileSignal = Channel<Unit>(Channel.CONFLATED)
    private val failedPages = HashSet<Int>()
    private val tileBudget = TileBudget()

    /**
     * Linear scale applied to base bitmaps (1 normally). Below 1 only when the visible pages at full
     * fit-width resolution would not fit the base cache together (see [BaseBudget.visibleScale]).
     */
    private var baseScaleFactor = 1f

    /** Step of the most recently rendered tile: drawn as a fallback while a big pinch moves past it. */
    private var lastTileStep = Int.MIN_VALUE

    private val whitePaint = Paint().apply { color = Color.WHITE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    /** Shows another document (or none). Drops every bitmap and stops work for the old one. */
    fun setDocument(handle: PdfDocumentHandle?) {
        stop()
        this.handle = handle
        failedPages.clear()
        baseCache.clear()
        clearTiles()
        requestRender()
    }

    /** The fit width changed (resize/rotation): tile keys are relative to it, so drop the tiles. */
    fun onFitWidthChanged() {
        tileJob?.cancel()
        tileJob = null
        clearTiles()
        requestRender()
    }

    /** Wakes the render loops after the viewport changed or a document/layout became available. */
    fun requestRender() {
        if (handle == null || viewport.layout == null) return
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        if (baseJob?.isActive != true) {
            baseSignal = Channel(Channel.CONFLATED)
            baseJob = s.launch { baseLoop(baseSignal) }
        } else {
            baseSignal.trySend(Unit)
        }
        if (tileJob?.isActive != true) {
            tileSignal = Channel(Channel.CONFLATED)
            tileJob = s.launch { tileLoop(tileSignal) }
        } else {
            tileSignal.trySend(Unit)
        }
    }

    /** Cancels all rendering work (bitmaps are kept). */
    fun stop() {
        scope?.cancel()
        scope = null
        baseJob = null
        tileJob = null
    }

    /** Cancels work and frees every bitmap. */
    fun release() {
        setDocument(null)
        for (b in tilePool) b.recycle()
        tilePool.clear()
    }

    /** Frees memory on system pressure: tiles always, base bitmaps when [severe]. */
    fun trimMemory(severe: Boolean) {
        clearTiles()
        for (b in tilePool) b.recycle()
        tilePool.clear()
        if (severe) baseCache.trimTo(baseCache.capacityBytes / 4)
    }

    /**
     * Frees every bitmap (the app is in the background and the system asks for memory). Rendering
     * starts again at the next [requestRender], e.g. when the window becomes visible.
     */
    fun dropBitmaps() {
        trimMemory(severe = false)
        baseCache.clear()
    }

    /**
     * Draws the white page, its base bitmap and any detail tiles into the view rect
     * ([left],[top])–([right],[bottom]). [viewWidth]/[viewHeight] bound the tiles that are looked up.
     */
    fun drawPage(
        canvas: Canvas,
        page: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        viewWidth: Float,
        viewHeight: Float,
    ) {
        canvas.drawRect(left, top, right, bottom, whitePaint)
        val layout = viewport.layout ?: return
        val base = baseCache.get(page.toLong())
        if (base != null) {
            dst.set(left, top, right, bottom)
            canvas.drawBitmap(base, null, dst, bitmapPaint)
        }
        val zoom = viewport.zoom
        if (!needsTiles(layout, page, zoom, base?.width ?: 0)) return
        val step = tileStep(zoom)
        val screenScale = layout.pxPerPt(page, zoom)
        val save = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        // Other steps first as a fallback while the current step renders (e.g. mid-pinch).
        if (lastTileStep < step - 1 || lastTileStep > step + 1) {
            drawTiles(canvas, layout, page, lastTileStep, left, top, screenScale, viewWidth, viewHeight)
        }
        drawTiles(canvas, layout, page, step - 1, left, top, screenScale, viewWidth, viewHeight)
        drawTiles(canvas, layout, page, step + 1, left, top, screenScale, viewWidth, viewHeight)
        drawTiles(canvas, layout, page, step, left, top, screenScale, viewWidth, viewHeight)
        canvas.restoreToCount(save)
    }

    private fun drawTiles(
        canvas: Canvas,
        layout: PageLayout,
        page: Int,
        step: Int,
        pageLeft: Float,
        pageTop: Float,
        screenScale: Float,
        viewWidth: Float,
        viewHeight: Float,
    ) {
        if (step < MIN_STEP || step > MAX_STEP) return
        val tileScale = tileScale(layout, page, step)
        val cols = tileCount(layout.pageWidthPt(page) * tileScale)
        val rows = tileCount(layout.pageHeightPt(page) * tileScale)
        val k = tileScale / screenScale // view px -> tile px
        val c0 = max(0, floor((-pageLeft) * k / TILE_SIZE).toInt())
        val c1 = min(cols - 1, floor((viewWidth - pageLeft) * k / TILE_SIZE).toInt())
        val r0 = max(0, floor((-pageTop) * k / TILE_SIZE).toInt())
        val r1 = min(rows - 1, floor((viewHeight - pageTop) * k / TILE_SIZE).toInt())
        val unit = TILE_SIZE / tileScale * screenScale // view px per tile
        for (r in r0..r1) {
            for (c in c0..c1) {
                val bmp = tileCache.get(tileKey(page, step, c, r)) ?: continue
                val l = pageLeft + c * unit
                val t = pageTop + r * unit
                // Interior edges come from the same expression as the neighbour's, so tiles abut exactly.
                val rr = if (bmp.width == TILE_SIZE) pageLeft + (c + 1) * unit else l + bmp.width / tileScale * screenScale
                val bb = if (bmp.height == TILE_SIZE) pageTop + (r + 1) * unit else t + bmp.height / tileScale * screenScale
                dst.set(l, t, rr, bb)
                canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            }
        }
    }

    // ---- Base bitmaps ------------------------------------------------------------------------

    private suspend fun baseLoop(signal: Channel<Unit>) {
        while (true) {
            val h = handle ?: return
            val layout = viewport.layout ?: return
            if (h.isClosed) return
            val page = nextBasePage(layout)
            if (page < 0) {
                signal.receive()
                continue
            }
            val width = baseWidth(layout, page)
            val bitmap = try {
                h.renderPage(page, width)
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                // Transient: free memory and retry on the next viewport change.
                trimMemory(severe = true)
                signal.receive()
                continue
            } catch (_: Exception) {
                // Closed handle, broken page...: draw it as a blank page instead of retrying forever.
                failedPages.add(page)
                continue
            }
            if (h !== handle) {
                bitmap.recycle()
                return
            }
            baseCache.put(page.toLong(), bitmap, width)
            onBitmapReady()
        }
    }

    /**
     * The most useful page lacking an up-to-date base bitmap: visible pages nearest the viewport
     * centre first, then up to two neighbours on each side while they fit the cache budget. -1 if none.
     */
    private fun nextBasePage(layout: PageLayout): Int {
        if (!layout.isConfigured || layout.pageCount == 0) return -1
        val zoom = viewport.zoom
        val first = layout.firstVisiblePage(viewport.scrollY, zoom)
        val last = layout.lastVisiblePage(viewport.scrollY, zoom)
        val centreY = viewport.scrollY + layout.viewHeight / 2f
        var best = -1
        var bestDistance = Float.MAX_VALUE
        // Visible pages that cannot all be cached at full size would evict each other and re-render
        // forever: render them a little smaller instead (the full size returns when fewer are visible).
        var fullBytes = 0L
        for (p in first..last) fullBytes += baseBytes(layout, p, 1f)
        baseScaleFactor = BaseBudget.visibleScale(fullBytes, baseCache.capacityBytes)
        var budget = baseCache.capacityBytes
        for (p in first..last) {
            budget -= baseBytes(layout, p)
            if (budget < 0) break // only at the minimum scale: never let visible pages evict each other
            if (!needsBase(layout, p)) continue
            val mid = layout.pageTop(p, zoom) + layout.pageHeight(p, zoom) / 2f
            val d = abs(mid - centreY)
            if (d < bestDistance) {
                bestDistance = d
                best = p
            }
        }
        if (best >= 0) return best
        for (distance in 1..NEIGHBOUR_PAGES) {
            for (side in 0..1) {
                val p = if (side == 0) last + distance else first - distance
                if (p < 0 || p >= layout.pageCount) continue
                budget -= baseBytes(layout, p)
                if (budget < 0) return -1
                if (needsBase(layout, p)) return p
            }
        }
        return -1
    }

    private fun needsBase(layout: PageLayout, page: Int): Boolean =
        page !in failedPages && baseCache.tagOf(page.toLong()) != baseWidth(layout, page)

    /**
     * Base bitmap width: the fit width, scaled down so the bitmap stays within [MAX_BASE_PIXELS],
     * times [scale] (the current [baseScaleFactor] unless given).
     */
    private fun baseWidth(layout: PageLayout, page: Int, scale: Float = baseScaleFactor): Int {
        val w = layout.fitWidth
        val h = w * layout.pageHeightPt(page) / layout.pageWidthPt(page)
        val area = w * h
        val capped = if (area > MAX_BASE_PIXELS) w * sqrt(MAX_BASE_PIXELS / area) else w
        return max(1, floor(capped * scale).toInt())
    }

    private fun baseBytes(layout: PageLayout, page: Int, scale: Float = baseScaleFactor): Long {
        val w = baseWidth(layout, page, scale).toLong()
        val h = max(1L, (w * layout.pageHeightPt(page) / layout.pageWidthPt(page)).toLong())
        return w * h * 4L
    }

    // ---- Detail tiles ------------------------------------------------------------------------

    /**
     * True when [page] at [zoom] needs detail tiles over its base bitmap. [storedBaseWidth] is the
     * width of the base actually cached (0 = none / unknown: use the width it would be rendered at).
     */
    private fun needsTiles(layout: PageLayout, page: Int, zoom: Float, storedBaseWidth: Int): Boolean {
        val width = if (storedBaseWidth > 0) storedBaseWidth else baseWidth(layout, page)
        val baseScale = width / layout.pageWidthPt(page)
        return layout.pxPerPt(page, zoom) > baseScale * TILE_THRESHOLD
    }

    /** Smallest √2 step at or just above [zoom] (relative to the fit-width scale). */
    private fun tileStep(zoom: Float): Int =
        ceil(2.0 * ln(zoom.toDouble()) / LN2 - 1e-3).toInt().coerceIn(MIN_STEP, MAX_STEP)

    private fun tileScale(layout: PageLayout, page: Int, step: Int): Float =
        (layout.fitScale(page) * Math.pow(SQRT2, step.toDouble())).toFloat()

    private suspend fun tileLoop(signal: Channel<Unit>) {
        while (true) {
            val h = handle ?: return
            val layout = viewport.layout ?: return
            if (h.isClosed) return
            if (viewport.scaling) {
                // The pinch/zoom animation keeps signalling; onScaleEnd / the animation end signal last.
                signal.receive()
                continue
            }
            val wait = viewport.lastChangeUptime + SETTLE_MS - SystemClock.uptimeMillis()
            if (wait > 0) {
                delay(wait)
                continue
            }
            tileBudget.beginPass(viewport.lastChangeUptime)
            // Never render more tiles for one viewport than the cache holds: they would evict each other.
            val capacity = (tileCache.capacityBytes / TILE_BYTES).toInt() - 1
            val key = if (tileBudget.isExhausted(capacity)) NO_TILE else nextTile(layout)
            if (key == NO_TILE) {
                signal.receive()
                continue
            }
            val page = tilePage(key)
            val step = tileStepOf(key)
            val col = tileCol(key)
            val row = tileRow(key)
            val scale = tileScale(layout, page, step)
            val left = col * TILE_SIZE / scale
            val top = row * TILE_SIZE / scale
            // A hair under one tile so ceil(width * scale) is exactly TILE_SIZE for interior tiles.
            val right = min(layout.pageWidthPt(page), left + (TILE_SIZE - TILE_EPSILON) / scale)
            val bottom = min(layout.pageHeightPt(page), top + (TILE_SIZE - TILE_EPSILON) / scale)
            val full = ceil((right - left) * scale).toInt() == TILE_SIZE &&
                ceil((bottom - top) * scale).toInt() == TILE_SIZE
            val reuse = if (full && tilePool.isNotEmpty()) tilePool.removeAt(tilePool.size - 1) else null
            tileBudget.onTileRendered()
            val bitmap = try {
                h.renderRegion(page, RectF(left, top, right, bottom), scale, reuse)
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                trimMemory(severe = false)
                signal.receive()
                continue
            } catch (_: Exception) {
                reuse?.let(::recycleTile)
                signal.receive()
                continue
            }
            if (h !== handle) {
                bitmap.recycle()
                return
            }
            tileCache.put(key, bitmap, 0)
            lastTileStep = step
            onBitmapReady()
        }
    }

    /** Missing tile of the current step nearest the viewport centre (with half a tile of margin), or [NO_TILE]. */
    private fun nextTile(layout: PageLayout): Long {
        if (!layout.isConfigured || layout.pageCount == 0) return NO_TILE
        val zoom = viewport.zoom
        val step = tileStep(zoom)
        val first = layout.firstVisiblePage(viewport.scrollY, zoom)
        val last = layout.lastVisiblePage(viewport.scrollY, zoom)
        val viewW = layout.viewWidth
        val viewH = layout.viewHeight
        var best = NO_TILE
        var bestDistance = Float.MAX_VALUE
        for (page in first..last) {
            if (!needsTiles(layout, page, zoom, baseCache.tagOf(page.toLong()))) continue
            val screenScale = layout.pxPerPt(page, zoom)
            val tileScale = tileScale(layout, page, step)
            val pageLeft = layout.pageLeft() - viewport.scrollX
            val pageTop = layout.pageTop(page, zoom) - viewport.scrollY
            val k = tileScale / screenScale
            val margin = TILE_SIZE / 2f / k
            val cols = tileCount(layout.pageWidthPt(page) * tileScale)
            val rows = tileCount(layout.pageHeightPt(page) * tileScale)
            val c0 = max(0, floor((-margin - pageLeft) * k / TILE_SIZE).toInt())
            val c1 = min(cols - 1, floor((viewW + margin - pageLeft) * k / TILE_SIZE).toInt())
            val r0 = max(0, floor((-margin - pageTop) * k / TILE_SIZE).toInt())
            val r1 = min(rows - 1, floor((viewH + margin - pageTop) * k / TILE_SIZE).toInt())
            val unit = TILE_SIZE / k
            for (r in r0..r1) {
                for (c in c0..c1) {
                    val key = tileKey(page, step, c, r)
                    if (tileCache.contains(key)) continue
                    val dx = pageLeft + (c + 0.5f) * unit - viewW / 2f
                    val dy = pageTop + (r + 0.5f) * unit - viewH / 2f
                    val d = dx * dx + dy * dy
                    if (d < bestDistance) {
                        bestDistance = d
                        best = key
                    }
                }
            }
        }
        return best
    }

    /** Drops every tile. The per-viewport tile budget starts over, or the dropped tiles never come back. */
    private fun clearTiles() {
        tileCache.clear()
        tileBudget.reset()
    }

    private fun recycleTile(bitmap: Bitmap) {
        if (!bitmap.isRecycled && bitmap.isMutable && bitmap.width == TILE_SIZE && bitmap.height == TILE_SIZE &&
            tilePool.size < TILE_POOL_SIZE
        ) {
            tilePool.add(bitmap)
        } else {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val MAX_BASE_PIXELS = 4_000_000f
        const val NEIGHBOUR_PAGES = 2
        const val TILE_SIZE = 512
        const val TILE_BYTES = TILE_SIZE * TILE_SIZE * 4L
        const val TILE_EPSILON = 0.01f
        const val TILE_POOL_SIZE = 8
        const val TILE_THRESHOLD = 1.15f
        const val SETTLE_MS = 120L
        const val MIN_STEP = -4
        const val MAX_STEP = 12
        const val NO_TILE = -1L
        val SQRT2 = sqrt(2.0)
        val LN2 = ln(2.0)

        fun tileCount(sizePx: Float): Int = max(1, ceil(sizePx / TILE_SIZE - 1e-3f).toInt())

        fun tileKey(page: Int, step: Int, col: Int, row: Int): Long =
            (page.toLong() shl 40) or ((step - MIN_STEP).toLong() shl 32) or
                ((col.toLong() and 0xFFFF) shl 16) or (row.toLong() and 0xFFFF)

        fun tilePage(key: Long): Int = (key ushr 40).toInt()
        fun tileStepOf(key: Long): Int = ((key ushr 32) and 0xFF).toInt() + MIN_STEP
        fun tileCol(key: Long): Int = ((key ushr 16) and 0xFFFF).toInt()
        fun tileRow(key: Long): Int = (key and 0xFFFF).toInt()
    }
}
