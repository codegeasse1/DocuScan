# DocuScan 📄

A modern, local-first Android document scanner. Capture or import images, auto-detect document edges, apply 20+ filters and cleanup presets, build multi-page documents, and export as searchable PDF or JPG.

Built with **Kotlin + Jetpack Compose (Material 3) + CameraX + OpenCV + Tesseract OCR**.

## Features

- 📷 **Camera capture** with in-app preview, flash & front/back flip (CameraX)
- 🖼️ **Gallery import** (system photo picker — no storage permissions needed)
- ✂️ **Auto-crop** — OpenCV edge detection (adaptive Canny + contour scoring + Hough fallback) finds the document in any image
- 🧭 **Manual perspective crop** — drag the four corners **or the edges** (parallel edge translation), with a **snap-to-right-angle** assist that locks near-vertical/horizontal edges to 90° and **aspect-ratio presets** (Auto, Original, A3/A4/A5, US Letter, Legal, custom) enforced at warp time
- 🎨 **22 filters** — including makeacopy-style **Natural / Enhanced / Clean Text** cleanup presets and magic-color filters
- 🔆 **Brightness & contrast** sliders
- 📚 **Multi-page documents** — add/remove/reorder pages
- 📄 **Export to PDF** with **page-format presets** (Fit-to-image, A4, US Letter, Legal) and **quality presets** (High/Standard/Small/Very small — 300/200/150/110 dpi), plus JPG export with quality & color options — saved to `Pictures/DocuScan` / `Download/DocuScan` via MediaStore
- 🔎 **OCR** — word-level recognition with confidence colors, tap-to-edit, dictionary suggestions and per-word re-OCR; optional (never automatic)
- 🌐 **OCR languages** — import additional `.traineddata` packs (English bundled)
- 📥 **Inbox mode** — scan straight into a system folder you choose (persisted permission)
- ♿ **Accessibility mode** — text-to-speech, haptics and volume-key shutter
- 📥 **PDF import** — bring pages in from an existing PDF
- 📤 **Share** with one tap (PDF or JPG)
- 🗂️ **Searchable document history** — browse, view, re-share or delete past scans
- 📨 **Share-to-scan** — open an image from any app with "Send to DocuScan"
- 🌙 **Modern Material 3 UI**, light/dark/system theme

Everything is processed **on-device** — no network calls, no accounts, no uploads.

## Download

Grab the latest signed APK from the [Releases](https://github.com/codegeasse1/DocuScan/releases) tab — every push to `main` is auto-built and released by GitHub Actions.

> Note: releases are signed with a fresh CI-generated key per build, so sideload updates require uninstalling the previous version first. (Set the `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD` secrets to keep a stable key — generate it locally with `keytool -genkeypair -keystore keystore/release.jks -alias docuscan`.)

## Building locally

```bash
# JDK 17 required
./gradlew :app:assembleDebug     # debug build
./gradlew :app:assembleRelease   # release build (unsigned unless a keystore exists)
```

Or just open the repo in Android Studio and press Run.

- **minSdk**: 26 (Android 8.0)
- **targetSdk**: 35

## Project layout

```
app/src/main/java/com/docuscan/app/
├── MainActivity.kt        # Compose entry point
├── App.kt                 # Navigation + bottom bar
├── DocViewModel.kt        # Shared state (pages, settings, history)
├── scan/                  # The engine
│   ├── AutoCrop.kt        # OpenCV corner detection + fallback
│   ├── Cleanup.kt         # makeacopy-style cleanup filters + OpenCV corner detector
│   ├── ImageFilters.kt    # 22 color-matrix filters
│   ├── CropGeometry.kt    # Edge dragging + snap-to-right-angle geometry
│   ├── CropAspectRatio.kt # Crop aspect-ratio presets
│   ├── Ocr.kt             # Tesseract word-level OCR
│   ├── Dictionary.kt      # Levenshtein suggestions
│   ├── BitmapUtil.kt      # Rotate/crop/perspective-warp/EXIF
│   ├── PdfExporter.kt     # PDF writer (page format + DPI presets)
│   ├── MediaSaver.kt      # MediaStore saves + inbox
│   └── Exporter.kt        # Export pipeline
├── data/                  # History + settings persistence + PDF options
├── ui/                    # Screens (Home, Camera, Editor, Crop, Documents, Settings, OCR)
└── util/ShareUtil.kt      # Sharing via FileProvider
```

## Third-party

- OpenCV 4.13.0 (Apache 2.0)
- Tesseract 4 (Apache 2.0, via tesseract4android)
- FrequencyWords English wordlist (MIT)
- CameraX, Compose, Material 3 (Apache 2.0)

## License

MIT — see [LICENSE](LICENSE).
