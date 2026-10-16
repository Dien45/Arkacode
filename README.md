# Arkacode

![Build Android APK](https://github.com/Dien45/Arkacode/actions/workflows/build-android-apk.yml/badge.svg)

**Frontend Android untuk [opencode](https://opencode.ai).** UI Kotlin + Jetpack
Compose murni yang mengendalikan server headless `opencode serve` yang berjalan
di dalam **proot Alpine di perangkat itu sendiri**.

Nol logika agent di sisi app — semua kecerdasan (loop chat, tools, memory,
skills, agents, MCP) milik opencode A–Z. Aplikasi ini hanya frontend.

> Repo terpisah dari `Arka`. Backend khas Arka (AiClient multi-provider,
> ChatController loop, Tools.kt, MemorySystem, SkillsManager, parser tool-call)
> sengaja dibuang dan digantikan bawaan opencode.

## Logo

Mark launcher: jendela terminal dengan huruf **A** brand dan baris kode di
sampingnya, putih di atas biru brand `#5B9BE0`.

- `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- `android/app/src/main/res/values/colors.xml`

## Fitur

- **Chat & streaming** — kirim async + SSE (`session.status`, `session.text/
  reasoning.delta`, `session.tool.*`, `permission.v2.*`), abort/interrupt,
  approval (Tolak / Izinkan / Selalu), render markdown + blok kode + output
  tool, thinking collapse, picker model & agent per sesi.
- **Sessions** — list/buat/hapus/rename, status semua sesi, revert + diff
  berkas, ringkas (`compact`).
- **Files** — tree, baca, cari, preview (markdown/gambar) via API fs.
- **Providers & auth** — daftar provider + model (badge Gratis), isi/hapus API
  key per provider (terenkripsi, disuntik sebagai env saat serve nyala).
- **Provider kustom (OpenAI-compatible)** — tambah endpoint `/v1` gaya OpenAI
  (LM Studio, llama.cpp, vLLM, Ollama, OpenRouter, …). Ditulis ke `opencode.json`
  server dengan `apiKey` = `{env:ARKACODE_<ID>_API_KEY}` (tanpa secret plaintext).
- **Integrasi** — hubungkan via API key atau OAuth (buka tautan → poll/tempel
  kode → selesai), kredensial tersimpan di server.
- **Kapabilitas** — daftar agent, command, dan skill.
- **Engine** — unduh binary opencode pinned (sha256), start/stop, health,
  auto-provisioning saat pertama buka, editor port & password server, log.
- **i18n ID/EN**, tema claymorphism terang/gelap persisten, backup rules.

## Arsitektur

```
┌─ APP (Kotlin + Compose, frontend murni) ──────────────┐
│ UI: Chat · Sessions · Files · Agents · MCP ·          │
│     Providers/Auth · Config · Approvals · Diff        │
│                          │ HTTP 127.0.0.1:4096        │
│ EngineManager: install/update binary, start/stop,     │
│ health, basic-auth, working dir, log                  │
└──────────────────────────┼───────────────────────────┘
                           │ satu network namespace (proot tidak isolasi net)
┌─ PROOT ALPINE ───────────┼───────────────────────────┐
│ opencode serve --port 4096 --hostname 127.0.0.1       │
│ working dir: /root/workspace/<project>                │
│ config: opencode.json + auth.json (ditulis frontend)  │
└──────────────────────────────────────────────────────┘
```

- Binary `opencode` pinned **v1.19.0** (Linux musl **arm64**) diunduh dari
  GitHub Releases + verifikasi sha256. `serve` adalah subcommand headless; TUI
  tidak pernah dibuka.
- Server default `127.0.0.1:4096`, basic auth (username `opencode`, password
  disimpan di EncryptedSharedPreferences).
- Config server: `<rootfs>/root/.config/opencode/opencode.json`; auth:
  `<rootfs>/root/.local/share/opencode/auth.json`.

## Kebutuhan

- **JDK 21** (CI memakai Temurin 21); source/target Java 17.
- **Android SDK** platform 35 (`compileSdk`/`targetSdk` 35, `minSdk` 26).
- Gradle wrapper **8.13** (sudah disertakan).
- Perangkat/emulator **arm64** Android 8.0+ (minSdk 26).

## Build & jalankan

Windows (PowerShell):

```powershell
$env:ANDROID_HOME='C:\Users\Dien\AppData\Local\Android\Sdk'
.\android\gradlew.bat -p android :app:assembleDebug --console=plain
```

Linux/macOS:

```bash
cd android && ./gradlew :app:assembleDebug
```

- APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Install: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`

Catatan: task `downloadProotBinaries` mengunduh binary proot (paket Termux) ke
`jniLibs`. Bila mirror tidak terjangkau, build tetap sukses tetapi fitur
distro/`run_command` tidak aktif.

## CI & alur kontribusi

- **CI**: `.github/workflows/build-android-apk.yml` menjalankan
  `:app:assembleDebug` pada PR (paths `android/**`) dan push ke `main`, lalu
  mengunggah artifact APK.
- **Release**: `.github/workflows/android-release.yml`.
- **Alur**: patch kecil → branch → PR → CI hijau → squash merge ke `main`.

## Struktur proyek

```
android/app/src/main/java/com/arkacode/app/
  ArkacodeApp.kt · MainActivity.kt
  core/  EngineManager · DistroManager · ExecRunner · ExecSettings ·
         SecureStore · GitCredentials · OpenCodeConfig · Attachments ·
         PromptExpander · SlashSuggest · SessionRunRegistry ·
         SessionUiPrefs · VirtualFs
  net/   OpenCodeClient · ServerModels
  ui/    ChatScreen · FilesScreen · ProvidersScreen · CapabilitiesScreen ·
         EngineScreen · SettingsScreen · IntegrationsSheet · ModelPickerSheet ·
         MarkdownText · Clay · Theme · I18n · SlashSuggest
```

## Status

Ringkas (detail di [`PRD.md`](PRD.md) §8):

- **Selesai**: engine + auto-provisioning, chat (SSE, abort, approval, model
  per sesi, markdown), sessions, files, providers + API key, provider kustom
  OpenAI-compatible, integrasi (OAuth/API key), kapabilitas (agent/command/
  skill), i18n ID/EN, tema clay terang/gelap, backup rules, CI debug + release.
- **Belum** (keterbatasan server v1.19, bukan app): editor tulis
  `opencode.json` berformulir, MCP/share/fork/summarize.

## Dokumen

- [`PRD.md`](PRD.md) — produk, arsitektur, kebutuhan fungsional, risiko.
- [`build-report.md`](build-report.md) — catatan implementasi tema clay.

## Lisensi

Belum ditentukan (TBD).
