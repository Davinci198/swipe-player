package com.swipe.player
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ProgressBar
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
 * Primește o listă de Uri-uri (content:// din SAF) și le redă vertical.
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
    private val memoryManager: MemoryManager by lazy {
        MemoryManager.getInstance(context)
    }
    private var lastSaveTime = 0L
    // valoarea curentă de controle (lumină | volum) pentru zonele de drag
    var currentBrightness: Float = 1f
    var currentVolume: Float = initialVolume ?: 1f
    var playerActiv: ExoPlayer? = null

    inner class VH(view: View) : ViewHolder(view) {
        val playerView: PlayerView = view.findViewById(R.id.player_view)
        var player: ExoPlayer? = null
        val tvName: TextView = view.findViewById(R.id.tvVideoName)
        val btnFav: ImageButton = view.findViewById(R.id.btnFavorite)
        val loadingContainer: LinearLayout = view.findViewById(R.id.loadingContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val uri = items[position]
        val videoName = names.getOrElse(position) { "Video ${position + 1}" }
        holder.tvName.text = videoName
        // Build player
        val player = ExoPlayer.Builder(context).build()
        holder.player = player
        playerActiv = player
        holder.playerView.player = player
        // Aplică volumul curent (salvat sau ajustat)
        player.volume = currentVolume
        // Aplică luminozitatea curentă
        onBrightnessChange?.invoke(currentBrightness)
        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        holder.loadingContainer.visibility = View.VISIBLE
                        Log.d(TAG, "Buffering: $videoName")
                    }
                    Player.STATE_READY -> {
                        holder.loadingContainer.visibility = View.GONE
                        Log.d(TAG, "Player ready: $videoName")
                    }
                    Player.STATE_ENDED -> {
                        salveazaProgres(videoName, player, 100)
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    salveazaProgresDacaTimpul(videoName, player)
                }
            }
            override fun onPositionDiscontinuity(reason: Int) {
                salveazaProgresDacaTimpul(videoName, player)
            }
            override fun onPlayerError(error: PlaybackException) {
                holder.loadingContainer.visibility = View.GONE
                Log.e(TAG, "Error playing $videoName: ${error.message}")
            }
        })

        player.prepare()
        player.playWhenReady = true

        val esteFav = memoryManager.esteFavorit(videoName)
        holder.btnFav.setImageResource(
            if (esteFav) android.R.drawable.star_on else android.R.drawable.star_off
        )
        holder.btnFav.setOnClickListener {
            val acumFav = memoryManager.toggleFavorite(videoName, (player.duration / 1000).toInt())
            holder.btnFav.setImageResource(
                if (acumFav) android.R.drawable.star_on else android.R.drawable.star_off
            )
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
        holder.player?.run {
            stop()
            release()
        }
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

    /** Ajustează volumul pentru videoclipul curent și îl propagează. */
    fun setVolume(v: Float) {
        currentVolume = v.coerceIn(0f, 1f)
        onVolumeChange?.invoke(currentVolume)
        // aplică pe playerul vizibil (poziția 0 în pager)
        // adapter nu are acces direct; playerii sunt în ViewHolders
    }
    /** Ajustează luminozitatea și o propagează. */
    fun setBrightness(b: Float) {
        currentBrightness = b.coerceIn(0.2f, 1.7f)
        onBrightnessChange?.invoke(currentBrightness)
    }
    /** Aplică volumul curent pe toți jucătorii existenți. */
    fun setVolumeToActive() {
        playerActiv?.volume = currentVolume
    }
}
