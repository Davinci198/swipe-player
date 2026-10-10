package com.swipe.player

internal fun seekDeltaMs(deltaPx: Float, widthPx: Float, stepSec: Int): Long {
    if (widthPx <= 0f || stepSec <= 0) return 0L
    return (deltaPx / widthPx * stepSec.coerceIn(2, 30) * 1000f).toLong()
}
