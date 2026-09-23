<p align=center><image src="app/src/main/ic_launcher-playstore.png" height="200" /></p>
<h1 align="center">Pantograph</h1>
<p align="center">A cursor-based drawing app for Android</p>
<p align="center">
  <a href="https://github.com/yighy/Pantograph/releases">
    <img src="https://img.shields.io/github/v/release/yighy/Pantograph?style=for-the-badge&logo=GitHub&include_prereleases" alt="GitHub release" />
  </a>
</p>

<p align=center>
<image src="assets/1.gif" height="512"/>
</p>


---

## Overview

You draw by driving a **precision cursor** rather than by touching the artwork. Your finger
stays off the canvas and the reticle lays the stroke, so nothing hides the end of your line.

Named after the 1821 drafting instrument that reproduced a drawing elsewhere as you traced it.

## Install

Download the APK from [Releases](https://github.com/yighy/Pantograph/releases). Requires Android 12 or newer.

> **Before 1.0, an update can clear your projects.** When the storage format changes, the app
> starts from an empty database rather than migrating the old one. Releases that do this say so
> at the top of their notes — export anything you want to keep first.

## How it works

- **Drag anywhere** on the canvas to move the cursor — the stroke happens at the reticle, not under your finger.
- **Tap** the screen or **hold** the floating button to lower and raise the pen.
- **Two fingers** to pan, zoom and rotate the canvas.
- While the pen is down the cursor moves slower than your finger, for precision — how much is up to you.
- Every tool you turn on shows as a **chip**: tap it to turn the tool off, drag it to adjust the tool's settings.

### Satellite gates

Four pills orbit the floating button, held and dragged like a gear stick so you never look away
from the canvas.

- **Top — colour.** Hue, saturation, brightness, and the eyedropper.
- **Right — brush.** Size, opacity, flow, softness.
- **Bottom — mode and shortcuts.** Straight line, eraser, undo, and a flip back to your previous brush.
- **Left — quick tools.** Four tools of your choosing; long-press one in the tools menu to pin it.

## Features

**Brush**
- Size, opacity, flow, softness, smoothing, spacing and rotation.
- Round or square tip with an adjustable ratio, anti-aliasing on or off.
- Jitter on size, rotation, scatter, flow and colour.
- Smudge, velocity dynamics, custom tips and textures.
- Presets in folders, a Brush Studio with a live preview, and nine starter brushes.
- Your brush carries over between projects; each drawing keeps its own colour.

**Tools**
- Freehand, straight line, eraser, bucket fill, linear gradient.
- Path: place points and bend a curve through them.
- Selection: lasso, rectangle, magic wand, colour select — move, transform, duplicate, invert.
- Lazy mode, for smooth curves.
- Recoil: the cursor returns to where each stroke began.
- Fine: slow the cursor down even with the pen up.
- Loupe: a magnified view of the canvas under the brush.

**Canvas**
- Layers with opacity, visibility, locking, reordering, duplicate and merge.
- Undo and redo — optionally returning the cursor to the undone stroke, and leaving it behind as a trace to redraw over.
- Image import, a floating reference image, colour history.
- Fullscreen, leaving only the floating button, its satellites and the chips.
- Export as PNG.

## Building

Requirements: Android Studio (or a JDK 17+), Android SDK 37, min SDK 31.

```bash
git clone https://github.com/yighy/Pantograph.git
cd Pantograph
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. Release builds are signed through a local
`keystore.properties` (see `keystore.properties.example`); without it `assembleRelease` produces
an unsigned APK.

Tests:

```bash
./gradlew testDebugUnitTest
```

## Tech Stack

Kotlin, Jetpack Compose, Material 3, Room, DataStore, Coil.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
