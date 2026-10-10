package com.swipe.player

internal object PlaybackControl {
    const val ACTION_PLAY = "com.swipe.player.PLAY"
    const val ACTION_PAUSE = "com.swipe.player.PAUSE"
    const val ACTION_STOP = "com.swipe.player.STOP"

    const val NOTIFICATION_PLAY = "com.swipe.player.NOTIFICATION_PLAY"
    const val NOTIFICATION_PAUSE = "com.swipe.player.NOTIFICATION_PAUSE"
    const val NOTIFICATION_STOP = "com.swipe.player.NOTIFICATION_STOP"

    fun commandForNotificationAction(action: String?): String? = when (action) {
        NOTIFICATION_PLAY -> ACTION_PLAY
        NOTIFICATION_PAUSE -> ACTION_PAUSE
        NOTIFICATION_STOP -> ACTION_STOP
        else -> null
    }
}
