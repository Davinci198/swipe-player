# Plan verificare și editare chirurgicală – Swipe Player

## 1. Obiectiv
Verificare completă a codului, identificare bug-uri critice și corectare chirurgicală păstrând comportamentul existent.

## 2. Domenii verificate
- Android Gradle / module
- MainActivity.kt (gesturi, PiP, SAF, SeekBars foto)
- VideoPagerAdapter.kt (ExoPlayer, gesturi, indicatoare)
- PlaybackService.kt (foreground service, PendingIntent)
- MemoryManager.kt
- DragonBones modul C++/CMake

## 3. Bug-uri identificate

### BUG-01 – SeekBar foto cu scală greșită
**Fișier:** `app/src/main/java/com/swipe/player/MainActivity.kt`
**Linii:** 208, 217, 259, 260
**Problemă:** `photoBrightnessSeek` și `photoVolumeSeek` au `max` implicit 100, dar progresul se setează la `luminozitateCurenta * 1000`. Rezultat: progresul este clampat la 100 → valoarea reală percepută e ~0.1.
**Impact:** Controlul luminozitate/volum din modul Poze nu funcționează corect.
**Corecție:** Setați `max = 1000` pentru ambele SeekBar și păstrați conversia `progress / 1000f`.

### BUG-02 – Calcul progres seek indicator eronat
**Fișier:** `VideoPagerAdapter.kt`
**Linii:** 733-735
**Problemă:** `p = ((posMs * 1000) / durMs)` → valori până la 1000 în timp ce `ProgressBar` are max implicit 100. Indicatorul de seek afișează mereu 100%.
**Corecție:** Calcul corect `((posMs * 100) / durMs).toInt()` sau setați max 1000 la ProgressBar.

### BUG-03 – PendingIntent implicit invalid în PlaybackService
**Fișier:** `PlaybackService.kt`
**Linii:** 192-197
**Problemă:** `Intent(this, controlReceiver::class.java)` creează un Intent cu componentă către o clasă anonimă neînregistrată. Pe Android 8+ PendingIntent implicit este interzis → `IllegalArgumentException` / `SecurityException`.
**Corecție:** Construiți Intent explicit cu acțiunea și `setPackage(packageName)`.

### BUG-04 – RegisterReceiver fără flag pe API 33+
**Fișier:** `MainActivity.kt`
**Linii:** 152
**Problemă:** `registerReceiver(phoneStateReceiver, ffPhone)` fără `RECEIVER_NOT_EXPORTED` aruncă `SecurityException` pe Android 13+.
**Corecție:** Înregistrați cu flag corespunzător.

### BUG-05 – Conflict STL DragonBones
**Fișier:** `dragonbones/build.gradle.kts` linia 15 și `dragonbones/CMakeLists.txt` linia 45
**Problemă:** Gradle forțează `ANDROID_STL=c++_static`, CMake link-ează `c++_shared`. Linker error posibil la build.
**Corecție:** Unificați pe `c++_shared` (recomandat în README).

### BUG-06 – Modul dragonbones neincluse în settings
**Fișier:** `settings.gradle.kts`
**Problemă:** Modulul `dragonbones` există dar nu este inclus în proiect → nu este compilat.
**Corecție:** Adăugați `include(":dragonbones")` sau eliminați modulul dacă nu este folosit.

### BUG-07 – compileSdk / AGP învechite
**Fișier:** `app/build.gradle.kts`, `build.gradle.kts`
**Problemă:** `compileSdk 34`, AGP 8.2.2, Kotlin 1.9.22 vs recomandările proiectului: AGP 8.11.0, compileSdk 36, Java 17.
**Impact:** Incompatibilități pe Android 15+, warning-uri.
**Corecție:** Actualizare la versiunile recomandate.

### BUG-08 – Restaurare poziție cu durata necunoscută
**Fișier:** `VideoPagerAdapter.kt`
**Linii:** 376-383
**Problemă:** `player.duration` poate fi `C.TIME_UNSET` (-9223372036854775807) la prima bind → seek la poziție invalidă.
**Corecție:** Verificați `durataMs > 0` înainte de seek.

## 4. Pași de editare chirurgicală
1. Corectare SeekBar max 1000 în MainActivity.
2. Corectare calcul progres seek indicator.
3. Refactor PendingIntent în PlaybackService.
4. Adăugare flag RECEIVER_NOT_EXPORTED pentru phoneStateReceiver.
5. Unificare STL DragonBones.
6. Actualizare settings.gradle.
7. Actualizare versiuni AGP/compileSdk.
8. Testare build debug.

## 5. Criterii de acceptare
- Build `./gradlew assembleDebug` reușește.
- SeekBar foto funcționează 0-100%.
- Indicator seek afișează progres corect.
- Notificarea playback nu crashează serviciul.
- Nu apar SecurityException la pornire.
