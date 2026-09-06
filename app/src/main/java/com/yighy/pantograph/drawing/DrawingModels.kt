package com.yighy.pantograph.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.yighy.pantograph.data.LayerEntity

sealed class DrawingMode {
    object Freehand : DrawingMode()
    object StraightLine : DrawingMode()
    object Eraser : DrawingMode()
    object StraightLineEraser : DrawingMode()
    object BucketFill : DrawingMode()
    object Gradient : DrawingMode()
    object SelectLasso : DrawingMode()
    object SelectRect : DrawingMode()
    object SelectWand : DrawingMode()
    object SelectColor : DrawingMode()

    /**
     * Places points instead of painting: the pen adds one at the cursor, the curve through them
     * is previewed live, and nothing lands on the layer until the path is committed.
     */
    object Path : DrawingMode()
}

/**
 * One point of a path tool curve.
 *
 * @param isCorner true to break the curve here into a kink. The curve is smooth by default,
 * which is the common case; sharpening a point is the exception you ask for.
 */
data class PathPoint(val position: Offset, val isCorner: Boolean = false)

/** True for every tool whose pen gesture manipulates the selection instead of painting. */
fun DrawingMode.isSelectionTool(): Boolean =
    this is DrawingMode.SelectLasso || this is DrawingMode.SelectRect ||
    this is DrawingMode.SelectWand || this is DrawingMode.SelectColor

data class DrawingPath(
    val points: List<Offset>,
    val color: Color,
    val width: Float,
    val mode: DrawingMode = DrawingMode.Freehand,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    val softness: Float = 0f,
    val spacing: Float = 0.1f,
    val rotation: Float = 0f
)

data class ReferenceImage(
    val uri: String,
    val offset: Offset = Offset.Zero,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val aspectRatio: Float = 1f,
    val bitmap: android.graphics.Bitmap? = null
)

data class BrushConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "New Brush",
    val size: Float = 20f,
    val softness: Float = 0f,
    val opacity: Float = 1.0f,
    val flow: Float = 1.0f,
    val spacing: Float = 0.1f,
    val smoothing: Float = 0.5f,
    val rotation: Float = 0f,
    val rotationJitter: Float = 0f,
    val sizeJitter: Float = 0f,
    val scatterJitter: Float = 0f,
    val flowJitter: Float = 0f,
    val rotationFollow: Float = 0f,
    val tipUri: String? = null,
    val textureUri: String? = null,
    val velocityEnabled: Boolean = false,
    val velocitySize: Float = 0f,
    val velocityFlow: Float = 0f,
    val velocityScatter: Float = 0f,
    /** Scales [size] past the ceiling of the size slider. 1x paints at the size as set. */
    val sizeMultiplier: Float = 1f,
    /** Which folder holds this preset; null means it sits loose at the top level. */
    val folderId: Long? = null
) {
    /**
     * True when [other] would paint identically, ignoring which preset each one is.
     *
     * Identity and filing - id, name, folder - are normalised away: moving a preset between
     * folders changes nothing about how it paints, and counting it as an unsaved edit would
     * light up the Save button for a change that has already been persisted.
     *
     * Compares the rest by leaning on data-class equality rather than listing
     * the fields: a new brush parameter is then covered the moment it is added. Spelling the
     * comparison out by hand is how a "no unsaved changes" badge quietly starts lying after
     * someone adds a setting and forgets this spot.
     */
    fun paintsSameAs(other: BrushConfig): Boolean =
        copy(id = other.id, name = other.name, folderId = other.folderId) == other
}

/** A named grouping of brush presets. */
data class BrushFolder(val id: Long, val name: String)

/**
 * The size the brush actually paints at: what the slider says, scaled by the multiplier.
 *
 * Everything that depends on brush size reads this rather than [DrawingState.selectedWidth] -
 * the stamp raster, the spacing between stamps and the scatter amplitudes alike. A multiplier
 * that only reached the drawn rect would give a stamp upscaled from a small bitmap, stamps
 * spaced for a brush a sixteenth of the size, and scatter that stayed put while the mark grew.
 */
val DrawingState.effectiveWidth: Float get() = selectedWidth * sizeMultiplier

/** [effectiveWidth] for a saved preset rather than the brush in hand. */
val BrushConfig.effectiveSize: Float get() = size * sizeMultiplier

/** The brush currently in hand, in the same shape as a saved preset so the two can be compared. */
fun DrawingState.toBrushConfig(id: String = "", name: String = ""): BrushConfig = BrushConfig(
    id = id,
    name = name,
    size = selectedWidth,
    softness = brushSoftness,
    opacity = brushOpacity,
    flow = brushFlow,
    spacing = brushSpacing,
    smoothing = brushSmoothing,
    rotation = brushRotation,
    rotationJitter = brushRotationJitter,
    sizeJitter = sizeJitter,
    scatterJitter = scatterJitter,
    flowJitter = flowJitter,
    rotationFollow = rotationFollow,
    tipUri = brushTipUri,
    textureUri = brushTextureUri,
    velocityEnabled = velocityEnabled,
    velocitySize = velocitySizeAmount,
    velocityFlow = velocityFlowAmount,
    velocityScatter = velocityScatterAmount,
    sizeMultiplier = sizeMultiplier
)

data class DrawingState(
    val projectId: Long = -1,
    val projectName: String = "",
    val canvasWidth: Int = 1080,
    val canvasHeight: Int = 1920,
    val layers: List<LayerEntity> = emptyList(),
    val activeLayerId: Long = -1,
    val layerBitmaps: Map<Long, android.graphics.Bitmap> = emptyMap(),
    val strokeBitmap: android.graphics.Bitmap? = null,
    /**
     * True until the layer bitmaps have been decoded and published. It starts true because the
     * screen composes before any of them exist - without it, a project still reading itself off
     * disk and a genuinely empty one drew the same picture, which is what made an open look
     * blank for a moment.
     */
    val isLoading: Boolean = true,
    /**
     * The project's own home-grid thumbnail, held only for the length of [isLoading] and
     * dropped as soon as the real layers land. Standing in for them keeps the project
     * recognisable while its full-size PNGs decode.
     */
    val loadingPreview: android.graphics.Bitmap? = null,
    
    // Brush State
    val selectedColor: Color = Color.Black,
    val selectedWidth: Float = 20f,
    val brushSoftness: Float = 0f,
    val brushOpacity: Float = 1.0f,
    val brushFlow: Float = 1.0f,
    val brushSmoothing: Float = 0.5f,
    val brushSpacing: Float = 0.1f,
    val brushRotation: Float = 0f,
    val brushRotationJitter: Float = 0f,
    val sizeJitter: Float = 0f,
    // Random offset per stamp, as a fraction of brush width, in any direction.
    // Unlike velocity scatter this is constant - it does not care how fast you draw.
    val scatterJitter: Float = 0f,
    /** Per-stamp variation in flow, so density breathes along a stroke. */
    val flowJitter: Float = 0f,
    /** 0 = the stamp keeps its fixed angle, 1 = it fully follows the path. */
    val rotationFollow: Float = 0f,
    val brushTipUri: String? = null,
    val brushTipBitmap: android.graphics.Bitmap? = null,
    val brushTextureUri: String? = null,
    val brushTextureBitmap: android.graphics.Bitmap? = null,
    // Canvas-anchored paper grain: texture luminance converted to an alpha mask,
    // multiplied over the whole stroke (DST_IN) at composition time
    val brushTextureMask: android.graphics.Bitmap? = null,
    // Velocity dynamics. All amounts are signed (-2..2). Size/flow: positive = fast strokes
    // get thicker/denser, negative = thinner/lighter. The scale is multiplicative (3^amount),
    // so the ends of the range are x9 and /9 rather than merely x2 - enough for a brush that
    // genuinely transforms with speed, which the old x3 ceiling could not reach.
    // Scatter: positive = stamps scatter on fast strokes, negative = on slow strokes.
    val velocityEnabled: Boolean = false,
    val velocitySizeAmount: Float = 0f,
    val velocityFlowAmount: Float = 0f,
    val velocityScatterAmount: Float = 0f,
    /** Scales [selectedWidth] past the slider's 300px ceiling. 1x paints at the size as set. */
    val sizeMultiplier: Float = 1f,
    
    val selectedCustomBrushId: String? = null,
    /**
     * The preset in hand before the current one, for the mode gate's swap cell.
     *
     * Session-scoped on purpose: it is a "go back" for the brush you were just using, and
     * restoring it from a previous run would offer a swap the user has no memory of.
     */
    val previousBrushId: String? = null,
    val customBrushes: List<BrushConfig> = emptyList(),
    val brushFolders: List<BrushFolder> = emptyList(),
    /** Bumped when preset tips/textures finish decoding, so thumbnails know to re-render. */
    val brushAssetsVersion: Int = 0,
    
    val drawingMode: DrawingMode = DrawingMode.Freehand,
    val isPenDown: Boolean = false,
    val currentPath: DrawingPath? = null,
    
    val cursorPosition: Offset = Offset.Zero,
    /**
     * Whether the cursor springs back to where a stroke began when the pen comes up.
     *
     * A constraint rather than a tool, like lazy mode: it modifies whatever brush is in hand
     * instead of replacing it, so it is a flag of its own rather than a [DrawingMode].
     */
    val isRecoilActive: Boolean = false,
    /**
     * Everything but the button, its satellites and the readout chips is hidden.
     *
     * Not persisted, deliberately: reopening a project already stripped of its controls, with
     * no memory of having asked for it, is a worse first second than an extra tap.
     */
    val isFullscreen: Boolean = false,
    /** The path tool's points, in the order they were placed. Empty unless one is being built. */
    val pathPoints: List<PathPoint> = emptyList(),
    /** Index of the path point riding the cursor, or -1. */
    val grabbedPathPoint: Int = -1,
    /**
     * How many points the press in progress created, so abandoning it can take them all back.
     * Two for the very first press, which lays an anchor and starts the point after it.
     */
    val pathPointsFromPress: Int = 0,
    /** Whether the path runs back into itself, making a loop rather than a line. */
    val pathClosed: Boolean = false,
    /** Mirrored from preferences; read by [HistoryCoordinator] when an entry carries an anchor. */
    val undoRestoresCursor: Boolean = true,
    /** Mirrored from preferences; decides whether an undone stroke is kept as a trace. */
    val keepUndoneStrokes: Boolean = false,
    val brushPosition: Offset = Offset.Zero,
    val cursorSensitivity: Float = 0.6f,
    val cursorThickness: Float = 1.0f,
    val fabDragThreshold: Float = 100f,
    // Scales the right satellite's vertical drag response: below 1 = longer travel + wider
    // dead zone (safer, coarser), above 1 = shorter travel + narrower dead zone (twitchier)
    val satelliteGateSensitivity: Float = 1f,
    /** Tools on the quick-access satellite, first is the one a plain tap fires. */
    val pinnedTools: List<PinnableTool> = emptyList(),

    val canvasScale: Float = 1f,
    val canvasOffset: Offset = Offset.Zero,
    val canvasRotation: Float = 0f,
    
    val renderVersion: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val historyLimit: Int = 5,
    val fitToScreenTrigger: Int = 0,
    val colorHistory: List<Color> = emptyList(),
    val isColorPickerSliderMode: Boolean = false,
    val isEyeDropperMode: Boolean = false,
    val fillTolerance: Float = 10f,
    val isLazyModeActive: Boolean = false,
    val lazyRadius: Float = 50f,
    val showOffscreenCursorArrow: Boolean = false,
    val referenceImage: ReferenceImage? = null,

    // Selection tools. The source of truth is selectionMask: a canvas-sized bitmap whose
    // alpha marks the selected area (works for lasso/rect polygons, wand/color pixel
    // selections and inversion alike). While closed, it also clips every drawing tool.
    // selectionPoints is only kept for the marching-ants outline of lasso/rect shapes.
    // While a selection is floating, its pixels live in floatingBitmap and are
    // rendered with the offset/scale/rotation below until committed back to the layer.
    val selectionPoints: List<Offset> = emptyList(),
    val isSelectionClosed: Boolean = false,
    val selectionMask: android.graphics.Bitmap? = null,
    val floatingBitmap: android.graphics.Bitmap? = null,
    val floatingOrigin: Offset = Offset.Zero,
    val floatingOffset: Offset = Offset.Zero,
    val floatingScale: Float = 1f,
    val floatingRotation: Float = 0f
) {
    /**
     * The layers undo/redo is allowed to see: the drawing, without the trace surface.
     *
     * History prunes anything an entry does not name, and the trace layer is by design named
     * by none of them - it is not part of the picture and is not built by any operation that
     * gets recorded. Handing it over would have every undo delete the traces it just made.
     */
    val drawingLayers: List<LayerEntity> get() = layers.filter { !it.isTrace }

    /**
     * Whether there is a path on screen waiting to be committed.
     *
     * The canvas needs this because a pending path draws through the same stroke buffer a live
     * stroke does, but without the pen ever going down - so isPenDown alone would leave the
     * preview invisible.
     */
    val hasPendingPath: Boolean get() = drawingMode is DrawingMode.Path && pathPoints.size >= 2

    /**
     * Whether the button is being held for something, stroke or not.
     *
     * The path tool holds the pen without ever putting it down - it is placing a point, not
     * painting - but everything about how the button *looks and behaves while held* should be
     * the same either way: it presses in, the satellites get out of the way, and the gates stop
     * accepting a stray second finger. What stays keyed to isPenDown is the drawing itself.
     */
    val isPenEngaged: Boolean get() = isPenDown || grabbedPathPoint >= 0

    /**
     * Index of the path point the cursor is close enough to pick up, or -1.
     *
     * Derived rather than stored: it is a function of where the cursor is, and a copy kept in
     * the state would be one more thing able to disagree with the cursor's actual position.
     */
    val hoveredPathPoint: Int get() =
        if (drawingMode !is DrawingMode.Path) -1
        else PathGeometry.nearestIndex(
            pathPoints, brushPosition, PathGeometry.GRAB_RADIUS / canvasScale.coerceAtLeast(0.01f)
        )

    /**
     * True while the end being held is near enough to the other end to join them on release.
     *
     * Either end will do: dragging the start onto the finish reads exactly like dragging the
     * finish onto the start, and refusing one of them would only be a rule to discover.
     */
    val pathClosingCandidate: Boolean get() {
        if (drawingMode !is DrawingMode.Path || pathClosed || pathPoints.size < 3) return false
        val last = pathPoints.lastIndex
        if (grabbedPathPoint != 0 && grabbedPathPoint != last) return false
        val other = if (grabbedPathPoint == 0) pathPoints[last] else pathPoints[0]
        val reach = PathGeometry.GRAB_RADIUS / canvasScale.coerceAtLeast(0.01f)
        return (pathPoints[grabbedPathPoint].position - other.position).getDistance() <= reach
    }

    /** Whether the point under the cursor is sharp, or null when there is none. */
    val pathPointUnderCursorIsCorner: Boolean? get() {
        val index = if (grabbedPathPoint >= 0) grabbedPathPoint else hoveredPathPoint
        return pathPoints.getOrNull(index)?.isCorner
    }
}
