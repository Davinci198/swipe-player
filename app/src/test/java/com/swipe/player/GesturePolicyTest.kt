package com.swipe.player

import org.junit.Assert.assertEquals
import org.junit.Test

class GesturePolicyTest {
    @Test
    fun edgeVerticalDrag_isCapturedByBrightnessOrVolume() {
        assertEquals(1, detectGestureMode(100f, 1000f, 0f, 30f))
        assertEquals(2, detectGestureMode(900f, 1000f, 0f, 30f))
    }

    @Test
    fun centerVerticalDrag_isLeftForPager() {
        assertEquals(4, detectGestureMode(500f, 1000f, 0f, 30f))
    }
}
