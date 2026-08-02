package com.swipe.player

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView cu ZOOM in/out (pinch + pan) pentru galeria de poze.
 * - O imagine se afișează inițial "centerCrop" (umple ecranul, fără distorsiune).
 * - pinch (1x..5x) + pan; dublu-tap = zoom la 3x / revenire la 1x.
 * - la 1x, gesturile trec mai departe => ViewPager2 poate derula (schimbă poza);
 *   mărit, swipe-urile fac pan și paging-ul e blocat (requestDisallowIntercept).
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val displayMatrix = Matrix()   // transformarea finală (base * zoom)
    private val baseMatrix = Matrix()      // doar "centerCrop" inițial (fără zoom)
    private var baseScale = 1f
    private var zoom = 1f                  // zoom-ul utilizatorului (1x..5x)
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
            val factor = newZoom / zoom
            // scalează în jurul punctului de focalizare
            displayMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            zoom = newZoom
            clampPan()
            imageMatrix = displayMatrix
            requestDisallowInterceptTouchEvent(true)
            return true
        }
    })

    private val doubleTap = object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom <= 1f) {
                val target = 3f
                displayMatrix.postScale(target, target, e.x, e.y)
                zoom = target
            } else {
                resetZoom()
            }
            imageMatrix = displayMatrix
            requestDisallowInterceptTouchEvent(zoom > 1f)
            return true
        }
    }
    private val gestureDetector = android.view.GestureDetector(context, doubleTap)

    init {
        scaleType = ImageView.ScaleType.MATRIX
        adjustViewBounds = false
        isClickable = true
    }

    /** calculăm matricea "centerCrop" de bază când știm dimensiunile imaginii / view-ului */
    private fun computeBaseMatrix() {
        val drawW = drawable?.intrinsicWidth ?: return
        val drawH = drawable?.intrinsicHeight ?: return
        if (drawW == 0 || drawH == 0 || width == 0 || height == 0) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        baseScale = maxOf(vw / drawW, vh / drawH)
        baseMatrix.reset()
        baseMatrix.postScale(baseScale, baseScale)
        // centrează
        val tx = (vw - drawW * baseScale) / 2f
        val ty = (vh - drawH * baseScale) / 2f
        baseMatrix.postTranslate(tx, ty)
        resetZoom()
    }

    private fun resetZoom() {
        zoom = 1f
        displayMatrix.set(baseMatrix)
        imageMatrix = displayMatrix
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) computeBaseMatrix()
    }

    private fun clampPan() {
        val drawW = drawable?.intrinsicWidth ?: return
        val drawH = drawable?.intrinsicHeight ?: return
        if (drawW == 0 || drawH == 0) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val values = FloatArray(9)
        displayMatrix.getValues(values)
        val sx = values[Matrix.MSCALE_X]
        val sy = values[Matrix.MSCALE_Y]
        val scaledW = drawW * sx
        val scaledH = drawH * sy
        var tx = values[Matrix.MTRANS_X]
        var ty = values[Matrix.MTRANS_Y]
        // orizontal
        if (scaledW <= vw) {
            tx = (vw - scaledW) / 2f
        } else {
            if (tx > 0f) tx = 0f
            else if (tx + scaledW < vw) tx = vw - scaledW
        }
        // vertical
        if (scaledH <= vh) {
            ty = (vh - scaledH) / 2f
        } else {
            if (ty > 0f) ty = 0f
            else if (ty + scaledH < vh) ty = vh - scaledH
        }
        values[Matrix.MTRANS_X] = tx
        values[Matrix.MTRANS_Y] = ty
        displayMatrix.setValues(values)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (event.pointerCount > 1) {
                    // pinch deja gestionat de scaleDetector
                    requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (zoom > 1f) {
                    displayMatrix.postTranslate(dx, dy)
                    clampPan()
                    imageMatrix = displayMatrix
                    requestDisallowInterceptTouchEvent(true)
                    return true
                }
                // la 1x: lăsăm ViewPager2 să deruleze (următoarea poză)
                requestDisallowInterceptTouchEvent(false)
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                requestDisallowInterceptTouchEvent(zoom > 1f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** apelat când i se setează un nou bitmap => re-computăm fit-ul inițial */
    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        computeBaseMatrix()
    }
}