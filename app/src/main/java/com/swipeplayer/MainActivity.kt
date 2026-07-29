package com.swipeplayer

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var tvStatus: TextView
    private lateinit var viewPager: ViewPager2
    private lateinit var memoryManager: MemoryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        viewPager = findViewById(R.id.viewPager)

        // Inițializare sistem memorie
        memoryManager = MemoryManager.getInstance(this)

        // Încărcare setări salvate (volum, luminozitate)
        val setari = memoryManager.incarcaSetari()
        if (setari != null) {
            Log.i(TAG, "Setări restaurate: volum=${setari.first}, lumina=${setari.second}")
        }

        // Example video list (replace with your sources or feed)
        val videos = listOf(
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4",
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_480x270.mp4"
        )

        viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        viewPager.adapter = VideoPagerAdapter(this, videos)

        // Init native DragonBones bridge
        val ok = DragonBonesBridge.init()
        val version = DragonBonesBridge.getVersion()

        // Statistici memorie
        val stats = memoryManager.getStatistici()
        val totalVizionari = stats["totalVizionari"] ?: 0
        val totalFavorite = stats["totalFavorite"] ?: 0

        val msg = buildString {
            appendLine("DragonBones Native v$version")
            appendLine("Status: ${if (ok) "✓ LOADED" else "✗ FAILED"}")
            appendLine("🧠 Memorii: $totalVizionari vizionări, $totalFavorite favorite")
        }
        tvStatus.text = msg
        Log.i(TAG, "DragonBones init: $ok, version=$version")
        Toast.makeText(this, "Memorie activă • $totalVizionari vids", Toast.LENGTH_LONG).show()
    }

    override fun onPause() {
        super.onPause()
        // Salvăm setările curente când aplicația intră în background
        // În practică, volume/brightness sunt per-instance, le salvăm acum
        Log.d(TAG, "Aplicația intră în pauză — memoria e persistentă")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Aplicația se distruge — datele sunt în SharedPreferences")
    }
}
