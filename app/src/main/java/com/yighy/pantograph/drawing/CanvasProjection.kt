package com.yighy.pantograph.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Where a canvas point lands in the viewport.
 *
 * The canvas box is centred in the viewport, its graphicsLayer scales and rotates it about its
 * own centre, and the canvas offset then moves the result. Two things need this mapping - the
 * edge arrow, to know the cursor has left the viewport, and the loupe, to know it is standing
 * where the brush is - and two copies of it were one edit away from disagreeing.
 */
fun canvasToScreen(
    point: Offset,
    canvasWidth: Int,
    canvasHeight: Int,
    viewport: Size,
    canvasScale: Float,
    canvasOffset: Offset,
    canvasRotation: Float
): Offset {
    val rel = point - Offset(canvasWidth / 2f, canvasHeight / 2f)
    return Offset(viewport.width / 2f, viewport.height / 2f) +
        canvasOffset +
        rel.rotate(canvasRotation) * canvasScale
}
