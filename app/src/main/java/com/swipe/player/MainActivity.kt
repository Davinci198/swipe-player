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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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
    private var rezolutieCurenta: Int = 0 // 0=Auto, 720, 1080, 1440 (2K), 2160 (4K)
    private var adapter: VideoPagerAdapter? = null
    private var videouri: MutableList<Uri> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        viewPager = findViewById(R.id.viewPager)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Buton setari (colt stanga sus)
        val btnSettings = findViewById<android.widget.ImageButton>(R.id.btnSettings)
        btnSettings.setOnClickListener { deschideSetari() }

        // Scroll VERTICAL (sus/jos) - ca TikTok
        viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        viewPager.isUserInputEnabled = true

        // Încărcare setări salvate (volum, luminozitate)
        val setari = MemoryManager.getInstance(this).incarcaSetari()
        if (setari != null) {
            volumCurent = setari.first
            luminozitateCurenta = setari.second
        }
        rezolutieCurenta = MemoryManager.getInstance(this).incarcaRezolutie()

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
        // aplica rezoluția salvată (0=Auto, 720, 1080, 1440/2K, 2160/4K)
        var (rw, rh) = rezolutieW(rezolutieCurenta)
        if (rw >= 3840 && !isDeviceSuports4K()) {
            rw = 1920; rh = 1080; rezolutieCurenta = 1080
            Toast.makeText(this, "Echipamentul nu suportă 4K → 1080p", Toast.LENGTH_SHORT).show()
        }
        if (rw > 0) nouAdapter.setResolutie(rw, rh)
        adapter = nouAdapter
        viewPager.adapter = nouAdapter

        // Când se schimbă pagina vizibilă => oprește celelalte videoclipuri, pornește pe cel nou
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                nouAdapter.setActivePage(position)
            }
        })
        nouAdapter.setActivePage(0) // doar primul videoclip pornește, restul rămân oprite

        // aplică luminozitatea doar dacă e într-un interval rezonabil (bug sistem: nu forțăm 10%)
        if (luminozitateCurenta in 0.3f..1.0f) aplicaLumina(luminozitateCurenta)
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

    override fun onResume() {
        super.onResume()
        // Luminozitate: NU restaura automat o valoare prea mică (bug sistem).
        // Reaplicăm doar dacă valoarea e într-un interval rezonabil (0.3..1.0).
        if (luminozitateCurenta in 0.3f..1.0f) {
            aplicaLumina(luminozitateCurenta)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        salveazaSetarileCurente()
        // restaurează luminozitatea sistemului (să nu rămână blocată pe valoarea setată)
        try {
            val lp = window.attributes ?: return
            lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        } catch (e: Exception) { /* ignoră */ }
    }
    private fun salveazaSetarileCurente() {
        try {
            MemoryManager.getInstance(this).salveazaSetari(volumCurent, luminozitateCurenta)
            MemoryManager.getInstance(this).salveazaRezolutie(rezolutieCurenta)
        } catch (e: Exception) {
            Log.e(TAG, "Eroare salvare setări", e)
        }
    }

    // Euristică de suport 4K: jumătate din dispozitivele cu >= 6GB RAM decodează 4K fără throttling
    private fun isDeviceSuports4K(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val ramTotalGB = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            ramTotalGB >= 6.0
        } catch (e: Exception) {
            false
        }
    }

    // înălțime salvată -> (width, height) maxim pentru decodare; 0=Auto
    private fun rezolutieW(h: Int): Pair<Int, Int> = when (h) {
        720 -> Pair(1280, 720)
        1080 -> Pair(1920, 1080)
        1440 -> Pair(2560, 1440) // 2K
        2160 -> Pair(3840, 2160) // 4K
        else -> Pair(7680, 4320) // Auto
    }
    private fun deschideSetari() {
        val mm = MemoryManager.getInstance(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
        }

        // ---- Luminozitate ----
        root.addView(TextView(this).apply {
            text = "Luminozitate"
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
        })
        val seekLumina = android.widget.SeekBar(this).apply {
            max = 1000
            progress = (luminozitateCurenta.coerceIn(0f, 1f) * 1000).toInt()
        }
        root.addView(seekLumina)

        // ---- Volum ----
        root.addView(TextView(this).apply {
            text = "Volum"
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
        })
        val seekVolum = android.widget.SeekBar(this).apply {
            max = 1000
            progress = (volumCurent.coerceIn(0f, 1f) * 1000).toInt()
        }
        root.addView(seekVolum)

        // ---- Rezoluție ----
        root.addView(TextView(this).apply {
            text = "Rezoluție"
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
        })
        val opts = listOf(
            Triple("Auto", 0, 7680 to 4320),
            Triple("720p", 720, 1280 to 720),
            Triple("1080p", 1080, 1920 to 1080),
            Triple("2K (1440p)", 1440, 2560 to 1440),
            Triple("4K", 2160, 3840 to 2160)
        )
        val radio = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val idRes = HashMap<Int, Pair<Int, Pair<Int, Int>>>() // id -> (inaltime, wh)
        opts.forEach { (nume, h, wh) ->
            val rb = android.widget.RadioButton(this).apply {
                text = nume
                id = View.generateViewId()
            }
            radio.addView(rb)
            idRes[rb.id] = Pair(h, wh)
            if (h == rezolutieCurenta) rb.isChecked = true
        }
        root.addView(radio)

        // ---- Build dialog ----
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("⚙️ Setări")
            .setView(root)
            .setNegativeButton("Închide", null)
            .setPositiveButton("Aplică") { _, _ ->
                // Luminozitate
                val lum = seekLumina.progress / 1000f
                luminozitateCurenta = lum
                aplicaLumina(lum)
                adapter?.setBrightness(lum)

                // Volum
                val vol = seekVolum.progress / 1000f
                volumCurent = vol
                adapter?.setVolume(vol)

                // Rezoluție (4K pe dispozitive slabe => fallback la 1080p + toast)
                val checked = radio.checkedRadioButtonId
                var (h, wh) = idRes[checked] ?: (0 to (7680 to 4320))
                if (wh.first >= 3840 && !isDeviceSuports4K()) {
                    h = 1080; wh = 1920 to 1080
                    Toast.makeText(this, "Echipamentul nu suportă 4K → 1080p", Toast.LENGTH_SHORT).show()
                }
                rezolutieCurenta = h
                adapter?.setResolutie(wh.first, wh.second)

                salveazaSetarileCurente()
            }
            .create()
        dialog.show()
    }
}