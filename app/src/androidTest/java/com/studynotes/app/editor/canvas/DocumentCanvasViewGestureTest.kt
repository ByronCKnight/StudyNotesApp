package com.studynotes.app.editor.canvas

import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.model.InkTool
import com.studynotes.app.model.Note
import com.studynotes.app.model.PageAnnotations
import com.studynotes.app.model.Palette
import com.studynotes.app.model.Stroke
import com.studynotes.app.model.Tool
import com.studynotes.app.model.ToolState
import com.studynotes.app.model.ViewState
import com.studynotes.app.pdf.PdfDocumentHandle
import com.studynotes.app.testutil.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min

/**
 * Pen / finger routing of [DocumentCanvasView] with real MotionEvents dispatched on the main thread,
 * a real [PdfDocumentHandle] ("Biology Lecture Notes.pdf") and a recording [CanvasListener].
 *
 * Every gesture is dispatched inside ONE main-thread task, so the hold-to-straighten check (posted
 * 550 ms later on the main looper) can never fire mid-gesture. Event times are synthetic and chosen
 * relative to the view's timing rules (400 ms palm guard, 150 ms second-finger window).
 *
 * Geometry: the document opens at ViewState(page 0, offset 0, zoom 1), so page 0's top-left corner is
 * at (12 dp, 12 dp) in the view and the page is scaled to fit the width minus 2 x 12 dp. Points are
 * chosen in page space and converted to view pixels with that mapping.
 */
@RunWith(AndroidJUnit4::class)
class DocumentCanvasViewGestureTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var handle: PdfDocumentHandle
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var view: DocumentCanvasView
    private lateinit var geo: Geometry
    private val listener = RecordingListener()
    private var touchSlop = 0f

    // ---- Setup ---------------------------------------------------------------------------------

    @Before
    fun setUp() {
        val file = Fixtures.copy(Fixtures.LECTURE_NOTES)
        handle = runBlocking { PdfDocumentHandle.open(file) }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            view = DocumentCanvasView(activity)
            view.listener = listener
            activity.setContentView(
                view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            view.setDocument(handle, ViewState())
        }
        waitUntil("canvas laid out") { view.isLaidOut && view.width > 0 && view.height > 0 }
        onMain {
            val density = view.resources.displayMetrics.density
            val margin = 12f * density
            val size = handle.pageSizes[0]
            geo = Geometry(
                left = margin,
                top = margin,
                scale = (view.width - 2f * margin) / size.width,
                viewWidth = view.width,
                viewHeight = view.height,
                pageWidthPt = size.width,
                pageHeightPt = size.height,
                density = density,
            )
            touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
            assertEquals("document should open at the top", ViewState(0, 0f, 1f), view.currentViewState())
        }
        listener.clear()
    }

    @After
    fun tearDown() {
        if (::view.isInitialized) onMain { view.release() }
        if (::scenario.isInitialized) scenario.close()
        if (::handle.isInitialized) handle.close()
    }

    // ---- Stylus ink ----------------------------------------------------------------------------

    @Test
    fun stylusHighlighterStroke_addsOneStrokeInPageSpace() {
        val tools = ToolState(tool = Tool.HIGHLIGHTER)
        configure(tools, stylusOnly = true)
        val y = geo.visibleHeightPt * 0.4f
        val t0 = SystemClock.uptimeMillis()
        dispatch(
            Gesture(t0).down(PEN, STYLUS, geo.toView(100f, y))
                .drag(PEN, geo.toView(400f, y), steps = 20)
                .up(PEN),
        )

        val added = listener.added.single()
        assertEquals(0, added.page)
        val stroke = added.strokes.single()
        assertEquals(InkTool.HIGHLIGHTER, stroke.tool)
        assertEquals(tools.highlighterColor, stroke.color)
        assertEquals(tools.highlighterWidth, stroke.width, 0f)
        assertBounds(stroke, minX = 100f, maxX = 400f, minY = y, maxY = y)
        assertEquals("onStylusDetected once", 1, listener.stylusDetected.get())
        assertTrue(listener.erased.isEmpty())
    }

    @Test
    fun stylusPenStroke_diagonal_keepsBothEndpoints() {
        val tools = ToolState(tool = Tool.PEN, penColor = Palette.PEN_COLORS[2], penWidth = Palette.PEN_WIDTHS[2])
        configure(tools, stylusOnly = true)
        val y0 = geo.visibleHeightPt * 0.2f
        val y1 = geo.visibleHeightPt * 0.6f
        val t0 = SystemClock.uptimeMillis()
        dispatch(
            Gesture(t0).down(PEN, STYLUS, geo.toView(80f, y0))
                .drag(PEN, geo.toView(480f, y1), steps = 30)
                .up(PEN),
        )

        val added = listener.added.single()
        assertEquals(0, added.page)
        val stroke = added.strokes.single()
        assertEquals(InkTool.PEN, stroke.tool)
        assertEquals(tools.penColor, stroke.color)
        assertEquals(tools.penWidth, stroke.width, 0f)
        val p = stroke.points
        assertNear("start x", 80f, p[0])
        assertNear("start y", y0, p[1])
        assertNear("end x", 480f, p[p.size - 2])
        assertNear("end y", y1, p[p.size - 1])
        // Every point lies on the drawn line (simplification keeps a straight drag straight).
        for (i in 0 until stroke.pointCount) {
            val x = p[2 * i]
            val expectedY = y0 + (x - 80f) / 400f * (y1 - y0)
            assertNear("point $i on the line", expectedY, p[2 * i + 1])
        }
    }

    @Test
    fun stylusPenTap_makesSingleDot() {
        configure(ToolState(tool = Tool.PEN), stylusOnly = true)
        val at = PointF(250f, geo.visibleHeightPt * 0.5f)
        val t0 = SystemClock.uptimeMillis()
        dispatch(Gesture(t0).down(PEN, STYLUS, geo.toView(at.x, at.y)).up(PEN, t0 + 60))

        val stroke = listener.added.single().strokes.single()
        assertEquals("a pen tap is a dot", 1, stroke.pointCount)
        assertNear("dot x", at.x, stroke.points[0])
        assertNear("dot y", at.y, stroke.points[1])
    }

    @Test
    fun stylusHighlighterTap_isIgnored() {
        configure(ToolState(tool = Tool.HIGHLIGHTER), stylusOnly = true)
        val t0 = SystemClock.uptimeMillis()
        dispatch(Gesture(t0).down(PEN, STYLUS, geo.toView(250f, 200f.coerceAtMost(geo.visibleHeightPt / 2))).up(PEN, t0 + 60))
        assertTrue("highlighter taps shorter than 2 pt are ignored", listener.added.isEmpty())
    }

    // ---- Straight lines and snap to text ---------------------------------------------------------

    @Test
    fun straightHighlighter_wavyStroke_becomesStartToEndSegment() {
        configure(ToolState(tool = Tool.HIGHLIGHTER, straightHighlighter = true), stylusOnly = true)
        val y = geo.visibleHeightPt * 0.4f
        val g = zigzag(Gesture(SystemClock.uptimeMillis()), from = 100f, to = 340f, y = y, amplitude = 12f)
        dispatch(g.up(PEN))

        val stroke = listener.added.single().strokes.single()
        assertEquals("ruler mode: a two-point segment", 2, stroke.pointCount)
        assertNear("start x", 100f, stroke.points[0])
        assertNear("start y", y, stroke.points[1])
        assertNear("end x", 340f, stroke.points[2])
        assertNear("end y", y, stroke.points[3])
    }

    @Test
    fun holdToStraighten_penHeldStill_turnsStrokeIntoSegment() {
        configure(ToolState(tool = Tool.PEN), stylusOnly = true)
        val y = geo.visibleHeightPt * 0.4f
        val g = zigzag(Gesture(SystemClock.uptimeMillis()), from = 100f, to = 340f, y = y, amplitude = 12f)
        val lastMove = g.lastTime
        dispatch(g)
        // Hold the pen still (no events) past HOLD_MILLIS = 550 ms; the view checks on its own timer.
        while (SystemClock.uptimeMillis() < lastMove + 550 + 350) Thread.sleep(25)
        dispatch(g.up(PEN, time = SystemClock.uptimeMillis()))

        val stroke = listener.added.single().strokes.single()
        assertEquals("held still: straightened to start -> current point", 2, stroke.pointCount)
        assertNear("start x", 100f, stroke.points[0])
        assertNear("end x", 340f, stroke.points[2])
        assertNear("end y", y, stroke.points[3])
    }

    @Test
    fun withoutHold_wavyPenStroke_staysFreehand() {
        configure(ToolState(tool = Tool.PEN), stylusOnly = true)
        val y = geo.visibleHeightPt * 0.4f
        val g = zigzag(Gesture(SystemClock.uptimeMillis()), from = 100f, to = 340f, y = y, amplitude = 12f)
        dispatch(g.up(PEN))
        val stroke = listener.added.single().strokes.single()
        assertTrue("freehand zigzag keeps its corners (${stroke.pointCount} points)", stroke.pointCount > 6)
        val xs = (0 until stroke.pointCount).map { stroke.points[2 * it] }
        val ys = (0 until stroke.pointCount).map { stroke.points[2 * it + 1] }
        assertNear("start x", 100f, xs.min())
        assertNear("end x", 340f, xs.max())
        // (1,2,1)/4 smoothing rounds the ±12 pt corners to about ±9 pt.
        assertTrue("zigzag amplitude kept: y ${ys.min()}..${ys.max()} around $y", ys.min() in (y - 12.5f)..(y - 7f) && ys.max() in (y + 7f)..(y + 12.5f))
    }

    /** Pen down at page ([from], [y]), then a zigzag (±[amplitude]) ending at ([to], [y]). No lift. */
    private fun zigzag(g: Gesture, from: Float, to: Float, y: Float, amplitude: Float, segments: Int = 8): Gesture {
        g.down(PEN, STYLUS, geo.toView(from, y))
        for (k in 1..segments) {
            val x = from + (to - from) * k / segments
            val yk = when {
                k == segments -> y
                k % 2 == 1 -> y - amplitude
                else -> y + amplitude
            }
            g.drag(PEN, geo.toView(x, yk), steps = 4)
        }
        return g
    }

    @Test
    fun snapToText_highlightAcrossTitle_becomesFlatLineStroke() {
        assumeTrue("snap to text needs text selection (MODERN backend)", handle.supportsTextSelection)
        val tools = ToolState(tool = Tool.HIGHLIGHTER, snapToText = true)
        configure(tools, stylusOnly = true)
        // The page-1 title "Chapter 1: Photosynthesis": 20 pt Helvetica, baseline y = 72, x 56..~291.
        // A highlight drawn through the middle of its x-height from x = 62 to x = 238 (mid-word).
        val y = 66f
        assumeTrue("title must be on screen", geo.visibleHeightPt > 100f)
        dispatch(Gesture(SystemClock.uptimeMillis()).down(PEN, STYLUS, geo.toView(62f, y)).drag(PEN, geo.toView(238f, y), steps = 20).up(PEN))
        waitUntil("snapped highlight", timeoutMs = 5_000) { listener.added.isNotEmpty() }

        val added = listener.added.single()
        assertEquals(0, added.page)
        val stroke = added.strokes.single()
        Log.i(TAG, "snapped: width ${stroke.width}, points ${stroke.points.toList()}")
        assertTrue("snapped strokes have flat caps", stroke.flatCaps)
        assertTrue("snapped strokes share a group", stroke.groupId != null)
        assertEquals(tools.highlighterColor, stroke.color)
        assertEquals("one horizontal segment per line", 2, stroke.pointCount)
        val p = stroke.points
        assertTrue("starts at the first glyph: ${p.toList()}", p[0] in 52f..64f)
        // The stroke ends inside "Photosynthesis"; the snap takes the whole word (title ends near 291).
        assertTrue("ends at the end of the last word touched: ${p.toList()}", p[2] in 282f..298f)
        assertEquals("centred on the line", p[1], p[3], 0f)
        assertTrue("centred on the title line: ${p[1]}", p[1] in 62f..72f)
        assertTrue("width = line height x 1.15: ${stroke.width}", stroke.width in 16f..32f)
    }

    // ---- Fingers in stylus-only mode, palm rejection -------------------------------------------

    @Test
    fun fingerDrag_stylusOnly_scrollsAndAddsNothing() {
        configure(ToolState(tool = Tool.HIGHLIGHTER), stylusOnly = true)
        val x = geo.viewWidth / 2f
        val from = geo.viewHeight * 0.7f
        val to = geo.viewHeight * 0.3f
        val t0 = SystemClock.uptimeMillis()
        val state = dispatch(
            Gesture(t0).down(F1, FINGER, PointF(x, from))
                .drag(F1, PointF(x, to), steps = 12)
                .up(F1),
        ) { view.currentViewState() }

        assertTrue("finger must not draw", listener.added.isEmpty())
        assertEquals(0, state.page)
        assertEquals("scrolled by the drag distance (px)", from - to, scrollPx(state), 2f)
        assertEquals(0, listener.stylusDetected.get())
    }

    @Test
    fun palmGuard_fingerWithin400msOfPenLiftIsIgnored_laterFingerScrolls() {
        configure(ToolState(tool = Tool.HIGHLIGHTER), stylusOnly = true)
        val y = geo.visibleHeightPt * 0.3f
        val t0 = SystemClock.uptimeMillis()
        val pen = Gesture(t0).down(PEN, STYLUS, geo.toView(100f, y)).drag(PEN, geo.toView(300f, y), steps = 10).up(PEN)
        val penUp = pen.lastTime
        dispatch(pen)
        assertEquals(1, listener.added.size)

        val x = geo.viewWidth / 2f
        val from = geo.viewHeight * 0.7f
        val to = geo.viewHeight * 0.4f
        // 300 ms after the pen lifted: inside the palm guard.
        val palmDown = penUp + 300
        val stateAfterPalm = dispatch(
            Gesture(palmDown).down(F1, FINGER, PointF(x, from)).drag(F1, PointF(x, to), steps = 8).up(F1),
        ) { view.currentViewState() }
        assertEquals("a finger 300 ms after the pen is a palm: no scroll", ViewState(0, 0f, 1f), stateAfterPalm)

        // 450 ms after the pen lifted: outside the guard, the finger scrolls.
        val fingerDown = penUp + 450
        val stateAfterFinger = dispatch(
            Gesture(fingerDown).down(F1, FINGER, PointF(x, from)).drag(F1, PointF(x, to), steps = 8).up(F1),
        ) { view.currentViewState() }
        assertEquals(from - to, scrollPx(stateAfterFinger), 2f)
        assertEquals("only the pen stroke was added", 1, listener.added.size)
    }

    @Test
    fun palmDuringPenStroke_isIgnored() {
        configure(ToolState(tool = Tool.PEN), stylusOnly = true)
        val y = geo.visibleHeightPt * 0.3f
        val palm = PointF(geo.viewWidth * 0.8f, geo.viewHeight * 0.85f)
        val t0 = SystemClock.uptimeMillis()
        val g = Gesture(t0).down(PEN, STYLUS, geo.toView(100f, y))
            .drag(PEN, geo.toView(200f, y), steps = 8)
        g.down(F1, FINGER, palm, g.lastTime + 8)
        g.drag(PEN, geo.toView(400f, y), steps = 12, others = mapOf(F1 to PointF(palm.x - 200f, palm.y - 300f)))
        g.up(PEN) // pen lifts first (POINTER_UP), the palm last (ACTION_UP)
        g.up(F1)
        val state = dispatch(g) { view.currentViewState() }

        val stroke = listener.added.single().strokes.single()
        assertBounds(stroke, minX = 100f, maxX = 400f, minY = y, maxY = y)
        assertEquals("the palm must not scroll", ViewState(0, 0f, 1f), state)
    }

    @Test
    fun pinch_withTwoFingers_zoomsAndAddsNothing() {
        configure(ToolState(tool = Tool.HIGHLIGHTER), stylusOnly = true)
        val cx = geo.viewWidth / 2f
        val cy = geo.viewHeight / 2f
        val small = min(geo.viewWidth, geo.viewHeight) * 0.18f
        val large = min(geo.viewWidth, geo.viewHeight) * 0.42f
        val t0 = SystemClock.uptimeMillis()
        val g = Gesture(t0).down(F1, FINGER, PointF(cx - small, cy - small))
        g.down(F2, FINGER, PointF(cx + small, cy + small), t0 + 10)
        g.drag(F1, PointF(cx - large, cy - large), steps = 12, others = mapOf(F2 to PointF(cx + large, cy + large)))
        g.up(F2).up(F1)
        val state = dispatch(g) { view.currentViewState() }

        assertTrue("zoom should grow, was ${state.zoom}", state.zoom > 1.2f)
        assertTrue(listener.added.isEmpty())
    }

    @Test
    fun penJoiningAFingerGesture_takesOverAndDraws_fingerNoLongerPans() {
        configure(ToolState(tool = Tool.PEN), stylusOnly = true)
        val y = geo.visibleHeightPt * 0.3f
        val finger = PointF(geo.viewWidth * 0.8f, geo.viewHeight * 0.8f)
        val t0 = SystemClock.uptimeMillis()
        val g = Gesture(t0).down(F1, FINGER, finger)
        // The pen lands while the finger is down: follow the pen, the finger becomes a palm.
        g.down(PEN, STYLUS, geo.toView(120f, y), t0 + 40)
        g.drag(PEN, geo.toView(420f, y), steps = 15, others = mapOf(F1 to PointF(finger.x, finger.y - 300f)))
        g.up(PEN).up(F1)
        val state = dispatch(g) { view.currentViewState() }

        val stroke = listener.added.single().strokes.single()
        assertBounds(stroke, minX = 120f, maxX = 420f, minY = y, maxY = y)
        assertEquals("the finger must not pan once the pen is down", ViewState(0, 0f, 1f), state)
        assertEquals(1, listener.stylusDetected.get())
    }

    @Test
    fun touchThatCatchesAFling_isNotATap() {
        configure(ToolState(tool = Tool.NOTE), stylusOnly = true)
        val x = geo.viewWidth / 2f
        val tap = geo.toView(300f, geo.visibleHeightPt * 0.3f)
        fun flick(start: Long) = Gesture(start).down(F1, FINGER, PointF(x, geo.viewHeight * 0.75f))
            .drag(F1, PointF(x, geo.viewHeight * 0.55f), steps = 5, dt = 8)
            .up(F1)

        // A pen tap right after a flick (same main-thread task, so the fling is still running).
        val t0 = SystemClock.uptimeMillis()
        val flick1 = flick(t0)
        val penCatch = Gesture(flick1.lastTime + 30).down(PEN, STYLUS, tap).up(PEN, flick1.lastTime + 90)
        val caughtAt = dispatchTogether(flick1, penCatch)
        Thread.sleep(200)
        assertEquals("the pen touch stopped the fling", caughtAt, onMain { view.currentViewState() })
        assertTrue("a pen touch that stops a fling must not place a note", listener.placements.isEmpty())

        // The same with a finger tap.
        val t1 = SystemClock.uptimeMillis()
        val flick2 = flick(t1)
        val fingerCatch = Gesture(flick2.lastTime + 30).down(F2, FINGER, tap).up(F2, flick2.lastTime + 90)
        val caughtAt2 = dispatchTogether(flick2, fingerCatch)
        // A finger single tap is confirmed after the double-tap timeout: wait past it.
        Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 300L)
        assertEquals("the finger touch stopped the fling", caughtAt2, onMain { view.currentViewState() })
        assertTrue("a finger touch that stops a fling must not place a note", listener.placements.isEmpty())

        // Control: the same flick alone keeps scrolling after the finger lifts (it really flings).
        val atLift = dispatchTogether(flick(SystemClock.uptimeMillis()))
        Thread.sleep(200)
        assertNotEquals("a flick flings", atLift, onMain { view.currentViewState() })
    }

    /** Dispatches several gestures back to back in ONE main-thread task; returns the view state after. */
    private fun dispatchTogether(vararg gestures: Gesture): ViewState {
        val events = gestures.flatMap { it.takeEvents() }
        return onMain {
            try {
                for (e in events) view.dispatchTouchEvent(e)
            } finally {
                events.forEach { it.recycle() }
            }
            view.currentViewState()
        }
    }

    @Test
    fun fingerDoubleTap_inViewTool_zoomsTo2_5() {
        configure(ToolState(tool = Tool.VIEW), stylusOnly = true)
        val at = PointF(geo.viewWidth / 2f, geo.viewHeight / 3f)
        val t0 = SystemClock.uptimeMillis()
        // Both taps in one main-thread task: the first tap's confirmation is still pending.
        dispatchTogether(
            Gesture(t0).down(F1, FINGER, at).up(F1, t0 + 50),
            Gesture(t0 + 150).down(F1, FINGER, at).up(F1, t0 + 200),
        )
        waitUntil("double-tap zoom animation", timeoutMs = 3_000) { abs(view.currentViewState().zoom - 2.5f) < 0.001f }
        assertTrue(listener.added.isEmpty())
        assertTrue(listener.placements.isEmpty())
    }

    // ---- Cancellation ----------------------------------------------------------------------------

    @Test
    fun actionCancel_midStroke_addsNothing_nextStrokeStillWorks() {
        configure(ToolState(tool = Tool.HIGHLIGHTER), stylusOnly = true)
        val y = geo.visibleHeightPt * 0.3f
        val t0 = SystemClock.uptimeMillis()
        val cancelled = Gesture(t0).down(PEN, STYLUS, geo.toView(100f, y)).drag(PEN, geo.toView(350f, y), steps = 15)
        cancelled.cancel()
        dispatch(cancelled)
        assertTrue("ACTION_CANCEL must discard the stroke", listener.added.isEmpty())

        val t1 = cancelled.lastTime + 50
        dispatch(Gesture(t1).down(PEN, STYLUS, geo.toView(100f, y + 40f)).drag(PEN, geo.toView(350f, y + 40f), steps = 15).up(PEN))
        val stroke = listener.added.single().strokes.single()
        assertBounds(stroke, minX = 100f, maxX = 350f, minY = y + 40f, maxY = y + 40f)
    }

    @Test
    fun flagCanceledPointerUp_midStroke_addsNothing() {
        assumeTrue("FLAG_CANCELED exists from API 33", Build.VERSION.SDK_INT >= 33)
        configure(ToolState(tool = Tool.PEN), stylusOnly = true)
        val y = geo.visibleHeightPt * 0.3f
        val palm = PointF(geo.viewWidth * 0.8f, geo.viewHeight * 0.85f)
        val t0 = SystemClock.uptimeMillis()
        val g = Gesture(t0).down(PEN, STYLUS, geo.toView(100f, y)).drag(PEN, geo.toView(250f, y), steps = 10)
        g.down(F1, FINGER, palm, g.lastTime + 8)
        g.drag(PEN, geo.toView(400f, y), steps = 8, others = mapOf(F1 to palm))
        // The system withdrew the pen pointer: POINTER_UP with FLAG_CANCELED, then the palm lifts.
        g.up(PEN, flags = MotionEvent.FLAG_CANCELED)
        g.up(F1)
        dispatch(g)
        assertTrue("a FLAG_CANCELED pointer-up must discard the stroke", listener.added.isEmpty())
    }

    // ---- Eraser ----------------------------------------------------------------------------------

    @Test
    fun eraserTool_removesTouchedStrokeAndItsGroup_only() {
        val y = geo.visibleHeightPt * 0.3f
        val hit = Stroke("hit", InkTool.PEN, Palette.PEN_COLORS[0], 2f, floatArrayOf(100f, y, 400f, y), groupId = "g1")
        val sibling = Stroke("sibling", InkTool.HIGHLIGHTER, Palette.HIGHLIGHTER_COLORS[0], 14f, floatArrayOf(100f, y + 120f, 400f, y + 120f), groupId = "g1")
        val other = Stroke("other", InkTool.PEN, Palette.PEN_COLORS[1], 2f, floatArrayOf(100f, y + 60f, 400f, y + 60f))
        configure(ToolState(tool = Tool.ERASER), stylusOnly = true)
        onMain { view.setAnnotations(DocumentAnnotations(pages = mapOf(0 to PageAnnotations(strokes = listOf(hit, sibling, other))))) }
        val radiusPt = 10f * geo.density / geo.scale

        // A miss: between "hit" and "other", farther than the eraser radius from both.
        val t0 = SystemClock.uptimeMillis()
        assertTrue("fixture geometry: radius $radiusPt pt", radiusPt + 1f < 25f)
        dispatch(Gesture(t0).down(PEN, STYLUS, geo.toView(250f, y + 26f)).drag(PEN, geo.toView(260f, y + 34f), steps = 5).up(PEN))
        assertTrue("nothing within reach: no erase callback", listener.erased.isEmpty())

        // A hit: a vertical swipe across "hit" only.
        val t1 = t0 + 500
        dispatch(Gesture(t1).down(PEN, STYLUS, geo.toView(250f, y - 20f)).drag(PEN, geo.toView(250f, y + 20f), steps = 10).up(PEN))
        val (page, ids) = listener.erased.single()
        assertEquals(0, page)
        assertEquals("the touched stroke and its whole group", setOf("hit", "sibling"), ids)
        assertTrue("the eraser never adds ink", listener.added.isEmpty())
    }

    @Test
    fun stylusSideButton_erasesWithPenTool() {
        val y = geo.visibleHeightPt * 0.3f
        val target = Stroke("t", InkTool.PEN, Palette.PEN_COLORS[0], 2f, floatArrayOf(100f, y, 400f, y))
        configure(ToolState(tool = Tool.PEN), stylusOnly = true)
        onMain { view.setAnnotations(DocumentAnnotations(pages = mapOf(0 to PageAnnotations(strokes = listOf(target))))) }
        val t0 = SystemClock.uptimeMillis()
        val g = Gesture(t0)
        g.buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY
        dispatch(g.down(PEN, STYLUS, geo.toView(200f, y - 20f)).drag(PEN, geo.toView(200f, y + 20f), steps = 10).up(PEN))
        assertEquals(listOf(0 to setOf("t")), listener.erased.toList())
        assertTrue(listener.added.isEmpty())
    }

    @Test
    fun eraserEndOfPen_erasesWithHighlighterTool() {
        val y = geo.visibleHeightPt * 0.3f
        val target = Stroke("t", InkTool.HIGHLIGHTER, Palette.HIGHLIGHTER_COLORS[1], 14f, floatArrayOf(100f, y, 400f, y))
        configure(ToolState(tool = Tool.HIGHLIGHTER), stylusOnly = true)
        onMain { view.setAnnotations(DocumentAnnotations(pages = mapOf(0 to PageAnnotations(strokes = listOf(target))))) }
        val t0 = SystemClock.uptimeMillis()
        dispatch(
            Gesture(t0).down(PEN, MotionEvent.TOOL_TYPE_ERASER, geo.toView(300f, y - 25f))
                .drag(PEN, geo.toView(300f, y + 25f), steps = 10).up(PEN),
        )
        assertEquals(listOf(0 to setOf("t")), listener.erased.toList())
        assertTrue(listener.added.isEmpty())
        assertEquals("TOOL_TYPE_ERASER counts as a stylus", 1, listener.stylusDetected.get())
    }

    // ---- Notes -----------------------------------------------------------------------------------

    @Test
    fun noteTool_stylusTap_requestsPlacementAtPagePoint() {
        configure(ToolState(tool = Tool.NOTE), stylusOnly = true)
        val at = PointF(321f, geo.visibleHeightPt * 0.45f)
        val t0 = SystemClock.uptimeMillis()
        dispatch(Gesture(t0).down(PEN, STYLUS, geo.toView(at.x, at.y)).up(PEN, t0 + 70))

        val (page, x, y) = listener.placements.single()
        assertEquals(0, page)
        assertEquals(at.x, x, 0.5f)
        assertEquals(at.y, y, 0.5f)
        assertTrue(listener.added.isEmpty())
    }

    @Test
    fun noteTool_fingerTap_requestsPlacementAfterDoubleTapTimeout() {
        configure(ToolState(tool = Tool.NOTE), stylusOnly = true)
        val at = PointF(150f, geo.visibleHeightPt * 0.6f)
        val t0 = SystemClock.uptimeMillis()
        dispatch(Gesture(t0).down(F1, FINGER, geo.toView(at.x, at.y)).up(F1, t0 + 60))

        // A finger single tap is confirmed only after the double-tap timeout (GestureDetector).
        assertTrue(
            "placement not requested within the double-tap timeout",
            listener.placementLatch.await(ViewConfiguration.getDoubleTapTimeout() + 3000L, TimeUnit.MILLISECONDS),
        )
        val (page, x, y) = listener.placements.single()
        assertEquals(0, page)
        assertEquals(at.x, x, 0.5f)
        assertEquals(at.y, y, 0.5f)
    }

    @Test
    fun noteTool_tapOnNoteOpensIt_dragMovesIt() {
        val note = Note("n1", 200f, geo.visibleHeightPt * 0.3f, "Remember", Palette.NOTE_COLORS[0], 1L, 1L)
        configure(ToolState(tool = Tool.NOTE), stylusOnly = true)
        onMain { view.setAnnotations(DocumentAnnotations(pages = mapOf(0 to PageAnnotations(notes = listOf(note))))) }
        val card = AnnotationPainter().noteBounds(note)
        val centre = geo.toView(card.centerX(), card.centerY())

        val t0 = SystemClock.uptimeMillis()
        dispatch(Gesture(t0).down(PEN, STYLUS, centre).up(PEN, t0 + 60))
        assertEquals(listOf(0 to "n1"), listener.noteTaps.toList())
        assertTrue("tapping a note never places another", listener.placements.isEmpty())

        val dxPt = 60f
        val dyPt = 30f
        val t1 = t0 + 500
        dispatch(
            Gesture(t1).down(PEN, STYLUS, centre)
                .drag(PEN, PointF(centre.x + dxPt * geo.scale, centre.y + dyPt * geo.scale), steps = 10)
                .up(PEN),
        )
        val move = listener.noteMoves.single()
        assertEquals(0, move.page)
        assertEquals("n1", move.noteId)
        assertEquals(note.x + dxPt, move.x, 0.5f)
        assertEquals(note.y + dyPt, move.y, 0.5f)
        assertTrue(listener.added.isEmpty())
    }

    // ---- Finger drawing (stylusOnly = false) ------------------------------------------------------

    @Test
    fun fingerDrawing_oneFingerDrawsPenStroke() {
        configure(ToolState(tool = Tool.PEN), stylusOnly = false)
        val y = geo.visibleHeightPt * 0.35f
        val t0 = SystemClock.uptimeMillis()
        val state = dispatch(
            Gesture(t0).down(F1, FINGER, geo.toView(100f, y)).drag(F1, geo.toView(380f, y), steps = 20).up(F1),
        ) { view.currentViewState() }

        val added = listener.added.single()
        assertEquals(0, added.page)
        val stroke = added.strokes.single()
        assertEquals(InkTool.PEN, stroke.tool)
        // The stroke starts at the touch-down point, not where the touch slop was passed.
        assertBounds(stroke, minX = 100f, maxX = 380f, minY = y, maxY = y)
        assertEquals("drawing must not scroll", ViewState(0, 0f, 1f), state)
        assertEquals(0, listener.stylusDetected.get())
    }

    @Test
    fun fingerDrawing_secondFingerWithin150ms_discardsStrokeAndPans() {
        configure(ToolState(tool = Tool.PEN), stylusOnly = false)
        val y = geo.visibleHeightPt * 0.35f
        val start = geo.toView(100f, y)
        val t0 = SystemClock.uptimeMillis()
        // The first finger is well past 3x touch slop (an established-looking stroke) by 90 ms...
        val g = Gesture(t0).down(F1, FINGER, start)
            .drag(F1, PointF(start.x + 8f * touchSlop, start.y), steps = 9, dt = 10)
        // ...but the second finger lands 100 ms after the first: that is a two-finger gesture.
        val f2 = PointF(start.x, start.y + 300f)
        g.down(F2, FINGER, f2, t0 + 100)
        g.drag(F1, PointF(start.x + 8f * touchSlop, start.y - 250f), steps = 10, others = mapOf(F2 to PointF(f2.x, f2.y - 250f)))
        g.up(F2).up(F1)
        val state = dispatch(g) { view.currentViewState() }

        assertTrue("stroke must be discarded (no onStrokesAdded)", listener.added.isEmpty())
        assertNotEquals("two fingers pan instead", ViewState(0, 0f, 1f), state)
    }

    @Test
    fun fingerDrawing_secondFingerBeforeStrokeIsEstablished_discardsStroke() {
        configure(ToolState(tool = Tool.PEN), stylusOnly = false)
        val y = geo.visibleHeightPt * 0.35f
        val start = geo.toView(100f, y)
        val t0 = SystemClock.uptimeMillis()
        // Past the touch slop (ink has started) but still under 3x touch slop...
        val g = Gesture(t0).down(F1, FINGER, start)
            .drag(F1, PointF(start.x + 2f * touchSlop, start.y), steps = 10, dt = 20)
        // ...when the second finger lands 300 ms later (outside the 150 ms window).
        val f2 = PointF(start.x, start.y + 300f)
        g.down(F2, FINGER, f2, t0 + 300)
        g.drag(F1, PointF(start.x + 2f * touchSlop, start.y - 200f), steps = 10, others = mapOf(F2 to PointF(f2.x, f2.y - 200f)))
        g.up(F1).up(F2)
        dispatch(g)

        assertTrue("stroke under 3x touch slop must be discarded", listener.added.isEmpty())
    }

    @Test
    fun fingerDrawing_secondFingerAfterEstablishedStroke_isIgnored() {
        configure(ToolState(tool = Tool.PEN), stylusOnly = false)
        val y = geo.visibleHeightPt * 0.35f
        val start = geo.toView(100f, y)
        val t0 = SystemClock.uptimeMillis()
        val g = Gesture(t0).down(F1, FINGER, start)
            .drag(F1, PointF(start.x + 8f * touchSlop, start.y), steps = 10, dt = 15)
        // 250 ms after the first finger and > 3x touch slop into the stroke: the thumb holding the
        // phone. It is ignored, the stroke continues and nothing scrolls.
        val thumb = PointF(geo.viewWidth * 0.85f, geo.viewHeight * 0.85f)
        g.down(F2, FINGER, thumb, t0 + 250)
        g.drag(F1, PointF(start.x + 12f * touchSlop, start.y), steps = 8, others = mapOf(F2 to PointF(thumb.x - 100f, thumb.y - 300f)))
        g.up(F2)
        g.drag(F1, PointF(start.x + 14f * touchSlop, start.y), steps = 4)
        g.up(F1)
        val state = dispatch(g) { view.currentViewState() }

        val stroke = listener.added.single().strokes.single()
        val endX = 100f + 14f * touchSlop / geo.scale
        assertBounds(stroke, minX = 100f, maxX = endX, minY = y, maxY = y)
        assertEquals("the ignored finger must not scroll", ViewState(0, 0f, 1f), state)
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private fun configure(tools: ToolState, stylusOnly: Boolean) = onMain {
        view.setToolState(tools)
        view.setStylusOnly(stylusOnly)
    }

    /** Scroll offset (px) of a state on page 0 at zoom 1: the fraction of page 0's height in px. */
    private fun scrollPx(state: ViewState): Float {
        assertEquals("expected to stay on page 0", 0, state.page)
        assertEquals(1f, state.zoom, 0f)
        return state.pageOffsetFraction * geo.pageHeightPt * geo.scale
    }

    private fun assertBounds(stroke: Stroke, minX: Float, maxX: Float, minY: Float, maxY: Float, tol: Float = TOLERANCE_PT) {
        val p = stroke.points
        var x0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (i in 0 until stroke.pointCount) {
            x0 = min(x0, p[2 * i]); x1 = maxOf(x1, p[2 * i])
            y0 = min(y0, p[2 * i + 1]); y1 = maxOf(y1, p[2 * i + 1])
        }
        val actual = "x $x0..$x1, y $y0..$y1"
        assertTrue("min x $minX expected, $actual", abs(x0 - minX) <= tol)
        assertTrue("max x $maxX expected, $actual", abs(x1 - maxX) <= tol)
        assertTrue("min y $minY expected, $actual", abs(y0 - minY) <= tol)
        assertTrue("max y $maxY expected, $actual", abs(y1 - maxY) <= tol)
    }

    private fun assertNear(what: String, expected: Float, actual: Float, tol: Float = TOLERANCE_PT) =
        assertTrue("$what: expected $expected ± $tol, was $actual", abs(expected - actual) <= tol)

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /**
     * Dispatches the events [gesture] built so far in one main-thread task, then runs [after] in that
     * task. The gesture can be continued and dispatched again (e.g. after a real-time pause).
     */
    private fun <T> dispatch(gesture: Gesture, after: () -> T): T {
        val events = gesture.takeEvents()
        return onMain {
            try {
                for (e in events) view.dispatchTouchEvent(e)
            } finally {
                events.forEach { it.recycle() }
            }
            after()
        }
    }

    private fun dispatch(gesture: Gesture) = dispatch(gesture) { }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!onMain(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    private class Geometry(
        val left: Float,
        val top: Float,
        /** View px per page point on page 0 at zoom 1. */
        val scale: Float,
        val viewWidth: Int,
        val viewHeight: Int,
        val pageWidthPt: Float,
        val pageHeightPt: Float,
        val density: Float,
    ) {
        fun toView(x: Float, y: Float) = PointF(left + x * scale, top + y * scale)

        /** How much of page 0 (page points from its top) is inside the view. */
        val visibleHeightPt: Float get() = min(pageHeightPt, (viewHeight - top) / scale)
    }

    private data class Pointer(val id: Int, val toolType: Int, val x: Float, val y: Float)

    /** Builds a multi-pointer event sequence; pointer indices follow the order pointers went down. */
    private class Gesture(private val downTime: Long) {
        private val events = ArrayList<MotionEvent>()
        private var started = false
        var buttonState = 0
        var lastTime = downTime
            private set
        private val active = ArrayList<Pointer>()

        /** The events built since the last call; the caller dispatches and recycles them. */
        fun takeEvents(): List<MotionEvent> = ArrayList(events).also { events.clear() }

        fun down(id: Int, toolType: Int, at: PointF, time: Long = if (!started) downTime else lastTime + STEP_MS): Gesture {
            active += Pointer(id, toolType, at.x, at.y)
            val action = if (active.size == 1) {
                MotionEvent.ACTION_DOWN
            } else {
                MotionEvent.ACTION_POINTER_DOWN or ((active.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            add(time, action)
            return this
        }

        /** Moves pointer [id] in a straight line to [to] over [steps] MOVE events ([others] move along). */
        fun drag(id: Int, to: PointF, steps: Int, dt: Long = STEP_MS, others: Map<Int, PointF> = emptyMap()): Gesture {
            val starts = HashMap<Int, PointF>()
            for (p in active) starts[p.id] = PointF(p.x, p.y)
            val targets = others + (id to to)
            for (s in 1..steps) {
                val f = s.toFloat() / steps
                for (i in active.indices) {
                    val p = active[i]
                    val target = targets[p.id] ?: continue
                    val from = starts.getValue(p.id)
                    active[i] = p.copy(x = from.x + (target.x - from.x) * f, y = from.y + (target.y - from.y) * f)
                }
                add(lastTime + dt, MotionEvent.ACTION_MOVE)
            }
            return this
        }

        fun up(id: Int, time: Long = lastTime + STEP_MS, flags: Int = 0): Gesture {
            val index = active.indexOfFirst { it.id == id }
            check(index >= 0) { "pointer $id is not down" }
            val action = if (active.size == 1) {
                MotionEvent.ACTION_UP
            } else {
                MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            add(time, action, flags)
            active.removeAt(index)
            return this
        }

        fun cancel(time: Long = lastTime + STEP_MS): Gesture {
            add(time, MotionEvent.ACTION_CANCEL)
            active.clear()
            return this
        }

        private fun add(time: Long, action: Int, flags: Int = 0) {
            val props = Array(active.size) { i ->
                MotionEvent.PointerProperties().apply {
                    id = active[i].id
                    toolType = active[i].toolType
                }
            }
            val coords = Array(active.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = active[i].x
                    y = active[i].y
                    pressure = 1f
                    size = 0.1f
                }
            }
            val stylus = active.any { it.toolType == STYLUS || it.toolType == MotionEvent.TOOL_TYPE_ERASER }
            val source = if (stylus) InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
            events += MotionEvent.obtain(
                downTime, time, action, active.size, props, coords,
                0, buttonState, 1f, 1f, 0, 0, source, flags,
            )
            lastTime = time
            started = true
        }
    }

    private class RecordingListener : CanvasListener {
        class Added(val page: Int, val strokes: List<Stroke>)
        class Move(val page: Int, val noteId: String, val x: Float, val y: Float)

        val added = CopyOnWriteArrayList<Added>()
        val erased = CopyOnWriteArrayList<Pair<Int, Set<String>>>()
        val noteTaps = CopyOnWriteArrayList<Pair<Int, String>>()
        val placements = CopyOnWriteArrayList<Triple<Int, Float, Float>>()
        val noteMoves = CopyOnWriteArrayList<Move>()
        val stylusDetected = AtomicInteger()

        @Volatile
        var placementLatch = CountDownLatch(1)
            private set

        fun clear() {
            added.clear(); erased.clear(); noteTaps.clear(); placements.clear(); noteMoves.clear()
            stylusDetected.set(0)
            placementLatch = CountDownLatch(1)
        }

        override fun onStrokesAdded(pageIndex: Int, strokes: List<Stroke>) {
            added += Added(pageIndex, strokes)
        }

        override fun onStrokesErased(pageIndex: Int, strokeIds: Set<String>) {
            erased += pageIndex to strokeIds
        }

        override fun onNoteTapped(pageIndex: Int, noteId: String) {
            noteTaps += pageIndex to noteId
        }

        override fun onNotePlacementRequested(pageIndex: Int, x: Float, y: Float) {
            placements += Triple(pageIndex, x, y)
            placementLatch.countDown()
        }

        override fun onNoteMoved(pageIndex: Int, noteId: String, x: Float, y: Float) {
            noteMoves += Move(pageIndex, noteId, x, y)
        }

        override fun onCurrentPageChanged(pageIndex: Int) = Unit

        override fun onStylusDetected() {
            stylusDetected.incrementAndGet()
        }
    }

    private companion object {
        const val TAG = "CanvasGestureTest"
        const val PEN = 0
        const val F1 = 1
        const val F2 = 2
        const val STYLUS = MotionEvent.TOOL_TYPE_STYLUS
        const val FINGER = MotionEvent.TOOL_TYPE_FINGER
        const val STEP_MS = 8L

        /** Page-point tolerance: smoothing/simplification and pixel rounding at fit-width scale. */
        const val TOLERANCE_PT = 1.5f
    }
}
