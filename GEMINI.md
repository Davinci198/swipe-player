# Aileron Protocol — GEMINI.md

Reguli stricte pentru agenții (Operit / Antigravity / Gemini CLI) lucrând pe **swipe-player**.

## 1. Scalare efort = scalare task
- Modificări de 1-3 linii: **FĂRĂ plan elaborat**. Citește fișierul țintă, fă editarea, commit. Zero documente, zero liste de pași.
- Plan scris doar dacă task-ul atinge ≥3 fișiere sau schimbă arhitectură.

## 2. Regula celor 2 fail-uri (STOP HARD)
- Dacă același bug primește **2 fix-uri consecutive eșuate** (build roșu / comportament nerezolvat): **OPREȘTE-TE**.
- Interzis: lanțuri de commit-uri `fix(ci)`, `fix2`, `final`, `final-FINAL`.
- La 2 fail-uri: scrie un raport scurt (ce ai încercat, ce eroare, ipoteza principală) și **așteaptă omul**.

## 3. Verificare înainte de editare
- Înainte de ORICE editare: citește fișierul țintă (nu presupune conținutul din memorie sau din task-uri vechi).
- Confirmă că textul căutat pentru replace există exact; dacă nu, întrerupe și raportează.
- Nu rescrie fișiere întregi pentru editări locale — folosește editări punctuale.

## 4. Reguli de build (hard)
- Interzis: `python`/`python3`/`*.py`, `./gradlew` local, `assemble*` local.
- Build-ul se face DOAR prin GitHub Actions; branch-ul lucrează cu: editare fișiere + `git` + `gh`.
- Push cu grijă: `--force` doar pe branch-uri de fix personale, niciodată pe `main`.

## 5. Contextul proiectului
- Branch de lucru tipic: `feature/gestures-180mb-fix-clean` / `fix/*`.
- Gesturi: `VideoPagerAdapter.kt` — volum/luminozitate incrementale (`dy * 0.002f`), seek preview la MOVE + `seekTo` la UP, swipe vertical TikTok (`dragMod = 4` → `return false`).
- Release: bump `versionCode`/`versionName` în `app/build.gradle.kts` pe `main`, apoi tag `v*` → workflow „Release APK Build".
