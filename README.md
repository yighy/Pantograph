<p align=center><image src="app/src/main/ic_launcher-playstore.png" height="200" /></p>
<h1 align="center">Paint Cursor</h1>
<p align="center">A cursor-based drawing app for Android</p>
<p align="center">
  <a href="https://github.com/yighy/paintcursor/releases">
    <img src="https://img.shields.io/github/v/release/yighy/paintcursor?style=for-the-badge&logo=GitHub&include_prereleases" alt="GitHub release" />
  </a>
</p>

<!-- Screenshots here

<p align=center>
<image src="" height="512"/>
<image src="" height="512"/>
<image src="" height="512"/>

-->
---

## 💡 Overview

Paint Cursor is a drawing app with a cursor control.
The app allows you to draw by driving a **precision cursor** on the screen. Your finger stays off the artwork and the reticle does the drawing, so no more fingers hiding your line endings.

## 🎯 How it works

- **Drag anywhere** on the canvas to move the cursor — the stroke happens at the reticle, not under your finger.
- **Tap** (or **hold** the floating button) to lower or raise the pen.
- **Two fingers** to pan, pinch-zoom and rotate the canvas around the cursor.
- Adjustable cursor sensitivity and stroke smoothing.

### Satellite gates

Two small pills sit beside the floating button and are worked without looking away from the canvas. Both fade out while you're drawing so they can't be hit mid-stroke.

- **Right pill — brush levels.** Hold and drag: sideways picks between size, softness, opacity and flow; up and down sets the level. A live readout follows your hand, staying clear of it.
- **Bottom pill — drawing mode.** Hold and drag towards one of four cells: freehand or straight line, eraser off or on. It applies on release.

Both gates start from a dead zone (no jump when you touch down), respond finely near the anchor and faster at full stretch, and tick haptically on each step so you can feel where you are with your finger covering the readout. Response distance is tunable in Settings.

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
- A lifted selection survives a layer change, so it can be pasted into another layer.
- Active selections clip every tool (draw inside the selection only).

**Canvas & workflow**
- Layers: reorder by drag & drop, opacity, visibility, duplicate, merge down, rename.
- Full undo/redo history.
- Import an image as a new layer (position/scale/rotate before applying).
- Floating reference image window (movable, resizable, minimizable, color-pickable).
- Eyedropper, color history, HSB picker (color field or sliders).
- Export as PNG.

**Accessibility**
- Controls meet the 48dp minimum touch target, including swatches and badges that stay visually small.
- The satellite gates are drag-only, so each also exposes discrete TalkBack actions — nudging a brush level, or setting a drawing mode outright.
- Haptic feedback on gate thresholds, so the gestures don't depend on watching the readout.

## 🛠️ Building

Requirements: Android Studio (or just a JDK 17+), Android SDK 37, min SDK 31.

```bash
git clone https://github.com/yighy/paintcursor.git
cd paintcursor
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

Release builds are signed through a local `keystore.properties` file (see `keystore.properties.example`). Without it, `assembleRelease` produces an unsigned APK.

### Tests

```bash
./gradlew testDebugUnitTest
```

The gesture arithmetic behind the satellite gates (`GateMath`) and the rules deciding what
history a floating selection needs when it lands (`SelectionCommitPolicy`) are kept free of
Android types so they can be covered by plain JVM tests.

## 👩‍💻 Tech Stack

- **Kotlin**: 100% Kotlin codebase, coroutines & flows throughout
- **Jetpack Compose**: the entire UI, no XML layouts
- **Material 3**: expressive theming with dynamic color (Material You); shared shape, type and spring-based motion tokens
- **Room**: local storage for projects, layers and brush presets
- **DataStore**: user preferences and app settings
- **Coil**: image loading for thumbnails and reference images

## license

This project is licensed under the [GNU General Public License v3.0](LICENSE).
