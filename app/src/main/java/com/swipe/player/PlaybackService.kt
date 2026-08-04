package com.swipe.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 🎧 PlaybackService — foreground service de redare în fundal.
 *
 * Rol:
 *  - menține procesul aplicației cu prioritate înaltă cât timp "Redare în fundal" e activă,
 *    astfel încât sistemul (Doze/WakeLock) să NU taie sunetul videoclipului;
 *  - afișează o notificare media persistentă cu acțiunile Play/Pause și Stop,
 *    folosite pentru a controla playerul direct și fără a deschide aplicația.
 *
 * Serviciul NU ține ExoPlayer-ul (aceștia rămân în adapter); doar ține procesul prioritar
 * și emite broadcast-uri de control spre MainActivity.
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIF_ID = 2001

        const val ACTION_CONTROL = "com.swipe.player.ACTION_PLAYBACK_CONTROL"
        const val ACTION_PLAY = "com.swipe.player.PLAY"
        const val ACTION_PAUSE = "com.swipe.player.PAUSE"
        const val ACTION_STOP = "com.swipe.player.STOP"

        /** Numele videoclipului aflat în redare (afișat în notificare. */
        private var currentName: String = "Swipe Player"

        /** Pornește serviciul de redare în fundal. Sigur la orice versiune. */
        fun startPlaybackService(context: Context, videoName: String? = null) {
            currentName = videoName ?: currentName
            val ctx = context.applicationContext
            val intent = Intent(ctx, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /** Oprește serviciul (apelat la revenire în prim-plan / închidere). */
        fun stopPlaybackService(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    // Receiver pentru acțiunile din notificare (forwardă spre MainActivity prin broadcast).
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val action = intent.action
            val forward = Intent(ACTION_CONTROL).setPackage(c.packageName)
            when (action) {
                ACTION_PLAY -> forward.action = ACTION_PLAY
                ACTION_PAUSE -> forward.action = ACTION_PAUSE
                ACTION_STOP -> forward.action = ACTION_STOP
            }
            c.sendBroadcast(forward)
            if (action == ACTION_STOP) {
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_STOP)
        }
        // FIX crash pe Android 13+ (API 33): registerReceiver FĂRĂ flag RECEIVER_EXPORTED /
        // RECEIVER_NOT_EXPORTED aruncă SecurityException la fiecare pornire a serviciului
        // => "Swipe Player se oprește încontinuu" la redarea în fundal.
        // controlReceiver e intern (doar control din notificare), deci RECEIVER_NOT_EXPORTED.
        registerControlReceiver(filter)
        startAsForeground()
    }

    private fun registerControlReceiver(filter: IntentFilter) {
        try {
            // dacă serviciul e repornit (sistemul îl ține orfan), receiver-ul poate fi deja
            // înregistrat => unregister întâi pt a evita IllegalArgumentException la re-register
            try { unregisterReceiver(controlReceiver) } catch (e: Exception) { /* nu era */ }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(controlReceiver, filter)
            }
        } catch (e: Exception) {
            // niciodată să nu lăsăm serviciul să crape din cauza receiver-ului
            Log.e("PlaybackService", "Nu pot înregistra controlReceiver: $e")
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(controlReceiver) } catch (e: Exception) {}
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(NOTIF_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        // START_NOT_STICKY: dacă sistemul ucide serviciul (optimizare baterie/Doze),
        // NU-l repornim singur. Așa evităm un serviciu orfan care se re-pornește în
        // buclă și cauzează "Swipe Player se oprește încontinuu".
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // Fallback sigur: nu lăsăm serviciul să crape; încercăm startForeground fără tip.
            try {
                startForeground(NOTIF_ID, buildNotification())
            } catch (e2: Exception) {
                Log.e("PlaybackService", "Nu pot porni foreground: $e2")
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Redare în fundal",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controlează redarea când aplicația rulează în fundal."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pm = packageManager
        val launcher = pm.getLaunchIntentForPackage(packageName)

        val contentIntent = PendingIntent.getActivity(
            this, 0, launcher,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPending = pendingFor(ACTION_PLAY)
        val pausePending = pendingFor(ACTION_PAUSE)
        val stopPending = pendingFor(ACTION_STOP)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Redare în fundal")
            .setContentText(currentName)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Pauză", pausePending)
            .addAction(android.R.drawable.ic_media_play, "Redare", playPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Oprire", stopPending)
            .build()
    }

    private fun pendingFor(action: String): PendingIntent {
        val intent = Intent(this, controlReceiver::class.java).apply { this.action = action }
        // explicit receiver => sigur pe Android 8+
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, action.hashCode(), intent, flags)
    }
}
