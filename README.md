# DocuScan 📄

A modern, local-first Android document scanner. Capture or import images, auto-detect document edges, apply 12+ filters, build multi-page documents, and export as PDF or JPG.

Built with **Kotlin + Jetpack Compose (Material 3) + CameraX**.

## Features

- 📷 **Camera capture** with in-app preview, flash & front/back flip (CameraX)
- 🖼️ **Gallery import** (system photo picker — no storage permissions needed)
- ✂️ **Auto-crop** — edge-detection algorithm finds the document in any image
- 🧭 **Manual perspective crop** — drag the four corners, output is deskewed via perspective warp
- 🎨 **12 filters** — Original, Enhance, B&W, Grayscale, Sepia, Invert, Warm, Cool, Vivid, Faded, Crisp, Night
- 🔆 **Brightness & contrast** sliders
- 📚 **Multi-page documents** — add/remove/reorder pages
- 📄 **Export to PDF** (multi-page, A4) and/or **JPG** — saved to `Pictures/DocuScan` / `Download/DocuScan` via MediaStore
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
│   ├── AutoCrop.kt        # Edge detection via gradient + union-find
│   ├── ImageFilters.kt    # 12 color-matrix filters
│   ├── BitmapUtil.kt      # Rotate/crop/perspective-warp/EXIF
│   ├── PdfExporter.kt     # android.graphics.pdf PDF writer
│   ├── MediaSaver.kt      # MediaStore saves
│   └── Exporter.kt        # Export pipeline
├── data/                  # History + settings persistence
├── ui/                    # Screens (Home, Camera, Editor, Crop, Documents, Settings)
└── util/ShareUtil.kt      # Sharing via FileProvider
```

## License

MIT — see [LICENSE](LICENSE).
