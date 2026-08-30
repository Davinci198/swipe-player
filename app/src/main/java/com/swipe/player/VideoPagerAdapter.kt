h.touchCatcher.setOnTouchListener { view, event ->
    gesture.onTouchEvent(event) // pentru single/double tap
    
    // PINCH ZOOM rămâne cum era
    if (event.pointerCount > 1) pinchActive = true
    if (event.pointerCount > 1 || pinchActive) {
        pinchPlayerView = holder.playerView
        pinchDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || 
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pinchActive = false
        }
        return@setOnTouchListener true
    }

    when(event.action) {
        MotionEvent.ACTION_DOWN -> {
            h.dragStartX = event.x
            h.dragStartY = event.y
            h.dragStartVal = when {
                event.x < view.width * 0.33f -> currentBrightness
                event.x > view.width * 0.66f -> currentVolume
                else -> player.currentPosition.toFloat()
            }
            h.dragZona = when {
                event.x < view.width * 0.33f -> 1 // BRIGHTNESS
                event.x > view.width * 0.66f -> 2 // VOLUME
                else -> 0 // SEEK
            }
            h.dragMod = h.dragZona
            h.dragStartPosMs = player.currentPosition
            
            // Arată overlay neon ca în demo HTML
            when(h.dragZona) {
                1 -> {
                    holder.brightnessIndicator.visibility = View.VISIBLE
                    holder.brightnessIndicator.alpha = 1f
                    holder.volumeIndicator.visibility = View.GONE
                    holder.seekIndicator.visibility = View.GONE
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                2 -> {
                    holder.volumeIndicator.visibility = View.VISIBLE
                    holder.volumeIndicator.alpha = 1f
                    holder.brightnessIndicator.visibility = View.GONE
                    holder.seekIndicator.visibility = View.GONE
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                else -> {
                    holder.seekIndicator.visibility = View.VISIBLE
                    holder.seekIndicator.alpha = 1f
                    holder.brightnessIndicator.visibility = View.GONE
                    holder.volumeIndicator.visibility = View.GONE
                }
            }
        }
        MotionEvent.ACTION_MOVE -> {
            val dy = h.dragStartY - event.y
            val dx = event.x - h.dragStartX
            
            when(h.dragMod) {
                1 -> { // BRIGHTNESS - stânga - ca în demo HTML
                    val newB = (h.dragStartVal + dy * 0.002f).coerceIn(0.01f, 1f)
                    currentBrightness = newB
                    onBrightnessChange?.invoke(newB)
                    aplicaDim(holder, newB)
                    // update fill neon #FF2A3D
                    val pct = (newB * 100).toInt()
                    holder.brightnessFill.layoutParams.height = (holder.brightnessIndicator.height * newB).toInt()
                    holder.brightnessFill.requestLayout()
                }
                2 -> { // VOLUME - dreapta
                    val newV = (h.dragStartVal + dy * 0.002f).coerceIn(0f, 1f)
                    currentVolume = newV
                    player.volume = newV
                    onVolumeChange?.invoke(newV)
                    holder.volumeFill.layoutParams.height = (holder.volumeIndicator.height * newV).toInt()
                    holder.volumeFill.requestLayout()
                }
                0 -> { // SEEK - centru
                    if (kotlin.math.abs(dx) > h.dragThreshold) {
                        val delta = (dx * 300).toLong() // ms per px ca în demo
                        val newPos = (h.dragStartPosMs + delta).coerceAtLeast(0)
                        player.seekTo(newPos)
                        holder.seekTime.text = "${if(delta>0) "+" else ""}${delta/1000}s"
                        holder.seekProgress.progress = ((newPos * 100 / (player.duration.coerceAtLeast(1))) .toInt())
                    }
                }
            }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            // hideOverlaysWithSpring() ca în demo HTML
            holder.brightnessIndicator.animate().alpha(0f).setDuration(600).withEndAction { holder.brightnessIndicator.visibility = View.GONE; holder.brightnessIndicator.alpha = 1f }.start()
            holder.volumeIndicator.animate().alpha(0f).setDuration(600).withEndAction { holder.volumeIndicator.visibility = View.GONE; holder.volumeIndicator.alpha = 1f }.start()
            holder.seekIndicator.animate().alpha(0f).setDuration(600).withEndAction { holder.seekIndicator.visibility = View.GONE; holder.seekIndicator.alpha = 1f }.start()
            h.dragMod = 0
        }
    }
    true
}
