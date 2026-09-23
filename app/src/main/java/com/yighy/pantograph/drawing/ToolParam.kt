package com.yighy.pantograph.drawing

/**
 * One value an armed tool carries: its range, how to read it, how to set it, how to show it.
 *
 * Declared once and read by everything that shows or sets the value - the tool's chip, which
 * adjusts it by dragging, and the settings panel, which gives it a slider. Before this the
 * panel spelled each range out at its own call site, and a second surface would have meant a
 * second copy of every range to keep in step with the first.
 */
class ToolParam(
    /** As the settings panel labels it. */
    val label: String,
    /**
     * As a chip labels it, when the chip carries more than one value and has to say which is
     * which. A chip with a single value shows the value alone - the tool's name beside it is
     * already the label.
     */
    val short: String? = null,
    val min: Float,
    val max: Float,
    val read: (DrawingState) -> Float,
    val write: (DrawingViewModel, Float) -> Unit,
    val format: (Float) -> String
) {
    /**
     * The longest thing [format] can produce, for reserving width.
     *
     * The chip that shows this value sits in a row that reflows as its members change size, and
     * a chip growing by a digit under a dragging finger could be pushed onto the next line - at
     * which point the finger is somewhere else on it and the drag reads a jump that never
     * happened. Holding the width at the widest the value gets rules that out.
     */
    val widest: String get() = listOf(format(min), format(max)).maxBy { it.length }
}

/** The parameters themselves. Tools name theirs through [PinnableTool.params]. */
object ToolParams {

    val LazyRadius = ToolParam(
        label = "Lazy Radius",
        min = 10f,
        max = 500f,
        read = { it.lazyRadius },
        write = { vm, v -> vm.setLazyRadius(v) },
        format = { "${it.toInt()}px" }
    )

    val LoupeZoom = ToolParam(
        label = "Loupe Zoom",
        min = 2f,
        max = 12f,
        read = { it.loupeZoom },
        write = { vm, v -> vm.setLoupeZoom(v) },
        format = { "${it.toInt()}x" }
    )

    // Shared by the fill and both colour-based selections: one tolerance, one expand, whichever
    // of the three is armed. They were always the same two stored values.
    val Tolerance = ToolParam(
        label = "Tolerance",
        short = "Tol",
        min = 0f,
        max = 200f,
        read = { it.fillTolerance },
        write = { vm, v -> vm.setFillTolerance(v) },
        format = { "${it.toInt()}" }
    )

    val Expand = ToolParam(
        label = "Expand",
        short = "Exp",
        min = 0f,
        max = 8f,
        read = { it.fillGrow },
        write = { vm, v -> vm.setFillGrow(v) },
        format = { "${it.toInt()} px" }
    )
}
