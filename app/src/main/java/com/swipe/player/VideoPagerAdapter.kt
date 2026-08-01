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
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
        set(v) { field = v.coerceIn(0.15f, 1f) } // max 1.0 (screenBrightness)
    var currentVolume: Float = initialVolume ?: 1f
        set(v) { field = v.coerceIn(0f, 1f); onVolumeChange?.invoke(field) }

    // selecttor de track comun, folosit pentru a limita rezoluția de decodare a tuturor player-urilor
    private val trackSelector = DefaultTrackSelector(context)

    // rezoluție de redare aleasă în setări (Auto = foarte mare)
    var currentResolutie: Pair<Int, Int> = Pair(7680, 4320) // Auto / acceptă tot
        set(v) { field = v }

    // playerul activ (vizibil in pager)
    var playerActiv: ExoPlayer? = null
        set(value) {
            field = value
            // opreste toate celelalte videoclipuri (audio + redare) - sa nu se auda in fundal
            for (p in live.values) {
                if (p !== value) {
                    p.volume = 0f
                    p.pause()
                }
            }
            value?.apply {
                volume = currentVolume
                playWhenReady = true
                play()
            }
        }

    // playerii aflați în uz, pe poziție
    private val live = HashMap<Int, ExoPlayer>()

    // pool de ExoPlayer refolosiți (evită crearea a 100+ jucători pt. 100 de videoclipuri)
    private val pool = java.util.ArrayDeque<ExoPlayer>()
    private val MAX_POOL = 3

    private fun acquirePlayer(): ExoPlayer {
        val p = if (pool.isNotEmpty()) pool.removeFirst()
        else ExoPlayer.Builder(context).setTrackSelector(trackSelector).build()
        p.clearMediaItems()
        return p
    }

    private fun releasePlayer(p: ExoPlayer) {
        p.stop()
        p.clearMediaItems()
        p.playWhenReady = false
        if (pool.size < MAX_POOL) pool.addLast(p) else p.release()
    }

    private val SEEK_STEP_MS = 10_000L // swipe orizontal = derulare in pas de 10 sec

    // pagina (poziția) considerată vizibilă/activă - pornește doar ea
    private var activePosition = -1

    inner class VH(view: View) : ViewHolder(view) {
        val playerView: PlayerView = view.findViewById(R.id.player_view)
        var player: ExoPlayer? = null
        val tvName: TextView = view.findViewById(R.id.tvVideoName)
        val btnFav: ImageButton = view.findViewById(R.id.btnFavorite)
        val loadingContainer: LinearLayout = view.findViewById(R.id.loadingContainer)

        // stare drag - locală pe ViewHolder (fără race condition între pagini)
        var dragMod = 0 // 0=none, 1=volum, 2=luminozitate, 3=seek, 4=scroll
        var dragStartX = 0f
        var dragStartY = 0f
        var dragStartVal = 0f
        var dragStartPosMs = 0L
        var seekActive = false
        val dragThreshold = 40f // prag activare gest (px)

        // stare controller (pentru toggle pe tap simplu) - locală pe ViewHolder
        var controllerVisibil = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val uri = items[position]
        val videoName = names.getOrElse(position) { "Video ${position + 1}" }
        holder.tvName.text = videoName

        val player = acquirePlayer() // reutilizat din pool (max 3)
        holder.player = player
        live[position] = player
        holder.playerView.player = player
        holder.playerView.setControllerShowTimeoutMs(2000) // controllerul dispare mai repede
        // NU pornim automat - doar videoclipul activ porneste (prin setActivePage)
        player.volume = 0f
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
        // continuă de unde ai rămas? restaurez poziția salvată, dar NU pornesc automat
        try {
            val istoric = memoryManager.getIstoric(videoName)
            if (istoric.isNotEmpty()) {
                val durataMs = player.duration
                val poz = (istoric.last()["pozitie"] as? Int ?: 0) * 1000L
                if (poz > 0 && (durataMs <= 0 || poz < durataMs - 2000)) {
                    player.seekTo(poz)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eroare restaurare progres", e)
        }
        player.prepare()
        // NU pornim automat: doar videoclipul activ pornește (pagina selectată)
        if (position == activePosition && player !== playerActiv) {
            playerActiv = player
        }

        // ===== Gesture State Machine =====
        // Un singur GestureDetector (taps) + un singur OnTouchListener (drag-uri).
        // Stări dragMod: 0=none, 2=luminozitate(stânga), 1=volum(dreapta),
        //                3=seek(orizontal), 4=scroll vertical (lăsat ViewPager2)
        val gesture = android.view.GestureDetector(
            context,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // 1 tap = arată/ascunde controllerul (play/pause)
                    if (holder.controllerVisibil) holder.playerView.hideController()
                    else holder.playerView.showController()
                    holder.controllerVisibil = !holder.controllerVisibil
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    togglePlay(holder, player) // 2 tap = play/pause
                    return true
                }
            }
        )

        // pragul lateral: sub acest % din lățime = luminozitate, peste = volum
        val limitaStanga = 0.42f
        val limitaDreapta = 0.58f

        val h = holder // referință locală pentru readabilitate
        h.playerView.setOnTouchListener { view, event ->
            gesture.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    h.dragMod = 0
                    h.seekActive = false
                    h.dragStartX = event.x
                    h.dragStartY = event.y
                    h.dragStartVal = event.y
                    h.dragStartPosMs = player.currentPosition
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    when (h.dragMod) {
                        // încă nedecis: stabilim direcția gestului
                        0 -> {
                            val dx = event.x - h.dragStartX
                            val dy = event.y - h.dragStartY
                            val orizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                            if (orizontal) {
                                if (kotlin.math.abs(dx) < h.dragThreshold) return@setOnTouchListener true
                                h.dragMod = 3 // seek
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                            } else {
                                if (kotlin.math.abs(dy) < h.dragThreshold) return@setOnTouchListener true
                                val raportX = event.x / view.width.toFloat().coerceAtLeast(1f)
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                                h.dragMod = when {
                                    raportX < limitaStanga -> 2 // luminozitate
                                    raportX > limitaDreapta -> 1 // volum
                                    else -> 4 // scroll vertical => lasă ViewPager2
                                }
                            }
                        }
                        2 -> { // luminozitate
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            val delta = (h.dragStartY - event.y) / (view.height.toFloat() * 1.6f)
                            currentBrightness = (h.dragStartVal + delta).coerceIn(0.15f, 1f)
                            onBrightnessChange?.invoke(currentBrightness)
                            return@setOnTouchListener true
                        }
                        1 -> { // volum
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            val delta = (h.dragStartY - event.y) / (view.height.toFloat() * 1.6f)
                            currentVolume = (h.dragStartVal + delta).coerceIn(0f, 1f)
                            playerActiv?.volume = currentVolume
                            onVolumeChange?.invoke(currentVolume)
                            return@setOnTouchListener true
                        }
                        3 -> { // seek orizontal, pași de 10s
                            val durata = player.duration.coerceAtLeast(0L)
                            if (durata > 0) {
                                val pas = ((event.x - h.dragStartX) / 15f).toInt()
                                val target = h.dragStartPosMs + pas * SEEK_STEP_MS
                                player.seekTo(target.coerceIn(0L, durata))
                            }
                            return@setOnTouchListener true
                        }
                        4 -> return@setOnTouchListener false // scroll vertical
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val eraScroll = h.dragMod == 4
                    h.dragMod = 0
                    h.seekActive = false
                    if (eraScroll) false else true
                }
                else -> true
            }
        }

        val esteFav = memoryManager.esteFavorit(videoName)
        holder.btnFav.setImageResource(if (esteFav) android.R.drawable.star_on else android.R.drawable.star_off)
        holder.btnFav.setOnClickListener {
            val ac = memoryManager.toggleFavorite(videoName, (player.duration / 1000).toInt())
            holder.btnFav.setImageResource(if (ac) android.R.drawable.star_on else android.R.drawable.star_off)
        }

    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        val position = holder.adapterPosition
        if (position != RecyclerView.NO_POSITION) {
            val videoName = names.getOrElse(position) { "unknown" }
            holder.player?.let { salveazaProgres(videoName, it, null) }
            live.remove(position)
        }
        holder.playerView.player = null
        val p = holder.player
        if (playerActiv === p) playerActiv = null
        if (p != null) releasePlayer(p) // return în pool, nu eliberăm neapărat
        holder.player = null
    }

    /**
     * Activează (pornește audiovideo) videoclipul de pe pagina [position] și
     * oprește toate celelalte. Apelat când se schimbă pagina în ViewPager2.
     */
    fun setActivePage(position: Int) {
        activePosition = position
        val player = live[position]
        if (player != null) playerActiv = player
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

    /**
     * Setați rezoluția maximă de decodare (selectată în setări). Se aplică pe
     * selecția de track comună, deci afectează toți player-urile (existente și viitoare).
     * Ex: Auto=7680x4320, 4K=3840x2160, 2K=2560x1440, 1080p=1920x1080, 720p=1280x720.
     */
    fun setResolutie(w: Int, h: Int) {
        currentResolutie = Pair(w, h)
        try {
            val params = trackSelector.buildUponParameters()
                .setMaxVideoSize(w, h)
                .setForceHighestSupportedBitrate(false) // nu forțăm cea mai mare rată de bit
                .build()
            trackSelector.setParameters(params)
        } catch (e: Exception) {
            Log.e(TAG, "Eroare aplicare rezoluție", e)
        }
    }

    // ===== dublu-tap = play / pause ====
    private fun togglePlay(holder: VH, player: ExoPlayer) {
        try {
            if (player.isPlaying) {
                player.pause()
            } else {
                holder.loadingContainer.visibility = View.GONE
                // facem acest player activ (oprește celelalte) și îl pornim
                if (player !== playerActiv) playerActiv = player else player.play()
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