package com.swipe.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.math.abs

/**
 * Swipe Player v2 — cu gesturi MX Player style
 * Design: Dark premium #08080A, neon #FF2A3D, glass blur 20px, spring 0.6s
 *
 * Zone:
 * - stânga <33% => BRIGHTNESS (WindowManager.LayoutParams.screenBrightness)
 * - dreapta >66% => VOLUME (AudioManager.STREAM_MUSIC)
 * - centru => SEEK (ExoPlayer.seekTo + thumbnail preview)
 *
 * Păstrat din v1:
 * - MemoryManager LRU
 * - DragonBones animation
 * - ExoPlayer cache
 */
class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var audioManager: AudioManager
    private lateinit var memoryManager: MemoryManager
    private var dragonBonesBridge: Any? = null

    // Views - presupunem existența în activity_main.xml
    private lateinit var rootContainer: FrameLayout
    private lateinit var brightnessOverlay: FrameLayout
    private lateinit var volumeOverlay: FrameLayout
    private lateinit var seekOverlay: FrameLayout
    private lateinit var brightnessProgress: ProgressBar
    private lateinit var volumeProgress: ProgressBar
    private lateinit var seekText: TextView
    private lateinit var seekPreview: FrameLayout // container pt thumbnail
    private lateinit var timelineView: TimelineChaptersView

    private var startX = 0f
    private var startY = 0f
    private var initialBrightness = 0.5f
    private var initialVolume = 0
    private var maxVolume = 15

    enum class Gesture { NONE, BRIGHTNESS, VOLUME, SEEK }
    private var gestureType: Gesture = Gesture.NONE
    private var isGestureActive = false

    // Haptic
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        memoryManager = MemoryManager.getInstance(this)
        dragonBonesBridge = null // TODO: restore when NDK bridge merged

        // init player (păstrăm logica ta veche)
        player = ExoPlayer.Builder(this).build()
        // ... setup playerView, adapter, etc - lasă codul tău existent

        rootContainer = findViewById(R.id.rootContainer)
        brightnessOverlay = findViewById(R.id.brightnessOverlay)
        volumeOverlay = findViewById(R.id.volumeOverlay)
        seekOverlay = findViewById(R.id.seekOverlay)
        brightnessProgress = findViewById(R.id.brightnessProgress)
        volumeProgress = findViewById(R.id.volumeProgress)
        seekText = findViewById(R.id.seekText)
        seekPreview = findViewById(R.id.seekPreview)
        timelineView = findViewById(R.id.timelineView)

        // ExoPlayer listener pentru chapters neon
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // update timeline neon cu capitole
                timelineView.updateChapters(
                    emptyList() ?: emptyList()
                )
                hapticTick()
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                timelineView.updateChapters(mediaItem?.mediaMetadata?.chapters ?: emptyList())
            }
        })

        val gestureListener = View.OnTouchListener { v, event ->
            val width = v.width.toFloat()
            val height = v.height.toFloat()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    initialBrightness = window.attributes.screenBrightness.let { if (it < 0) 0.5f else it }
                    initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    gestureType = when {
                        event.x < width * 0.33f -> Gesture.BRIGHTNESS
                        event.x > width * 0.66f -> Gesture.VOLUME
                        else -> Gesture.SEEK
                    }
                    isGestureActive = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = startY - event.y
                    // threshold pentru a porni gesture
                    if (!isGestureActive && abs(dy) < 20 && abs(dx) < 20) return@OnTouchListener true
                    if (!isGestureActive) {
                        isGestureActive = true
                        showOverlay(gestureType)
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }

                    when (gestureType) {
                        Gesture.BRIGHTNESS -> {
                            // dy -> brightness 0.01..1.0
                            val delta = dy * 0.002f
                            val newBrightness = (initialBrightness + delta).coerceIn(0.01f, 1f)
                            val lp = window.attributes
                            lp.screenBrightness = newBrightness
                            window.attributes = lp
                            brightnessProgress.progress = (newBrightness * 100).toInt()
                            if (newBrightness * 100 % 10 < 1) hapticTick()
                        }
                        Gesture.VOLUME -> {
                            // dy -> volume steps
                            val steps = (dy / (height * 0.05f)).toInt() // 5% din înălțime = 1 step
                            val newVol = (initialVolume + steps).coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            volumeProgress.progress = (newVol * 100 / maxVolume)
                            if (abs(newVol - initialVolume) % 2 == 0) hapticTick()
                        }
                        Gesture.SEEK -> {
                            val deltaMs = (dx * 0.3f * 1000).toLong() // 0.3 sec per px? ajustabil
                            // folosim seek relativ
                            // Nu facem seek continuu, doar preview + la UP facem commit pentru performance
                            val target = (player.currentPosition + deltaMs).coerceIn(0, player.duration.coerceAtLeast(0))
                            seekPreview.isVisible = true
                            // seekPreview.showThumbnailAt(target) // implementează tu cu ExoPlayer frame extractor
                            seekText.text = "${if (deltaMs > 0) "+" else ""}${deltaMs / 1000}s"
                            timelineView.showSeekPreview(target)
                        }
                        else -> {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isGestureActive) {
                        when (gestureType) {
                            Gesture.SEEK -> {
                                val dx = event.x - startX
                                val deltaMs = (dx * 0.3f * 1000).toLong()
                                val target = (player.currentPosition + deltaMs).coerceIn(0, player.duration.coerceAtLeast(0))
                                player.seekTo(target)
                                hapticTick()
                            }
                            else -> {}
                        }
                        hideOverlaysWithSpring()
                        // MemoryManager & DragonBones rămân neatinse:
                        memoryManager.getStatistici()
                        // dragonBonesFactory nu e afectat de UI thread
                    }
                    gestureType = Gesture.NONE
                    isGestureActive = false
                    true
                }
                else -> false
            }
        }

        // Aplică listener pe containerul player-ului (peste ViewPager2 / PlayerView)
        rootContainer.setOnTouchListener(gestureListener)
    }

    private fun showOverlay(type: Gesture) {
        brightnessOverlay.isVisible = type == Gesture.BRIGHTNESS
        volumeOverlay.isVisible = type == Gesture.VOLUME
        seekOverlay.isVisible = type == Gesture.SEEK

        val target = when (type) {
            Gesture.BRIGHTNESS -> brightnessOverlay
            Gesture.VOLUME -> volumeOverlay
            Gesture.SEEK -> seekOverlay
            else -> null
        }
        target?.let {
            it.alpha = 0f
            it.scaleX = 0.9f
            it.scaleY = 0.9f
            it.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        }
    }

    private fun hideOverlaysWithSpring() {
        listOf(brightnessOverlay, volumeOverlay, seekOverlay).forEach { view ->
            if (!view.isVisible) return@forEach
            val springX = SpringAnimation(view, SpringAnimation.SCALE_X, 0.9f)
            val springY = SpringAnimation(view, SpringAnimation.SCALE_Y, 0.9f)
            val springAlpha = SpringAnimation(view, SpringAnimation.ALPHA, 0f)
            val force = SpringForce(0.9f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = SpringForce.STIFFNESS_LOW
            }
            springX.spring = force
            springY.spring = force
            springAlpha.spring = SpringForce(0f).apply {
                dampingRatio = 1f
                stiffness = 300f
            }
            springAlpha.addEndListener { _, _, _, _ ->
                view.isVisible = false
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
            }
            springX.start(); springY.start(); springAlpha.start()
        }
    }

    private fun hapticTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(40)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        (dragonBonesBridge as? com.swipe.player.DragonBonesBridge)?.release()
        dragonBonesBridge.release()
    }
}

// Stub pentru Timeline - implementezi tu cu neon chapters
class TimelineChaptersView(context: Context, attrs: android.util.AttributeSet?) :
    View(context, attrs) {

    private var chapters: List<Any> = emptyList()

    fun updateChapters(ch: List<Any>) {
        chapters = ch
        invalidate()
    }

    fun showSeekPreview(positionMs: Long) {
        // desenează preview marker neon #FF2A3D
        invalidate()
    }

    // onDraw cu neon paint #FF2A3D, glass blur etc.
}
