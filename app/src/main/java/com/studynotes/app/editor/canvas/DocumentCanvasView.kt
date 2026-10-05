package com.studynotes.app.editor.canvas

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.model.InkTool
import com.studynotes.app.model.Note
import com.studynotes.app.model.PageAnnotations
import com.studynotes.app.model.Stroke
import com.studynotes.app.model.Tool
import com.studynotes.app.model.ToolState
import com.studynotes.app.model.ViewState
import com.studynotes.app.model.newAnnotationId
import com.studynotes.app.pdf.PdfDocumentHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Callbacks from the canvas to the editor. All are invoked on the main thread. */
interface CanvasListener {
    /**
     * One finished ink gesture on [pageIndex]. Usually one stroke; a highlight snapped to text yields
     * one stroke per text line, all sharing a groupId. Ids are already assigned (newAnnotationId()).
     */
    fun onStrokesAdded(pageIndex: Int, strokes: List<Stroke>)

    /** One finished eraser gesture removed these strokes (whole strokes, including their whole group). */
    fun onStrokesErased(pageIndex: Int, strokeIds: Set<String>)

    /** User tapped an existing note (any tool). */
    fun onNoteTapped(pageIndex: Int, noteId: String)

    /** With the NOTE tool, user tapped an empty spot of a page; ([x],[y]) in page points. */
    fun onNotePlacementRequested(pageIndex: Int, x: Float, y: Float)

    /** With the NOTE tool, user dragged a note to a new top-left position (page points, same page). */
    fun onNoteMoved(pageIndex: Int, noteId: String, x: Float, y: Float)

    /** The page under the viewport centre changed. Called only when the value changes. */
    fun onCurrentPageChanged(pageIndex: Int)

    /** A stylus (TOOL_TYPE_STYLUS or TOOL_TYPE_ERASER) touched the screen for the first time since setDocument. */
    fun onStylusDetected()
}

/**
 * The document surface: vertically stacked pages with scroll, fling, pinch-zoom and double-tap
 * zoom, PDF page rendering via [PdfDocumentHandle], annotation drawing and all pen/finger input.
 *
 * CONTRACT (frozen): public methods below. They are called from Compose's AndroidView update block
 * on every recomposition, so each setter must be cheap and a no-op when the value is unchanged.
 * See docs/ARCHITECTURE.md §Canvas for the full behaviour spec.
 */
class DocumentCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var listener: CanvasListener? = null

    // ---- Configuration -----------------------------------------------------------------------

    private val density = resources.displayMetrics.density
    private val sideMarginPx = 12f * density
    private val pageGapPx = 12f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val eraserRadiusPx = ERASER_RADIUS_DP * density

    private var handle: PdfDocumentHandle? = null
    private val viewport = Viewport()
    private val renderer = PageRenderer(viewport) { postInvalidateOnAnimation() }
    private val painter = AnnotationPainter()
    private var annotations = DocumentAnnotations.EMPTY

    /** [annotations] indexed by page, so onDraw does not box page indices for the map lookup. */
    private var annotationsByPage: Array<PageAnnotations> = emptyArray()
    private var toolState = ToolState()
    private var stylusOnly = false
    private var notesVisible = true
    private var contentPaddingTop = 0
    private var contentPaddingBottom = 0
    private var sideInsetLeft = 0
    private var sideInsetRight = 0
    private var backdropColor = DEFAULT_BACKDROP

    /** View state to apply at the next layout (set by setDocument / early scrollToPage). */
    private var pendingViewState: ViewState? = null
    private var currentPage = -1
    private var stylusNotified = false

    /** Scope for snap-to-text requests; created on demand, cancelled on detach/release. */
    private var inkScope: CoroutineScope? = null

    // ---- Annotation overlays (strokes sent to the editor but not yet in a snapshot, etc.) -----

    private class PendingStroke(val page: Int, val stroke: Stroke, val time: Long)
    private class ErasedStroke(val page: Int, val time: Long)
    private class NotePreview(val page: Int, val x: Float, val y: Float, val time: Long)

    private val pendingStrokes = ArrayList<PendingStroke>()
    private val awaitingSnap = ArrayList<PendingStroke>()

    /** Every stroke id that must not be drawn (erased in the current gesture or awaiting a snapshot). */
    private val hiddenStrokeIds = HashSet<String>()
    private val erasedAwaitingSnapshot = HashMap<String, ErasedStroke>()
    private val notePreviews = HashMap<String, NotePreview>()

    // ---- Gesture state -----------------------------------------------------------------------

    private enum class Mode { NONE, IGNORE, NAVIGATE, FINGER_PENDING, INK, ERASE, NOTE_PRESS }

    private var mode = Mode.NONE
    private var gestureIsStylus = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var didScale = false
    private var gestureFromDown = false
    private var tapHandled = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var lastStylusUptime = Long.MIN_VALUE / 2

    /** The current gesture's ACTION_DOWN stopped a fling/animation: it is a catch, not a tap. */
    private var caughtMotion = false

    /** [caughtMotion] of the gesture GestureDetector is tracking (its tap callbacks may run later). */
    private var tapCaughtMotion = false

    /** A stylus navigating (View tool, Note tool off a note) has not moved or scaled yet: may be a tap. */
    private var stylusTapCandidate = false

    /** requestUnbufferedDispatch was accepted for this stylus gesture (only ACTION_DOWN/MOVE are). */
    private var unbufferedRequested = false

    /** Sub-pixel parts of pan deltas that a Float scroll offset could not represent (long documents). */
    private val panRemainderX = PanRemainder()
    private val panRemainderY = PanRemainder()

    // Live ink
    private val livePoints = PointBuffer()
    private val livePath = Path()
    private val drawPath = Path()
    private var livePage = -1
    private var liveTool = InkTool.PEN
    private var liveColor = 0
    private var liveWidth = 1f
    private var liveStraightMode = false
    private var liveStraightened = false
    private var liveLengthPx = 0f
    private var liveMaxMovePx = 0f
    private var lastInkX = 0f
    private var lastInkY = 0f
    private val holdDetector = HoldDetector(HOLD_RADIUS_DP * density, HOLD_MILLIS, HOLD_MIN_LENGTH_DP * density)
    private val holdCheck = Runnable { checkHold() }

    /** [holdCheck] is posted (it stops polling while the stroke is too short to straighten). */
    private var holdCheckArmed = false
    private var predictor: InkPredictor? = null

    // Eraser
    private val eraseGesture = HashMap<Int, HashSet<String>>()
    private var erasePage = -1

    /** The page an eraser gesture is confined to (its first page), so one gesture is one undo step. */
    private var eraseLockedPage = -1

    /** Farthest the erasing pointer got from its down point (view px). */
    private var eraseMaxMovePx = 0f
    private var eraseLastX = 0f
    private var eraseLastY = 0f
    private var eraseBoundsPage = -1
    private var eraseBoundsStrokes: List<Stroke>? = null
    private var eraseBounds = FloatArray(0)
    private val strokeBox = FloatArray(4)

    // Note drag
    private var dragNote: Note? = null
    private var dragPage = -1
    private var dragActive = false
    private var dragX = 0f
    private var dragY = 0f

    // Hover ring and edge gesture exclusion
    private var hoverVisible = false
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverErasing = false
    private var exclusionActive = false
    private var exclusionY = 0f
    private val clearExclusionLater = Runnable {
        exclusionClearPending = false
        clearGestureExclusion()
    }
    private var exclusionClearPending = false

    /** Drops page bitmaps when the system asks for memory while the app is in the background. */
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
            // Stop the render loops too, or a pass in flight would re-render the visible pages while
            // hidden. onWindowVisibilityChanged(VISIBLE) restarts them.
            if (windowVisibility != VISIBLE) renderer.stop()
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                renderer.dropBitmaps()
            } else {
                renderer.trimMemory(severe = true)
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
            renderer.trimMemory(severe = true)
            if (windowVisibility == VISIBLE) {
                renderer.requestRender()
                invalidate()
            }
        }
    }
    private var memoryCallbacksRegistered = false

    // Motion
    private val scroller = OverScroller(context)
    private var animator: ValueAnimator? = null
    private var animatingScrollTarget = Float.NaN

    // Drawing helpers (allocated once; onDraw must not allocate)
    private val pageOutlinePaint = Paint().apply { color = 0x1F000000 }
    private val pageShadowPaint = Paint().apply { color = 0x12000000 }
    private val hoverDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x8C1F1F24.toInt()
    }
    private val hoverLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0xB3FFFFFF.toInt()
    }
    private val tmpRect = RectF()

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener()).apply {
        isQuickScaleEnabled = false
        isStylusScaleEnabled = false
    }
    private val gestureDetector = GestureDetector(context, TapListener()).apply {
        setIsLongpressEnabled(false)
    }

    init {
        contentDescription = "Document pages"
    }

    // ---- Public API --------------------------------------------------------------------------

    /** Shows [handle] (null = nothing). Restores [initialViewState] once laid out. Does not close the old handle. */
    fun setDocument(handle: PdfDocumentHandle?, initialViewState: ViewState) {
        if (handle === this.handle) return
        flushAwaitingSnaps()
        inkScope?.cancel()
        inkScope = null
        cancelGesture()
        stopMotion()
        this.handle = handle
        // The annotation snapshot is kept: the editor may have set it before the document.
        pendingStrokes.clear()
        hiddenStrokeIds.clear()
        erasedAwaitingSnapshot.clear()
        notePreviews.clear()
        painter.clearCaches()
        stylusNotified = false
        currentPage = -1
        viewport.scrollX = 0f
        viewport.scrollY = 0f
        viewport.zoom = 1f
        viewport.layout = handle?.let { h ->
            val sizes = h.pageSizes
            val widths = FloatArray(sizes.size) { sizes[it].width.coerceAtLeast(1f) }
            val heights = FloatArray(sizes.size) { sizes[it].height.coerceAtLeast(1f) }
            PageLayout(widths, heights)
        }
        pendingViewState = if (handle != null) initialViewState else null
        indexAnnotations()
        renderer.setDocument(handle)
        if (handle != null) relayout(keepAnchor = false)
        invalidate()
    }

    /** Latest immutable annotation snapshot from the editor. */
    fun setAnnotations(annotations: DocumentAnnotations) {
        if (annotations === this.annotations) return
        this.annotations = annotations
        indexAnnotations()
        pruneOverlays(SystemClock.uptimeMillis())
        val dragged = dragNote
        if (dragged != null && pageAnnotations(dragPage).notes.none { it.id == dragged.id }) {
            dragNote = null
            if (mode == Mode.NOTE_PRESS) mode = Mode.IGNORE
        }
        invalidate()
    }

    private fun indexAnnotations() {
        val count = viewport.layout?.pageCount ?: 0
        val byPage = Array(count) { PageAnnotations.EMPTY }
        for ((index, page) in annotations.pages) if (index in 0 until count) byPage[index] = page
        annotationsByPage = byPage
    }

    private fun pageAnnotations(page: Int): PageAnnotations =
        if (page in annotationsByPage.indices) annotationsByPage[page] else PageAnnotations.EMPTY

    fun setToolState(state: ToolState) {
        if (state == toolState) return
        val inkBefore = isInkTool(toolState.tool)
        toolState = state
        if (inkBefore && !isInkTool(state.tool)) clearGestureExclusion()
        if (hoverVisible) invalidate()
    }

    /** true = only stylus draws, fingers navigate (palm rejection). false = one finger draws with ink tools. */
    fun setStylusOnly(stylusOnly: Boolean) {
        this.stylusOnly = stylusOnly
    }

    fun setNotesVisible(visible: Boolean) {
        if (visible == notesVisible) return
        notesVisible = visible
        if (!visible && mode == Mode.NOTE_PRESS) {
            dragNote = null
            mode = Mode.IGNORE
        }
        invalidate()
    }

    /** Extra scrollable space (px) so content can scroll clear of overlaid toolbars. */
    fun setContentPadding(topPx: Int, bottomPx: Int) {
        if (topPx == contentPaddingTop && bottomPx == contentPaddingBottom) return
        contentPaddingTop = topPx
        contentPaddingBottom = bottomPx
        // The view-state anchor is independent of the paddings, so keeping the scroll keeps the position.
        relayout(keepAnchor = false)
    }

    /**
     * Horizontal safe-area insets (px; side navigation bar, display cutout). Fit-width pages lay out
     * between them while the backdrop still fills the view. Cheap and a no-op when unchanged.
     * Internal addition next to the frozen [setContentPadding].
     */
    internal fun setSideInsets(leftPx: Int, rightPx: Int) {
        val left = max(0, leftPx)
        val right = max(0, rightPx)
        if (left == sideInsetLeft && right == sideInsetRight) return
        sideInsetLeft = left
        sideInsetRight = right
        if (viewport.layout?.isConfigured == true) stopMotion()
        // The fit width changes, so keep the page + fraction + zoom rather than the pixel offsets.
        relayout(keepAnchor = true)
    }

    /**
     * Commits ink the editor does not know about yet: a highlight still waiting for its snap-to-text
     * result is emitted as drawn (freehand) through [CanvasListener.onStrokesAdded], synchronously.
     * Call before undo, redo, save or export so they act on what the user sees. Cheap when nothing
     * is pending. Internal addition to the frozen API.
     */
    internal fun commitPendingInk() {
        flushAwaitingSnaps()
    }

    /** Colour behind the pages. */
    fun setBackdropColor(color: Int) {
        if (color == backdropColor) return
        backdropColor = color
        invalidate()
    }

    fun scrollToPage(pageIndex: Int, animate: Boolean = true) {
        val layout = viewport.layout ?: return
        if (layout.pageCount == 0) return
        val page = pageIndex.coerceIn(0, layout.pageCount - 1)
        if (!layout.isConfigured) {
            val base = pendingViewState ?: ViewState()
            pendingViewState = base.copy(page = page, pageOffsetFraction = 0f)
            return
        }
        val target = layout.clampScrollY(layout.scrollYFor(page, 0f, viewport.zoom), viewport.zoom)
        if (animate) {
            animateScrollTo(target)
        } else {
            stopMotion()
            setScroll(viewport.scrollX, target)
        }
    }

    /** Animated scroll by [screens] viewport heights (negative = up). Used for keyboard / pen-remote paging. */
    fun scrollByScreens(screens: Float) {
        val layout = viewport.layout ?: return
        if (!layout.isConfigured) return
        val visible = max(height / 2f, (height - contentPaddingTop - contentPaddingBottom).toFloat())
        val from = if (animatingScrollTarget.isNaN()) viewport.scrollY else animatingScrollTarget
        animateScrollTo(layout.clampScrollY(from + screens * visible, viewport.zoom))
    }

    /** Current position, saved with the annotations so the document reopens where the user left off. */
    fun currentViewState(): ViewState {
        pendingViewState?.let { return it }
        val layout = viewport.layout ?: return ViewState()
        if (!layout.isConfigured) return ViewState(zoom = viewport.zoom)
        return layout.viewStateAt(viewport.scrollY, viewport.zoom)
    }

    /** Frees bitmaps and cancels rendering. Call when the editor is disposed. */
    fun release() {
        flushAwaitingSnaps()
        inkScope?.cancel()
        inkScope = null
        cancelGesture()
        stopMotion()
        renderer.release()
        painter.clearCaches()
        handle = null
        viewport.layout = null
        pendingViewState = null
        annotations = DocumentAnnotations.EMPTY
        annotationsByPage = emptyArray()
        pendingStrokes.clear()
        hiddenStrokeIds.clear()
        erasedAwaitingSnapshot.clear()
        notePreviews.clear()
        velocityTracker?.recycle()
        velocityTracker = null
        invalidate()
    }

    // ---- Layout & viewport -------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A fling or scroll/zoom animation works in the old layout's coordinates and would overwrite
        // the anchored position on its next frame.
        if (viewport.layout?.isConfigured == true) stopMotion()
        relayout(keepAnchor = true)
    }

    /**
     * Re-measures the page strip. With [keepAnchor] the page + fraction + zoom (and horizontal
     * centre) under the reference line are kept, otherwise the scroll offsets are kept. A pending
     * initial view state wins over both.
     */
    private fun relayout(keepAnchor: Boolean) {
        val layout = viewport.layout ?: return
        if (width <= 0 || height <= 0) return
        val wasConfigured = layout.isConfigured
        val oldFitWidth = layout.fitWidth
        val anchor = if (keepAnchor && wasConfigured) layout.viewStateAt(viewport.scrollY, viewport.zoom) else null
        val anchorX = if (anchor != null) layout.centreFractionX(viewport.scrollX, viewport.zoom) else 0f
        layout.configure(
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            sideMargin = sideMarginPx,
            gap = pageGapPx,
            paddingTop = contentPaddingTop.toFloat(),
            paddingBottom = contentPaddingBottom.toFloat(),
            insetLeft = sideInsetLeft.toFloat(),
            insetRight = sideInsetRight.toFloat(),
        )
        val pending = pendingViewState
        val zoom = viewport.zoom
        when {
            pending != null -> {
                pendingViewState = null
                val z = PageLayout.clampZoom(pending.zoom)
                viewport.zoom = z
                viewport.scrollY = layout.scrollYFor(pending.page, pending.pageOffsetFraction, z)
                viewport.scrollX = (layout.contentWidth(z) - layout.viewWidth) / 2f
            }
            anchor != null -> {
                viewport.scrollY = layout.scrollYFor(anchor.page, anchor.pageOffsetFraction, zoom)
                viewport.scrollX = layout.scrollXForCentreFraction(anchorX, zoom)
            }
        }
        viewport.scrollX = layout.clampScrollX(viewport.scrollX, viewport.zoom)
        viewport.scrollY = layout.clampScrollY(viewport.scrollY, viewport.zoom)
        if (wasConfigured && oldFitWidth != layout.fitWidth) renderer.onFitWidthChanged()
        onViewportChanged()
    }

    /** Sets the scroll offsets (clamped) and refreshes everything that depends on them. */
    private fun setScroll(x: Float, y: Float) {
        val layout = viewport.layout ?: return
        val cx = layout.clampScrollX(x, viewport.zoom)
        val cy = layout.clampScrollY(y, viewport.zoom)
        if (cx == viewport.scrollX && cy == viewport.scrollY) return
        viewport.scrollX = cx
        viewport.scrollY = cy
        onViewportChanged()
    }

    /** Changes the zoom keeping the document point under view point ([focusX],[focusY]) fixed. */
    private fun zoomAround(newZoom: Float, focusX: Float, focusY: Float) {
        val layout = viewport.layout ?: return
        val old = viewport.zoom
        val z = PageLayout.clampZoom(newZoom)
        if (z == old) return
        val sx = layout.zoomAroundScrollX(viewport.scrollX, focusX, old, z)
        val sy = layout.zoomAroundScrollY(viewport.scrollY, focusY, old, z)
        viewport.zoom = z
        viewport.scrollX = layout.clampScrollX(sx, z)
        viewport.scrollY = layout.clampScrollY(sy, z)
        onViewportChanged()
    }

    private fun onViewportChanged() {
        viewport.lastChangeUptime = SystemClock.uptimeMillis()
        renderer.requestRender()
        updateCurrentPage()
        postInvalidateOnAnimation()
    }

    private fun updateCurrentPage() {
        val layout = viewport.layout ?: return
        if (!layout.isConfigured) return
        val page = layout.pageAtViewportCentre(viewport.scrollY, viewport.zoom)
        if (page >= 0 && page != currentPage) {
            currentPage = page
            listener?.onCurrentPageChanged(page)
        }
    }

    private fun pageLeftOnScreen(layout: PageLayout): Float = layout.pageLeft() - viewport.scrollX

    private fun pageTopOnScreen(layout: PageLayout, page: Int): Float = layout.pageTop(page, viewport.zoom) - viewport.scrollY

    private fun pageAt(x: Float, y: Float): Int {
        val layout = viewport.layout ?: return -1
        return layout.pageAtView(x, y, viewport.scrollX, viewport.scrollY, viewport.zoom)
    }

    private fun toPageX(layout: PageLayout, page: Int, x: Float): Float =
        ((x - pageLeftOnScreen(layout)) / layout.pxPerPt(page, viewport.zoom)).coerceIn(0f, layout.pageWidthPt(page))

    private fun toPageY(layout: PageLayout, page: Int, y: Float): Float =
        ((y - pageTopOnScreen(layout, page)) / layout.pxPerPt(page, viewport.zoom)).coerceIn(0f, layout.pageHeightPt(page))

    // ---- Motion: fling and animations --------------------------------------------------------

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            setScroll(scroller.currX.toFloat(), scroller.currY.toFloat())
            postInvalidateOnAnimation()
        }
    }

    private fun fling(velocityX: Float, velocityY: Float) {
        val layout = viewport.layout ?: return
        val z = viewport.zoom
        scroller.forceFinished(true)
        scroller.fling(
            viewport.scrollX.roundToInt(), viewport.scrollY.roundToInt(),
            (-velocityX).roundToInt(), (-velocityY).roundToInt(),
            layout.minScrollX(z).roundToInt(), layout.maxScrollX(z).roundToInt(),
            layout.minScrollY(z).roundToInt(), layout.maxScrollY(z).roundToInt(),
        )
        postInvalidateOnAnimation()
    }

    private fun stopMotion() {
        scroller.forceFinished(true)
        animator?.cancel()
        animator = null
        animatingScrollTarget = Float.NaN
        viewport.scaling = false
    }

    private fun animateScrollTo(targetY: Float) {
        val layout = viewport.layout ?: return
        stopMotion()
        val visible = layout.viewHeight
        var startY = viewport.scrollY
        // Long jumps: cut to one screen before the target, then animate the last screen.
        if (abs(targetY - startY) > 2f * visible) {
            startY = if (targetY > startY) targetY - visible else targetY + visible
            setScroll(viewport.scrollX, startY)
            startY = viewport.scrollY
        }
        if (startY == targetY) return
        animatingScrollTarget = targetY
        val from = startY
        startAnimator(SCROLL_ANIMATION_MS) { f ->
            setScroll(viewport.scrollX, from + (targetY - from) * f)
        }
    }

    private fun animateZoomTo(targetZoom: Float, focusX: Float, focusY: Float) {
        val layout = viewport.layout ?: return
        stopMotion()
        val z0 = viewport.zoom
        val z1 = PageLayout.clampZoom(targetZoom)
        if (z0 == z1) return
        val sx0 = viewport.scrollX
        val sy0 = viewport.scrollY
        viewport.scaling = true
        startAnimator(ZOOM_ANIMATION_MS) { f ->
            // Always derive from the start state so clamping in one frame cannot accumulate drift.
            val z = z0 + (z1 - z0) * f
            viewport.zoom = z
            viewport.scrollX = layout.clampScrollX(layout.zoomAroundScrollX(sx0, focusX, z0, z), z)
            viewport.scrollY = layout.clampScrollY(layout.zoomAroundScrollY(sy0, focusY, z0, z), z)
            if (f >= 1f) viewport.scaling = false
            onViewportChanged()
        }
    }

    private inline fun startAnimator(duration: Long, crossinline onFrame: (Float) -> Unit) {
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = duration
        a.interpolator = DecelerateInterpolator(1.5f)
        a.addUpdateListener { onFrame(it.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator === animation) {
                    animator = null
                    animatingScrollTarget = Float.NaN
                    viewport.scaling = false
                    onViewportChanged()
                }
            }
        })
        animator = a
        a.start()
    }

    // ---- Lifecycle ---------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!memoryCallbacksRegistered) {
            context.applicationContext.registerComponentCallbacks(memoryCallbacks)
            memoryCallbacksRegistered = true
        }
        renderer.requestRender()
    }

    override fun onDetachedFromWindow() {
        flushAwaitingSnaps()
        inkScope?.cancel()
        inkScope = null
        cancelGesture()
        stopMotion()
        renderer.stop()
        if (memoryCallbacksRegistered) {
            context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            memoryCallbacksRegistered = false
        }
        clearGestureExclusion()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // In the background the detail tiles are the cheapest memory to give back (ComponentCallbacks2
        // drops more when the system asks). Back in front, redraw at once and re-render what is missing.
        if (visibility != VISIBLE) {
            renderer.trimMemory(severe = false)
        } else {
            renderer.requestRender()
            invalidate()
        }
    }

    // ---- Drawing -----------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backdropColor)
        val layout = viewport.layout ?: return
        if (!layout.isConfigured || layout.pageCount == 0) return
        val zoom = viewport.zoom
        val first = layout.firstVisiblePage(viewport.scrollY, zoom)
        val last = layout.lastVisiblePage(viewport.scrollY, zoom)
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val left = pageLeftOnScreen(layout)
        val right = left + layout.pageWidth(zoom)
        val outline = max(1f, density * 0.75f)
        for (page in first..last) {
            val top = pageTopOnScreen(layout, page)
            val bottom = top + layout.pageHeight(page, zoom)
            if (bottom < 0f || top > viewH || right < 0f || left > viewW) continue
            canvas.drawRect(left - outline, top - outline, right + outline, bottom + outline, pageOutlinePaint)
            canvas.drawRect(left, bottom, right, bottom + 2f * density, pageShadowPaint)
            renderer.drawPage(canvas, page, left, top, right, bottom, viewW, viewH)
            drawPageAnnotations(canvas, layout, page, left, top, right, bottom)
        }
        drawHoverRing(canvas, layout)
    }

    private fun drawPageAnnotations(
        canvas: Canvas,
        layout: PageLayout,
        page: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val pageAnn = pageAnnotations(page)
        val hasLive = mode == Mode.INK && livePage == page && livePoints.pointCount > 0
        val hasOverlay = hasLive || hasPendingOn(page) || (dragNote != null && dragPage == page) ||
            notePreviews.isNotEmpty()
        if (pageAnn.isEmpty && !hasOverlay) return
        val scale = layout.pxPerPt(page, viewport.zoom)
        val save = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        val customNotes = notesVisible && (dragNote != null || notePreviews.isNotEmpty())
        painter.drawPage(canvas, pageAnn, scale, notesVisible && !customNotes, hiddenStrokeIds)
        for (i in 0 until pendingStrokes.size) {
            val p = pendingStrokes[i]
            if (p.page == page && p.stroke.id !in hiddenStrokeIds) painter.drawStroke(canvas, p.stroke, scale)
        }
        for (i in 0 until awaitingSnap.size) {
            val p = awaitingSnap[i]
            if (p.page == page) painter.drawStroke(canvas, p.stroke, scale)
        }
        if (customNotes) drawNotesWithPreviews(canvas, pageAnn.notes, page, scale)
        if (hasLive) drawLiveStroke(canvas, layout, scale)
        canvas.restoreToCount(save)
    }

    private fun hasPendingOn(page: Int): Boolean {
        for (i in 0 until pendingStrokes.size) if (pendingStrokes[i].page == page) return true
        for (i in 0 until awaitingSnap.size) if (awaitingSnap[i].page == page) return true
        return false
    }

    /** Draws notes, moving the one being dragged (and recently moved ones) to their preview spot. */
    private fun drawNotesWithPreviews(canvas: Canvas, notes: List<Note>, page: Int, scale: Float) {
        val dragged = dragNote
        for (i in 0 until notes.size) {
            val note = notes[i]
            var x = note.x
            var y = note.y
            if (dragged != null && dragPage == page && note.id == dragged.id) {
                x = dragX
                y = dragY
            } else {
                val preview = notePreviews[note.id]
                if (preview != null && preview.page == page) {
                    x = preview.x
                    y = preview.y
                }
            }
            if (x == note.x && y == note.y) {
                painter.drawNote(canvas, note, scale)
            } else {
                val save = canvas.save()
                canvas.translate(x - note.x, y - note.y)
                painter.drawNote(canvas, note, scale)
                canvas.restoreToCount(save)
            }
        }
    }

    private fun drawLiveStroke(canvas: Canvas, layout: PageLayout, scale: Float) {
        val n = livePoints.pointCount
        if (n == 1) {
            painter.drawInkDot(canvas, livePoints.x(0), livePoints.y(0), liveTool, liveColor, liveWidth)
            return
        }
        val lastX = livePoints.x(n - 1)
        val lastY = livePoints.y(n - 1)
        if (isLiveStraight()) {
            drawPath.rewind()
            drawPath.moveTo(livePoints.x(0), livePoints.y(0))
            drawPath.lineTo(lastX, lastY)
        } else {
            drawPath.set(livePath)
            drawPath.lineTo(lastX, lastY)
            appendPredictedTail(layout, scale, lastX, lastY)
        }
        painter.drawInk(canvas, drawPath, liveTool, liveColor, liveWidth)
    }

    /** Appends a few predicted pen positions (display only) to [drawPath]. */
    private fun appendPredictedTail(layout: PageLayout, scale: Float, lastX: Float, lastY: Float) {
        if (Build.VERSION.SDK_INT < 34 || !gestureIsStylus) return
        val p = predictor ?: return
        val predicted = p.predict(PREDICTION_NANOS) ?: return
        try {
            val index = predicted.findPointerIndex(activePointerId)
            if (index < 0) return
            val maxTail = MAX_TAIL_DP * density / scale
            val pageW = layout.pageWidthPt(livePage)
            val pageH = layout.pageHeightPt(livePage)
            val left = pageLeftOnScreen(layout)
            val top = pageTopOnScreen(layout, livePage)
            val history = predicted.historySize
            val count = min(history + 1, MAX_PREDICTED_SAMPLES)
            for (h in 0 until count) {
                val sx = if (h < history) predicted.getHistoricalX(index, h) else predicted.getX(index)
                val sy = if (h < history) predicted.getHistoricalY(index, h) else predicted.getY(index)
                val x = ((sx - left) / scale).coerceIn(0f, pageW)
                val y = ((sy - top) / scale).coerceIn(0f, pageH)
                if (hypot(x - lastX, y - lastY) > maxTail) break
                drawPath.lineTo(x, y)
            }
        } finally {
            predicted.recycle()
        }
    }

    private fun drawHoverRing(canvas: Canvas, layout: PageLayout) {
        if (!hoverVisible) return
        val tool = toolState.tool
        val radius = when {
            hoverErasing || tool == Tool.ERASER -> eraserRadiusPx
            tool == Tool.HIGHLIGHTER || tool == Tool.PEN -> {
                val page = pageAt(hoverX, hoverY)
                if (page < 0) return
                val width = if (tool == Tool.HIGHLIGHTER) toolState.highlighterWidth else toolState.penWidth
                max(width * layout.pxPerPt(page, viewport.zoom) / 2f, 2f * density)
            }
            else -> return
        }
        canvas.drawCircle(hoverX, hoverY, radius + density, hoverLightPaint)
        canvas.drawCircle(hoverX, hoverY, radius, hoverDarkPaint)
    }

    // ---- Touch input -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val layout = viewport.layout
        if (layout == null || !layout.isConfigured || layout.pageCount == 0) return false
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> cancelGesture(event)
        }
        // Pinch: the detector sees every event; its listener only acts while navigating. Fed after
        // the pan so the focal point is panned first and then scaled around (see onMove).
        scaleDetector.onTouchEvent(event)
        if (gestureFromDown && (mode == Mode.NAVIGATE || action == MotionEvent.ACTION_UP)) {
            gestureDetector.onTouchEvent(event)
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (gestureIsStylus) scheduleExclusionClear()
            mode = Mode.NONE
            gestureFromDown = false
            stylusTapCandidate = false
        }
        return true
    }

    private fun onDown(event: MotionEvent) {
        // A touch that stops a fling or an animation is a catch, not a tap.
        caughtMotion = !scroller.isFinished || animator != null
        stopMotion()
        didScale = false
        gestureFromDown = false
        stylusTapCandidate = false
        if (isStylus(event, 0)) {
            startStylusGesture(event, 0)
        } else {
            startFingerGesture(event)
        }
    }

    private fun startStylusGesture(event: MotionEvent, index: Int) {
        // Before reading toolState: the editor may switch the tool synchronously on first detection.
        notifyStylus()
        parent?.requestDisallowInterceptTouchEvent(true)
        gestureIsStylus = true
        lastStylusUptime = event.eventTime
        hoverVisible = false
        cancelScheduledExclusionClear()
        unbufferedRequested = false
        stylusTapCandidate = false
        // A pen joining a palm/finger: that pointer's ACTION_DOWN already stopped any motion.
        if (event.actionMasked != MotionEvent.ACTION_DOWN) caughtMotion = false
        val x = event.getX(index)
        val y = event.getY(index)
        rememberDown(event, index)
        val tool = toolState.tool
        val erasing = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
            (event.buttonState and STYLUS_BUTTONS) != 0 || tool == Tool.ERASER
        if (isInkTool(tool) || erasing) updateGestureExclusion(x, y)
        when {
            erasing -> {
                requestUnbuffered(event)
                beginErase()
                eraseTo(x, y)
                mode = Mode.ERASE
            }
            tool == Tool.HIGHLIGHTER || tool == Tool.PEN -> {
                val page = pageAt(x, y)
                if (page < 0) {
                    mode = Mode.IGNORE
                } else {
                    requestUnbuffered(event)
                    beginInk(page, x, y, event.eventTime)
                    if (Build.VERSION.SDK_INT >= 34) {
                        val p = predictor ?: InkPredictor(context).also { predictor = it }
                        p.start(event)
                    }
                    mode = Mode.INK
                }
            }
            tool == Tool.NOTE && startNotePress(x, y) -> Unit
            else -> {
                // Stylus navigation never feeds GestureDetector (a palm's POINTER_DOWN would cancel the
                // tap there); stylus taps are recognised in handleStylusTap instead.
                startNavigation(event, fromDown = false)
                stylusTapCandidate = true
            }
        }
    }

    /**
     * Stylus gestures only (it makes finger scrolling jittery). The platform honours only
     * ACTION_DOWN/ACTION_MOVE events, so a pen arriving as POINTER_DOWN asks again on its first move.
     */
    private fun requestUnbuffered(event: MotionEvent) {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return
        requestUnbufferedDispatch(event)
        unbufferedRequested = true
    }

    private fun startFingerGesture(event: MotionEvent) {
        gestureIsStylus = false
        rememberDown(event, 0)
        if (stylusOnly && event.eventTime - lastStylusUptime < PALM_GUARD_MS) {
            mode = Mode.IGNORE
            return
        }
        val tool = toolState.tool
        if (!stylusOnly && isInkTool(tool)) {
            mode = Mode.FINGER_PENDING
            return
        }
        if (tool == Tool.NOTE && startNotePress(downX, downY)) return
        startNavigation(event, fromDown = true)
    }

    private fun rememberDown(event: MotionEvent, index: Int) {
        activePointerId = event.getPointerId(index)
        downX = event.getX(index)
        downY = event.getY(index)
        downTime = event.eventTime
    }

    private fun onPointerDown(event: MotionEvent) {
        val index = event.actionIndex
        if (isStylus(event, index)) {
            if (gestureIsStylus) return // a second stylus pointer: keep the first
            // Pen after palm/finger: drop whatever the fingers were doing and follow the pen.
            abortCurrent(event)
            didScale = false
            startStylusGesture(event, index)
            return
        }
        if (gestureIsStylus) return // palm while the pen is down
        when (mode) {
            Mode.FINGER_PENDING, Mode.NOTE_PRESS -> {
                // Two fingers mean pan/zoom: discard the pending stroke / drag (no undo entry).
                abortCurrent(event)
                startNavigation(event, fromDown = false)
            }
            Mode.INK, Mode.ERASE -> {
                val moved = if (mode == Mode.INK) liveMaxMovePx else eraseMaxMovePx
                if (GesturePolicy.secondFingerCancelsStroke(event.eventTime - downTime, moved, touchSlop)) {
                    abortCurrent(event)
                    startNavigation(event, fromDown = false)
                }
                // Otherwise the stroke is established: the extra finger is ignored, like a palm.
            }
            Mode.NAVIGATE -> {
                velocityTracker?.addMovement(event)
                updateFocus(event, -1)
            }
            Mode.NONE, Mode.IGNORE -> Unit
        }
    }

    private fun onMove(event: MotionEvent) {
        if (gestureIsStylus) lastStylusUptime = event.eventTime
        when (mode) {
            Mode.INK -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return
                if (gestureIsStylus && !unbufferedRequested) requestUnbuffered(event)
                if (Build.VERSION.SDK_INT >= 34) predictor?.record(event)
                for (h in 0 until event.historySize) {
                    addInkPoint(event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalEventTime(h))
                }
                addInkPoint(event.getX(index), event.getY(index), event.eventTime)
                if (gestureIsStylus) updateGestureExclusion(event.getX(index), event.getY(index))
                invalidate()
            }
            Mode.ERASE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return
                if (gestureIsStylus && !unbufferedRequested) requestUnbuffered(event)
                for (h in 0 until event.historySize) eraseTo(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                val x = event.getX(index)
                val y = event.getY(index)
                eraseTo(x, y)
                eraseMaxMovePx = max(eraseMaxMovePx, hypot(x - downX, y - downY))
                if (gestureIsStylus) updateGestureExclusion(x, y)
            }
            Mode.FINGER_PENDING -> onFingerPendingMove(event)
            Mode.NOTE_PRESS -> onNoteDragMove(event)
            Mode.NAVIGATE -> {
                velocityTracker?.addMovement(event)
                val fx = focusX(event, -1)
                val fy = focusY(event, -1)
                if (stylusTapCandidate && hypot(fx - downX, fy - downY) > touchSlop) stylusTapCandidate = false
                panBy(lastFocusX - fx, lastFocusY - fy)
                lastFocusX = fx
                lastFocusY = fy
            }
            Mode.NONE, Mode.IGNORE -> Unit
        }
    }

    private fun onFingerPendingMove(event: MotionEvent) {
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        val x = event.getX(index)
        val y = event.getY(index)
        if (hypot(x - downX, y - downY) <= touchSlop) return
        if (toolState.tool == Tool.ERASER) {
            beginErase()
            eraseTo(downX, downY)
            eraseTo(x, y)
            eraseMaxMovePx = hypot(x - downX, y - downY)
            mode = Mode.ERASE
            return
        }
        val page = pageAt(downX, downY)
        if (page < 0) {
            // Dragging from the margin between pages scrolls, which keeps phones easy to navigate.
            startNavigation(event, fromDown = false)
            return
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        beginInk(page, downX, downY, downTime)
        for (h in 0 until event.historySize) {
            addInkPoint(event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalEventTime(h))
        }
        addInkPoint(x, y, event.eventTime)
        mode = Mode.INK
        invalidate()
    }

    private fun onPointerUp(event: MotionEvent) {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        if (isStylus(event, index)) lastStylusUptime = event.eventTime
        if (gestureIsStylus && id == activePointerId) scheduleExclusionClear()
        val canceled = Build.VERSION.SDK_INT >= 33 && (event.flags and MotionEvent.FLAG_CANCELED) != 0
        when (mode) {
            Mode.INK -> if (id == activePointerId) {
                if (!canceled) addInkPoint(event.getX(index), event.getY(index), event.eventTime)
                finishInk(cancelled = canceled, endEvent = event)
                mode = Mode.IGNORE
            }
            Mode.ERASE -> if (id == activePointerId) {
                if (!canceled) eraseTo(event.getX(index), event.getY(index))
                finishErase(cancelled = canceled)
                mode = Mode.IGNORE
            }
            Mode.NOTE_PRESS -> if (id == activePointerId) {
                finishNotePress(cancelled = canceled)
                mode = Mode.IGNORE
            }
            Mode.FINGER_PENDING -> if (id == activePointerId) mode = Mode.IGNORE
            Mode.NAVIGATE -> if (gestureIsStylus) {
                // Only the pen navigates; once it lifts, a resting palm must not pan, pinch or fling.
                if (id == activePointerId) {
                    if (!canceled) handleStylusTap()
                    stylusTapCandidate = false
                    mode = Mode.IGNORE
                }
            } else {
                velocityTracker?.addMovement(event)
                updateFocus(event, index)
            }
            Mode.NONE, Mode.IGNORE -> Unit
        }
    }

    private fun onUp(event: MotionEvent) {
        if (gestureIsStylus) lastStylusUptime = event.eventTime
        val index = event.findPointerIndex(activePointerId)
        when (mode) {
            Mode.INK -> {
                if (index >= 0) addInkPoint(event.getX(index), event.getY(index), event.eventTime)
                finishInk(cancelled = false, endEvent = event)
            }
            Mode.ERASE -> {
                if (index >= 0) eraseTo(event.getX(index), event.getY(index))
                finishErase(cancelled = false)
            }
            Mode.NOTE_PRESS -> finishNotePress(cancelled = false)
            Mode.FINGER_PENDING -> onFingerTap()
            Mode.NAVIGATE -> {
                val vt = velocityTracker
                if (gestureIsStylus && handleStylusTap()) {
                    // A pen tap: nothing to fling.
                } else if (vt != null) {
                    vt.addMovement(event)
                    vt.computeCurrentVelocity(1000, maxFlingVelocity)
                    val id = event.getPointerId(event.actionIndex)
                    val vx = vt.getXVelocity(id)
                    val vy = vt.getYVelocity(id)
                    if (!didScale && hypot(vx, vy) > minFlingVelocity) fling(vx, vy)
                }
            }
            Mode.NONE, Mode.IGNORE -> Unit
        }
    }

    /** ACTION_CANCEL ([event]) or teardown (null): discard whatever the current gesture was doing. */
    private fun cancelGesture(event: MotionEvent? = null) {
        abortCurrent(event)
        mode = Mode.NONE
        viewport.scaling = false
    }

    /** Ends the current mode without committing anything. [event] is the event that ends it, if any. */
    private fun abortCurrent(event: MotionEvent? = null) {
        when (mode) {
            Mode.INK -> finishInk(cancelled = true, endEvent = event)
            Mode.ERASE -> finishErase(cancelled = true)
            Mode.NOTE_PRESS -> finishNotePress(cancelled = true)
            Mode.NAVIGATE -> if (gestureFromDown) {
                val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                gestureDetector.onTouchEvent(cancel)
                cancel.recycle()
            }
            Mode.NONE, Mode.IGNORE, Mode.FINGER_PENDING -> Unit
        }
        gestureFromDown = false
        stylusTapCandidate = false
        mode = Mode.IGNORE
    }

    /**
     * The pen lifted from a navigation gesture (View tool, or the Note tool off a note). If it neither
     * moved past the touch slop nor scaled, nor only caught a fling, it is a tap: open the note under
     * it, or with the Note tool place a new one. True when the tap did something.
     */
    private fun handleStylusTap(): Boolean {
        if (!stylusTapCandidate) return false
        stylusTapCandidate = false
        if (didScale || caughtMotion) return false
        val layout = viewport.layout ?: return false
        val page = pageAt(downX, downY)
        if (page < 0) return false
        val px = toPageX(layout, page, downX)
        val py = toPageY(layout, page, downY)
        val note = noteAt(page, px, py)
        if (note != null) {
            listener?.onNoteTapped(page, note.id)
            return true
        }
        if (toolState.tool == Tool.NOTE) {
            listener?.onNotePlacementRequested(page, px, py)
            return true
        }
        return false
    }

    /** A finger lifted without passing the touch slop in an ink tool (finger drawing enabled). */
    private fun onFingerTap() {
        if (caughtMotion) return
        val layout = viewport.layout ?: return
        val page = pageAt(downX, downY)
        if (page < 0) return
        val px = toPageX(layout, page, downX)
        val py = toPageY(layout, page, downY)
        val note = noteAt(page, px, py)
        if (note != null) {
            listener?.onNoteTapped(page, note.id)
            return
        }
        when (toolState.tool) {
            Tool.PEN -> emitStrokes(
                page,
                listOf(Stroke(newAnnotationId(), InkTool.PEN, toolState.penColor, toolState.penWidth, floatArrayOf(px, py))),
            )
            Tool.ERASER -> {
                beginErase()
                eraseTo(downX, downY)
                finishErase(cancelled = false)
            }
            else -> Unit
        }
    }

    private fun notifyStylus() {
        if (stylusNotified) return
        stylusNotified = true
        listener?.onStylusDetected()
    }

    // ---- Navigation --------------------------------------------------------------------------

    private fun startNavigation(event: MotionEvent, fromDown: Boolean) {
        mode = Mode.NAVIGATE
        gestureFromDown = fromDown
        panRemainderX.reset()
        panRemainderY.reset()
        val vt = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        vt.clear()
        vt.addMovement(event)
        updateFocus(event, -1)
    }

    private fun updateFocus(event: MotionEvent, excludeIndex: Int) {
        lastFocusX = focusX(event, excludeIndex)
        lastFocusY = focusY(event, excludeIndex)
    }

    /**
     * Scrolls by ([dx],[dy]) view px. The parts a Float offset cannot represent (deep into long
     * documents at high zoom) are carried to the next move instead of being lost.
     */
    private fun panBy(dx: Float, dy: Float) {
        val layout = viewport.layout ?: return
        val z = viewport.zoom
        val x = panRemainderX.next(viewport.scrollX, dx, layout.minScrollX(z), layout.maxScrollX(z))
        val y = panRemainderY.next(viewport.scrollY, dy, layout.minScrollY(z), layout.maxScrollY(z))
        setScroll(x, y)
    }

    /** Index of the pen pointer when a stylus navigates (palms never move the page), else -1. */
    private fun stylusFocusIndex(event: MotionEvent, excludeIndex: Int): Int {
        if (!gestureIsStylus) return -1
        val index = event.findPointerIndex(activePointerId)
        return if (index == excludeIndex) -1 else index
    }

    private fun focusX(event: MotionEvent, excludeIndex: Int): Float {
        val pen = stylusFocusIndex(event, excludeIndex)
        if (pen >= 0) return event.getX(pen)
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex) continue
            sum += event.getX(i)
            count++
        }
        return if (count == 0) event.x else sum / count
    }

    private fun focusY(event: MotionEvent, excludeIndex: Int): Float {
        val pen = stylusFocusIndex(event, excludeIndex)
        if (pen >= 0) return event.getY(pen)
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex) continue
            sum += event.getY(i)
            count++
        }
        return if (count == 0) event.y else sum / count
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // Pen + palm is not a pinch: only fingers zoom.
            if (mode != Mode.NAVIGATE || gestureIsStylus) return false
            didScale = true
            viewport.scaling = true
            scroller.forceFinished(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // A pen that joined a finger pinch takes over: it pans, it does not zoom.
            if (mode != Mode.NAVIGATE || gestureIsStylus) return true
            zoomAround(viewport.zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            viewport.scaling = false
            onViewportChanged()
        }
    }

    private inner class TapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // Called after onDoubleTap for the second tap, so the double-tap still sees the first tap's flag.
            tapHandled = false
            tapCaughtMotion = caughtMotion
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (didScale || tapCaughtMotion) return false
            val layout = viewport.layout ?: return false
            val page = pageAt(e.x, e.y)
            if (page < 0) return false
            val px = toPageX(layout, page, e.x)
            val py = toPageY(layout, page, e.y)
            val note = noteAt(page, px, py)
            if (note != null) {
                tapHandled = true
                listener?.onNoteTapped(page, note.id)
                return true
            }
            // Pen taps never reach GestureDetector (see handleStylusTap), so this is always a finger.
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tapHandled || didScale || tapCaughtMotion || toolState.tool != Tool.NOTE) return false
            val layout = viewport.layout ?: return false
            val page = pageAt(e.x, e.y)
            if (page < 0) return false
            listener?.onNotePlacementRequested(page, toPageX(layout, page, e.x), toPageY(layout, page, e.y))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (tapHandled) return false
            val target = if (viewport.zoom < DOUBLE_TAP_THRESHOLD) DOUBLE_TAP_ZOOM else 1f
            animateZoomTo(target, e.x, e.y)
            return true
        }
    }

    // ---- Ink ---------------------------------------------------------------------------------

    private fun beginInk(page: Int, x: Float, y: Float, time: Long) {
        val layout = viewport.layout ?: return
        val highlighter = toolState.tool == Tool.HIGHLIGHTER
        liveTool = if (highlighter) InkTool.HIGHLIGHTER else InkTool.PEN
        liveColor = if (highlighter) toolState.highlighterColor else toolState.penColor
        liveWidth = if (highlighter) toolState.highlighterWidth else toolState.penWidth
        liveStraightMode = highlighter && toolState.straightHighlighter
        liveStraightened = false
        livePage = page
        liveLengthPx = 0f
        liveMaxMovePx = 0f
        livePoints.clear()
        val px = toPageX(layout, page, x)
        val py = toPageY(layout, page, y)
        livePoints.add(px, py)
        livePath.rewind()
        livePath.moveTo(px, py)
        lastInkX = x
        lastInkY = y
        holdDetector.start(x, y, time)
        removeCallbacks(holdCheck)
        holdCheckArmed = !liveStraightMode
        if (holdCheckArmed) postDelayed(holdCheck, HOLD_MILLIS)
        invalidate()
    }

    private fun isLiveStraight(): Boolean = liveStraightMode || liveStraightened

    private fun addInkPoint(x: Float, y: Float, time: Long) {
        val layout = viewport.layout ?: return
        if (livePage < 0) return
        val px = toPageX(layout, livePage, x)
        val py = toPageY(layout, livePage, y)
        val n = livePoints.pointCount
        val prevX = livePoints.x(n - 1)
        val prevY = livePoints.y(n - 1)
        liveMaxMovePx = max(liveMaxMovePx, hypot(x - downX, y - downY))
        liveLengthPx += hypot(x - lastInkX, y - lastInkY)
        lastInkX = x
        lastInkY = y
        holdDetector.move(x, y, time)
        // The hold check stops polling while the stroke is too short; re-arm it once it is long enough.
        if (!holdCheckArmed && !isLiveStraight() && holdDetector.isLongEnough(liveLengthPx)) {
            holdCheckArmed = true
            postDelayed(holdCheck, holdDetector.millisUntilDue(time))
        }
        if (abs(px - prevX) < MIN_POINT_DISTANCE_PT && abs(py - prevY) < MIN_POINT_DISTANCE_PT) return
        if (isLiveStraight()) {
            if (n == 1) livePoints.add(px, py) else livePoints.set(n - 1, px, py)
            return
        }
        livePoints.add(px, py)
        if (n >= 2) {
            // Same construction as AnnotationPainter.buildSmoothPath, extended one segment at a time.
            val cx = livePoints.x(n - 1)
            val cy = livePoints.y(n - 1)
            livePath.quadTo(cx, cy, (cx + px) / 2f, (cy + py) / 2f)
        }
    }

    private fun checkHold() {
        holdCheckArmed = false
        if (mode != Mode.INK || isLiveStraight()) return
        val now = SystemClock.uptimeMillis()
        if (holdDetector.shouldStraighten(now, liveLengthPx)) {
            val n = livePoints.pointCount
            if (n >= 2) {
                livePoints.set(1, livePoints.x(n - 1), livePoints.y(n - 1))
                livePoints.truncate(2)
            }
            liveStraightened = true
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        } else {
            val delay = holdDetector.nextCheckMillis(now, liveLengthPx)
            if (delay != HoldDetector.NO_CHECK) {
                holdCheckArmed = true
                postDelayed(holdCheck, delay)
            }
        }
    }

    /** Ends the live stroke; [endEvent] (UP / POINTER_UP / CANCEL) also closes the predictor's stream. */
    private fun finishInk(cancelled: Boolean, endEvent: MotionEvent? = null) {
        removeCallbacks(holdCheck)
        holdCheckArmed = false
        if (Build.VERSION.SDK_INT >= 34) predictor?.end(endEvent)
        val page = livePage
        livePage = -1
        invalidate()
        if (cancelled || page < 0 || livePoints.pointCount == 0) return
        val layout = viewport.layout ?: return
        val duration = SystemClock.uptimeMillis() - downTime
        if (gestureIsStylus && notesVisible && liveMaxMovePx < STYLUS_TAP_SLOP_PX && duration < STYLUS_TAP_MILLIS) {
            val note = noteAt(page, toPageX(layout, page, downX), toPageY(layout, page, downY))
            if (note != null) {
                listener?.onNoteTapped(page, note.id)
                return
            }
        }
        val raw = livePoints.toArray()
        val tool = liveTool
        val points = when {
            isLiveStraight() -> if (raw.size >= 4) floatArrayOf(raw[0], raw[1], raw[raw.size - 2], raw[raw.size - 1]) else raw
            else -> InkGeometry.simplify(InkGeometry.smooth(raw), InkGeometry.simplifyTolerance(tool))
        }
        val finalPoints = if (tool == InkTool.PEN && InkGeometry.isDot(points, DOT_TOLERANCE_PT)) {
            floatArrayOf(points[0], points[1])
        } else {
            points
        }
        if (tool == InkTool.HIGHLIGHTER && InkGeometry.polylineLength(finalPoints) < MIN_HIGHLIGHT_LENGTH_PT) return
        val stroke = Stroke(newAnnotationId(), tool, liveColor, liveWidth, finalPoints)
        val h = handle
        if (tool == InkTool.HIGHLIGHTER && toolState.snapToText && h != null && h.supportsTextSelection) {
            requestSnap(h, page, stroke, raw)
        } else {
            emitStrokes(page, listOf(stroke))
        }
    }

    private fun emitStrokes(page: Int, strokes: List<Stroke>) {
        val now = SystemClock.uptimeMillis()
        for (s in strokes) pendingStrokes.add(PendingStroke(page, s, now))
        listener?.onStrokesAdded(page, strokes)
        invalidate()
    }

    /**
     * Asks the PDF engine for the text lines under a finished highlight. The freehand stroke stays
     * visible meanwhile and is emitted unchanged when the snap is implausible or unavailable.
     */
    private fun requestSnap(h: PdfDocumentHandle, page: Int, stroke: Stroke, raw: FloatArray) {
        val entry = PendingStroke(page, stroke, SystemClock.uptimeMillis())
        awaitingSnap.add(entry)
        val pts = stroke.points
        // Down the screen is towards the baseline only for horizontal text. A mostly vertical stroke
        // follows text that runs down or up the screen (a rotated page), where it would move the ends
        // along the line instead.
        val horizontal = abs(pts[pts.size - 2] - pts[0]) >= abs(pts[pts.size - 1] - pts[1])
        val bias = if (horizontal) stroke.width * InkGeometry.SNAP_BASELINE_BIAS else 0f
        val start = PointF(pts[0], pts[1] + bias)
        val end = PointF(pts[pts.size - 2], pts[pts.size - 1] + bias)
        val scope = inkScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { inkScope = it }
        scope.launch {
            val rects = try {
                h.selectTextRects(page, start, end)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Already flushed (document switched / view released): the freehand stroke was emitted.
            if (!awaitingSnap.remove(entry)) return@launch
            val flat = if (rects.isNullOrEmpty()) null else FloatArray(rects.size * 4).also { a ->
                rects.forEachIndexed { i, r ->
                    a[4 * i] = r.left
                    a[4 * i + 1] = r.top
                    a[4 * i + 2] = r.right
                    a[4 * i + 3] = r.bottom
                }
            }
            val strokes = flat?.let {
                InkGeometry.snapStrokes(it, raw, stroke.width, stroke.color, newAnnotationId(), ::newAnnotationId)
            } ?: listOf(stroke)
            emitStrokes(page, strokes)
        }
    }

    /** Emits every highlight still waiting for a snap result as drawn (freehand). */
    private fun flushAwaitingSnaps() {
        if (awaitingSnap.isEmpty()) return
        val waiting = ArrayList(awaitingSnap)
        awaitingSnap.clear()
        for (p in waiting) emitStrokes(p.page, listOf(p.stroke))
    }

    // ---- Eraser ------------------------------------------------------------------------------

    private fun beginErase() {
        eraseGesture.clear()
        erasePage = -1
        eraseLockedPage = -1
        eraseMaxMovePx = 0f
        eraseBoundsPage = -1
        eraseBoundsStrokes = null
    }

    /** Erases along the pen path from the previous eraser position to view point ([x],[y]). */
    private fun eraseTo(x: Float, y: Float) {
        val layout = viewport.layout ?: return
        val page = pageAt(x, y)
        // One gesture erases on one page only (like ink), so it is one onStrokesErased / one undo step.
        if (page < 0 || (eraseLockedPage >= 0 && page != eraseLockedPage)) {
            erasePage = -1
            return
        }
        eraseLockedPage = page
        val px = toPageX(layout, page, x)
        val py = toPageY(layout, page, y)
        val ax = if (erasePage == page) eraseLastX else px
        val ay = if (erasePage == page) eraseLastY else py
        erasePage = page
        eraseLastX = px
        eraseLastY = py
        val radius = eraserRadiusPx / layout.pxPerPt(page, viewport.zoom)
        val strokes = pageAnnotations(page).strokes
        ensureEraseBounds(page, strokes)
        var hits: ArrayList<String>? = null
        for (i in 0 until strokes.size) {
            val s = strokes[i]
            if (s.id in hiddenStrokeIds) continue
            strokeBox[0] = eraseBounds[4 * i]
            strokeBox[1] = eraseBounds[4 * i + 1]
            strokeBox[2] = eraseBounds[4 * i + 2]
            strokeBox[3] = eraseBounds[4 * i + 3]
            if (InkGeometry.eraserHits(s, strokeBox, ax, ay, px, py, radius)) {
                (hits ?: ArrayList<String>().also { hits = it }).add(s.id)
            }
        }
        for (i in 0 until pendingStrokes.size) {
            val p = pendingStrokes[i]
            if (p.page != page || p.stroke.id in hiddenStrokeIds) continue
            InkGeometry.bounds(p.stroke.points, strokeBox)
            if (InkGeometry.eraserHits(p.stroke, strokeBox, ax, ay, px, py, radius)) {
                (hits ?: ArrayList<String>().also { hits = it }).add(p.stroke.id)
            }
        }
        val found = hits ?: return
        val candidates = ArrayList<Stroke>(strokes)
        for (p in pendingStrokes) if (p.page == page) candidates.add(p.stroke)
        val ids = InkGeometry.expandGroups(candidates, found)
        eraseGesture.getOrPut(page) { HashSet() }.addAll(ids)
        hiddenStrokeIds.addAll(ids)
        invalidate()
    }

    private fun ensureEraseBounds(page: Int, strokes: List<Stroke>) {
        if (eraseBoundsPage == page && eraseBoundsStrokes === strokes) return
        eraseBoundsPage = page
        eraseBoundsStrokes = strokes
        if (eraseBounds.size < strokes.size * 4) eraseBounds = FloatArray(strokes.size * 4)
        for (i in 0 until strokes.size) {
            InkGeometry.bounds(strokes[i].points, strokeBox)
            System.arraycopy(strokeBox, 0, eraseBounds, 4 * i, 4)
        }
    }

    private fun finishErase(cancelled: Boolean) {
        if (cancelled) {
            for (ids in eraseGesture.values) hiddenStrokeIds.removeAll(ids)
        } else {
            val now = SystemClock.uptimeMillis()
            for ((page, ids) in eraseGesture) {
                if (ids.isEmpty()) continue
                for (id in ids) erasedAwaitingSnapshot[id] = ErasedStroke(page, now)
                listener?.onStrokesErased(page, HashSet(ids))
            }
        }
        eraseGesture.clear()
        erasePage = -1
        eraseLockedPage = -1
        eraseBoundsStrokes = null
        invalidate()
    }

    // ---- Notes -------------------------------------------------------------------------------

    /** Topmost visible note whose card (plus a small touch margin) contains page point ([x],[y]). */
    private fun noteAt(page: Int, x: Float, y: Float): Note? {
        if (!notesVisible) return null
        val layout = viewport.layout ?: return null
        val notes = pageAnnotations(page).notes
        val margin = NOTE_TOUCH_MARGIN_DP * density / layout.pxPerPt(page, viewport.zoom)
        for (i in notes.size - 1 downTo 0) {
            val note = notes[i]
            painter.noteBoundsInto(note, tmpRect)
            val preview = notePreviews[note.id]
            if (preview != null && preview.page == page) tmpRect.offsetTo(preview.x, preview.y)
            if (x >= tmpRect.left - margin && x <= tmpRect.right + margin &&
                y >= tmpRect.top - margin && y <= tmpRect.bottom + margin
            ) {
                return note
            }
        }
        return null
    }

    /** NOTE tool down: if a note is under the pointer, start a press that may become a drag. */
    private fun startNotePress(x: Float, y: Float): Boolean {
        val layout = viewport.layout ?: return false
        val page = pageAt(x, y)
        if (page < 0) return false
        val note = noteAt(page, toPageX(layout, page, x), toPageY(layout, page, y)) ?: return false
        parent?.requestDisallowInterceptTouchEvent(true)
        dragNote = note
        dragPage = page
        dragActive = false
        val preview = notePreviews[note.id]
        dragX = preview?.x ?: note.x
        dragY = preview?.y ?: note.y
        mode = Mode.NOTE_PRESS
        return true
    }

    private fun onNoteDragMove(event: MotionEvent) {
        val note = dragNote ?: return
        val layout = viewport.layout ?: return
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        val dx = event.getX(index) - downX
        val dy = event.getY(index) - downY
        if (!dragActive && hypot(dx, dy) <= touchSlop) return
        dragActive = true
        val scale = layout.pxPerPt(dragPage, viewport.zoom)
        painter.noteBoundsInto(note, tmpRect)
        val preview = notePreviews[note.id]
        val startX = preview?.x ?: note.x
        val startY = preview?.y ?: note.y
        dragX = (startX + dx / scale).coerceIn(0f, max(0f, layout.pageWidthPt(dragPage) - tmpRect.width()))
        dragY = (startY + dy / scale).coerceIn(0f, max(0f, layout.pageHeightPt(dragPage) - tmpRect.height()))
        invalidate()
    }

    private fun finishNotePress(cancelled: Boolean) {
        val note = dragNote ?: return
        dragNote = null
        invalidate()
        if (cancelled) return
        if (!dragActive) {
            if (!caughtMotion) listener?.onNoteTapped(dragPage, note.id)
            return
        }
        if (dragX != note.x || dragY != note.y) {
            notePreviews[note.id] = NotePreview(dragPage, dragX, dragY, SystemClock.uptimeMillis())
            listener?.onNoteMoved(dragPage, note.id, dragX, dragY)
        }
    }

    // ---- Overlay bookkeeping -----------------------------------------------------------------

    /**
     * Drops local overlays the new snapshot has caught up with: pending strokes now present, erased
     * strokes now absent, moved notes now at their new spot. Anything older than
     * [OVERLAY_TIMEOUT_MS] is dropped too, so a rejected edit cannot leave a ghost behind.
     */
    private fun pruneOverlays(now: Long) {
        // A pending stroke the user already erased is never shown again, whatever the snapshot says.
        pendingStrokes.removeAll { p ->
            now - p.time > OVERLAY_TIMEOUT_MS || p.stroke.id in erasedAwaitingSnapshot ||
                pageAnnotations(p.page).strokes.any { it.id == p.stroke.id }
        }
        if (erasedAwaitingSnapshot.isNotEmpty()) {
            val it = erasedAwaitingSnapshot.entries.iterator()
            while (it.hasNext()) {
                val (id, erased) = it.next()
                val stillThere = pageAnnotations(erased.page).strokes.any { s -> s.id == id }
                if (!stillThere || now - erased.time > OVERLAY_TIMEOUT_MS) {
                    it.remove()
                    if (eraseGesture[erased.page]?.contains(id) != true) hiddenStrokeIds.remove(id)
                }
            }
        }
        if (notePreviews.isNotEmpty()) {
            val it = notePreviews.entries.iterator()
            while (it.hasNext()) {
                val (id, preview) = it.next()
                val note = pageAnnotations(preview.page).notes.firstOrNull { n -> n.id == id }
                if (note == null || now - preview.time > OVERLAY_TIMEOUT_MS ||
                    (abs(note.x - preview.x) < 0.01f && abs(note.y - preview.y) < 0.01f)
                ) {
                    it.remove()
                }
            }
        }
    }

    // ---- Hover, edge exclusion, mouse wheel --------------------------------------------------

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!isStylus(event, 0)) return super.onHoverEvent(event)
        lastStylusUptime = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hoverVisible = viewport.layout != null
                hoverX = event.x
                hoverY = event.y
                hoverErasing = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                    (event.buttonState and STYLUS_BUTTONS) != 0
                if (isInkTool(toolState.tool) || hoverErasing) updateGestureExclusion(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hoverVisible) invalidate()
                hoverVisible = false
                // Delayed: HOVER_EXIT also precedes a touch-down at the edge, which still needs the band.
                scheduleExclusionClear()
            }
        }
        return true
    }

    /**
     * Keeps the system back gesture from stealing pen strokes that start near the left/right screen
     * edge: a [EXCLUSION_BAND_DP] band centred on the pen while an ink tool is active.
     */
    private fun updateGestureExclusion(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < 29) return
        cancelScheduledExclusionClear()
        val edge = EXCLUSION_EDGE_DP * density
        val near = x < edge || x > width - edge
        if (!near) {
            clearGestureExclusion()
            return
        }
        if (exclusionActive && abs(y - exclusionY) < EXCLUSION_UPDATE_DP * density) return
        exclusionActive = true
        exclusionY = y
        val half = EXCLUSION_BAND_DP * density / 2f
        val top = (y - half).roundToInt()
        val bottom = (y + half).roundToInt()
        val e = edge.roundToInt()
        systemGestureExclusionRects = listOf(Rect(0, top, e, bottom), Rect(width - e, top, width, bottom))
    }

    private fun clearGestureExclusion() {
        cancelScheduledExclusionClear()
        if (!exclusionActive || Build.VERSION.SDK_INT < 29) return
        exclusionActive = false
        systemGestureExclusionRects = emptyList()
    }

    /**
     * The pen left (hover exit, lift, cancel): drop the edge exclusion shortly afterwards so finger
     * back swipes work again, unless the pen comes back first (hover/touch re-arms it).
     */
    private fun scheduleExclusionClear() {
        if (!exclusionActive) return
        removeCallbacks(clearExclusionLater)
        postDelayed(clearExclusionLater, EXCLUSION_LINGER_MS)
        exclusionClearPending = true
    }

    private fun cancelScheduledExclusionClear() {
        if (!exclusionClearPending) return
        exclusionClearPending = false
        removeCallbacks(clearExclusionLater)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val layout = viewport.layout
        if (layout != null && layout.isConfigured && event.actionMasked == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        ) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            stopMotion()
            if ((event.metaState and KeyEvent.META_CTRL_ON) != 0) {
                zoomAround(viewport.zoom * (1f + WHEEL_ZOOM_STEP * v), event.x, event.y)
            } else {
                val step = WHEEL_STEP_DP * density
                setScroll(viewport.scrollX + hScroll * step, viewport.scrollY - v * step)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun isStylus(event: MotionEvent, index: Int): Boolean {
        val type = event.getToolType(index)
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun isInkTool(tool: Tool): Boolean = tool == Tool.HIGHLIGHTER || tool == Tool.PEN || tool == Tool.ERASER

    private companion object {
        const val DEFAULT_BACKDROP = 0xFFE9EAF0.toInt()
        const val STYLUS_BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY

        const val PALM_GUARD_MS = 400L
        const val STYLUS_TAP_SLOP_PX = 8f
        const val STYLUS_TAP_MILLIS = 300L
        const val ERASER_RADIUS_DP = 10f
        const val NOTE_TOUCH_MARGIN_DP = 4f

        const val HOLD_RADIUS_DP = 3f
        const val HOLD_MILLIS = 550L
        const val HOLD_MIN_LENGTH_DP = 24f

        const val MIN_POINT_DISTANCE_PT = 0.05f
        const val DOT_TOLERANCE_PT = 0.5f
        const val MIN_HIGHLIGHT_LENGTH_PT = 2f

        const val PREDICTION_NANOS = 16_000_000L
        const val MAX_PREDICTED_SAMPLES = 4
        const val MAX_TAIL_DP = 24f

        const val DOUBLE_TAP_THRESHOLD = 1.8f
        const val DOUBLE_TAP_ZOOM = 2.5f
        const val SCROLL_ANIMATION_MS = 250L
        const val ZOOM_ANIMATION_MS = 250L
        const val OVERLAY_TIMEOUT_MS = 3000L

        const val EXCLUSION_EDGE_DP = 48f
        const val EXCLUSION_BAND_DP = 200f
        const val EXCLUSION_UPDATE_DP = 16f
        const val EXCLUSION_LINGER_MS = 750L

        const val WHEEL_STEP_DP = 64f
        const val WHEEL_ZOOM_STEP = 0.1f
    }
}
