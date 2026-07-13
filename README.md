<div align="center">
<a href="https://github.com/yighy/paintcursor" target="blank">
<img src="app/src/main/ic_launcher-playstore.png" width="100" alt="Logo" />
</a>

<h2> Paint Cursor </h2>

![](https://img.shields.io/badge/Kotlin-a503fc?logo=kotlin&logoColor=white&style=for-the-badge)
![](https://img.shields.io/static/v1?style=for-the-badge&message=Jetpack+Compose&color=4285F4&logo=Jetpack+Compose&logoColor=FFFFFF&label=)
![](https://custom-icon-badges.demolab.com/badge/m3%20expressive-lightblue?style=for-the-badge&logoColor=333&logo=material-you)

![](https://img.shields.io/github/v/release/yighy/paintcursor?color=purple&include_prereleases&logo=github&style=for-the-badge)

<!-- TODO screenshots: <img src="art/screenshots.png" width="95%"> -->

</div>

## 💡 Overview

Paint Cursor is a drawing app with a cursor control.
The app allows you to draw by driving a **precision cursor** on the screen. Your finger stays off the artwork and the reticle does the drawing, so no more fingers hiding your line endings.

## 🎯 How it works

- **Drag anywhere** on the canvas to move the cursor — the stroke happens at the reticle, not under your finger.
- **Tap** (or **hold** the floating button) to lower or raise the pen.
- **Two fingers** to pan, pinch-zoom and rotate the canvas around the cursor.
- Adjustable cursor sensitivity and stroke smoothing.

## ✨ Features

**Brush engine**
- Size, softness, opacity, flow, spacing, rotation, size or rotation jitter.
- Custom brush tips and textures.
- Velocity dynamics: stroke speed can drive size, flow and scatter.
- Saveable brush presets.

**Tools**
- Freehand, straight line, eraser in both modes, bucket fill with tolerance, linear gradient.
- Lazy/rope mode for extra-smooth curves.
- Selection: lasso, rectangle, magic wand and color select — move, transform, duplicate, delete, invert.
- Active selections clip every tool (draw inside the selection only).

**Canvas & workflow**
- Layers: reorder by drag & drop, opacity, visibility, duplicate, merge down, rename.
- Full undo/redo history.
- Import an image as a new layer (position/scale/rotate before applying).
- Floating reference image window (movable, resizable, minimizable, color-pickable).
- Eyedropper, color history, HSB picker.
- Export as PNG.

## 🛠️ Building

Requirements: Android Studio (or just a JDK 17+), Android SDK 37, min SDK 31.

```bash
git clone https://github.com/yighy/paintcursor.git
cd paintcursor
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

Release builds are signed through a local `keystore.properties` file (see `keystore.properties.example`). Without it, `assembleRelease` produces an unsigned APK.

## 👩‍💻 Tech Stack

- **Kotlin**: 100% Kotlin codebase, coroutines & flows throughout
- **Jetpack Compose**: the entire UI, no XML layouts
- **Material 3**: expressive theming with dynamic color (Material You)
- **Room**: local storage for projects, layers and brush presets
- **DataStore**: user preferences and app settings
- **Coil**: image loading for thumbnails and reference images

## 📄 Status & license

This is a personal project under active development. No license has been chosen yet — all rights reserved for now.
