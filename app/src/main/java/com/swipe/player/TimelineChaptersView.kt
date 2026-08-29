package com.swipe.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Timeline neon pentru capitole + preview marker (#FF2A3D).
 * Stub vizual: desenează un contur neon + marker la poziția de seek.
 */
class TimelineChaptersView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var chapters: List<Any> = emptyList()
    private var seekPositionMs: Long = -1L

    private val paintNeon = Paint().apply {
        color = 0xFFFF2A3D.toInt()
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val paintTrack = Paint().apply {
        color = 0x14FFFFFF.toInt()
        strokeWidth = 2f
        isAntiAlias = true
    }

    fun updateChapters(ch: List<Any>) {
        chapters = ch
        invalidate()
    }

    fun showSeekPreview(positionMs: Long) {
        seekPositionMs = positionMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // track
        canvas.drawLine(0f, h / 2, w, h / 2, paintTrack)
        // marker neon la poziția de preview
        if (seekPositionMs >= 0 && chapters.isNotEmpty()) {
            val frac = (seekPositionMs % (chapters.size * 10_000L)).toFloat() / (chapters.size * 10_000f)
            canvas.drawCircle(frac.coerceIn(0f, 1f) * w, h / 2, 5f, paintNeon)
        }
    }
}