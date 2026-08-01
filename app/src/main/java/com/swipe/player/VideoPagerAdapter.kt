package com.swipe.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.ui.PlayerView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView.ViewHolder

/**
 * Swipe Player - adapter pentru videoclipuri locale (offline).
 * Scroll vertical. Drag pe stanga = luminozitate, drag pe dreapta = volum.
 */
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
    private var lastSaveTime = 0L

    var currentBrightness: Float = 1f
        set(v) { field = v.coerceIn(0.2f, 1.7f) }
    var currentVolume: Float = initialVolume ?: 1f
        set(v) { field = v.coerceIn(0f, 1f); onVolumeChange?.invoke(field) }

    // playerul activ (vizibil in pager)
    var playerActiv: ExoPlayer? = null
        set(value) { field = value; value?.volume = currentVolume }

    // stare drag
    private var dragMod = 0 // 0=none, 1=volum, 2=luminozitate, 3=seek (orizontal)
    private var dragStartY = 0f
    private var dragStartX = 0f
    private var dragStartVal = 0f
    private var dragStartPosMs = 0L
    private var seekActive = false
    private var dragThreshold = 40f // prag activare gest (px)

    inner class VH(view: View) : ViewHolder(view) {
        val playerView: PlayerView = view.findViewById(R.id.player_view)
        var player: ExoPlayer? = null
        val tvName: TextView = view.findViewById(R.id.tvVideoName)
        val btnFav: ImageButton = view.findViewById(R.id.btnFavorite)
        val loadingContainer: LinearLayout = view.findViewById(R.id.loadingContainer)
        val brightZone: View = view.findViewById(R.id.brightZone)
        val volZone: View = view.findViewById(R.id.volZone)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val uri = items[position]
        val videoName = names.getOrElse(position) { "Video ${position + 1}" }
        holder.tvName.text = videoName

        val player = ExoPlayer.Builder(context).build()
        holder.player = player
        playerActiv = player
        holder.playerView.player = player
        player.volume = currentVolume
        onBrightnessChange?.invoke(currentBrightness)

        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> holder.loadingContainer.visibility = View.VISIBLE
                    Player.STATE_READY -> holder.loadingContainer.visibility = View.GONE
                    Player.STATE_ENDED -> salveazaProgres(videoName, player, 100)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) salveazaProgresDacaTimpul(videoName, player)
            }
            override fun onPositionDiscontinuity(reason: Int) {
                salveazaProgresDacaTimpul(videoName, player)
            }
            override fun onPlayerError(error: PlaybackException) {
                holder.loadingContainer.visibility = View.GONE
                Log.e(TAG, "Error $videoName: ${error.message}")
            }
        })
        player.prepare()
        player.playWhenReady = true

        // ===== Gesturi: drag orizontal = seek, dublu-tap = play/pause =====
        // drag orizontal = seek / derulare video; dublu-tap = play/pause
        val seekGesture = android.view.GestureDetector(
            context,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    togglePlay(holder, player)
                    return true
                }
                override fun onDown(e: MotionEvent): Boolean = true
            }
        )
        holder.playerView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragMod = 0
                    seekActive = false
                    dragStartX = event.x
                    dragStartY = event.y
                    dragStartVal = dragStartY
                    dragStartPosMs = player.currentPosition
                    true // prindem stream-ul ca să observăm direcția gestului
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - dragStartX
                    val dy = event.y - dragStartY
                    // dacă gestul e VERTICAL => lăsăm scroll-ul vertical (schimbare videoclip)
                    // să fie gestionat de ViewPager2; returnăm false ca RecyclerView-ul să preia.
                    if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        dragMod = 0
                        seekActive = false
                        return@setOnTouchListener false
                    }
                    // altfel => drag orizontal = seek / derulare video
                    if (!seekActive && kotlin.math.abs(dx) < dragThreshold) {
                        return@setOnTouchListener true
                    }
                    dragMod = 3
                    seekActive = true
                    val durata = player.duration
                    if (durata > 0) {
                        val latimeView = view.width.toFloat().coerceAtLeast(1f)
                        val factor = durata.toFloat() * (event.x - dragStartX) / latimeView
                        val viteza = if (durata > 240_000) 4f else 1f // clipuri lungi => netezit
                        val target = (dragStartPosMs + factor / viteza).toLong()
                        player.seekTo(target.coerceIn(0L, durata))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // dacă a fost doar un tap (fără drag), lasă player-ul (controller play/pause)
                    // și GestureDetector-ul să proceseze (dublu-tap)
                    if (dragMod == 0) {
                        seekGesture.onTouchEvent(event)
                        return@setOnTouchListener view.onTouchEvent(event)
                    }
                    dragMod = 0
                    seekActive = false
                    true
                }
                else -> seekGesture.onTouchEvent(event)
            }
        }

        val esteFav = memoryManager.esteFavorit(videoName)
        holder.btnFav.setImageResource(if (esteFav) android.R.drawable.star_on else android.R.drawable.star_off)
        holder.btnFav.setOnClickListener {
            val ac = memoryManager.toggleFavorite(videoName, (player.duration / 1000).toInt())
            holder.btnFav.setImageResource(if (ac) android.R.drawable.star_on else android.R.drawable.star_off)
        }

        // drag luminozitate (stanga)
        holder.brightZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragMod = 2; dragStartY = event.y; dragStartVal = currentBrightness; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragMod != 2) return@setOnTouchListener true
                    val delta = (dragStartY - event.y) / (holder.brightZone.height * 2.2f)
                    currentBrightness = (dragStartVal + delta).coerceIn(0.2f, 1.7f)
                    onBrightnessChange?.invoke(currentBrightness)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragMod = 0; true }
                else -> true
            }
        }
        // drag volum (dreapta)
        holder.volZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragMod = 1; dragStartY = event.y; dragStartVal = currentVolume; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragMod != 1) return@setOnTouchListener true
                    val delta = (dragStartY - event.y) / (holder.volZone.height * 2.2f)
                    currentVolume = (dragStartVal + delta).coerceIn(0f, 1f)
                    playerActiv?.volume = currentVolume
                    onVolumeChange?.invoke(currentVolume)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragMod = 0; true }
                else -> true
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        val position = holder.adapterPosition
        if (position != RecyclerView.NO_POSITION) {
            val videoName = names.getOrElse(position) { "unknown" }
            holder.player?.let { salveazaProgres(videoName, it, null) }
        }
        holder.playerView.player = null
        holder.player?.run { stop(); release() }
        holder.player = null
    }

    override fun getItemCount(): Int = items.size
    private fun salveazaProgresDacaTimpul(nume: String, player: ExoPlayer) {
        val acum = System.currentTimeMillis()
        if (acum - lastSaveTime < 5000) return
        lastSaveTime = acum
        salveazaProgres(nume, player, null)
    }
    private fun salveazaProgres(nume: String, player: ExoPlayer, progresForced: Int?) {
        try {
            val durataMs = player.duration
            if (durataMs <= 0) return
            val pozitieMs = player.currentPosition
            val progres = progresForced ?: ((pozitieMs * 100) / durataMs).toInt()
            memoryManager.salveazaInIstoric(
                nume = nume,
                progres = progres,
                pozitieSecunde = (pozitieMs / 1000).toInt(),
                durataSecunde = (durataMs / 1000).toInt()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Eroare salvare progres", e)
        }
    }

    fun setVolume(v: Float) {
        currentVolume = v
        playerActiv?.volume = currentVolume
        onVolumeChange?.invoke(currentVolume)
    }
    fun setBrightness(b: Float) {
        currentBrightness = b
        onBrightnessChange?.invoke(currentBrightness)
    }

    // ===== dublu-tap = play / pause ====
    private fun togglePlay(holder: VH, player: ExoPlayer) {
        try {
            if (player.isPlaying) {
                player.pause()
            } else {
                holder.loadingContainer.visibility = View.GONE
                player.play()
            }
            val nume = names.getOrElse(holder.adapterPosition) { "unknown" }
            if (!player.isPlaying && player.duration > 0) {
                salveazaProgres(nume, player, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eroare toggle play/pause", e)
        }
    }
}