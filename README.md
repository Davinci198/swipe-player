# Swipe Player - Android Native (fără Expo)

Player video vertical (TikTok-style) cu accelerație hardware DragonBones C++ native.

## Structură

```
SwipePlayer/
├── app/
│   ├── src/main/
│   │   ├── java/com/swipe/player/
│   │   │   ├── MainActivity.kt
│   │   │   ├── VideoPagerAdapter.kt
│   │   │   ├── MemoryManager.kt
│   │   │   └── DragonBonesBridge.java
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt
│   │   │   ├── jni/           (JNI bridge)
│   │   │   └── dragonBones/   (sursa C++)
│   │   ├── res/
│   │   ├── assets/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── .github/workflows/build.yml
```

## Build

```bash
# Debug (variantă principală)
./gradlew assembleDebug

# Debug (variantă clone - se instalează separat)
./gradlew assembleCloneDebug

# Release
./gradlew assembleRelease
```

## GitHub Actions

La push/PR către `main` sau manual din **Actions** tab, workflow-ul `Full APK Build`:
1. Instalează Android SDK + NDK 27 + CMake
2. Compilează DragonBones C++ cu NDK 27 fix
3. Rulează `assembleOriginalDebug` implicit; varianta clone este disponibilă manual
4. Returnează APK-ul ca artifact

## NDK 27 Fix

- C++17 standard
- `-faligned-new -faligned-allocation` pentru `operator new(align_val_t)`
- Link cu `c++_shared` (necesar pentru aligned new/delete)
- `-Wl,-z,max-page-size=16384` compatibilitate Android 15+
