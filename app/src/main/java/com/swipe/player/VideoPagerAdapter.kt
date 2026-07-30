package com.swipe.player

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.ui.PlayerView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player.PositionInfo
import androidx.media3.exoplayer.ExoPlayer

class VideoPagerAdapter(
    private val context: Context,
    private val items: List<String>,
    private val names: List<String> = items.map { it.substringAfterLast("/").substringBefore("?") }
) : RecyclerView.Adapter<VideoPagerAdapter.VH>() {

    private val TAG = "VideoPagerAdapter"
    private val memoryManager: MemoryManager by lazy {
        MemoryManager.getInstance(context)
    }

    // Câte secunde trebuie să treacă pentru a salva progresul (evităm salvarea la fiecare frame)
    private var lastSaveTime = 0L

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val playerView: PlayerView = view.findViewById(R.id.player_view)
        var player: ExoPlayer? = null
        val tvName: TextView = view.findViewById(R.id.tvVideoName)
        val btnFav: ImageButton = view.findViewById(R.id.btnFavorite)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val url = items[position]
        val videoName = names.getOrElse(position) { "Video ${position + 1}" }

        // Nume video
        holder.tvName.text = videoName

        // Build player
        val player = ExoPlayer.Builder(context).build()
        holder.player = player
        holder.playerView.player = player

        val mediaItem = MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)

        // Listener pentru salvare istoric și progres
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    Log.d(TAG, "Player ready: $videoName (${player.duration}ms)")
                }
                if (playbackState == Player.STATE_ENDED) {
                    // Salvare când se termină video
                    salveazaProgres(videoName, player, 100)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    // La începutul redării, dacă avem poziție salvată
                    Log.d(TAG, "Redare pornită: $videoName")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Eroare redare $videoName: ${error.message}")
            }
        })

        // Actualizare periodică la 5 secunde pentru salvare progres
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                salveazaProgresDacaTimpul(videoName, player)
            }
        })

        player.prepare()
        player.playWhenReady = true

        // Buton favorite
        val esteFav = memoryManager.esteFavorit(videoName)
        holder.btnFav.setImageResource(if (esteFav) android.R.drawable.star_on else android.R.drawable.star_off)
        holder.btnFav.setOnClickListener {
            val acumFav = memoryManager.toggleFavorite(videoName, (player.duration / 1000).toInt())
            holder.btnFav.setImageResource(
                if (acumFav) android.R.drawable.star_on else android.R.drawable.star_off
            )
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        // Salvare progres înainte de reciclare
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

    // ─── Salvare progres ───

    private fun salveazaProgresDacaTimpul(nume: String, player: ExoPlayer) {
        val acum = System.currentTimeMillis()
        if (acum - lastSaveTime < 5000) return // maxim o dată la 5 sec
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
}
