# Spec — Arkacode UI: Claymorphism Biru Muda

## Brief (dari user)
Desain UI Arkacode sekarang kurang bagus. Ganti seluruh tampilan UI ke
**claymorphism warna biru muda** (soft, puffy, ramah), konsisten di semua layar.

## Target
Aplikasi Android `com.arkacode.app` — seluruh layar:
- Shell + bottom navigation (`EngineScreen` / `ReadyScreen`)
- Boot / provisioning log (`BootScreen`, `Screen.Stopped`)
- Chat (`ChatScreen`, `CommandSheet`)
- Files (`FilesScreen`)
- Providers (`ProvidersScreen`)
- Capabilities: Agents / Commands / Skills (`CapabilitiesScreen`)
- Integrations (`IntegrationsSheet`)
- Model picker (`ModelPickerSheet`)

## Bahasa desain
- **Claymorphism**: sudut besar (20–28dp), bayangan lembut bertinta biru,
  permukaan puffy dengan gradien vertikal halus (highlight atas → base bawah).
- **Palet biru muda**:
  - Accent / primary: `#5B9BE0`
  - Latar kanvas: `#EAF4FF`
  - Kartu: `#FFFFFF` dengan gradien `#FFFFFF → #EAF3FC`
  - Ink (teks utama): `#27415F`, sekunder `#566B85`
  - Outline lembut: `#C2DCF4`
- **Tipografi**: sans default; judul Bold, subtitle bodySmall muted.
- **Kontras**: teks utama vs latar ≥ WCAG AA (4.5:1).
- **Dark mode**: tetap ada; navy laut dalam dengan aksen `#8FC3FF`.

## Non-goal
- Tidak mengubah logika, jaringan, engine, atau alur sesi.
- Tidak menambah fitur baru.

## Sumber kebenaran
`android/app/src/main/java/com/arkacode/app/ui/Theme.kt` (palet + komponen clay).
Semua layar wajib memakai token `MaterialTheme.colorScheme` + `ClayCard` / komponen clay.

## Kriteria lulus (ringkas)
1. Tidak ada warna hardcoded di luar `Theme.kt` yang bertentangan dengan palet.
2. Semua layar memakai kartu/permukaan clay yang konsisten.
3. `assembleDebug` hijau, app tidak crash, kedua mode (terang/gelap) rapi.
