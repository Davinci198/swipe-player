package com.swipe.player
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2

/**
 * Swipe Player PRO - reproduce videoclipuri offline din storage-ul telefonului.
 * - Alege videoclipuri prin Storage Access Framework (SAF)
 * - Glisare sus/jos: video următor/anterior
 * - Glisare verticală pe stânga: luminozitate (screen)
 * - Glisare verticală pe dreapta: volum
 */
class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val REQUEST_VIDEOS = 1001
    private val REQUEST_PERMS = 1002
    private lateinit var tvStatus: TextView
    private lateinit var viewPager: ViewPager2
    private lateinit var memoryManager: MemoryManager
    private var volumCurent: Float = 1f
    private var luminozitateCurenta: Float = 1f
    private var adapter: VideoPagerAdapter? = null
    private var videouri: MutableList<Uri> = mutableListOf()

    // drag pentru volum / luminozitate
    private var urmaritControl = 0 // 0=niciunul, 1=volum, 2=luminozitate
    private var startControlY = 0f
    private var startValoare = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        viewPager = findViewById(R.id.viewPager)
        memoryManager = MemoryManager.getInstance(this)

        // Încărcare setări salvate (volum, luminozitate)
        val setari = memoryManager.incarcaSetari()
        if (setari != null) {
            volumCurent = setari.first
            luminozitateCurenta = setari.second
            Log.i(TAG, "Setări restaurate: volum=${setari.first}, lumina=${setari.second}")
        }

        // Buton alegere videoclipuri (SAF)
        val btnAlege = findViewById<Button>(R.id.btnChoose)
        btnAlege.setOnClickListener { alegeVideoclipuri() }

        // Verifică + cere permisiune storage (doar pentru Android < 13, SAF era neatins)
        cerePermisiuniDacaNecesar()

        // Gestionează touch pentru volum/luminozitate pe zona video
        viewPager.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val w = viewPager.width
                    if (event.x < w * 0.32f) { urmaritControl = 2; startValoare = luminozitateCurenta }
                    else if (event.x > w * 0.68f) { urmaritControl = 1; startValoare = volumCurent }
                    else urmaritControl = 0
                    startControlY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (urmaritControl != 0) {
                        val h = viewPager.height
                        val delta = (startControlY - event.y) / (h * 0.6f)
                        if (urmaritControl == 1) {
                            volumCurent = (startValoare + delta).coerceIn(0f, 1f)
                            adapter?.let { it.setVolume(volumCurent); it.setVolumeToActive() }
                            tvStatus.text = "🔊 VOLUM ${Math.round(volumCurent * 100)}%"
                        } else {
                            luminozitateCurenta = (startValoare + delta).coerceIn(0.2f, 1.7f)
                            adapter?.setBrightness(luminozitateCurenta)
                            aplicaLumina(luminozitateCurenta)
                            tvStatus.text = "☀ LUMINĂ ${Math.round(luminozitateCurenta * 100)}%"
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    urmaritControl = 0
                    reafiseazaStatus()
                }
            }
            false
        }
        viewPager.isUserInputEnabled = true
    }

    private fun cerePermisiuniDacaNecesar() {
        if (Build.VERSION.SDK_INT >= 33) return // SAF nu cere permisiune explicită
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
                // persistem permisiunea de acces (offline, reporniri) => NEEDS contentResolver.takePersistableUriPermission
                uriSele.forEach { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) { Log.w(TAG, "nu pot persista accesul: $e") }
                }
                videouri.clear()
                videouri.addAll(uriSele)
                incarcaLista(videouri)
            }
        }
    }

    private fun incarcaLista(lista: List<Uri>) {
        if (lista.isEmpty()) { Toast.makeText(this, "Nu s-a selectat niciun video", Toast.LENGTH_SHORT).show(); return }
        val nouAdapter = VideoPagerAdapter(
            context = this,
            items = lista,
            initialVolume = volumCurent,
            onBrightnessChange = { aplicaLumina(it) },
            onVolumeChange = { }
        )
        adapter = nouAdapter
        viewPager.adapter = nouAdapter
        val st = memoryManager.getStatistici()
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

    private fun reafiseazaStatus() {
        val n = adapter?.itemCount ?: 0
        val st = memoryManager.getStatistici()
        if (n > 0) {
            tvStatus.text = "🎬 $n videoclipuri • ${st["totalVizionari"]} vizionări"
        } else {
            tvStatus.text = "SWIPE PLAYER PRO\nAlege videoclipuri din telefon (offline)"
        }
    }

    override fun onPause() {
        super.onPause()
        salveazaSetarileCurente()
    }
    override fun onDestroy() {
        super.onDestroy()
        salveazaSetarileCurente()
        // restabilește luminozitatea implicită
        try {
            val lp = window.attributes ?: return
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        } catch (e: Exception) { /* ignoră */ }
    }
    private fun salveazaSetarileCurente() {
        try {
            memoryManager.salveazaSetari(volumCurent, luminozitateCurenta)
        } catch (e: Exception) {
            Log.e(TAG, "Eroare salvare setări", e)
        }
    }
}
