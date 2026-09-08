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

## How it works

- **Drag anywhere** on the canvas to move the cursor — the stroke happens at the reticle, not under your finger.
- **Tap** on the screen or **hold/unhold** the floating button, to lower/raise the pen.
- **Two fingers** to pan, pinch-zoom and rotate the canvas around the cursor.
- Adjustable cursor sensitivity.

### Satellite gates

Four pills orbit the floating button, held and dragged like a gear stick so you never look away
from the canvas. They fade out while you draw and reposition themselves as you move the button
around the screen.

- **Top — colour.** Hue, saturation, brightness, and the eyedropper.
- **Right — brush levels.** Size, opacity, flow, softness, with a live readout beside your hand.
- **Bottom — mode and shortcuts.** Freehand/straight line, eraser on/off, undo, and a flip back to your previous brush.
- **Left — quick tools.** Four tools of your choosing; long-press an entry in the tools menu to pin it.

Each starts from a dead zone, so letting go without moving does nothing, and ticks haptically
as you cross a threshold.

## Features

**Brush engine**
- Size, opacity, flow, softness, smoothing, spacing and rotation.
- Round or square tip, squashed by a ratio into a nib or a flat brush — which is what gives rotation and follow-direction something to turn.
- Anti-aliasing off for a hard, stepped edge.
- Symmetric jitter on size, rotation, scatter and flow, and on hue, saturation and value.
- Smudge: paint with the colour already on the layer instead of your own.
- Velocity dynamics: stroke speed drives size, flow and scatter.
- Custom brush tips and textures.
- The whole brush follows you between projects; only the colour stays with the drawing.
- Presets in folders, edited in a Brush Studio that previews each as a real stroke drawn by the engine.
- Ships with nine starter brushes — liner, ink, nib, pencil, chalk, marker, airbrush, stipple and smudge — each showing a different part of the engine.

**Tools**
- Freehand, straight line, eraser, bucket fill, linear gradient.
- Path: place points and bend the curve running through them. Hold the button to steer a point before it settles, sharpen one to turn a corner, or join the two ends into a loop.
- Bucket fill reaches under the antialiased edge that stopped it, so no unfilled hem is left hugging the outline.
- Lazy/rope mode for extra-smooth curves.
- Recoil: the cursor springs back to where each stroke began, so the next mark is offset from somewhere meaningful.
- Fine: Draw Sensitivity applies to the raised pen too, so the scale never changes under the finger and the cursor can be placed as precisely as it draws.
- Selection: lasso, rectangle, magic wand, colour select — move, transform, duplicate, invert.
- A lifted selection survives a layer change, so it can be pasted into another layer.
- Active selections clip every tool.

**Canvas & workflow**
- Layers: reorder by drag & drop, opacity, visibility, duplicate, merge down, rename.
- Lock a layer to protect its pixels: the pen, clearing, merging and deleting are refused, while hiding, renaming and reordering carry on.
- Full undo/redo history.
- Optionally, undo walks the cursor back to where the undone stroke started, and keeps the stroke itself on a faint Traces layer to redraw over.
- Import an image as a new layer, positioned before it is applied.
- Floating reference image window — movable, resizable, colour-pickable.
- Eyedropper, colour history, HSB picker.
- Fullscreen: everything goes but the floating button, its satellites and the state chips — which is still a complete set of controls.
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

### Tests

```bash
./gradlew testDebugUnitTest
```

The parts where a mistake stays invisible until it bites — gate maths, satellite placement,
colour conversion, flood fill, the brush scales — are deliberately free of Android types so
plain JVM tests can cover them. Each test class says at the top which failure it exists to
catch.

## Tech Stack

- **Kotlin**: 100% Kotlin codebase, coroutines & flows throughout
- **Jetpack Compose**: the entire UI, no XML layouts
- **Material 3**: expressive theming with dynamic color (Material You); shared shape, type and spring-based motion tokens
- **Room**: local storage for projects, layers and brush presets
- **DataStore**: user preferences and app settings
- **Coil**: image loading for thumbnails and reference images

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
