package com.swipe.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackControlTest {
    @Test
    fun notificationActions_areDistinctFromActivityCommands() {
        assertNotEquals(PlaybackControl.ACTION_PLAY, PlaybackControl.NOTIFICATION_PLAY)
        assertNotEquals(PlaybackControl.ACTION_PAUSE, PlaybackControl.NOTIFICATION_PAUSE)
        assertNotEquals(PlaybackControl.ACTION_STOP, PlaybackControl.NOTIFICATION_STOP)
    }

    @Test
    fun notificationActions_mapToOneActivityCommand() {
        assertEquals(PlaybackControl.ACTION_PLAY,
            PlaybackControl.commandForNotificationAction(PlaybackControl.NOTIFICATION_PLAY))
        assertEquals(PlaybackControl.ACTION_PAUSE,
            PlaybackControl.commandForNotificationAction(PlaybackControl.NOTIFICATION_PAUSE))
        assertEquals(PlaybackControl.ACTION_STOP,
            PlaybackControl.commandForNotificationAction(PlaybackControl.NOTIFICATION_STOP))
        assertNull(PlaybackControl.commandForNotificationAction("unknown"))
    }
}
