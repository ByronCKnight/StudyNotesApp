package com.studynotes.app.editor.canvas

import android.content.Context
import android.view.MotionEvent
import android.view.MotionPredictor
import androidx.annotation.RequiresApi

/**
 * Thin wrapper around the platform [MotionPredictor] (API 34+). Predicted points are only drawn as
 * a short tail of the live stroke to hide display latency; they are never stored.
 *
 * Every call into the platform is guarded: a predictor problem only disables the predicted tail for
 * the current stroke, it never reaches onTouchEvent/onDraw.
 */
@RequiresApi(34)
internal class InkPredictor(private val context: Context) {
    /** Created lazily and dropped when its event stream could not be terminated cleanly. */
    private var predictor: MotionPredictor? = null
    private var available = false

    /** Starts a gesture with its ACTION_DOWN (or POINTER_DOWN) event. */
    fun start(event: MotionEvent) {
        val p = predictor ?: MotionPredictor(context).also { predictor = it }
        available = try {
            p.isPredictionAvailable(event.deviceId, event.source)
        } catch (_: RuntimeException) {
            false
        }
        if (available) recordSafely(event)
    }

    fun record(event: MotionEvent) {
        if (available) recordSafely(event)
    }

    /**
     * Ends the stroke. The platform predictor only forgets the stream (its sample history and the
     * device it came from) when it records an UP/CANCEL, so the stream is closed with a copy of
     * [event] turned into ACTION_CANCEL (a copy keeps the deviceId and source the predictor checks).
     * Without an event the predictor is dropped and a fresh one is created for the next stroke.
     */
    fun end(event: MotionEvent?) {
        if (available) {
            if (event != null) {
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                recordSafely(cancel)
                cancel.recycle()
            } else {
                predictor = null
            }
        }
        available = false
    }

    /**
     * Predicted event ~[aheadNanos] in the future, or null. The caller must recycle it.
     * MotionPredictor expects CLOCK_MONOTONIC nanoseconds, which is System.nanoTime() on Android
     * (SystemClock.uptimeNanos() is API 35+ and must not be used here).
     */
    fun predict(aheadNanos: Long): MotionEvent? {
        if (!available) return null
        val p = predictor ?: return null
        return try {
            p.predict(System.nanoTime() + aheadNanos)
        } catch (_: RuntimeException) {
            available = false
            null
        } catch (_: LinkageError) {
            available = false
            null
        }
    }

    private fun recordSafely(event: MotionEvent) {
        val p = predictor ?: return
        try {
            p.record(event)
        } catch (_: RuntimeException) {
            // "Inconsistent event stream" (e.g. another input device): stop predicting this stroke
            // and start over with a fresh predictor next time.
            available = false
            predictor = null
        }
    }
}
