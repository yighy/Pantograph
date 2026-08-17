package com.yighy.paintcursor.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.yighy.paintcursor.data.LayerEntity

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
}

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
)
