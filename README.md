# DocuScan 📄

A modern, local-first Android document scanner. Capture or import images, apply 22 filters, crop and rotate each page, and export as PDF or JPG.

Built with **Kotlin + Jetpack Compose (Material 3) + CameraX**, modeled after the MakeACopy feature set.

## Features

- 📷 **Camera capture** with in-app preview, flash & front/back flip (CameraX)
- 🖼️ **Gallery import** (system photo picker — no storage permissions needed)
- 📄 **PDF import** — open an existing PDF and its pages become editable pages
- 🧭 **Manual crop** — like MakeACopy: drag the four corners **or the edges** (parallel edge translation), with a **snap-to-right-angle** assist that locks near-vertical/horizontal edges to 90° and **aspect-ratio presets** (Auto, Original, A3/A4/A5, US Letter, Legal, custom) enforced at warp time; output is deskewed via perspective warp. The crop toolbar sits **at the bottom of the image** with **Rotate ⟲/⟳**, aspect chips and **Crop**/**Reset**/**Cancel** — exactly like MakeACopy's crop screen
- 🔄 **Rotate** — rotate a page 90° at a time, either in the editor or while cropping
- ✨ **MakeACopy document cleanup** — the same OpenCV presets: **Natural**, **Enhanced** and **Clean Text** (background flattening, CLAHE local contrast, clean-up), on top of the 19 classic filters
- 🎨 **22 filters** — Original, Natural, Enhanced, Clean Text, Magic, B&W (real Otsu threshold binarization), Grayscale, Sepia, Polaroid, Vintage, Soft, Warm, Cool, Ocean, Rose, Blue, Invert, Vivid, Faded, Crisp, Sharpen, Night
- 🔆 **Brightness & contrast** sliders
- 📚 **Multi-page documents** — add/remove/reorder pages
- 📄 **Export** — PDF with **page-format presets** (Fit-to-image, A4, US Letter, Legal) and **quality presets** (High/Standard/Small/Very small, 300/200/150/110 dpi); JPG with **configurable quality + color/B&W**
- 📨 **Inbox Mode** — pick a folder (SAF) and every export is automatically saved there too — great for paperless-ngx / Syncthing / Nextcloud workflows
- ♿ **Accessibility Mode** — spoken + haptic feedback and volume-key shutter in the camera
- 📤 **Share** with one tap (PDF or JPG)
- 🗂️ **Document library** — browse, view, re-share or delete past scans, with **search over titles**
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
├── DocViewModel.kt        # Shared state (pages, settings, history)
├── scan/                  # The engine
│   ├── Cleanup.kt         # OpenCV document cleanup (Natural/Enhanced/Clean Text) + corner detector
│   ├── WarpTarget.kt      # Warp target size: Zhang & He projective estimate + fixed ratios
│   ├── ImageFilters.kt    # 22 filters (incl. MakeACopy cleanup presets) + Otsu B&W + sharpen
│   ├── CropGeometry.kt    # Edge dragging + snap-to-right-angle geometry (MakeACopy port)
│   ├── CropAspectRatio.kt # Crop aspect-ratio presets enforced at warp time
│   ├── PdfImport.kt       # PdfRenderer -> page bitmaps
│   ├── BitmapUtil.kt      # Rotate/crop/perspective-warp/EXIF/grayscale
│   ├── PdfExporter.kt     # android.graphics.pdf PDF writer (page format + DPI presets)
│   ├── MediaSaver.kt      # MediaStore saves + inbox folder writes
│   └── Exporter.kt        # Export pipeline (PDF/JPG + inbox mirror)
├── data/                  # History + settings persistence + PDF page-format/quality options
├── ui/                    # Screens (Home, Camera, Editor, Crop, Documents, Settings)
└── util/ShareUtil.kt      # Sharing via FileProvider
```

## Third-party licenses

- **OpenCV 4.13.0** (Apache 2.0) — corner detection and document cleanup presets, like MakeACopy.

## License

MIT — see [LICENSE](LICENSE).
