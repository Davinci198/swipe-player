package com.swipe.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekMathTest {
    @Test
    fun fullWidthSwipe_usesConfiguredStep() {
        assertEquals(10_000L, seekDeltaMs(400f, 400f, 10))
    }

    @Test
    fun halfWidthSwipe_usesHalfConfiguredStep() {
        assertEquals(-5_000L, seekDeltaMs(-200f, 400f, 10))
    }
}
