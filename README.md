# DocuScan 📄

A modern, local-first Android document scanner. Capture or import images, auto-align crop guides to the detected page corners, apply 19 filters, run on-device OCR, and export as searchable PDF or JPG.

Built with **Kotlin + Jetpack Compose (Material 3) + CameraX**.

## Features

- 📷 **Camera capture** with in-app preview, flash & front/back flip (CameraX)
- 🖼️ **Gallery import** (system photo picker — no storage permissions needed)
- 🧭 **Manual perspective crop** — drag the four corners, output is deskewed via perspective warp
- 🎯 **Auto-align crop frame** — one tap aligns the crop guides to the detected page corners; nothing is cropped until you tap **Crop**, and you can still drag any corner afterwards to fine-tune
- ✂️ **Smart corner detection** — real corner-detection finds the page's four corners (works on a photo of a page lying on a bed, desk, or any background), so photos that are *not* of a page are never touched
- 🔤 **OCR (opt-in)** — fully offline text recognition (Tesseract 4, English). Nothing is OCR'd automatically: tap **OCR** in the editor, review/correct the text, and the recognized text becomes a searchable layer in exported PDFs
- 🎨 **19 filters** — Original, Magic, B&W (real Otsu threshold binarization), Grayscale, Sepia, Polaroid, Vintage, Soft, Warm, Cool, Ocean, Rose, Blue, Invert, Vivid, Faded, Crisp, Sharpen, Night
- 🔆 **Brightness & contrast** sliders
- 📚 **Multi-page documents** — add/remove/reorder pages
- 📄 **Export to PDF** (multi-page, A4, searchable when OCR text exists) and/or **JPG** — saved to `Pictures/DocuScan` / `Download/DocuScan` via MediaStore
- 📤 **Share** with one tap (PDF or JPG)
- 🗂️ **Document history** — browse, view, re-share or delete past scans
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
│   ├── AutoCrop.kt        # Corner detection: gradient thresholds, components, hull -> quad
│   ├── ImageFilters.kt    # 19 color-matrix filters + Otsu B&W + spatial sharpen
│   ├── Ocr.kt             # Offline Tesseract OCR (opt-in, English traineddata in assets)
│   ├── BitmapUtil.kt      # Rotate/crop/perspective-warp/EXIF
│   ├── PdfExporter.kt     # android.graphics.pdf PDF writer
│   ├── MediaSaver.kt      # MediaStore saves
│   └── Exporter.kt        # Export pipeline
├── data/                  # History + settings persistence
├── ui/                    # Screens (Home, Camera, Editor, Crop, OCR, Documents, Settings)
└── util/ShareUtil.kt      # Sharing via FileProvider
```

`app/src/main/assets/tessdata/` bundles the English OCR model (`eng.traineddata`, tessdata_fast), copied to private storage on first use.

## License

MIT — see [LICENSE](LICENSE).
