package com.swipe.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
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
class MainActivity : AppCompatActivity(), SettingsBottomSheetDialogFragment.Listener {
    private val TAG = "MainActivity"
    private val REQUEST_VIDEOS = 1001
    private val REQUEST_PHOTOS = 1003
    private val REQUEST_PERMS = 1002
    private val PREFS = "swipe_uv"
    private val KEY_URIS = "uris_video"
    private val KEY_PHOTO_URIS = "uris_photo"

    private lateinit var tvStatus: TextView
    private lateinit var viewPager: ViewPager2
    private lateinit var imagePager: ViewPager2
    private lateinit var modeVideoBtn: TextView
    private lateinit var modePhotoBtn: TextView
    private lateinit var prefs: SharedPreferences
    private var volumCurent: Float = 1f
    private var luminozitateCurenta: Float = 1f
    private var rezolutieCurenta: Int = 0 // 0=Auto, 720, 1080, 1440 (2K), 2160 (4K)
    private var adapter: VideoPagerAdapter? = null
    private var photoAdapter: ImagePagerAdapter? = null
    private var videouri: MutableList<Uri> = mutableListOf()
    private var poze: MutableList<Uri> = mutableListOf()

    // modul curent: "video" sau "photo"
    private var modCurent: String = "video"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        viewPager = findViewById(R.id.viewPager)
        imagePager = findViewById(R.id.imagePager)
        modeVideoBtn = findViewById(R.id.modeVideoBtn)
        modePhotoBtn = findViewById(R.id.modePhotoBtn)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Buton setari (colț dreapta jos - evită back-swipe din stânga sus)
        val btnSettings = findViewById<android.widget.ImageButton>(R.id.btnSettings)
        btnSettings.setOnClickListener { deschideSetari() }

        // Scroll VERTICAL (sus/jos) - ca TikTok
        viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        viewPager.isUserInputEnabled = true
        imagePager.orientation = ViewPager2.ORIENTATION_VERTICAL
        imagePager.isUserInputEnabled = true

        // mod videoclipuri / poze
        modeVideoBtn.setOnClickListener { setMod("video") }
        modePhotoBtn.setOnClickListener { setMod("photo") }

        // Încărcare setări salvate (volum, luminozitate)
        val setari = MemoryManager.getInstance(this).incarcaSetari()
        if (setari != null) {
            volumCurent = setari.first
            luminozitateCurenta = setari.second
        }
        rezolutieCurenta = MemoryManager.getInstance(this).incarcaRezolutie()

        cerePermisiuniDacaNecesar()
        cerePermisiuneLuminozitate() // Motorola/Android 12+: WRITE_SETTINGS pentru swipe lumina

        // Restaurează listele salvate (videoclipuri + poze)
        restaurareLista()
        restaurarePoze()
        setMod("video")
    }

    /** comută între modul de videoclipuri și galeria de poze (ambele cu swipe vertical) */
    private fun setMod(mod: String) {
        modCurent = mod
        val video = mod == "video"
        viewPager.visibility = if (video && adapter != null) View.VISIBLE else View.GONE
        imagePager.visibility = if (!video && photoAdapter != null) View.VISIBLE else View.GONE
        // evidențiază butonul activ
        modeVideoBtn.setBackgroundColor(if (video) 0x33FFFFFF.toInt() else 0x00000000)
        modePhotoBtn.setBackgroundColor(if (!video) 0x33FFFFFF.toInt() else 0x00000000)
        val status = if (video) {
            val n = adapter?.itemCount ?: videouri.size
            if (n > 0) "🎬 $n videoclipuri" else "Niciun videoclip ales"
        } else {
            val n = photoAdapter?.itemCount ?: poze.size
            if (n > 0) "🖼️ $n poze" else "Nicio poză aleasă"
        }
        tvStatus.text = status
    }

    /**
     * Luminozitatea ecranului (window.attributes.screenBrightness) e ignorată pe
     * Android 12+ / Motorola dacă app nu are WRITE_SETTINGS. Aici cerem accesul.
     */
    private fun cerePermisiuneLuminozitate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.System.canWrite(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                Toast.makeText(
                    this,
                    "Permiteți modificarea luminozității pentru controlul swipe",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Nu pot deschide setările WRITE_SETTINGS", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eroare verificare WRITE_SETTINGS", e)
        }
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
        deschidePicker("video/*", REQUEST_VIDEOS)
    }
    private fun alegePoze() {
        deschidePicker("image/*", REQUEST_PHOTOS)
    }
    private fun deschidePicker(mime: String, requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Toast.makeText(this, "Nu am găsit un picker de fișiere", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode != REQUEST_VIDEOS && requestCode != REQUEST_PHOTOS) return
        val uriSele = mutableListOf<Uri>()
        if (data.clipData != null) {
            for (i in 0 until data.clipData!!.itemCount) {
                data.clipData!!.getItemAt(i).uri?.let { uriSele.add(it) }
            }
        } else if (data.data != null) {
            data.data?.let { uriSele.add(it) }
        }
        if (uriSele.isEmpty()) return
        uriSele.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { Log.w(TAG, "nu pot persista accesul: $e") }
        }
        if (requestCode == REQUEST_VIDEOS) {
            videouri.clear(); videouri.addAll(uriSele)
            salveazaLista()
            incarcaLista(videouri)
        } else {
            poze.clear(); poze.addAll(uriSele)
            salveazaPoze()
            incarcaPoze(poze)
        }
    }

    private fun salveazaLista() {
        val joined = videouri.joinToString("\n") { it.toString() }
        prefs.edit().putString(KEY_URIS, joined).apply()
    }

    private fun salveazaPoze() {
        val joined = poze.joinToString("\n") { it.toString() }
        prefs.edit().putString(KEY_PHOTO_URIS, joined).apply()
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

    private fun restaurarePoze() {
        val raw = prefs.getString(KEY_PHOTO_URIS, null) ?: return
        val uriStrings = raw.split("\n").filter { it.isNotBlank() }
        if (uriStrings.isEmpty()) return
        poze.clear()
        uriStrings.forEach { s ->
            runCatching { Uri.parse(s) }.getOrNull()?.let { poze.add(it) }
        }
        if (poze.isNotEmpty()) incarcaPoze(poze)
    }

    /** încarcă galeria de poze (swipe vertical separat de videoclipuri) */
    private fun incarcaPoze(lista: List<Uri>) {
        if (lista.isEmpty()) {
            Toast.makeText(this, "Nu s-a selectat nicio poză", Toast.LENGTH_SHORT).show()
            return
        }
        val nouAdapter = ImagePagerAdapter(this, lista)
        photoAdapter = nouAdapter
        imagePager.adapter = nouAdapter
        imagePager.setCurrentItem(0, false)
        setMod("photo")
        tvStatus.text = "🖼️ ${lista.size} poze"
    }

    private fun incarcaLista(lista: List<Uri>) {
        if (lista.isEmpty()) {
            Toast.makeText(this, "Nu s-a selectat niciun video", Toast.LENGTH_SHORT).show()
            return
        }
        // ecranul rămâne treaz cât timp există videoclipuri de redat
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val nouAdapter = VideoPagerAdapter(
            context = this,
            items = lista,
            initialVolume = volumCurent,
            onBrightnessChange = { aplicaLumina(it) },
            onVolumeChange = {}
        )
        nouAdapter.currentBrightness = luminozitateCurenta
        nouAdapter.currentVolume = volumCurent
        // aplică rezoluția salvată (Auto / 720p / 1080p)
        val (rw, rh) = rezolutieW(rezolutieCurenta)
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
        setMod("video") // afișează pagerul de videoclipuri
    }

    private fun aplicaLumina(valoare: Float) {
        Log.d("BRIGHT", "aplicaLumina value=${"%.2f".format(valoare)}")
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
        adapter?.pauseAllPlayers() // oprește audio când app merge în fundal
        // ecranul nu mai trebuie să rămână treaz forțat când app iese din prim-plan
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        salveazaSetarileCurente()
    }

    override fun onStop() {
        super.onStop()
        adapter?.pauseAllPlayers() // garanție suplimentară (ex: Home button)
    }

    override fun onResume() {
        super.onResume()
        // ecranul rămâne treaz cât timp avem videoclipuri (previne black screen la vizionare)
        if (adapter != null) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Luminozitate: NU restaura automat o valoare prea mică (bug sistem).
        // Reaplicăm doar dacă valoarea e într-un interval rezonabil (0.3..1.0).
        if (luminozitateCurenta in 0.3f..1.0f) {
            aplicaLumina(luminozitateCurenta)
        }
        adapter?.resumeActivePlayer() // la întoarcere doar videoclipul activ pornește
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    // înălțime salvată -> (width, height) maxim pentru decodare; 0=Auto
    private fun rezolutieW(h: Int): Pair<Int, Int> = when (h) {
        720 -> Pair(1280, 720)
        1080 -> Pair(1920, 1080)
        else -> Pair(Int.MAX_VALUE, Int.MAX_VALUE) // Auto => fără limită
    }
    private fun deschideSetari() {
        val sheet = SettingsBottomSheetDialogFragment()
        sheet.setInitial(luminozitateCurenta, volumCurent, rezolutieCurenta)
        sheet.show(supportFragmentManager, "settings_sheet")
    }

    // ===== SettingsBottomSheetDialogFragment.Listener =====
    // aplicare live, direct pe sistem/player (fără buton "Aplică")
    override fun onBrightnessChange(brightness: Float) {
        luminozitateCurenta = brightness.coerceIn(0.15f, 1f)
        aplicaLumina(luminozitateCurenta)      // native de sistem (pe fereastră)
        adapter?.setBrightness(luminozitateCurenta)
        salveazaSetarileCurente()
    }

    override fun onVolumeChange(volume: Float) {
        volumCurent = volume.coerceIn(0f, 1f)
        adapter?.setVolume(volumCurent)        // aplicat direct pe playerul activ
        salveazaSetarileCurente()
    }

    override fun onResolutieChange(resolutionH: Int) {
        rezolutieCurenta = resolutionH
        val (w, h) = rezolutieW(resolutionH)
        adapter?.setResolutie(w, h)
        salveazaSetarileCurente()
    }

    override fun onClearHistory() {
        // șterge doar istoricul de vizionare, NU fișierele locale
        MemoryManager.getInstance(this).stergeTotIstoricul()
        Toast.makeText(this, "Istoric vizionare șters", Toast.LENGTH_SHORT).show()
    }

    override fun onChooseVideos() {
        alegeVideoclipuri()
    }
    override fun onChoosePhotos() {
        alegePoze()
    }

    override fun onReset() {
        // Reset la valori implicite: luminozitate 100%, volum 100%, rezoluție Auto
        luminozitateCurenta = 1f
        volumCurent = 1f
        rezolutieCurenta = 0
        aplicaLumina(1f)
        adapter?.setBrightness(1f)
        adapter?.setVolume(1f)
        val (w, h) = rezolutieW(0)
        adapter?.setResolutie(w, h)
        salveazaSetarileCurente()
    }
}