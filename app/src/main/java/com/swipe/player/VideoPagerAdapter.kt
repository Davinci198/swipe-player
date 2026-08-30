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

    // holder-ul viu pentru fiecare player aflat în uz (folosit de listenerul per player)
    private val playerHolder = HashMap<ExoPlayer, VH>()

    // pool de ExoPlayer refolosiți (evită crearea a 100+ jucători pt. 100 de videoclipuri)
    private val pool = java.util.ArrayDeque<ExoPlayer>()
    private val MAX_POOL = 3

    // ===== Pinch ZOOM pe video (experimental, aditiv; nu atinge gesturile cu un deget) =====
    private var pinchPlayerView: PlayerView? = null
    private var videoZoom = 1f
    private var pinchActive = false // rămâne adevărat până scapi ultimul deget

    private val pinchListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val pv = pinchPlayerView ?: return false
            val newZoom = (videoZoom * detector.scaleFactor).coerceIn(1f, 4f)
            pv.scaleX = newZoom
            pv.scaleY = newZoom
            // centrare pe focalizare (simplu: pivot la centru)
            pv.pivotX = pv.width / 2f
            pv.pivotY = pv.height / 2f
            videoZoom = newZoom
            return true
        }
    }
    private val pinchDetector = ScaleGestureDetector(context, pinchListener)

    private fun resetVideoZoom(p: PlayerView?) {
        p?.also {
            it.scaleX = 1f
            it.scaleY = 1f
        }
        videoZoom = 1f
    }

    private fun acquirePlayer(): ExoPlayer {
        val p: ExoPlayer
        if (pool.isNotEmpty()) {
            p = pool.removeFirst()
        } else {
            p = ExoPlayer.Builder(context)
                .setTrackSelector(trackSelector)
                .setAudioAttributes(AudioAttributes.DEFAULT, true) // gestionează audio focus
                .build()
            // listenerul (wrapper per player, care închide DOAR acest [p]) se atașează
            // o singură dată, la crearea playerului. La reutilizarea din pool NU se mai adaugă
            // alt listener => fără leak / duplicate callbacks / salvare de progres greșită.
            p.addListener(creeazaListener(p))
        }
        p.clearMediaItems()
        return p
    }

    /** poziția curentă ocupată de un player în listă (-1 dacă nu e în uz) */
    private fun pozitiePentru(p: ExoPlayer): Int =
        live.entries.firstOrNull { it.value === p }?.key ?: -1

    /** numele videoclipului pentru un player (dacă e în uz) */
    private fun numePentru(p: ExoPlayer): String {
        val poz = pozitiePentru(p)
        return if (poz in 0 until names.size) names[poz] else "Video"
    }

    // Wrapper de listener PER PLAYER, atașat o singură dată la crearea playerului.
    // Închide DOAR lucruri stabile (acest [p] și adapterul [this]), nu holder/nume per
    // bind, deci la reutilizarea din pool NU se acumulează listeners și NU se scrie
    // progres pe videoclipul greșit.
    private fun creeazaListener(p: ExoPlayer): Player.Listener = object : Player.Listener {

        // Gard anti-crash: callbacks-urile ExoPlayer pot ajunge DUPĂ ce Activity / ViewPager2
        // au fost distruse (ex. redare în fundal). Dacă holder-ul e reciclat sau view-ul nu mai
        // e atașat, NU atinge UI-ul => altfel "Swipe Player se oprește încontinuu".
        private fun uiAttached(): Boolean {
            val holder = playerHolder[p] ?: return false
            val v = holder.itemView ?: return false
            return v.isAttachedToWindow
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // Funcționează și în fundal / fără UI (progres + autoplay trebuie să meargă mereu)
                salveazaProgres(numePentru(p), p, 100)
                val holder = playerHolder[p] ?: let {
                    if (autoOrder && pozitiePentru(p) == activePosition) {
                        onItemEnded?.invoke()
                    }
                    return
                }
                if (holder.itemView.isAttachedToWindow) {
                    holder.loadingContainer.visibility = View.GONE
                }
                if (autoOrder && pozitiePentru(p) == activePosition) {
                    onItemEnded?.invoke()
                }
                return
            }
            if (!uiAttached()) {
                // în fundal: chiar și fără UI, nu atingem UI-ul
                return
            }
            val holder = playerHolder[p] ?: return
            when (playbackState) {
                Player.STATE_BUFFERING -> holder.loadingContainer.visibility = View.VISIBLE
                Player.STATE_READY -> holder.loadingContainer.visibility = View.GONE
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) salveazaProgresDacaTimpul(numePentru(p), p)
        }
        override fun onPositionDiscontinuity(reason: Int) {
            salveazaProgresDacaTimpul(numePentru(p), p)
        }
        override fun onPlayerError(error: PlaybackException) {
            try {
                val h = playerHolder[p] ?: return
                if (h.itemView.isAttachedToWindow) {
                    h.loadingContainer.visibility = View.GONE
                }
            } catch (e: Exception) { }
            Log.e(TAG, "Error: ${error.message}")
        }
    }

    private fun releasePlayer(p: ExoPlayer) {
        p.volume = 0f
        p.playWhenReady = false
        p.pause()
        p.stop()
        p.clearMediaItems()
        if (pool.size < MAX_POOL) pool.addLast(p) else p.release()
    }

    /**
     * Eliberează defintiv TOȚI ExoPlayerii (folosiți + din pool). Apelat la închiderea
     * completă a aplicației, ca să NU rămână playere legate de o Activitate distrusă
     * (evită crash-uri pe thread-ul media la reinstanțiere).
     */
    fun elibereazaTot() {
        try {
            val toti = LinkedHashSet<ExoPlayer>().apply { addAll(live.values); addAll(pool) }
            for (p in toti) {
                try {
                    p.volume = 0f
                    p.playWhenReady = false
                    p.pause()
                    p.stop()
                    p.clearMediaItems()
                    p.release()
                } catch (e: Exception) { /* player deja eliberat */ }
            }
            live.clear()
            pool.clear()
            playerHolder.clear()
            playerActiv = null
            playerHolder.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Eroare eliberare playere", e)
        }
    }

    /** Toți ExoPlayerii aflați în uz sau în pool (pentru pause/resume global) */
    private fun allPlayers(): Collection<ExoPlayer> =
        LinkedHashSet<ExoPlayer>().apply { addAll(live.values); addAll(pool) }

    /**
     * Oprește toată redarea (apelat la onPause/onStop - când app merge în fundal).
     * Nu distruge playerii, doar îi pune în pauză și taie sunetul.
     */
    fun pauseAllPlayers() {
        for (p in allPlayers()) {
            try {
                p.playWhenReady = false
                p.volume = 0f
                p.pause()
            } catch (e: Exception) { /* player eliberat/oprit */ }
        }
    }

    /**
     * Reia doar videoclipul activ (apelat la onResume - când app revine în prim-plan).
     */
    fun resumeActivePlayer() {
        val p = playerActiv ?: return
        try {
            p.volume = currentVolume
            p.playWhenReady = true
            p.play()
        } catch (e: Exception) {
            // dacă playerul a fost eliberat (ex. Activitate distrusă în fundal) sau e în
            // stare invalidă, îl scoatem din activ și nu crăpăm
            if (playerActiv === p) playerActiv = null
            Log.w("VideoPagerAdapter", "resumeActiv: nu pot relua", e)
        }
    }

    /**
     * Returnează true dacă oricare dintre playerii activi rulează (playWhenReady).
     * Folosit pentru pauza/reluarea automată la apel telefonic.
     */
    fun isAnyPlayerPlaying(): Boolean {
        var any = false
        for (p in allPlayers()) {
            try {
                if (p.playWhenReady && p.duration > 0 && p.currentPosition >= 0) {
                    any = true
                    break
                }
            } catch (e: Exception) { /* player indisponibil */ }
        }
        return any || (try { playerActiv?.isPlaying == true } catch (e: Exception) { false })
    }

    // secunde de derulare per pas/swipe orizontal - ajustabil din Setări (2..30)
    var seekStepSec = 10

    // autoplay în ordine: când un videoclip se termină, trece automat la următorul
    var autoOrder = true

    // callback apelat când videoclipul activ ajunge la final (folosit pentru autoplay)
    var onItemEnded: (() -> Unit)? = null

    // timpul cât rămân vizibile controllerul + butoanele de derulare după o atingere
    private val CTRL_TIMEOUT_MS = 2500L

    // pagina (poziția) considerată vizibilă/activă - pornește doar ea
    private var activePosition = -1

    inner class VH(view: View) : ViewHolder(view) {
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
        val seekProgress: android.widget.ProgressBar = view.findViewById(R.id.seekProgress)
        // Overlay-uri NEON noi (design #08080A / #FF2A3D) + stratul de intercept
        val touchIntercept: View = view.findViewById(R.id.touchIntercept)
        val brightnessOverlay: View = view.findViewById(R.id.brightnessOverlay)
        val volumeOverlay: View = view.findViewById(R.id.volumeOverlay)
        val brightnessProgressBar: android.widget.ProgressBar = view.findViewById(R.id.brightnessProgress)
        val volumeProgressBar: android.widget.ProgressBar = view.findViewById(R.id.volumeProgress)
        val brightnessPct: TextView = view.findViewById(R.id.brightnessPct)
        val volumePct: TextView = view.findViewById(R.id.volumePct)

        // stare drag - locală pe ViewHolder (fără race condition între pagini)
        var dragMod = 0 // 0=none, 1=volum, 2=luminozitate, 3=seek, 4=scroll
        var dragZona = 0 // 0=mijloc(scroll), 1=margine stânga(lumină), 2=margine dreapta(volum)
        var dragStartX = 0f
        var dragStartY = 0f
        var dragStartVal = 0f
        var dragStartPosMs = 0L
        var seekActive = false
        var seekTargetMs = -1L // poziția țintă în timpul drag-ului de seek (seek real doar la UP)
        val dragThreshold = 8f // prag activare gest orizontal (px) pentru seek (mai sensibil)

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
        holder.playerView.setControllerShowTimeoutMs(CTRL_TIMEOUT_MS.toInt()) // sincron cu butoanele
        resetVideoZoom(holder.playerView) // fără zoom rămas din holder-ul reciclat
        // aplică starea de luminozitate pe paginile nou afișate (fallback dim vizibil)
        aplicaDim(holder, currentBrightness)
        // NU pornim automat - doar videoclipul activ porneste (prin setActivePage)
        player.volume = 0f
        onBrightnessChange?.invoke(currentBrightness)

        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        // listener-ul e deja atașat pe acest [player] (adăugat o singură dată la creare).
        // Păstrăm maparea player->holder pentru ca listenerul să găsească UI-ul corect.
        playerHolder[player] = holder
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

        // Ascunde butoanele ⏪/⏩ automat, când controllerul dispare după timeout
        // (nu le ținem mereu pe ecran; apar doar cu controllerul la atingere).
        val hideButtons = Runnable {
            if (holder.controllerVisibil) {
                holder.btnSeekBack.visibility = View.GONE
                holder.btnSeekFwd.visibility = View.GONE
                holder.dragMod = 0
            }
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
                    // dacă butoanele de control sunt dezactivate din Setări, nu le mai afișăm;
                    // doar ascundem orice era vizibil (gesturile swipe rămân active)
                    if (!controlsVisible) {
                        holder.playerView.hideController()
                        holder.itemView.removeCallbacks(hideButtons)
                        holder.btnSeekBack.visibility = View.GONE
                        holder.btnSeekFwd.visibility = View.GONE
                        holder.controllerVisibil = false
                        return true
                    }
                    // 1 tap = arată/ascunde controllerul (play/pause) + butoanele de derulare
                    val noul = !holder.controllerVisibil
                    if (noul) {
                        holder.playerView.showController()
                        holder.btnSeekBack.visibility = View.VISIBLE
                        holder.btnSeekFwd.visibility = View.VISIBLE
                        // butoanele dispar automat împreună cu controllerul (după timeout)
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
                    if (videoZoom > 1f) {
                        resetVideoZoom(holder.playerView) // dublu-tap = reset zoom video
                    } else {
                        togglePlay(holder, player) // 2 tap = play/pause
                    }
                    return true
                }
            }
        )
        val h = holder // referință locală pentru readabilitate
        // Ascultam pe perdeaua deasupra videoclipului (touchCatcher), nu pe PlayerView,
        // ca PlayerView/controllerul sa nu concureze pentru gesturi.
        h.touchIntercept.setOnTouchListener { view, event ->
            // PINCH ZOOM pe video: cu două degete, doar zoom (gesturile cu un deget nu se ating).
            // Chiar și după ce ridici un deget (pointerCount=1) rămânem în modul pinch până
            // la ACTION_UP, ca să NU se combine cu luminozitatea/volumul (bug „zip" final).
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
            // GestureDetector pentru tap/dublu-tap (play/pause/controller)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                gesture.onTouchEvent(event)
            } else if (event.actionMasked != MotionEvent.ACTION_MOVE ||
                !(h.dragMod in 1..3)) { // nu consumă MOVE-urile când facem lumina/volum/seek
                gesture.onTouchEvent(event)
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    h.dragStartX = event.x
                    h.dragStartY = event.y
                    h.dragStartPosMs = player.currentPosition
                    h.seekActive = false
                    h.seekTargetMs = -1L
                    h.dragMod = 0
                    h.dragZona = 0
                    Log.d("GESTURE", "DOWN x=${"%.0f".format(event.x)} y=${"%.0f".format(event.y)}")
                    // NU blocăm încă — așteptăm să vedem direcția
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - h.dragStartX
                    val dy = event.y - h.dragStartY
                    val adx = kotlin.math.abs(dx)
                    val ady = kotlin.math.abs(dy)

                    // Prima decizie: pe baza direcției dominante
                    if (h.dragMod == 0) {
                        val touchSlop = 8f * view.context.resources.displayMetrics.density // 8dp
                        if (adx > touchSlop || ady > touchSlop) {
                            // BLOCHEAZĂ TOT LANȚUL DE PĂRINȚI — asta e criteriul lipsă
                            var p = view.parent
                            while (p != null) {
                                p.requestDisallowInterceptTouchEvent(true)
                                p = p.parent
                            }

                            h.dragMod = if (adx > ady) 3 else h.dragZona // orizontal=SEEK, vertical=zona
                            when (h.dragMod) {
                                2 -> showVerticalIndicator(h, 2, currentBrightness)
                                1 -> showVerticalIndicator(h, 1, currentVolume)
                                3 -> {
                                    val d = player.duration.coerceAtLeast(0L)
                                    if (d > 0) showSeekIndicator(h, player.currentPosition, d)
                                    h.dragStartPosMs = player.currentPosition
                                }
                            }
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        return@setOnTouchListener true
                    }

                    // RECONFIRMĂ disallow la FIECARE MOVE cât timp suntem în seek
                    // Altfel ViewPager2 recapătă controlul între MOVE-uri
                    if (h.dragMod == 3) {
                        var p = view.parent
                        while (p != null) {
                            p.requestDisallowInterceptTouchEvent(true)
                            p = p.parent
                        }
                    }

                    when (h.dragMod) {
                        2 -> { // BRIGHTNESS: dy * 0.002f adunat incremental (ca în demo)
                            currentBrightness = (currentBrightness + dy * 0.002f).coerceIn(0.01f, 1f)
                            h.dragStartY = event.y // delta incremental
                            onBrightnessChange?.invoke(currentBrightness)
                            aplicaDim(h, currentBrightness)
                            showVerticalIndicator(h, 2, currentBrightness)
                            true
                        }
                        1 -> { // VOLUME: trepte sistem cu prag (ca în demo, fără flooding)
                            if (kotlin.math.abs(dy) >= 8f * view.context.resources.displayMetrics.density) {
                                val am = audioManager()
                                am?.adjustStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    if (dy > 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE,
                                    0
                                )
                                val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                                val cur = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                                currentVolume = if (max > 0) cur.toFloat() / max else currentVolume
                                playerActiv?.volume = currentVolume.coerceIn(0f, 1f)
                                onVolumeChange?.invoke(currentVolume)
                                showVerticalIndicator(h, 1, currentVolume)
                                h.dragStartY = event.y // reset prag
                            }
                            true
                        }
                        3 -> { // SEEK: preview la fiecare MOVE; seek REAL doar la UP
                            val durata = player.duration.coerceAtLeast(0L)
                            if (durata > 0) {
                                // Folosim dx proaspăt (ca în demo) — poziție relativă la start-ul gestului
                                val percent = dx / view.width // -1..1
                                val seekDelta = (percent * 90_000).toLong() // max ±90 sec
                                val target = (h.dragStartPosMs + seekDelta).coerceIn(0L, durata)
                                h.seekTargetMs = target
                                h.seekActive = true
                                showSeekIndicator(h, target, durata)
                            }
                            true
                        }
                        else -> true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // ELIBEREAZĂ TOT LANȚUL DE PĂRINȚI
                    var p = view.parent
                    while (p != null) {
                        p.requestDisallowInterceptTouchEvent(false)
                        p = p.parent
                    }
                    // seek REAL o singură dată, la ridicarea degetului
                    if (h.dragMod == 3 && h.seekActive && h.seekTargetMs >= 0L) {
                        player.seekTo(h.seekTargetMs)
                        h.seekTargetMs = -1L
                    }
                    // ascunde overlay-urile cu un mic delay (spring-like), ca în demo
                    view.postDelayed({ hideIndicators(h) }, 800)
                    h.dragMod = 0
                    h.dragZona = 0
                    h.seekActive = false
                    true
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

        // Butoane ⏪ / ⏩ de derulare rapidă: pas = seekStepSec (configurat în Setări).
        // Implicit ASCUNSE; apar doar împreună cu controllerul media (la atingere).
        val secMs = seekStepSec.coerceIn(2, 30) * 1000L
        holder.btnSeekBack.visibility = View.GONE
        holder.btnSeekFwd.visibility = View.GONE
        holder.btnSeekBack.setOnClickListener {
            val d = player.duration.coerceAtLeast(0L)
            val target = (player.currentPosition - secMs).coerceIn(0L, d)
            player.seekTo(target)
            if (d > 0) showSeekIndicator(holder, target, d)
        }
        holder.btnSeekFwd.setOnClickListener {
            val d = player.duration.coerceAtLeast(0L)
            val target = (player.currentPosition + secMs).coerceIn(0L, d)
            player.seekTo(target)
            if (d > 0) showSeekIndicator(holder, target, d)
        }

    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        // reset fallback dim + indicatoare ca să nu rămână pe altă pagină
        holder.dimOverlay?.let { ov ->
            ov.alpha = 0f
            ov.visibility = View.INVISIBLE
        }
        hideIndicators(holder)
        val position = holder.adapterPosition
        if (position != RecyclerView.NO_POSITION) {
            val videoName = names.getOrElse(position) { "unknown" }
            holder.player?.let { salveazaProgres(videoName, it, null) }
            live.remove(position)
        }
        holder.playerView.player = null
        val p = holder.player
        if (playerActiv === p) playerActiv = null
        p?.let { playerHolder.remove(it) } // scoatem maparea player->holder
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

    // ===== volum sistem + câștig player (Motorola/Stock) =====
    // Pe Android 12+/Motorola, player.volume (ExoPlayer) doar scade câștigul intern al
    // playerului, NU volumul sistemului. Atunci când userul face swipe pe dreapta se așteaptă
    // să se schimbe volumul media al telefonului => folosim AudioManager.
    private fun audioManager(): AudioManager? =
        try { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager } catch (e: Exception) { null }

    fun aplicaVolumSistem(v: Float) {
        try {
            val am = audioManager() ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val newVol = (v.coerceIn(0f, 1f) * max).toInt()
            // FLAG_SHOW_UI => apare sliderul de volum al sistemului (fara click sonor
            // FLAG_PLAY_SOUND, ca sa nu tacaia continuu in timpul swipe-ului).
            am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_SHOW_UI)
            Log.d("VOLUME", "setStreamMusic=$newVol/$max ratio=${"%.2f".format(v)}")
        } catch (e: Exception) {
            Log.e(TAG, "Eroare setare volum sistem", e)
        }
        // setăm și câștigul intern al playerului activ pentru acuratețe
        playerActiv?.volume = v.coerceIn(0f, 1f)
    }

    // ===== attenuire (dim) vizual ca fallback luminozitate =====
    // Pe unele dispozitive (Motorola 12+) fără WRITE_SETTINGS, window.screenBrightness
    // poate fi ignorat/limitat. Acest overlay negru peste player simulează scăderea
    // luminozității ca să fie VIZIBIL. Dacă app ARE WRITE_SETTINGS, lumina e aplicată
    // nativ pe fereastră => nu mai ateniem (evităm dubla întunecare).
    fun aplicaDim(holder: VH?, b: Float) {
        holder?.dimOverlay?.let { ov ->
            val amScrisSetari = canWriteBrightness()
            val alfa = if (amScrisSetari) {
                0f // lumina e gestionată nativ pe fereastră
            } else {
                ((1f - b.coerceIn(0.15f, 1f)) * 0.8f).coerceIn(0f, 0.8f)
            }
            ov.alpha = alfa
            ov.visibility = if (alfa > 0.02f) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun canWriteBrightness(): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.System.canWrite(context)
    } catch (e: Exception) { false }

    // ===== indicatoare vizuale (fallback slider) =====
    // Bara verticală (stânga=lumina, dreapta=volum) umplută după nivel; se ascunde la UP.
    private fun setVerticalFill(fill: View, level: Float) {
        fill.post {
            val parentV = fill.parent as? View
            val hMax = (parentV?.height ?: 200).coerceAtLeast(40)
            val nh = (hMax * level.coerceIn(0f, 1f)).toInt().coerceAtLeast(if (level > 0.01f) 6 else 0)
            val lp = fill.layoutParams ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, nh)
            lp.height = nh
            fill.layoutParams = lp
        }
    }
    private fun showVerticalIndicator(holder: VH?, kind: Int, level: Float) {
        holder ?: return
        holder.brightnessOverlay.bringToFront()
        holder.volumeOverlay.bringToFront()
        if (kind == 2) { // luminozitate (stânga) -> overlay NEON stânga
            holder.brightnessOverlay.visibility = View.VISIBLE
            val pct = (level.coerceIn(0f, 1f) * 100).toInt()
            holder.brightnessProgressBar.progress = pct
            holder.brightnessPct.text = "$pct%"
            holder.volumeOverlay.visibility = View.GONE
        } else if (kind == 1) { // volum (dreapta) -> overlay NEON dreapta
            holder.volumeOverlay.visibility = View.VISIBLE
            val pct = (level.coerceIn(0f, 1f) * 100).toInt()
            holder.volumeProgressBar.progress = pct
            holder.volumePct.text = "$pct%"
            holder.brightnessOverlay.visibility = View.GONE
        }
    }
    private fun showSeekIndicator(holder: VH?, posMs: Long, durMs: Long) {
        holder ?: return
        holder.seekIndicator.visibility = View.VISIBLE
        val p = (if (durMs > 0) ((posMs * 1000) / durMs).toInt() else 0).coerceIn(0, 1000)
        holder.seekProgress.progress = p
        holder.seekTime.text = "${fmtTimp(posMs)} / ${fmtTimp(durMs)}"
    }
    private fun hideIndicators(holder: VH?) {
        holder ?: return
        holder.brightnessIndicator.visibility = View.GONE
        holder.volumeIndicator.visibility = View.GONE
        holder.seekIndicator.visibility = View.GONE
        // ascund și overlay-urile NEON noi
        holder.brightnessOverlay.visibility = View.GONE
        holder.volumeOverlay.visibility = View.GONE
    }
    private fun fmtTimp(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val m = s / 60
        val sec = s % 60
        return "$m:${if (sec < 10) "0" else ""}$sec"
    }

    fun setVolume(v: Float) {
        currentVolume = v
        aplicaVolumSistem(currentVolume)
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

    // ===== vizibilitatea butoanelor de control (play/pause + ⏪/⏩) =====
    // Controlat din Setări: true = apar la atingere; false = ascunse complet.
    private var controlsVisible: Boolean = true

    /**
     * Activează/dezactivează afișarea butoanelor de control media în modul Video.
     * Dacă false, controllerul + ⏪/⏩ nu se mai afișează nici la atingere.
     */
    fun setControlsVisible(activat: Boolean) {
        controlsVisible = activat
        if (!activat) {
            // ascundem imediat orice controale vizibile în toți holder-ii activi
            for (h in live.values) {
                playerHolder[h]?.let { holder ->
                    holder.btnSeekBack.visibility = View.GONE
                    holder.btnSeekFwd.visibility = View.GONE
                }
            }
        }
    }

    /** Ascunde toate controllerele media + butoanele ⏪/⏩ în toți holder-ii activi. */
    fun hideAllControllers() {
        for (h in live.values) {
            playerHolder[h]?.let { holder ->
                holder.playerView.hideController()
                holder.btnSeekBack.visibility = View.GONE
                holder.btnSeekFwd.visibility = View.GONE
                holder.controllerVisibil = false
            }
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