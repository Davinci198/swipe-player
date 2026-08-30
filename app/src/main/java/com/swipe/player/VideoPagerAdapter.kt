
package com.swipe.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.ui.PlayerView
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

class VideoPagerAdapter(
    private val context: Context,
    private val items: List<Uri>,
    private val names: List<String> = items.map { uri ->
        uri.lastPathSegment?.substringAfterLast("/")?.substringBefore("?") ?: "Video"
    },
    private val initialVolume: Float? = null,
    private val onBrightnessChange: ((Float) -> Unit)? = null,
    private val onVolumeChange: ((Float) -> Unit)? = null
) : RecyclerView.Adapter<VideoPagerAdapter.VH>() {
    private val TAG = "VideoPagerAdapter"
    private val memoryManager: MemoryManager by lazy { MemoryManager.getInstance(context) }
    var currentBrightness: Float = 1f
        set(v) { field = v.coerceIn(0.15f, 1f) }
    var currentVolume: Float = initialVolume ?: 1f
        set(v) { field = v.coerceIn(0f, 1f); onVolumeChange?.invoke(field) }
    private val trackSelector = DefaultTrackSelector(context)
    var currentResolutie: Pair<Int, Int> = Pair(7680, 4320)
    var playerActiv: ExoPlayer? = null
        set(value) {
            field = value
            for (p in live.values) { if (p !== value) { p.volume = 0f; p.pause() } }
            value?.apply { volume = currentVolume; playWhenReady = true; play() }
        }
    private val live = HashMap<Int, ExoPlayer>()
    private val playerHolder = HashMap<ExoPlayer, VH>()
    private val pool = java.util.ArrayDeque<ExoPlayer>()
    private val MAX_POOL = 3
    private var pinchPlayerView: PlayerView? = null
    private var videoZoom = 1f
    private var pinchActive = false
    private val pinchListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val pv = pinchPlayerView ?: return false
            val newZoom = (videoZoom * detector.scaleFactor).coerceIn(1f, 4f)
            pv.scaleX = newZoom; pv.scaleY = newZoom
            pv.pivotX = pv.width / 2f; pv.pivotY = pv.height / 2f
            videoZoom = newZoom; return true
        }
    }
    private val pinchDetector = ScaleGestureDetector(context, pinchListener)
    private fun resetVideoZoom(p: PlayerView?) { p?.also { it.scaleX = 1f; it.scaleY = 1f }; videoZoom = 1f }
    private fun acquirePlayer(): ExoPlayer {
        val p: ExoPlayer = if (pool.isNotEmpty()) pool.removeFirst() else {
            ExoPlayer.Builder(context).setTrackSelector(trackSelector).setAudioAttributes(AudioAttributes.DEFAULT, true).build().also { it.addListener(creeazaListener(it)) }
        }
        p.clearMediaItems(); return p
    }
    private fun pozitiePentru(p: ExoPlayer): Int = live.entries.firstOrNull { it.value === p }?.key ?: -1
    private fun numePentru(p: ExoPlayer): String { val poz = pozitiePentru(p); return if (poz in 0 until names.size) names[poz] else "Video" }
    private fun creeazaListener(p: ExoPlayer): Player.Listener = object : Player.Listener {
        private fun uiAttached(): Boolean { val holder = playerHolder[p] ?: return false; return holder.itemView.isAttachedToWindow }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                salveazaProgres(numePentru(p), p, 100)
                val holder = playerHolder[p]
                if (holder != null && holder.itemView.isAttachedToWindow) holder.loadingContainer.visibility = View.GONE
                if (autoOrder && pozitiePentru(p) == activePosition) onItemEnded?.invoke()
                return
            }
            if (!uiAttached()) return
            val holder = playerHolder[p] ?: return
            when (playbackState) { Player.STATE_BUFFERING -> holder.loadingContainer.visibility = View.VISIBLE; Player.STATE_READY -> holder.loadingContainer.visibility = View.GONE }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) salveazaProgresDacaTimpul(numePentru(p), p) }
        override fun onPositionDiscontinuity(reason: Int) { salveazaProgresDacaTimpul(numePentru(p), p) }
        override fun onPlayerError(error: PlaybackException) {
            try { val h = playerHolder[p] ?: return; if (h.itemView.isAttachedToWindow) h.loadingContainer.visibility = View.GONE } catch (e: Exception) {}
            Log.e(TAG, "Error: ${error.message}")
        }
    }
    private fun releasePlayer(p: ExoPlayer) { p.volume = 0f; p.playWhenReady = false; p.pause(); p.stop(); p.clearMediaItems(); if (pool.size < MAX_POOL) pool.addLast(p) else p.release() }
    fun elibereazaTot() { try { val toti = LinkedHashSet<ExoPlayer>().apply { addAll(live.values); addAll(pool) }; for (p in toti) { try { p.volume = 0f; p.playWhenReady = false; p.pause(); p.stop(); p.clearMediaItems(); p.release() } catch (e: Exception) {} }; live.clear(); pool.clear(); playerHolder.clear(); playerActiv = null } catch (e: Exception) { Log.e(TAG, "Eroare eliberare", e) } }
    private fun allPlayers(): Collection<ExoPlayer> = LinkedHashSet<ExoPlayer>().apply { addAll(live.values); addAll(pool) }
    fun pauseAllPlayers() { for (p in allPlayers()) { try { p.playWhenReady = false; p.volume = 0f; p.pause() } catch (e: Exception) {} } }
    fun resumeActivePlayer() { val p = playerActiv ?: return; try { p.volume = currentVolume; p.playWhenReady = true; p.play() } catch (e: Exception) { if (playerActiv === p) playerActiv = null } }
    fun isAnyPlayerPlaying(): Boolean { var any = false; for (p in allPlayers()) { try { if (p.playWhenReady && p.duration > 0) { any = true; break } } catch (e: Exception) {} }; return any || (try { playerActiv?.isPlaying == true } catch (e: Exception) { false }) }
    var seekStepSec = 10; var autoOrder = true; var onItemEnded: (() -> Unit)? = null
    private val CTRL_TIMEOUT_MS = 2500L
    private var activePosition = -1
    var controlsVisible = true

    private fun aplicaDim(holder: VH, b: Float) {
        val alpha = (1f - b).coerceIn(0f, 0.85f)
        holder.dimOverlay.alpha = alpha
        holder.dimOverlay.visibility = if (alpha > 0.02f) View.VISIBLE else View.GONE
    }
    private fun salveazaProgres(nume: String, p: ExoPlayer, procent: Int) { try { memoryManager.salveazaProgres(nume, procent) } catch (e: Exception) {} }
    private fun salveazaProgresDacaTimpul(nume: String, p: ExoPlayer) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastSaveTime < 1000) return
            lastSaveTime = now
            val dur = p.duration; if (dur <= 0) return
            val pos = p.currentPosition
            val proc = ((pos * 100) / dur).toInt().coerceIn(0, 100)
            memoryManager.salveazaProgres(nume, proc)
        } catch (e: Exception) {}
    }
    private fun togglePlay(holder: VH, p: ExoPlayer) { if (p.isPlaying) p.pause() else p.play() }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val playerView: PlayerView = view.findViewById(R.id.player_view)
        val touchCatcher: View = view.findViewById(R.id.touch_catcher)
        val dimOverlay: View = view.findViewById(R.id.dimOverlay)
        var player: ExoPlayer? = null
        val tvName: TextView = view.findViewById(R.id.tvVideoName)
        val btnFav: ImageButton = view.findViewById(R.id.btnFavorite)
        val btnSeekBack: ImageButton = view.findViewById(R.id.btnSeekBack)
        val btnSeekFwd: ImageButton = view.findViewById(R.id.btnSeekFwd)
        val loadingContainer: LinearLayout = view.findViewById(R.id.loadingContainer)
        val brightnessIndicator: FrameLayout = view.findViewById(R.id.brightnessIndicator)
        val brightnessFill: View = view.findViewById(R.id.brightnessFill)
        val volumeIndicator: FrameLayout = view.findViewById(R.id.volumeIndicator)
        val volumeFill: View = view.findViewById(R.id.volumeFill)
        val seekIndicator: LinearLayout = view.findViewById(R.id.seekIndicator)
        val seekTime: TextView = view.findViewById(R.id.seekTime)
        val seekProgress: ProgressBar = view.findViewById(R.id.seekProgress)
        var dragMod = 0; var dragZona = 0; var dragStartX = 0f; var dragStartY = 0f; var dragStartVal = 0f; var dragStartPosMs = 0L; var seekActive = false
        val dragThreshold = 12f
        var controllerVisibil = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false); return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val uri = items[position]
        val videoName = names.getOrElse(position) { "Video ${position + 1}" }
        holder.tvName.text = videoName
        val player = acquirePlayer()
        holder.player = player
        live[position] = player
        holder.playerView.player = player
        holder.playerView.setControllerShowTimeoutMs(CTRL_TIMEOUT_MS.toInt())
        resetVideoZoom(holder.playerView)
        aplicaDim(holder, currentBrightness)
        player.volume = 0f
        onBrightnessChange?.invoke(currentBrightness)
        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        playerHolder[player] = holder
        try {
            val istoric = memoryManager.getIstoric(videoName)
            if (istoric.isNotEmpty()) {
                val poz = (istoric.last()["pozitie"] as? Int ?: 0) * 1000L
                if (poz > 0) player.seekTo(poz)
            }
        } catch (e: Exception) {}
        player.prepare()
        if (position == activePosition && player !== playerActiv) playerActiv = player

        val hideButtons = Runnable {
            if (holder.controllerVisibil) {
                holder.btnSeekBack.visibility = View.GONE
                holder.btnSeekFwd.visibility = View.GONE
                holder.dragMod = 0
            }
        }

        val gesture = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!controlsVisible) {
                    holder.playerView.hideController()
                    holder.itemView.removeCallbacks(hideButtons)
                    holder.btnSeekBack.visibility = View.GONE
                    holder.btnSeekFwd.visibility = View.GONE
                    holder.controllerVisibil = false
                    return true
                }
                val noul = !holder.controllerVisibil
                if (noul) {
                    holder.playerView.showController()
                    holder.btnSeekBack.visibility = View.VISIBLE
                    holder.btnSeekFwd.visibility = View.VISIBLE
                    holder.itemView.removeCallbacks(hideButtons)
                    holder.itemView.postDelayed(hideButtons, CTRL_TIMEOUT_MS)
                } else {
                    holder.playerView.hideController()
                    holder.itemView.removeCallbacks(hideButtons)
                    holder.btnSeekBack.visibility = View.GONE
                    holder.btnSeekFwd.visibility = View.GONE
                }
                holder.controllerVisibil = noul
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (videoZoom > 1f) resetVideoZoom(holder.playerView) else togglePlay(holder, player)
                return true
            }
        })

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        holder.touchCatcher.setOnTouchListener { _, event ->
            // pinch zoom cu 2 degete
            if (event.pointerCount > 1) pinchActive = true
            if (event.pointerCount > 1 || pinchActive) {
                pinchPlayerView = holder.playerView
                pinchDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pinchActive = false
                return@setOnTouchListener true
            }

            gesture.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    holder.dragStartX = event.x
                    holder.dragStartY = event.y
                    holder.dragMod = 0
                    val w = holder.itemView.width
                    holder.dragZona = when {
                        event.x < w * 0.25f -> 1 // stanga = brightness
                        event.x > w * 0.75f -> 2 // dreapta = volum
                        else -> 0 // mijloc = seek / scroll
                    }
                    holder.dragStartVal = when (holder.dragZona) {
                        1 -> currentBrightness
                        2 -> currentVolume
                        else -> 0f
                    }
                    holder.dragStartPosMs = player.currentPosition
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - holder.dragStartX
                    val dy = event.y - holder.dragStartY
                    val adx = kotlin.math.abs(dx)
                    val ady = kotlin.math.abs(dy)

                    // DECIZIA CHEIE: daca nu am decis inca modul
                    if (holder.dragMod == 0) {
                        if (adx < holder.dragThreshold && ady < holder.dragThreshold) return@setOnTouchListener true
                        // daca miscarea e preponderent VERTICALA -> lasa ViewPager2 sa faca swipe la video
                        if (ady > adx && ady > 30) {
                            holder.dragMod = 4 // scroll vertical
                            return@setOnTouchListener false
                        }
                        // altfel e gesture orizontal / vertical mic -> volum / brightness / seek
                        holder.dragMod = when (holder.dragZona) {
                            1 -> 2 // brightness
                            2 -> 1 // volum
                            else -> 3 // seek
                        }
                    }

                    // daca am decis ca e scroll vertical, nu consumam eventul
                    if (holder.dragMod == 4) return@setOnTouchListener false

                    // GESTURE HANDLING
                    when (holder.dragMod) {
                        1 -> { // volum dreapta
                            val delta = -dy / holder.itemView.height
                            currentVolume = (holder.dragStartVal + delta).coerceIn(0f, 1f)
                            player.volume = currentVolume
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (currentVolume * maxVol).toInt(), 0)
                            holder.volumeIndicator.visibility = View.VISIBLE
                            holder.volumeFill.layoutParams.height = (holder.volumeIndicator.height * currentVolume).toInt()
                            holder.volumeFill.requestLayout()
                            true
                        }
                        2 -> { // brightness stanga
                            val delta = -dy / holder.itemView.height
                            currentBrightness = (holder.dragStartVal + delta).coerceIn(0.15f, 1f)
                            aplicaDim(holder, currentBrightness)
                            onBrightnessChange?.invoke(currentBrightness)
                            holder.brightnessIndicator.visibility = View.VISIBLE
                            holder.brightnessFill.layoutParams.height = (holder.brightnessIndicator.height * currentBrightness).toInt()
                            holder.brightnessFill.requestLayout()
                            true
                        }
                        3 -> { // seek orizontal
                            val deltaSec = (dx / holder.itemView.width) * 90 // 90 sec max swipe
                            val newPos = (holder.dragStartPosMs + deltaSec * 1000).toLong().coerceIn(0, player.duration.coerceAtLeast(0))
                            holder.seekIndicator.visibility = View.VISIBLE
                            holder.seekTime.text = "${newPos/1000}s / ${player.duration/1000}s"
                            player.seekTo(newPos)
                            true
                        }
                        else -> true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holder.volumeIndicator.visibility = View.GONE
                    holder.brightnessIndicator.visibility = View.GONE
                    holder.seekIndicator.visibility = View.GONE
                    val wasScroll = holder.dragMod == 4
                    holder.dragMod = 0
                    // daca a fost scroll, return false ca sa nu blocam click-ul urmator
                    if (wasScroll) false else true
                }
                else -> true
            }
        }

        holder.btnSeekBack.setOnClickListener { player.seekTo((player.currentPosition - seekStepSec*1000).coerceAtLeast(0)) }
        holder.btnSeekFwd.setOnClickListener { player.seekTo((player.currentPosition + seekStepSec*1000).coerceAtMost(player.duration)) }
    }

    override fun getItemCount(): Int = items.size
    override fun onViewRecycled(holder: VH) {
        holder.player?.let { p -> playerHolder.remove(p); live.remove(holder.bindingAdapterPosition); releasePlayer(p) }
        holder.playerView.player = null
        holder.player = null
        super.onViewRecycled(holder)
    }
    fun setActivePage(pos: Int) {
        if (activePosition == pos) return
        val old = activePosition
        activePosition = pos
        live[old]?.let { it.volume = 0f; it.pause() }
        live[pos]?.let { playerActiv = it }
    }
}
