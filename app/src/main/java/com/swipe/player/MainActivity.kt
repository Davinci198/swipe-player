package com.swipe.player

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.app.PictureInPictureParams
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
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
    private val KEY_PHOTO_FAV = "fav_photo"
    private val KEY_LAST_MODE = "last_mode"
    private val KEY_LAST_POS = "last_pos"
    private val favoritesPoze: MutableSet<String> = mutableSetOf()
    private var dragonBonesBridge: Any? = null

    private lateinit var tvStatus: TextView
    private lateinit var viewPager: ViewPager2
    private lateinit var imagePager: ViewPager2
    private lateinit var modeVideoBtn: TextView
    private lateinit var modePhotoBtn: TextView
    private lateinit var photoControlsBar: View
    private lateinit var photoBottomPanel: View
    private lateinit var photoThumbStrip: androidx.recyclerview.widget.RecyclerView
    private lateinit var photoRenameBtn: android.widget.ImageButton
    private lateinit var photoDeleteBtn: android.widget.ImageButton
    private lateinit var photoFavBtn: android.widget.ImageButton
    private lateinit var photoBrightnessSeek: android.widget.SeekBar
    private lateinit var photoVolumeSeek: android.widget.SeekBar
    private var thumbAdapter: ThumbnailAdapter? = null
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ascundeFotoCtrls = Runnable { ascundeControaleFoto() }
    private lateinit var prefs: SharedPreferences
    private var volumCurent: Float = 1f
    private var luminozitateCurenta: Float = 1f
    private var rezolutieCurenta: Int = 0 // 0=Auto, 720, 1080, 1440 (2K), 2160 (4K)
    private var seekStepCurent: Int = 10 // secunde de derulare per swipe/buton (2..30)
    private var backgroundPlayCurent: Boolean = false // redare în fundal (oprestea sunetului la blocare/iesire)
    private var autoOrderCurent: Boolean = true // autoplay continuu către următorul videoclip
    // --- Gesture controls (brightness / volume / seek) ---
    private enum class GestureMode { NONE, BRIGHTNESS, VOLUME, SEEK }
    private var gestureMode: GestureMode = GestureMode.NONE
    private var gestureStartX: Float = 0f
    private var gestureStartY: Float = 0f
    private var gestureStartBrightness: Float = 1f
    private var gestureStartVolume: Float = 1f
    private var gestureStartPositionMs: Long = 0L
    private var lastHapticBucket: Int = -1
    private lateinit var rootContainer: View
    private var brightnessOverlay: View? = null
    private var volumeOverlay: View? = null
    private var seekOverlay: View? = null
    private var brightnessProgress: ProgressBar? = null
    private var volumeProgress: ProgressBar? = null
    private var seekText: TextView? = null
    // Vizibilitatea butoanelor de control și a listelor de redare (controlate din Setări)
    private var ctrlVideoVizibil: Boolean = true  // video: play/pause + ⏪/⏩
    private var ctrlPhotoVizibil: Boolean = true  // poze: lumină/volum + butoanele
    private var playlistVizibil: Boolean = true   // liste de redare (miniaturi)

    // Marchează că playerul rulează în momentul în care începe un apel telefonic,
    // ca să-l reluăm automat după încheierea apelului.
    private var eraRedareLaApel = false

    // Pauză automată la apel telefonic (RINGING/OFFHOOK) + reluare la IDLE.
    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            try {
                val state = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)
                when (state) {
                    android.telephony.TelephonyManager.EXTRA_STATE_RINGING,
                    android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        if (modCurent == "video") {
                            eraRedareLaApel =
                                adapter?.isAnyPlayerPlaying() ?: false
                            adapter?.pauseAllPlayers()
                        }
                    }
                    android.telephony.TelephonyManager.EXTRA_STATE_IDLE -> {
                        if (eraRedareLaApel && modCurent == "video") {
                            eraRedareLaApel = false
                            adapter?.resumeActivePlayer()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Eroare receptor stare telefon", e)
            }
        }
    }

    // Receiver pentru acțiunile din notificarea de redare în fundal (play/pause/stop)
    private val playbackControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                PlaybackService.ACTION_PLAY ->
                    if (modCurent == "video") adapter?.resumeActivePlayer()
                PlaybackService.ACTION_PAUSE -> adapter?.pauseAllPlayers()
                PlaybackService.ACTION_STOP -> {
                    adapter?.pauseAllPlayers()
                    if (backgroundPlayCurent) {
                        backgroundPlayCurent = false
                        salveazaSetarileCurente()
                    }
                    PlaybackService.stopPlaybackService(this@MainActivity)
                }
            }
        }
    }
    private var adapter: VideoPagerAdapter? = null
    private var photoAdapter: ImagePagerAdapter? = null
    private var videouri: MutableList<Uri> = mutableListOf()
    private var poze: MutableList<Uri> = mutableListOf()

    // modul curent: "video" sau "photo"
    private var modCurent: String = "video"

    // true cât timp e deschis picker-ul de fișiere (galerie) → ca onUserLeaveHint să NU intre în PiP atunci
    private var pickerDeschis = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        dragonBonesBridge = null // disabled - stub safe

        // Ascultăm acțiunile din notificarea de redare în fundal (play/pause/stop)
        val ff = IntentFilter().apply {
            addAction(PlaybackService.ACTION_PLAY)
            addAction(PlaybackService.ACTION_PAUSE)
            addAction(PlaybackService.ACTION_STOP)
        }
        registerReceiver(playbackControlReceiver, ff, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Pauză automată la apel telefonic (cu permisiunile READ_PHONE_STATE opționale)
        try {
            val ffPhone = IntentFilter(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            registerReceiver(phoneStateReceiver, ffPhone)
        } catch (e: Exception) {
            Log.w(TAG, "Nu pot înregistra receiver-ul de stare telefonică", e)
        }

        tvStatus = findViewById(R.id.tvStatus)
        viewPager = findViewById(R.id.viewPager)
        imagePager = findViewById(R.id.imagePager)
        modeVideoBtn = findViewById(R.id.modeVideoBtn)
        modePhotoBtn = findViewById(R.id.modePhotoBtn)
        photoControlsBar = findViewById(R.id.photoControlsBar)
        photoBottomPanel = findViewById(R.id.photoBottomPanel)
        photoThumbStrip = findViewById(R.id.photoThumbStrip)
        photoRenameBtn = findViewById(R.id.photoRenameBtn)
        photoDeleteBtn = findViewById(R.id.photoDeleteBtn)
        photoFavBtn = findViewById(R.id.photoFavBtn)
        photoBrightnessSeek = findViewById(R.id.photoBrightnessSeek)
        photoVolumeSeek = findViewById(R.id.photoVolumeSeek)
        // Gesture overlays + container
        rootContainer = findViewById(R.id.rootContainer)
        brightnessOverlay = findViewById(R.id.brightnessOverlay)
        volumeOverlay = findViewById(R.id.volumeOverlay)
        seekOverlay = findViewById(R.id.seekOverlay)
        brightnessProgress = findViewById(R.id.brightnessProgress)
        volumeProgress = findViewById(R.id.volumeProgress)
        seekText = findViewById(R.id.seekText)
        configureazaGestureControls()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // miniaturi (navigare rapidă prin poze)
        photoThumbStrip.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            this, androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false)

        // creionul redenumește POZA AFIȘATĂ în prezent (fără să se mai suprapună cu setările)
        photoRenameBtn.setOnClickListener {
            deschideRedenumire(imagePager.currentItem)
            afiseazaControaleFoto()
        }

        // șterge poza afișată
        photoDeleteBtn.setOnClickListener {
            afiseazaControaleFoto()
            confirmareStergere()
        }

        // comută favorit pentru poza afișată
        photoFavBtn.setOnClickListener {
            comutaFavorit(imagePager.currentItem)
            afiseazaControaleFoto()
        }

        // când schimbi poza din galerie, evidențiem miniatura corespunzătoare
        imagePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                thumbAdapter?.let { ta ->
                    photoThumbStrip.post { ta.notifyDataSetChanged() }
                    photoThumbStrip.scrollToPosition(position)
                }
                actualizeazaButonFavorit(position)
                // interacțiune => resetăm cronometrul de ascundere a controalelor
                planificaAscundere()
            }
        })

        // bara de control foto (jos, doar în modul Poze): luminozitate + volum
        photoBrightnessSeek.progress = (luminozitateCurenta * 1000).toInt()
        photoBrightnessSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onBrightnessChange(progress / 1000f)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        photoVolumeSeek.progress = (volumCurent * 1000).toInt()
        photoVolumeSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onVolumeChange(progress / 1000f)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })

        // Buton setari (colț dreapta jos - evită back-swipe din stânga sus)
        val btnSettings = findViewById<android.widget.ImageButton>(R.id.btnSettings)
        btnSettings.setOnClickListener { deschideSetari() }

        // Buton Picture-in-Picture (se afișează doar dacă există conținut de afișat)
        val btnPip = findViewById<android.widget.ImageButton>(R.id.btnPip)
        btnPip.setOnClickListener { intraInPiP() }
        btnPip.visibility = View.GONE

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
        seekStepCurent = MemoryManager.getInstance(this).incarcaSeekStep()
        backgroundPlayCurent = MemoryManager.getInstance(this).incarcaBackgroundPlay()
        autoOrderCurent = MemoryManager.getInstance(this).incarcaAutoOrder()
        ctrlVideoVizibil = MemoryManager.getInstance(this).incarcaCtrlVideoVizibil()
        ctrlPhotoVizibil = MemoryManager.getInstance(this).incarcaCtrlPhotoVizibil()
        playlistVizibil = MemoryManager.getInstance(this).incarcaPlaylistVizibil()
        incarcaFavoritPoze()
        // sincronizează barele foto (luminozitate/volum) cu valorile salvate
        photoBrightnessSeek.progress = (luminozitateCurenta * 1000).toInt()
        photoVolumeSeek.progress = (volumCurent * 1000).toInt()

        cerePermisiuniDacaNecesar()
        cerePermisiuneLuminozitate() // Motorola/Android 12+: WRITE_SETTINGS pentru swipe lumina

        // Restaurează listele salvate (videoclipuri + poze)
        restaurareLista()
        restaurarePoze()
        setMod("video")
        // reia de unde ai rămas (mod + poziție) — rezolvă „după închidere nu mai merge”
        restaureazaStareSesiune()
        // Gesture-urile (luminozitate/volum/seek) sunt gestionate per-pagină de
        // VideoPagerAdapter prin stratul touchIntercept din item_video.xml.
    }

    /** comută între modul de videoclipuri și galeria de poze (ambele cu swipe vertical) */
    private fun setMod(mod: String) {
        modCurent = mod
        // dacă ieșim din modul video, oprim redarea (să NU se mai audă videoclipul
        // în fundal, ex. când privim pozele sau alegem fișiere)
        if (mod != "video") {
            adapter?.pauseAllPlayers()
            PlaybackService.stopPlaybackService(this)
        }
        val video = mod == "video"
        viewPager.visibility = if (video && adapter != null) View.VISIBLE else View.GONE
        imagePager.visibility = if (!video && photoAdapter != null) View.VISIBLE else View.GONE
        // panoul foto (miniaturi + controale) + creionul — DOAR în modul Poze
        if (!video) {
            photoBottomPanel.visibility = View.VISIBLE
            photoRenameBtn.visibility = if (ctrlPhotoVizibil) View.VISIBLE else View.GONE
            photoDeleteBtn.visibility = if (ctrlPhotoVizibil) View.VISIBLE else View.GONE
            photoFavBtn.visibility = if (ctrlPhotoVizibil) View.VISIBLE else View.GONE
            // lista de miniaturi (playlist) respectă opțiunea din Setări
            photoThumbStrip.visibility = if (playlistVizibil) View.VISIBLE else View.GONE
            actualizeazaButonFavorit(imagePager.currentItem)
            afiseazaControaleFoto()
        } else {
            photoBottomPanel.visibility = View.GONE
            photoRenameBtn.visibility = View.GONE
            photoDeleteBtn.visibility = View.GONE
            photoFavBtn.visibility = View.GONE
            uiHandler.removeCallbacks(ascundeFotoCtrls)
        }
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
            pickerDeschis = true // previne intrarea în PiP la deschiderea galeriei
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            pickerDeschis = false
            Toast.makeText(this, "Nu am găsit un picker de fișiere", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        pickerDeschis = false // am revenit din galerie; PiP-ul automat e din nou permis
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
        // ADUNA selecția la lista existentă (nu o înlocuiește!), ca să nu dispară
        // pozele/videoclipurile adăugate anterior. Eliminăm duplicatele, păstrând ordinea.
        if (requestCode == REQUEST_VIDEOS) {
            val existente = videouri.map { it.toString() }.toSet()
            for (uri in uriSele) {
                if (uri.toString() !in existente) videouri.add(uri)
            }
            salveazaLista()
            incarcaLista(videouri)
        } else {
            val existente = poze.map { it.toString() }.toSet()
            for (uri in uriSele) {
                if (uri.toString() !in existente) poze.add(uri)
            }
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
        actualizeazaMiniaturi()
        setMod("photo")
        tvStatus.text = "🖼️ ${lista.size} poze"
        arataButonPipDacaAreLoc()
    }

    /** creionul din galeria de poze: dialog pentru REDENUMIREA pozei */
    private fun deschideRedenumire(position: Int) {
        if (position < 0 || position >= poze.size) return
        val uri = poze[position]
        val numeCurent = numePoza(uri) ?: ""
        val input = android.widget.EditText(this).apply {
            setText(numeCurent)
            selectAll()
            setSingleLine(true)
            hint = "Noul nume (fără extensie .jpg)"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Redenumește poza")
            .setView(input)
            .setPositiveButton("Salvează") { _, _ ->
                val noulNume = input.text.toString().trim()
                if (noulNume.isEmpty()) return@setPositiveButton
                val reusit = redenumestePoza(uri, noulNume)
                if (reusit != null) {
                    // actualizează lista + persistă + actualizează galeria curentă (fără a sări la prima poză)
                    poze[position] = reusit
                    salveazaPoze()
                    actualizeazaMiniaturi()
                    photoAdapter?.notifyItemChanged(position)
                    Toast.makeText(this, "Poza redenumită ✔", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Nu am putut redenumi. Poate este .jpg .png .gif .bmp .webp", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun numePoza(uri: Uri): String? {
        return try {
            val display = android.provider.DocumentsContract.getDocumentId(uri)
                ?.substringAfterLast('/')
            if (display.isNullOrBlank()) {
                queryDisplayName(uri)
            } else display
        } catch (e: Exception) { queryDisplayName(uri) }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            val c = contentResolver.query(uri, arrayOf(
                android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            c?.use {
                if (it.moveToFirst()) {
                    val name = it.getString(it.getColumnIndexOrThrow(
                        android.provider.OpenableColumns.DISPLAY_NAME))
                    name?.substringBeforeLast('.')
                } else null
            }
        } catch (e: Exception) { null }
    }

    /** rename prin SAF; reîntoarce noul Uri sau null dacă eșuează */
    private fun redenumestePoza(uri: Uri, numeNou: String): Uri? {
        return try {
            val newUri = android.provider.DocumentsContract.renameDocument(
                contentResolver, uri, nomFisier(numeNou))
            if (newUri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        newUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) { }
                newUri
            } else uri
        } catch (e: Exception) {
            Log.w(TAG, "renameDocument a eșuat", e)
            null
        }
    }

    private fun nomFisier(nume: String): String {
        return if (nume.contains('.')) nume else "$nume.jpg"
    }

    // ===== Auto-hide controale foto (creion + bară luminozitate/volum), după 3s =====
    private fun afiseazaControaleFoto() {
        // dacă utilizatorul a dezactivat butoanele de control din Setări, nu le mai afișăm
        if (!ctrlPhotoVizibil) return
        val vizibile = listOf(photoControlsBar, photoRenameBtn, photoDeleteBtn, photoFavBtn)
        for (v in vizibile) { v.alpha = 1f; v.visibility = View.VISIBLE }
        // re-aplicăm corect starea vizuală a butonului de favorit (alpha depinde de stare)
        actualizeazaButonFavorit(imagePager.currentItem)
        planificaAscundere()
    }

    private fun planificaAscundere() {
        uiHandler.removeCallbacks(ascundeFotoCtrls)
        uiHandler.postDelayed(ascundeFotoCtrls, 3000)
    }

    private fun ascundeControaleFoto() {
        if (modCurent != "photo") return
        val ascunde = listOf(photoControlsBar, photoRenameBtn, photoDeleteBtn, photoFavBtn)
        for (v in ascunde) {
            v.animate().alpha(0f).setDuration(250)
                .withEndAction { if (modCurent == "photo") v.visibility = View.GONE }
        }
    }

    private fun actualizeazaMiniaturi() {
        val curent = imagePager.currentItem.coerceIn(0, (poze.size - 1).coerceAtLeast(0))
        thumbAdapter = ThumbnailAdapter(this, poze, curent) { pos ->
            imagePager.setCurrentItem(pos, false)
            afiseazaControaleFoto()
        }
        photoThumbStrip.adapter = thumbAdapter
    }

    // ===== Ștergere poză =====
    private fun confirmareStergere() {
        val pos = imagePager.currentItem
        if (poze.isEmpty() || pos < 0 || pos >= poze.size) return
        val nume = numePoza(poze[pos]) ?: "poza"
        android.app.AlertDialog.Builder(this)
            .setTitle("Șterge poza?")
            .setMessage("„$nume” va fi ștearsă definitiv.")
            .setPositiveButton("Șterge") { _, _ -> stergePoza(pos) }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun stergePoza(position: Int) {
        if (position < 0 || position >= poze.size) return

        // SCOATE din lista aplicației, dar NU șterge fișierul fizic de pe dispozitiv
        // (respectăm cerința: fișierele rămân întotdeauna intacte pe telefon).
        val scos = poze[position]
        poze.removeAt(position)
        favoritesPoze.remove(scos.toString())
        salveazaPoze()
        salveazaFavoritPoze()

        if (poze.isEmpty()) {
            imagePager.adapter = null
            photoAdapter = null
            actualizeazaMiniaturi()
            setMod("video")
            tvStatus.text = "Nu mai sunt poze. Adaugă din ⚙️"
        } else {
            val nouPos = position.coerceIn(0, poze.size - 1)
            incarcaPoze(poze)
            imagePager.post { imagePager.setCurrentItem(nouPos, false) }
            actualizeazaMiniaturi()
        }
        Toast.makeText(this, "Poza scoasă din aplicație (fișierul rămâne pe telefon)", Toast.LENGTH_SHORT).show()
    }

    // ===== Favorit poză =====
    private fun comutaFavorit(position: Int) {
        if (poze.isEmpty() || position < 0 || position >= poze.size) return
        val key = poze[position].toString()
        if (favoritesPoze.contains(key)) {
            favoritesPoze.remove(key)
            Toast.makeText(this, "Scos de la favorite ⭐", Toast.LENGTH_SHORT).show()
        } else {
            favoritesPoze.add(key)
            Toast.makeText(this, "Adăugat la favorite ⭐", Toast.LENGTH_SHORT).show()
        }
        salveazaFavoritPoze()
        actualizeazaButonFavorit(position)
    }

    private fun actualizeazaButonFavorit(position: Int) {
        if (!::photoFavBtn.isInitialized) return
        if (poze.isEmpty()) return
        val key = poze.getOrNull(position)?.toString() ?: ""
        val esteFavorit = favoritesPoze.contains(key)
        if (esteFavorit) {
            photoFavBtn.setImageResource(R.drawable.ic_favorite) // galben/plin
            photoFavBtn.alpha = 1f
        } else {
            photoFavBtn.setImageResource(R.drawable.ic_favorite)
            photoFavBtn.alpha = 0.45f
        }
    }

    private fun incarcaFavoritPoze() {
        val raw = prefs.getString(KEY_PHOTO_FAV, null) ?: return
        favoritesPoze.clear()
        favoritesPoze.addAll(raw.split("\n").filter { it.isNotBlank() })
    }

    private fun salveazaFavoritPoze() {
        prefs.edit().putString(KEY_PHOTO_FAV, favoritesPoze.joinToString("\n")).apply()
    }

    // ===== Salvare/reîncărcare a stării sesiunii (mod + poziție) =====
    private fun salveazaStareSesiune() {
        val pos = when {
            modCurent == "photo" && ::imagePager.isInitialized -> imagePager.currentItem
            modCurent == "video" && ::viewPager.isInitialized -> viewPager.currentItem
            else -> 0
        }
        prefs.edit()
            .putString(KEY_LAST_MODE, modCurent)
            .putInt(KEY_LAST_POS, pos.coerceAtLeast(0))
            .apply()
    }

    private fun restaureazaStareSesiune() {
        val mode = prefs.getString(KEY_LAST_MODE, null) ?: return
        val pos = prefs.getInt(KEY_LAST_POS, 0)
        if (mode == "video" && adapter != null) {
            val p = pos.coerceIn(0, (videouri.size - 1).coerceAtLeast(0))
            setMod("video")
            viewPager.post {
                viewPager.setCurrentItem(p, false)
                adapter?.setActivePage(p) // pornește playerul de pe pagina restabilită
            }
        } else if (mode == "photo" && poze.isNotEmpty() && photoAdapter != null) {
            val p = pos.coerceIn(0, poze.size - 1)
            setMod("photo")
            imagePager.post { imagePager.setCurrentItem(p, false) }
        }
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
        nouAdapter.seekStepSec = seekStepCurent // secunde de derulare per swipe/buton
        nouAdapter.autoOrder = autoOrderCurent // autoplay continuu
        nouAdapter.setControlsVisible(ctrlVideoVizibil) // vizibilitatea butoanelor de control (Video)
        // la finalul videoclipului : trecem la următorul (doar dacă autoplay e activ, vezi adapter)
        nouAdapter.onItemEnded = {
            // Gard anti-crash: callback-ul poate veni când Activity e în curs de distrugere
            // (redare în fundal / PiP). Dacă adapter-ul nu mai e cel activ sau viewPager-ul
            // nu mai e atașat, NU mai navigăm la următorul videoclip.
            val trebuieNavigat = adapter === nouAdapter &&
                viewPager.isAttachedToWindow &&
                modCurent == "video"
            if (trebuieNavigat) {
                try {
                    val curent = viewPager.currentItem
                    if (curent < videouri.size - 1) {
                        viewPager.post { if (adapter === nouAdapter) viewPager.setCurrentItem(curent + 1, true) }
                    }
                } catch (e: Exception) { /* ignoră - nu crăpăm în fundal */ }
            }
        }
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
        arataButonPipDacaAreLoc()
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

    // ==================== GESTURE CONTROLS ====================
    /**
     * Configurează OnTouchListener pe rootContainer:
     *  - 33% stânga   → drag vertical = luminozitate
     *  - 33% dreapta  → drag vertical = volum
     *  - 34% centru   → drag orizontal = seek
     * Haptic feedback la ACTION_DOWN și la fiecare prag de 10%.
     */
    private fun configureazaGestureControls() {
        rootContainer.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.x
                    gestureStartY = event.y
                    gestureStartBrightness = luminozitateCurenta
                    gestureStartVolume = volumCurent
                    gestureStartPositionMs = try {
                        adapter?.playerActiv?.currentPosition ?: 0L
                    } catch (e: Exception) { 0L }
                    lastHapticBucket = -1
                    val w = rootContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    gestureMode = when {
                        event.x < w * 0.33f -> GestureMode.BRIGHTNESS
                        event.x > w * 0.67f -> GestureMode.VOLUME
                        else -> GestureMode.SEEK
                    }
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = gestureStartY - event.y   // pozitiv = swipe în sus
                    val dx = event.x - gestureStartX   // pozitiv = swipe la dreapta
                    when (gestureMode) {
                        GestureMode.BRIGHTNESS -> {
                            val noua = (gestureStartBrightness + dy * 0.002f).coerceIn(0.15f, 1f)
                            onBrightnessChange(noua)
                            val procent = (noua * 100).toInt()
                            brightnessProgress?.progress = procent
                            brightnessOverlay?.visibility = View.VISIBLE
                            volumeOverlay?.visibility = View.GONE
                            seekOverlay?.visibility = View.GONE
                            hapticLaPrag(procent, v)
                        }
                        GestureMode.VOLUME -> {
                            val noua = (gestureStartVolume + dy * 0.002f).coerceIn(0f, 1f)
                            onVolumeChange(noua)
                            val procent = (noua * 100).toInt()
                            volumeProgress?.progress = procent
                            volumeOverlay?.visibility = View.VISIBLE
                            brightnessOverlay?.visibility = View.GONE
                            seekOverlay?.visibility = View.GONE
                            hapticLaPrag(procent, v)
                        }
                        GestureMode.SEEK -> {
                            val deltaMs = (dx * 0.3 * 1000L).toLong()  // 0.3 px→factor, *1000 ms
                            val tinta = gestureStartPositionMs + deltaMs
                            val dur = try { adapter?.playerActiv?.duration ?: 0L } catch (e: Exception) { 0L }
                            val clamped = if (dur > 0) tinta.coerceIn(0L, dur) else tinta.coerceAtLeast(0L)
                            try { adapter?.playerActiv?.seekTo(clamped) } catch (e: Exception) { Log.w(TAG, "seek gesture", e) }
                            val secDelta = deltaMs / 1000
                            seekText?.text = if (secDelta >= 0) "+${secDelta}s" else "${secDelta}s"
                            seekOverlay?.visibility = View.VISIBLE
                            brightnessOverlay?.visibility = View.GONE
                            volumeOverlay?.visibility = View.GONE
                            val bucket = (kotlin.math.abs(secDelta) / 10).toInt()
                            if (bucket != lastHapticBucket) {
                                lastHapticBucket = bucket
                                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        GestureMode.NONE -> {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    brightnessOverlay?.visibility = View.GONE
                    volumeOverlay?.visibility = View.GONE
                    seekOverlay?.visibility = View.GONE
                    gestureMode = GestureMode.NONE
                    lastHapticBucket = -1
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    /** Haptic feedback de fiecare dată când procentul trece un multiplu de 10. */
    private fun hapticLaPrag(procent: Int, v: View) {
        val bucket = procent / 10
        if (bucket != lastHapticBucket) {
            lastHapticBucket = bucket
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    // ================== SFÂRȘIT GESTURE CONTROLS ==================

override fun onPause() {
        super.onPause()
        // Dacă suntem în Picture-in-Picture, NU oprim redarea și NU pornim serviciul:
        // activitatea e încă vizibilă în fereastra mică, deci playerul continuă normal.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            return
        }
        // Redare în fundal: dacă e activă și suntem în mod video, lăsăm sunetul să continue
        // (nu oprim playerul + pornim un foreground service care ține procesul prioritar,
        //  ca sistemul să NU taie sunetul din cauza Doze/WakeLock).
        val continuareFundal = backgroundPlayCurent && modCurent == "video"
        if (!continuareFundal) {
            adapter?.pauseAllPlayers()
        } else {
            PlaybackService.startPlaybackService(this, numeVideoclipActiv())
        }
        // ecranul nu mai trebuie să rămână treaz forțat când app iese din prim-plan
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        salveazaSetarileCurente()
        salveazaStareSesiune() // nu pierdem poziția/modul la închidere
    }

    override fun onStop() {
        super.onStop()
        // Dacă suntem în Picture-in-Picture, NU oprim/ascundem redarea — continuă în fereastra mică.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return
        // Redare în fundal: la Home/minimize păstrăm sunetul dacă opțiunea e activă (mod video)
        val continuareFundal = backgroundPlayCurent && modCurent == "video"
        if (!continuareFundal) {
            adapter?.pauseAllPlayers() // garanție suplimentară (ex: Home button)
        } else {
            PlaybackService.startPlaybackService(this, numeVideoclipActiv())
        }
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
        // Reia videoclipul activ DOAR dacă suntem în modul video (altfel, după ce alegi
        // poze / ieși din video, videoclipul NU trebuie să pornească singur în fundal)
        if (modCurent == "video") adapter?.resumeActivePlayer()
        // La revenirea în prim-plan nu mai avem nevoie de foreground service-ul de fundal.
        PlaybackService.stopPlaybackService(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        salveazaSetarileCurente()
        // altfel rămâne un service orfan + notificare după închiderea aplicației
        PlaybackService.stopPlaybackService(this)
        // eliberăm toți ExoPlayerii la închiderea completă (evită crash/leak de playere
        // legate de o Activitate distrusă); imaginile nu țin playere, doar decodează.
        try { adapter?.elibereazaTot() } catch (e: Exception) { }
        adapter = null
        (dragonBonesBridge as? com.swipe.player.DragonBonesBridge)?.release()
        try { unregisterReceiver(playbackControlReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(phoneStateReceiver) } catch (e: Exception) {}
        // restaurează luminozitatea sistemului (să nu rămână blocată pe valoarea setată)
        try {
            val lp = window.attributes ?: return
            lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        } catch (e: Exception) { /* ignoră */ }
    }

    // ===== Picture-in-Picture (PiP) — fereastră plutitoare pentru poze + videoclipuri =====
    /** Afișează/ascunde butonul PiP în funcție de faptul că există conținut de arătat. */
    private fun arataButonPipDacaAreLoc() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val existaContinut = (modCurent == "video" && videouri.isNotEmpty()) ||
                (modCurent == "photo" && poze.isNotEmpty())
            findViewById<View>(R.id.btnPip).visibility =
                if (existaContinut) View.VISIBLE else View.GONE
        } catch (e: Exception) { /* ignoră */ }
    }

    private fun intraInPiP() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Nu intrăm în PiP fără conținut
        val existaContinut = (modCurent == "video" && videouri.isNotEmpty()) ||
            (modCurent == "photo" && poze.isNotEmpty())
        if (!existaContinut) return
        try {
            val ratio = Rational(16, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(params)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nu pot intra în PiP", e)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Nu intra în PiP dacă s-a deschis galeria/picker-ul de fișiere.
        if (pickerDeschis) return
        // Ieșire automată din aplicație => trecem în PiP dacă redăm ceva
        // (în loc să oprim imaginea, continuăm redarea în fereastra plutitoare)
        if (modCurent == "video" && adapter?.isAnyPlayerPlaying() != false) {
            intraInPiP()
        } else if (modCurent == "photo" && poze.isNotEmpty()) {
            intraInPiP()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // ascundem UI-ul (butoane, header, panouri) când suntem în PiP — rămâne doar conținutul
            findViewById<View>(R.id.btnSettings).visibility = View.GONE
            findViewById<View>(R.id.btnPip).visibility = View.GONE
            findViewById<View>(R.id.headerOverlay).visibility = View.GONE
            findViewById<View>(R.id.photoBottomPanel).visibility = View.GONE
            findViewById<View>(R.id.photoRenameBtn).visibility = View.GONE
            findViewById<View>(R.id.photoDeleteBtn).visibility = View.GONE
            findViewById<View>(R.id.photoFavBtn).visibility = View.GONE
        } else {
            // revenire în prim-plan: readucem UI-ul în funcție de mod
            findViewById<View>(R.id.btnSettings).visibility = View.VISIBLE
            findViewById<View>(R.id.btnPip).visibility =
                if (modCurent == "video" && videouri.isNotEmpty() || modCurent == "photo" && poze.isNotEmpty())
                    View.VISIBLE else View.GONE
            findViewById<View>(R.id.headerOverlay).visibility = View.VISIBLE
            if (modCurent == "photo") {
                findViewById<View>(R.id.photoBottomPanel).visibility = View.VISIBLE
            }
        }
    }
    private fun salveazaSetarileCurente() {
        try {
            MemoryManager.getInstance(this).salveazaSetari(volumCurent, luminozitateCurenta)
            MemoryManager.getInstance(this).salveazaRezolutie(rezolutieCurenta)
            MemoryManager.getInstance(this).salveazaSeekStep(seekStepCurent)
            MemoryManager.getInstance(this).salveazaBackgroundPlay(backgroundPlayCurent)
            MemoryManager.getInstance(this).salveazaAutoOrder(autoOrderCurent)
            MemoryManager.getInstance(this).salveazaCtrlVideoVizibil(ctrlVideoVizibil)
            MemoryManager.getInstance(this).salveazaCtrlPhotoVizibil(ctrlPhotoVizibil)
            MemoryManager.getInstance(this).salveazaPlaylistVizibil(playlistVizibil)
        } catch (e: Exception) {
            Log.e(TAG, "Eroare salvare setări", e)
        }
    }

    /** Numele videoclipului activ (afișat în notificarea de redare în fundal). */
    private fun numeVideoclipActiv(): String {
        return try {
            val poz = viewPager.currentItem
            if (poz in videouri.indices) {
                videouri[poz].lastPathSegment?.substringAfterLast("/") ?: "Video"
            } else "Video"
        } catch (e: Exception) { "Video" }
    }

    // înălțime salvată -> (width, height) maxim pentru decodare; 0=Auto
    private fun rezolutieW(h: Int): Pair<Int, Int> = when (h) {
        720 -> Pair(1280, 720)
        1080 -> Pair(1920, 1080)
        else -> Pair(Int.MAX_VALUE, Int.MAX_VALUE) // Auto => fără limită
    }
    private fun deschideSetari() {
        val sheet = SettingsBottomSheetDialogFragment()
        sheet.setInitial(
            brightness = luminozitateCurenta,
            volume = volumCurent,
            resH = rezolutieCurenta,
            seekStep = seekStepCurent,
            backgroundPlay = backgroundPlayCurent,
            autoOrder = autoOrderCurent,
            ctrlVideo = ctrlVideoVizibil,
            ctrlPhoto = ctrlPhotoVizibil,
            playlist = playlistVizibil
        )
        sheet.show(supportFragmentManager, "settings_sheet")
    }

    // ===== SettingsBottomSheetDialogFragment.Listener =====
    // aplicare live, direct pe sistem/player (fără buton "Aplică")
    override fun onBrightnessChange(brightness: Float) {
        luminozitateCurenta = brightness.coerceIn(0.15f, 1f)
        aplicaLumina(luminozitateCurenta)      // native de sistem (pe fereastră)
        adapter?.setBrightness(luminozitateCurenta)
        if (::photoBrightnessSeek.isInitialized) {
            photoBrightnessSeek.progress = (luminozitateCurenta * 1000).toInt()
        }
        salveazaSetarileCurente()
    }

    override fun onVolumeChange(volume: Float) {
        volumCurent = volume.coerceIn(0f, 1f)
        adapter?.setVolume(volumCurent)        // aplicat direct pe playerul activ (video)
        // și volumul media de sistem, ca să funcționeze și în modul Poze (fără player video)
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val target = (volumCurent * max).toInt().coerceIn(0, max)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Nu pot seta volumul media", e)
        }
        photoVolumeSeek.progress = (volumCurent * 1000).toInt()
        salveazaSetarileCurente()
    }

    override fun onResolutieChange(resolutionH: Int) {
        rezolutieCurenta = resolutionH
        val (w, h) = rezolutieW(resolutionH)
        adapter?.setResolutie(w, h)
        salveazaSetarileCurente()
    }

    override fun onSeekStepChange(stepSec: Int) {
        seekStepCurent = stepSec.coerceIn(2, 30)
        adapter?.seekStepSec = seekStepCurent
        salveazaSetarileCurente()
    }

    override fun onBackgroundPlayChange(activat: Boolean) {
        backgroundPlayCurent = activat
        salveazaSetarileCurente()
        // dacă dezactivăm din mers și sună ceva în fundal, îl oprim imediat
        if (!activat) adapter?.pauseAllPlayers()
    }

    override fun onAutoOrderChange(activat: Boolean) {
        autoOrderCurent = activat
        adapter?.autoOrder = activat
        salveazaSetarileCurente()
    }

    override fun onCtrlVideoChange(activat: Boolean) {
        ctrlVideoVizibil = activat
        adapter?.setControlsVisible(activat)
        // dacă ascundem butoanele, ascundem imediat controllerul media + ⏪/⏩
        if (!activat) adapter?.hideAllControllers()
        salveazaSetarileCurente()
    }

    override fun onCtrlPhotoChange(activat: Boolean) {
        ctrlPhotoVizibil = activat
        if (!activat) {
            // ascundem complet controalele foto (luminozitate/volum + butoanele rename/delete/fav)
            ascundeControaleFoto()
            photoControlsBar.visibility = View.GONE
            photoRenameBtn.visibility = View.GONE
            photoDeleteBtn.visibility = View.GONE
            photoFavBtn.visibility = View.GONE
        }
        salveazaSetarileCurente()
    }

    override fun onPlaylistChange(activat: Boolean) {
        playlistVizibil = activat
        photoThumbStrip.visibility = if (activat) View.VISIBLE else View.GONE
        salveazaSetarileCurente()
    }

    override fun onClearHistory() {
        // Șterge biblioteca + istoricul (videoclipuri & poze din aplicație, progress, favorite).
        // Nu șterge fișierele de pe telefon, doar le scoate din lista aplicației.
        android.app.AlertDialog.Builder(this)
            .setTitle("Șterge biblioteca?")
            .setMessage(
                "Videoclipurile și pozele adăugate, progresul de vizionare, \n" +
                "favoritele și poziția curentă vor fi șterse din aplicație. \n\n" +
                "Fișierele rămân pe telefon."
            )
            .setPositiveButton("Șterge") { _, _ ->
                // 1. istoric vizionare (progress)
                MemoryManager.getInstance(this).stergeTotIstoricul()
                // 2. listele salvate din preferințe
                prefs.edit()
                    .remove(KEY_URIS)
                    .remove(KEY_PHOTO_URIS)
                    .remove(KEY_PHOTO_FAV)
                    .remove(KEY_LAST_MODE)
                    .remove(KEY_LAST_POS)
                    .apply()
                favoritesPoze.clear()
                // 3. curăță și memoria + pager-urile
                videouri.clear()
                poze.clear()
                viewPager.adapter = null
                imagePager.adapter = null
                adapter = null
                photoAdapter = null
                actualizeazaMiniaturi()
                viewPager.setCurrentItem(0, false)
                imagePager.setCurrentItem(0, false)
                tvStatus.text = "Bibliotecă golită. Alege fișiere din ⚙️"
                setMod("video")
                Toast.makeText(this, "Bibliotecă + istoric șterse", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    override fun onChooseVideos() {
        alegeVideoclipuri()
    }
    override fun onChoosePhotos() {
        alegePoze()
    }

    override fun onReset() {
        // Reset la valori implicite: luminozitate 100%, volum 100%, rezoluție Auto, seek 10s
        luminozitateCurenta = 1f
        volumCurent = 1f
        rezolutieCurenta = 0
        seekStepCurent = 10
        aplicaLumina(1f)
        adapter?.setBrightness(1f)
        adapter?.setVolume(1f)
        adapter?.seekStepSec = seekStepCurent
        val (w, h) = rezolutieW(0)
        adapter?.setResolutie(w, h)
        backgroundPlayCurent = false
        autoOrderCurent = true
        adapter?.autoOrder = autoOrderCurent
        // reset la vizibilități implicite (totul vizibil)
        ctrlVideoVizibil = true
        ctrlPhotoVizibil = true
        playlistVizibil = true
        adapter?.setControlsVisible(true)
        photoThumbStrip.visibility = View.VISIBLE
        salveazaSetarileCurente()
    }
}