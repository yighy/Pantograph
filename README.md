<p align=center><image src="app/src/main/ic_launcher-playstore.png" height="200" /></p>
<h1 align="center">Pantograph</h1>
<p align="center">A cursor-based drawing app for Android</p>
<p align="center">
  <a href="https://github.com/yighy/pantograph/releases">
    <img src="https://img.shields.io/github/v/release/yighy/pantograph?style=for-the-badge&logo=GitHub&include_prereleases" alt="GitHub release" />
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

Pantograph is a drawing app with a cursor control, named after the 1821 drafting instrument that reproduced a drawing elsewhere as you traced it.
The app allows you to draw by driving a **precision cursor** on the screen. Your finger stays off the artwork and the reticle does the drawing, so no more fingers hiding your line endings.

## 🎯 How it works

- **Drag anywhere** on the canvas to move the cursor — the stroke happens at the reticle, not under your finger.
- **Tap** (or **hold** the floating button) to lower or raise the pen.
- **Two fingers** to pan, pinch-zoom and rotate the canvas around the cursor.
- Adjustable cursor sensitivity and stroke smoothing.

### Satellite gates

Four small pills sit around the floating button and are worked without looking away from the canvas. They fade out while you're drawing so they can't be hit mid-stroke, and reposition themselves as you drag the button around — flipping to the opposite side, or onto a free flank, rather than running off the screen.

- **Top pill — colour.** Hold and drag: sideways picks between hue, saturation and brightness; up and down sets the level. The fourth column is the eyedropper, which has no level and arms on release. The pill itself carries a swatch of the loaded colour.
- **Right pill — brush levels.** Hold and drag: sideways picks between size, opacity, flow and softness; up and down sets the level. A live readout follows your hand, staying clear of it.
- **Bottom pill — mode and shortcuts.** Hold and drag towards one of four cells, applied on release: top-left toggles freehand and straight line, top-right toggles the eraser, bottom-left undoes, bottom-right flips back to the brush you were using before this one. The two mode cells are independent, so switching shape keeps the eraser as it was and the other way round.
- **Third pill — quick tools.** Hold and drag towards one of four cells, same as the mode pill. Long-press any entry in the tools menu to pin or unpin it — pinning a fifth drops the oldest.

The gates start from a dead zone, so nothing is selected until the finger commits to a direction and letting go without moving does nothing. They tick haptically on each step, so you can feel where you are with your finger covering the readout. The brush gate responds finely near the anchor and faster at full stretch; its travel is tunable in Settings.

Four is the ceiling on the quick-tool pill because that is what a quadrant holds — and a direction costs no travel, so a cell stays reachable however the button is parked. The rest of the tools stay in the menu, where they have labels.

## ✨ Features

**Brush engine**
- Size, opacity, flow, softness, smoothing, spacing and rotation.
- Jitter on size, rotation, scatter and flow — each symmetric, so raising one varies the
  stroke without shifting its average.
- Follow direction as an amount rather than a switch: the stamp can lean into the path
  instead of only ever locking to it.
- Velocity dynamics: stroke speed can drive size, flow and scatter, up to ×9 either way.
- Size multiplier, 0x to 16x, taking a brush past the size slider's 300px ceiling. It scales
  the stamp, the spacing between stamps and the scatter amplitudes together, so a 4x brush is
  the same brush drawn larger rather than a different one.
- Custom brush tips and textures.
- Saveable presets, organised into folders, each shown in the list as a real stroke drawn by
  the engine itself rather than an illustration of one.

**Tools**
- Freehand, straight line, eraser in both modes, bucket fill with tolerance, linear gradient.
- Lazy/rope mode for extra-smooth curves.
- Selection: lasso, rectangle, magic wand and color select — move, transform, duplicate, delete, invert.
- A lifted selection survives a layer change, so it can be pasted into another layer.
- Active selections clip every tool (draw inside the selection only).

**Canvas & workflow**
- Brush presets live in a panel under the toolbar: tap to load one, and edit, delete or file
  it into a folder. The Brush Studio opens on the preset you picked — and on a brand-new one as
  soon as you name it — and warns before closing on unsaved changes.
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
git clone https://github.com/yighy/pantograph.git
cd pantograph
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

Release builds are signed through a local `keystore.properties` file (see `keystore.properties.example`). Without it, `assembleRelease` produces an unsigned APK.

### Tests

```bash
./gradlew testDebugUnitTest
```

The parts where a mistake is invisible until it bites are kept free of Android types, so plain
JVM tests can cover them:

- `GateMath` — dead zone, response curve and column hysteresis behind the satellite gates.
- `SatelliteLayout` — where the four pills land for any button position and screen size,
  including the sweep asserting that no two ever overlap.
- `ColourGate` — hue/saturation/brightness both ways, and that desaturating to grey and back
  returns the colour it started from rather than losing the hue on the way through.
- `SelectionCommitPolicy` — which layers need a history entry when a floating selection lands.
- `PinnableTool` — parsing and toggling the persisted quick-tool pins, including the value
  format written by earlier builds.
- `BrushConfig` — what counts as an unsaved edit, checked by reflection so a brush parameter
  added later cannot quietly escape the comparison.
- `BrushJitter` — that a jitter varies a value without moving its average.
- `StampRaster` — that an ordinary brush is still rasterised at its own size and drawn
  unscaled, and that a brush at the top of the size multiplier is capped rather than asking
  for a 92MB bitmap.
- `SizeMultiplierScale` — that the multiplier's detent lands on exactly 1.00x without
  swallowing the values on either side of it.
- `BrushPreviewScale` — fitting a 300px brush onto a list thumbnail without it swallowing the
  strip or squeezing the fine brushes into identical hairlines.
- `FloodFill` — that the bucket stops at a line, spreads around one that doesn't reach the edge,
  and stays inside an active selection.
- `MagicSelect` — what the wand grabs versus colour select, and the transparent-pixel rule.
- `MirroredBrushSetting` — that every brush setting stored both on a preset and as a global
  preference writes the state field it claims and no other, so a preference cannot be restored
  over a neighbouring setting.
- `SchemaPolicy` — when the database may still fall back to wiping itself, and when a real
  migration becomes mandatory.

## 👩‍💻 Tech Stack

- **Kotlin**: 100% Kotlin codebase, coroutines & flows throughout
- **Jetpack Compose**: the entire UI, no XML layouts
- **Material 3**: expressive theming with dynamic color (Material You); shared shape, type and spring-based motion tokens
- **Room**: local storage for projects, layers and brush presets
- **DataStore**: user preferences and app settings
- **Coil**: image loading for thumbnails and reference images

## license

This project is licensed under the [GNU General Public License v3.0](LICENSE).
