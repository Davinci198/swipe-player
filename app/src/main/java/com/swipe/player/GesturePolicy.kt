package com.swipe.player

import kotlin.math.abs

internal fun detectGestureMode(startX: Float, width: Float, dx: Float, dy: Float): Int {
    val w = width.coerceAtLeast(1f)
    val isRight = startX > w * 0.78f
    val isLeft = startX < w * 0.22f
    return when {
        isRight && abs(dy) > 8f -> 2 // volume
        isLeft && abs(dy) > 8f -> 1 // brightness
        abs(dy) > abs(dx) && abs(dy) > 15f -> 4 // vertical pager scroll
        abs(dx) > 12f -> 3 // horizontal seek
        else -> 0
    }
}
