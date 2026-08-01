package com.swipe.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2

/**
 * Swipe Player PRO - player offline cu acces la storage.
 * - Alegere videoclipuri din telefon prin Storage Access Framework (SAF)
 * - Scroll vertical (sus/jos) printre videoclipuri
 * - Drag pe marginea stanga: luminozitate / dreapta: volum
 * - Lista de videoclipuri este persistata; se restaureaza la repornire
 */
class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val REQUEST_VIDEOS = 1001
    private val REQUEST_PERMS = 1002
    private val PREFS = "swipe_uv"
    private val KEY_URIS = "uris_video"

    private lateinit var tvStatus: TextView
    private lateinit var viewPager: ViewPager2
    private lateinit var prefs: SharedPreferences
    private var volumCurent: Float = 1f
    private var luminozitateCurenta: Float = 1f
    private var adapter: VideoPagerAdapter? = null
    private var videouri: MutableList<Uri> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        viewPager = findViewById(R.id.viewPager)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Scroll VERTICAL (sus/jos) - ca TikTok
        viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        viewPager.isUserInputEnabled = true

        // Încărcare setări salvate (volum, luminozitate)
        val setari = MemoryManager.getInstance(this).incarcaSetari()
        if (setari != null) {
            volumCurent = setari.first
            luminozitateCurenta = setari.second
        }

        val btnAlege = findViewById<Button>(R.id.btnChoose)
        btnAlege.setOnClickListener { alegeVideoclipuri() }

        cerePermisiuniDacaNecesar()

        // Restaurează lista salvată de videoclipuri (dacă există)
        restaurareLista()
    }

    private fun cerePermisiuniDacaNecesar() {
        if (Build.VERSION.SDK_INT >= 33) return
        if (Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_PERMS)
        }
    }

    private fun alegeVideoclipuri() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_VIDEOS)
        } catch (e: Exception) {
            Toast.makeText(this, "Nu am găsit un picker de fișiere", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VIDEOS && resultCode == RESULT_OK && data != null) {
            val uriSele = mutableListOf<Uri>()
            if (data.clipData != null) {
                for (i in 0 until data.clipData!!.itemCount) {
                    data.clipData!!.getItemAt(i).uri?.let { uriSele.add(it) }
                }
            } else if (data.data != null) {
                data.data?.let { uriSele.add(it) }
            }
            if (uriSele.isNotEmpty()) {
                uriSele.forEach { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) { Log.w(TAG, "nu pot persista accesul: $e") }
                }
                videouri.clear()
                videouri.addAll(uriSele)
                salveazaLista()
                incarcaLista(videouri)
            }
        }
    }

    private fun salveazaLista() {
        val joined = videouri.joinToString("\n") { it.toString() }
        prefs.edit().putString(KEY_URIS, joined).apply()
    }

    private fun restaurareLista() {
        val raw = prefs.getString(KEY_URIS, null) ?: return
        val uriStrings = raw.split("\n").filter { it.isNotBlank() }
        if (uriStrings.isEmpty()) return
        videouri.clear()
        uriStrings.forEach { s ->
            runCatching { Uri.parse(s) }.getOrNull()?.let { videouri.add(it) }
        }
        if (videouri.isNotEmpty()) incarcaLista(videouri)
    }

    private fun incarcaLista(lista: List<Uri>) {
        if (lista.isEmpty()) {
            Toast.makeText(this, "Nu s-a selectat niciun video", Toast.LENGTH_SHORT).show()
            return
        }
        val nouAdapter = VideoPagerAdapter(
            context = this,
            items = lista,
            initialVolume = volumCurent,
            onBrightnessChange = { aplicaLumina(it) },
            onVolumeChange = {}
        )
        nouAdapter.currentBrightness = luminozitateCurenta
        nouAdapter.currentVolume = volumCurent
        adapter = nouAdapter
        viewPager.adapter = nouAdapter

        // Când se schimbă pagina vizibilă => oprește celelalte videoclipuri, pornește pe cel nou
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                nouAdapter.setActivePage(position)
            }
        })
        nouAdapter.setActivePage(0) // doar primul videoclip pornește, restul rămân oprite

        aplicaLumina(luminozitateCurenta)
        val st = MemoryManager.getInstance(this).getStatistici()
        tvStatus.text = "🎬 ${lista.size} videoclipuri • ${st["totalVizionari"]} vizionări"
        Toast.makeText(this, "S-au încărcat ${lista.size} videoclipuri", Toast.LENGTH_SHORT).show()
    }

    private fun aplicaLumina(valoare: Float) {
        try {
            val lp = window.attributes ?: return
            lp.screenBrightness = valoare.coerceIn(0f, 1f)
            window.attributes = lp
        } catch (e: Exception) {
            Log.e(TAG, "Eroare aplicare luminozitate", e)
        }
    }

    override fun onPause() {
        super.onPause()
        salveazaSetarileCurente()
    }
    override fun onDestroy() {
        super.onDestroy()
        salveazaSetarileCurente()
        try {
            val lp = window.attributes ?: return
            lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        } catch (e: Exception) { /* ignoră */ }
    }
    private fun salveazaSetarileCurente() {
        try {
            MemoryManager.getInstance(this).salveazaSetari(volumCurent, luminozitateCurenta)
        } catch (e: Exception) {
            Log.e(TAG, "Eroare salvare setări", e)
        }
    }
}