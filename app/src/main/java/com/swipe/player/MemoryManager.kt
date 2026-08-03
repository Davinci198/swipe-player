package com.swipe.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🧠 MemorieLocala — sistem de memorie bazat pe SharedPreferences
 *
 * Echivalentul lui memorie-operit-v2.js, rescris în Kotlin pentru Android.
 *
 * Stochează:
 *   - Istoric vizionări (video name, timestamp, progres %)
 *   - Favorite (nume video, timestamp adăugare)
 *   - Setări persistente (volum, luminozitate)
 */
class MemoryManager private constructor(context: Context) {

    companion object {
        private const val TAG = "MemoryManager"
        private const val PREFS_NAME = "swipe_memorie"
        private const val KEY_HISTORY = "istoric"
        private const val KEY_FAVORITES = "favorite"
        private const val KEY_SETTINGS = "setari"
        private const val KEY_RESOLUTIE = "rezolutie"
        private const val KEY_SEEK_STEP = "seek_step_sec"
        private const val MAX_HISTORY = 200

        @Volatile
        private var instance: MemoryManager? = null

        fun getInstance(context: Context): MemoryManager {
            return instance ?: synchronized(this) {
                instance ?: MemoryManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // =============================================
    // ISTORIC
    // =============================================

    /**
     * Salvează o intrare în istoric.
     * @param nume numele fișierului video
     * @param progres procentul 0-100
     * @param pozitieSecunde poziția curentă în secunde
     * @param durataSecunde durata totală în secunde
     */
    fun salveazaInIstoric(
        nume: String,
        progres: Int,
        pozitieSecunde: Int,
        durataSecunde: Int
    ) {
        try {
            val arr = getJsonArray(KEY_HISTORY)
            val data = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

            // UPSERT: dacă videoul există deja, actualizăm poziția (nu append negrăbit)
            var index = -1
            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).optString("nume", "") == nume) { index = i; break }
            }
            val entry = JSONObject().apply {
                put("nume", nume)
                put("data", data)
                put("progres", progres.coerceIn(0, 100))
                put("pozitie", pozitieSecunde)
                put("durata", durataSecunde)
            }
            if (index >= 0) arr.put(index, entry) else arr.put(entry)

            // Limitare strictă la MAX_HISTORY (împiedică creșterea infinită)
            while (arr.length() > MAX_HISTORY) {
                arr.remove(0)
            }

            putJsonArray(KEY_HISTORY, arr)
            Log.d(TAG, "Salvat istoric(${if (index >= 0) "update" else "nou"}): $nume ($progres%)")
        } catch (e: Exception) {
            Log.e(TAG, "Eroare salvare istoric", e)
        }
    }

    /**
     * Returnează istoricul ca listă de map-uri.
     * @param query opțional, filtrează după nume (case-insensitive)
     */
    fun getIstoric(query: String? = null): List<Map<String, Any?>> {
        return try {
            val arr = getJsonArray(KEY_HISTORY)
            val results = mutableListOf<Map<String, Any?>>()
            val lowerQuery = query?.lowercase(Locale.ROOT)

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val nume = obj.optString("nume", "")

                if (lowerQuery != null && !nume.lowercase(Locale.ROOT).contains(lowerQuery)) {
                    continue
                }

                results.add(mapOf(
                    "nume" to nume,
                    "data" to obj.optString("data", ""),
                    "progres" to obj.optInt("progres", 0),
                    "pozitie" to obj.optInt("pozitie", 0),
                    "durata" to obj.optInt("durata", 0)
                ))
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "Eroare citire istoric", e)
            emptyList()
        }
    }

    /**
     * Șterge o intrare din istoric după index.
     */
    fun stergeDinIstoric(index: Int): Boolean {
        return try {
            val arr = getJsonArray(KEY_HISTORY)
            if (index in 0 until arr.length()) {
                arr.remove(index)
                putJsonArray(KEY_HISTORY, arr)
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Eroare ștergere istoric", e)
            false
        }
    }

    /**
     * Șterge tot istoricul.
     */
    fun stergeTotIstoricul() {
        putJsonArray(KEY_HISTORY, JSONArray())
        Log.d(TAG, "Istoric șters complet")
    }

    // =============================================
    // FAVORITE
    // =============================================

    /**
     * Adaugă sau elimină un video din favorite. Returnează true dacă a devenit favorit.
     */
    fun toggleFavorite(nume: String, durataSecunde: Int = 0): Boolean {
        return try {
            val arr = getJsonArray(KEY_FAVORITES)
            val existingIndex = findFavoriteIndex(arr, nume)

            if (existingIndex >= 0) {
                arr.remove(existingIndex)
                putJsonArray(KEY_FAVORITES, arr)
                Log.d(TAG, "Eliminat din favorite: $nume")
                false
            } else {
                val entry = JSONObject().apply {
                    put("nume", nume)
                    put("data", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
                    put("durata", durataSecunde)
                }
                arr.put(entry)
                putJsonArray(KEY_FAVORITES, arr)
                Log.d(TAG, "Adăugat la favorite: $nume")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eroare toggle favorite", e)
            false
        }
    }

    /**
     * Verifică dacă un video este favorit.
     */
    fun esteFavorit(nume: String): Boolean {
        return try {
            val arr = getJsonArray(KEY_FAVORITES)
            findFavoriteIndex(arr, nume) >= 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returnează lista de favorite.
     */
    fun getFavorite(): List<Map<String, Any?>> {
        return try {
            val arr = getJsonArray(KEY_FAVORITES)
            val results = mutableListOf<Map<String, Any?>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                results.add(mapOf(
                    "nume" to obj.optString("nume", ""),
                    "data" to obj.optString("data", ""),
                    "durata" to obj.optInt("durata", 0)
                ))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Elimină un favorit după nume.
     */
    fun eliminaFavorit(nume: String): Boolean {
        return try {
            val arr = getJsonArray(KEY_FAVORITES)
            val idx = findFavoriteIndex(arr, nume)
            if (idx >= 0) {
                arr.remove(idx)
                putJsonArray(KEY_FAVORITES, arr)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    private fun findFavoriteIndex(arr: JSONArray, nume: String): Int {
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("nume", "") == nume) {
                return i
            }
        }
        return -1
    }

    // =============================================
    // SETĂRI PERSISTENTE
    // =============================================

    /**
     * Salvează setările (volum, luminozitate).
     */
    fun salveazaSetari(volum: Float, lumina: Float) {
        try {
            val settings = JSONObject().apply {
                put("volum", volum.toDouble())
                put("lumina", lumina.toDouble())
                put("ultimulUpdate",
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
            }
            prefs.edit().putString(KEY_SETTINGS, settings.toString()).apply()
            Log.d(TAG, "Setări salvate: volum=$volum, lumina=$lumina")
        } catch (e: Exception) {
            Log.e(TAG, "Eroare salvare setări", e)
        }
    }

    /**
     * Salvează rezoluția de redare aleasă (înălțime în pixeli: 0=Auto, 720, 1080, 1440, 2160).
     */
    fun salveazaRezolutie(inaltime: Int) {
        prefs.edit().putInt(KEY_RESOLUTIE, inaltime).apply()
    }

    /**
     * Încarcă rezoluția salvată (0 = Auto dacă nu există).
     */
    fun incarcaRezolutie(): Int {
        return prefs.getInt(KEY_RESOLUTIE, 0)
    }

    /**
     * Salvează secunde de derulare per swipe/buton (2..30).
     */
    fun salveazaSeekStep(secunde: Int) {
        prefs.edit().putInt(KEY_SEEK_STEP, secunde.coerceIn(2, 30)).apply()
    }

    /**
     * Încarcă secunde de derulare salvate (implicit 10).
     */
    fun incarcaSeekStep(): Int {
        return prefs.getInt(KEY_SEEK_STEP, 10).coerceIn(2, 30)
    }

    /**
     * Încarcă setările salvate. Returnează perechea (volum, lumina) sau null.
     */
    fun incarcaSetari(): Pair<Float, Float>? {
        return try {
            val raw = prefs.getString(KEY_SETTINGS, null) ?: return null
            val settings = JSONObject(raw)
            val volum = settings.optDouble("volum", 1.0).toFloat()
            val lumina = settings.optDouble("lumina", 1.0).toFloat()
            Pair(volum.coerceIn(0f, 1f), lumina.coerceIn(0.15f, 1f)) // screenBrightness max = 1.0
        } catch (e: Exception) {
            null
        }
    }

    // =============================================
    // STATISTICI
    // =============================================

    /**
     * Returnează statistici: număr total vizionări, videouri unice, favorite, timp total.
     */
    fun getStatistici(): Map<String, Any> {
        val istoric = getIstoric()
        val favs = getFavorite()

        val numeUnice = mutableSetOf<String>()
        var timpTotal = 0
        for (entry in istoric) {
            val nume = entry["nume"] as? String ?: ""
            if (nume.isNotBlank()) numeUnice.add(nume)
            timpTotal += (entry["durata"] as? Int) ?: 0
        }

        return mapOf(
            "totalVizionari" to istoric.size,
            "videouriUnice" to numeUnice.size,
            "totalFavorite" to favs.size,
            "timpTotalSecunde" to timpTotal
        )
    }

    // =============================================
    // UTILITARE
    // =============================================

    private fun getJsonArray(key: String): JSONArray {
        val raw = prefs.getString(key, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun putJsonArray(key: String, arr: JSONArray) {
        prefs.edit().putString(key, arr.toString()).apply()
    }

    /**
     * Formatează secunde în "2h 15m 30s"
     */
    fun formateazaDurata(secunde: Int): String {
        if (secunde <= 0) return "0s"
        val h = secunde / 3600
        val m = (secunde % 3600) / 60
        val s = secunde % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts.add("${h}h")
        if (m > 0) parts.add("${m}m")
        if (s > 0 || parts.isEmpty()) parts.add("${s}s")
        return parts.joinToString(" ")
    }

    /**
     Șterge toate datele (factory reset al memoriei).
     */
    fun stergeTot() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Toată memoria a fost ștearsă")
    }
}