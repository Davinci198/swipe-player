package com.swipe.player

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
    private var volumCurent: Float = 1f

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
            volumCurent = setari.first
            Log.i(TAG, "Setări restaurate: volum=${setari.first}, lumina=${setari.second}")
        }

        // Example video list (replace with your sources or feed)
        val videos = listOf(
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4",
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_480x270.mp4"
        )

        viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        viewPager.adapter = VideoPagerAdapter(this, videos, initialVolume = volumCurent)

        // Init native DragonBones bridge
        val ok = try { DragonBonesBridge.init() } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "DragonBones native not available"); false }
        val version = try { DragonBonesBridge.getVersion() } catch (e: UnsatisfiedLinkError) { "N/A" }

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
        // Salvăm setările curente (volum, luminozitate) când aplicația intră în background
        salveazaSetarileCurente()
        Log.d(TAG, "Aplicația intră în pauză — memoria e persistentă")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Salvează setările la distrugere (finală) pentru siguranță
        salveazaSetarileCurente()
        Log.d(TAG, "Aplicația se distruge — datele sunt în SharedPreferences")
    }

    /**
     * Salvează setările curente de redare în memoria persistentă.
     * Luminozitatea rămâne implicită (1.0) deoarece playerul video nu o gestionează;
     * logica de volum este persistată și reaplicată la fiecare pornire.
     */
    private fun salveazaSetarileCurente() {
        try {
            memoryManager.salveazaSetari(volumCurent, 1f)
            Log.d(TAG, "Setări salvate: volum=$volumCurent")
        } catch (e: Exception) {
            Log.e(TAG, "Eroare salvare setări", e)
        }
    }
}
