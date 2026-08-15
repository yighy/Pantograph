package com.yighy.paintcursor.drawing

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.HighlightAlt
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Polyline
import androidx.compose.material.icons.rounded.Cable
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
    val icon: ImageVector,
    /**
     * Whether switching this tool on should reveal the toolbar's Settings panel, because that
     * is where its own controls live - tolerance for the fill, radius for lazy.
     */
    val opensSettings: Boolean = false
) {
    Fill("Fill", Icons.Rounded.FormatColorFill, opensSettings = true),
    Gradient("Gradient", Icons.Rounded.Gradient),
    Lazy("Lazy", Icons.Rounded.Cable, opensSettings = true),
    Lasso("Lasso", Icons.Rounded.Polyline),
    Rect("Rect", Icons.Rounded.HighlightAlt),
    Wand("Wand", Icons.Rounded.AutoFixHigh),
    ColorSelect("Color", Icons.Rounded.Palette);

    fun isActive(state: DrawingState): Boolean = when (this) {
        Fill -> state.drawingMode is DrawingMode.BucketFill
        Gradient -> state.drawingMode is DrawingMode.Gradient
        Lazy -> state.isLazyModeActive
        Lasso -> state.drawingMode is DrawingMode.SelectLasso
        Rect -> state.drawingMode is DrawingMode.SelectRect
        Wand -> state.drawingMode is DrawingMode.SelectWand
        ColorSelect -> state.drawingMode is DrawingMode.SelectColor
    }

    /**
     * Mirrors what the matching entry in the tools menu does, including its toggle-off and the
     * panel it opens. The callback is passed in because only the screen knows which panel is
     * showing - without it the satellite armed a tool and left its settings buried, while the
     * same tool tapped from the menu revealed them.
     */
    fun toggle(viewModel: DrawingViewModel, onRequestSettingsPanel: () -> Unit = {}) {
        val wasActive = isActive(viewModel.uiState.value)
        // Read before the call: these are toggles, and switching one *off* must not pop open
        // the panel holding its options.
        if (!wasActive && opensSettings) onRequestSettingsPanel()
        when (this) {
            Fill -> viewModel.setBucketFillMode()
            Lazy -> viewModel.toggleLazyMode()
            Gradient -> viewModel.setDrawingMode(if (wasActive) DrawingMode.Freehand else DrawingMode.Gradient)
            Lasso -> viewModel.setDrawingMode(if (wasActive) DrawingMode.Freehand else DrawingMode.SelectLasso)
            Rect -> viewModel.setDrawingMode(if (wasActive) DrawingMode.Freehand else DrawingMode.SelectRect)
            Wand -> viewModel.setDrawingMode(if (wasActive) DrawingMode.Freehand else DrawingMode.SelectWand)
            ColorSelect -> viewModel.setDrawingMode(if (wasActive) DrawingMode.Freehand else DrawingMode.SelectColor)
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
