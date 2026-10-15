# Eval Rubric — GAN Design Harness (Arkacode UI)

Bounded loop: Generator memperbaiki UI, Evaluator menilai. Pass jika skor berbobot ≥ **7.5**.

## Bobot
| Dimensi | Bobot | Fokus |
|---|---|---|
| Design Quality | 0.35 | Keindahan claymorphism biru muda, konsistensi palet, hirarki visual |
| Originality | 0.30 | Detail yang khas, bukan sekadar Material default |
| Craft | 0.25 | Spacing, radius, bayangan, tipografi, alignment, ikon |
| Fungsional | 0.10 | Semua kontrol tetap berfungsi, tidak crash, aksesibel |

Catatan: Originality dinaikkan (0.30) untuk mendorong lompatan kreatif; Fungsional
diturunkan karena mode desain mengejar kualitas visual.

## Cara menilai (skala 0–10 per dimensi)
- **Design Quality**: Apakah terasa clay lembut & biru muda? Seragam antar layar? Ada kontras cukup?
- **Originality**: Apakah ada sentuhan khas (bottom-nav clay mengambang, kartu puffy, header gradient)? Atau masih Material default?
- **Craft**: Radius & shadow konsisten? Padding 12–16dp? Tidak ada teks terpotong / alignment meleset?
- **Fungsional**: Tombol/tab masih bekerja? Mode gelap rapi? Kontras teks lulus AA?

## Gerbang
- Skor berbobot < 7.5 → ulangi dengan umpan balik spesifik per dimensi.
- Skor ≥ 7.5 → lulus, tulis `feedback/final.md` + simpan bukti screenshot.
- Maksimum iterasi sesuai `--max-iterations` (default 10).

## Bukti wajib
- `design/styleguide.html` (light + dark).
- `screenshots/` hasil dump UI device (chat, daftar sesi, providers, capabilities).
- `build-report.md` hasil `assembleDebug`.
