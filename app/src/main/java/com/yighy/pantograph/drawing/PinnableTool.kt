package com.yighy.pantograph.drawing

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.HighlightAlt
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Polyline
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The tools that can be pinned to the quick-access satellite.
 *
 * Only tools that flip a mode on and off qualify: the satellite is a single tap with no
 * follow-up UI of its own. One-shot actions and anything needing further choices stay in the
 * tools menu, where there is room to label them.
 *
 * Entry names are persisted, so renaming one silently unpins it for existing users.
 */
enum class PinnableTool(
    val label: String,
    val icon: ImageVector
) {
    Fill("Fill", Icons.Rounded.FormatColorFill),
    Gradient("Gradient", Icons.Rounded.Gradient),
    Lazy("Lazy", Icons.Rounded.Cable),
    Recoil("Recoil", Icons.Rounded.Replay),
    /**
     * Puts the raised cursor on Draw Sensitivity too, so the scale never changes under the
     * finger at pen-down.
     *
     * A toggle rather than a permanent behaviour because it is paid for in finger travel:
     * crossing the canvas at 0.2x is a lot of wiping, and that is a price worth paying for the
     * detail work and not for getting to it.
     */
    Fine("Fine", Icons.Rounded.CenterFocusStrong),
    /** Opens the magnified window. Its zoom is on its chip, like every tool's values. */
    Loupe("Loupe", Icons.Rounded.ZoomIn),
    /**
     * Pinnable like the rest, which is what gives it a way *out*: the readout row draws every
     * armed tool with a cross on it, and that cross is the only control left on screen.
     */
    Fullscreen("Full", Icons.Rounded.Fullscreen),
    Lasso("Lasso", Icons.Rounded.Polyline),
    Rect("Rect", Icons.Rounded.HighlightAlt),
    Wand("Wand", Icons.Rounded.AutoFixHigh),
    ColorSelect("Color", Icons.Rounded.Palette),

    /**
     * The odd one out: arming this opens an editing session rather than just changing what the
     * pen paints, and it has a toolbar panel of its own to finish or abandon the curve. It
     * qualifies anyway - the satellite tap arms and disarms it like any other mode, and
     * disarming throws the pending curve away, which is what leaving a tool should do.
     */
    Path("Path", Icons.Rounded.Timeline);

    /**
     * The values this tool carries, adjusted by dragging on its chip - the only place they
     * live. Empty for a tool with nothing to set.
     *
     * Wand and colour select read the same tolerance and expand as the fill. They were given
     * none of the fill's handling for a long time, which is how arming either from a satellite
     * came to leave its settings in a panel it never asked to open.
     */
    val params: List<ToolParam>
        get() = when (this) {
            Lazy -> listOf(ToolParams.LazyRadius)
            Loupe -> listOf(ToolParams.LoupeZoom)
            Fill, Wand, ColorSelect -> listOf(ToolParams.Tolerance, ToolParams.Expand)
            else -> emptyList()
        }

    fun isActive(state: DrawingState): Boolean = when (this) {
        Fill -> state.drawingMode is DrawingMode.BucketFill
        Gradient -> state.drawingMode is DrawingMode.Gradient
        Lazy -> state.isLazyModeActive
        Recoil -> state.isRecoilActive
        Fine -> state.isFineCursor
        Loupe -> state.isLoupeActive
        Fullscreen -> state.isFullscreen
        Lasso -> state.drawingMode is DrawingMode.SelectLasso
        Rect -> state.drawingMode is DrawingMode.SelectRect
        Wand -> state.drawingMode is DrawingMode.SelectWand
        ColorSelect -> state.drawingMode is DrawingMode.SelectColor
        Path -> state.drawingMode is DrawingMode.Path
    }

    /**
     * Mirrors what the matching entry in the tools menu does, including its toggle-off.
     *
     * Arming a tool with values used to open the Settings panel as well, because that was where
     * the values had their sliders. They are on the chip now, which appears the moment the tool
     * is armed, so there is no panel to open - and one opening onto nothing about the tool
     * would be a panel in the way.
     */
    fun toggle(viewModel: DrawingViewModel) {
        val wasActive = isActive(viewModel.uiState.value)
        when (this) {
            Fill -> viewModel.setBucketFillMode()
            Lazy -> viewModel.toggleLazyMode()
            Recoil -> viewModel.toggleRecoil()
            Fine -> viewModel.toggleFineCursor()
            Loupe -> viewModel.toggleLoupe()
            Fullscreen -> viewModel.toggleFullscreen()
            Gradient -> viewModel.setDrawingMode(if (wasActive) DrawingMode.Freehand else DrawingMode.Gradient)
            Lasso -> viewModel.setDrawingMode(if (wasActive) DrawingMode.Freehand else DrawingMode.SelectLasso)
            Rect -> viewModel.setDrawingMode(if (wasActive) DrawingMode.Freehand else DrawingMode.SelectRect)
            Wand -> viewModel.setDrawingMode(if (wasActive) DrawingMode.Freehand else DrawingMode.SelectWand)
            ColorSelect -> viewModel.setDrawingMode(if (wasActive) DrawingMode.Freehand else DrawingMode.SelectColor)
            Path -> viewModel.setDrawingMode(if (wasActive) DrawingMode.Freehand else DrawingMode.Path)
        }
    }

    companion object {

        /**
         * Four is the ceiling because the satellite picks between them on a blind sideways
         * drag, the same way the brush gate picks between its four parameters.
         */
        const val MAX_PINNED = 4

        /** Unknown or absent names read as an empty slot rather than throwing. */
        fun fromName(name: String?): PinnableTool? =
            name?.let { stored -> entries.firstOrNull { it.name == stored } }

        /**
         * Names are stored comma-separated. Unknown entries are dropped rather than throwing,
         * which also means a value written by an older build - a single bare name - still
         * reads correctly as a one-tool list.
         */
        fun fromNames(stored: String?): List<PinnableTool> =
            stored?.split(',')
                ?.mapNotNull { fromName(it.trim()) }
                ?.distinct()
                ?.take(MAX_PINNED)
                .orEmpty()

        fun toNames(tools: List<PinnableTool>): String? =
            tools.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }

        /**
         * Adds [tool] if absent, removes it if already there. At the ceiling the oldest pin
         * makes way, so the gesture always does something visible instead of silently
         * refusing once four slots are full.
         */
        fun togglePin(current: List<PinnableTool>, tool: PinnableTool): List<PinnableTool> =
            if (current.contains(tool)) {
                current - tool
            } else {
                (current + tool).takeLast(MAX_PINNED)
            }
    }
}
