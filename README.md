# DocuScan 📄

A modern, local-first Android document scanner. Capture or import images, auto-align crop guides to the detected page corners, apply 19 filters, run on-device opt-in OCR with word-level review, and export as searchable PDF, JPG, or TXT.

Built with **Kotlin + Jetpack Compose (Material 3) + CameraX**, modeled after the MakeACopy feature set.

## Features

- 📷 **Camera capture** with in-app preview, flash & front/back flip (CameraX)
- 🖼️ **Gallery import** (system photo picker — no storage permissions needed)
- 📄 **PDF import** — open an existing PDF and its pages become editable pages
- 🧭 **Manual perspective crop** — drag the four corners **or the edges** (parallel edge translation), with a **snap-to-right-angle** assist that locks near-vertical/horizontal edges to 90° and **aspect-ratio presets** (Auto, Original, A3/A4/A5, US Letter, Legal, custom) enforced at warp time; output is deskewed via perspective warp
- 🎯 **Auto-align crop frame** — one tap aligns the crop guides to the detected page corners; nothing is cropped until you tap **Crop**, and you can still drag any corner afterwards to fine-tune
- 🧠 **MakeACopy corner detection, exactly** — the full DocQuadNet-256 ONNX neural detector (portable Runtime), the 5×5-quadratic peak refinement + corner/mask path chooser, gradient **edge-snap refinement**, and geometry **plausibility gating**, with the OpenCV detector as fallback and the full-image rectangle as the safe last resort — a bad detection can never crop the image in half
- ✂️ **Smart corner detection** — real corner-detection finds the page's four corners (works on a photo of a page lying on a bed, desk, or any background)
- ✨ **MakeACopy document cleanup** — the same OpenCV presets: **Natural**, **Enhanced** and **Clean Text** (background flattening, CLAHE local contrast, OCR-grade clean-up), on top of the 19 classic filters
- ⚡ **One-tap Auto crop** — detect the page and perspective-warp to it instantly (OpenCV contour pipeline with Hough fallback, same as MakeACopy)
- 🔤 **OCR (opt-in, like MakeACopy's Tesseract flavor)** — fully offline text recognition (Tesseract 4). Nothing is OCR'd automatically: tap **OCR** in the editor to review and correct the text
- 🔍 **Word-level OCR review** — every word is shown with its confidence (green/amber/red); tap a word to fix it, get dictionary suggestions, or re-run OCR on just that word
- 📖 **OCR dictionary** — frequency-word list suggests corrections for low-confidence words
- 🌐 **OCR languages** — English bundled; import more `.traineddata` language packs (e.g. from tessdata_fast) via Settings or the OCR screen
- 🎨 **22 filters** — Original, Natural, Enhanced, Clean Text, Magic, B&W (real Otsu threshold binarization), Grayscale, Sepia, Polaroid, Vintage, Soft, Warm, Cool, Ocean, Rose, Blue, Invert, Vivid, Faded, Crisp, Sharpen, Night
- 🔆 **Brightness & contrast** sliders
- 📚 **Multi-page documents** — add/remove/reorder pages
- 📄 **Export** — PDF with **page-format presets** (Fit-to-image, A4, US Letter, Legal) and **quality presets** (High/Standard/Small/Very small, 300/200/150/110 dpi), searchable when OCR text exists; JPG with **configurable quality + color/B&W**; or plain **TXT**
- 📨 **Inbox Mode** — pick a folder (SAF) and every export is automatically saved there too — great for paperless-ngx / Syncthing / Nextcloud workflows
- ♿ **Accessibility Mode** — spoken + haptic feedback and volume-key shutter in the camera
- 📤 **Share** with one tap (PDF or JPG)
- 🗂️ **Document library** — browse, view, re-share or delete past scans, with **search over titles and OCR text**
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
├── MainActivity.kt        # Compose entry point + volume-key shutter (A11y)
├── App.kt                 # Navigation + bottom bar
├── A11y.kt                # Accessibility Mode: TTS + haptics
├── DocViewModel.kt        # Shared state (pages, settings, history, OCR)
├── scan/                  # The engine
│   ├── AutoCrop.kt        # Corner detection (MakeACopy pipeline: DocQuadNet-256 → edge-snap → OpenCV → full-frame fallback)
│   ├── Cleanup.kt         # OpenCV document cleanup (Natural/Enhanced/Clean Text) + corner detector
│   ├── DocQuad.kt         # DocQuadNet-256 letterbox + score + postprocessor (MakeACopy port)
│   ├── DocQuadOrtRunner.kt# ONNX Runtime inference for DocQuadNet-256
│   ├── DocQuadDetector.kt # Detection result, plausibility gates, gradient edge-snap refiner
│   ├── WarpTarget.kt      # Warp target size: Zhang & He projective estimate + fixed ratios
│   ├── ImageFilters.kt    # 22 filters (incl. MakeACopy cleanup presets) + Otsu B&W + sharpen
│   ├── CropGeometry.kt    # Edge dragging + snap-to-right-angle geometry (MakeACopy port)
│   ├── CropAspectRatio.kt # Crop aspect-ratio presets enforced at warp time
│   ├── Ocr.kt             # Offline Tesseract OCR: word-level boxes + confidences, language packs
│   ├── Dictionary.kt      # Frequency-word dictionary for OCR suggestions
│   ├── PdfImport.kt       # PdfRenderer -> page bitmaps
│   ├── BitmapUtil.kt      # Rotate/crop/perspective-warp/EXIF/grayscale
│   ├── PdfExporter.kt     # android.graphics.pdf PDF writer (page format + DPI presets, searchable text layer)
│   ├── MediaSaver.kt      # MediaStore saves + inbox folder writes
│   └── Exporter.kt        # Export pipeline (PDF/JPG/TXT + inbox mirror)
├── data/                  # History + settings persistence + PDF page-format/quality options
├── ui/                    # Screens (Home, Camera, Editor, Crop, OCR review, Documents, Settings)
└── util/ShareUtil.kt      # Sharing via FileProvider
```

`app/src/main/assets/tessdata/` bundles the English OCR model (`eng.traineddata`, tessdata_fast), copied to private storage on first use. `app/src/main/assets/words/` bundles the English frequency word list used for OCR suggestions.

## Third-party licenses

- **Tesseract4Android** (Apache 2.0) and **tessdata_fast** `eng.traineddata` (Apache 2.0) — offline OCR.
- **OpenCV 4.13.0** (Apache 2.0) — corner detection and document cleanup presets, like MakeACopy.
- **ONNX Runtime Android** (MIT) — runs the DocQuadNet-256 document-corner model.
- **DocQuadNet-256** `docquad/docquadnet256_trained_opset17.ort` (Apache 2.0) — bundled with makeacopy (github.com/egdels/makeacopy); rebuild/update by downloading the asset from that repo's `app/src/main/assets/docquad/` and replacing `app/src/main/assets/docquad/`.
- **Word frequency data**: ["Word Frequency" by Hermit Dave](https://github.com/hermitdave/FrequencyWords) (CC BY-SA 4.0) — used for OCR suggestions, like MakeACopy.

## License

MIT — see [LICENSE](LICENSE).
