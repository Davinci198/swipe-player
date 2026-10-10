package com.swipe.player

import android.net.Uri

internal fun mediaId(uri: Uri): String = uri.toString()

internal fun mediaId(uri: String): String = uri
