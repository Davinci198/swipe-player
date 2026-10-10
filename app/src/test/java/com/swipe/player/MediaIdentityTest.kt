package com.swipe.player

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaIdentityTest {
    @Test
    fun differentUrisWithSameDisplayName_haveDifferentIds() {
        val first = "content://media/one/video.mp4"
        val second = "content://media/two/video.mp4"

        assertEquals(first, mediaId(first))
        assertNotEquals(mediaId(first), mediaId(second))
    }
}
