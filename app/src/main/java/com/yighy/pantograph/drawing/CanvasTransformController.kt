package com.yighy.pantograph.drawing

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * How the canvas sits in the viewport: zoom, pan, rotation and fit-to-screen.
 *
 * Every transform pivots on the cursor rather than the screen centre, so the reticle stays put
 * while the canvas moves under it - that's the whole point of a cursor-driven canvas.
 */
class CanvasTransformController(
    private val session: DrawingSession,
    private val scope: CoroutineScope
) {
    /** Running fit-to-screen animation; cancelled as soon as the user grabs the canvas. */
    private var canvasAnimJob: Job? = null

    fun zoom(factor: Float) {
        canvasAnimJob?.cancel()
        session.update { state ->
            val oldScale = state.canvasScale
            val newScale = (oldScale * factor).coerceIn(0.1f, 15f)
            val center = Offset(state.canvasWidth / 2f, state.canvasHeight / 2f)
            val relPivot = state.cursorPosition - center
            val rotatedPivotOld = relPivot.rotate(state.canvasRotation) * oldScale
            val rotatedPivotNew = relPivot.rotate(state.canvasRotation) * newScale
            val diff = rotatedPivotNew - rotatedPivotOld
            state.copy(canvasScale = newScale, canvasOffset = state.canvasOffset - diff)
        }
    }

    fun transform(zoom: Float, pan: Offset, rotation: Float) {
        canvasAnimJob?.cancel()
        session.update { state ->
            val oldScale = state.canvasScale
            val newScale = (oldScale * zoom).coerceIn(0.01f, 25f)

            val oldRot = state.canvasRotation
            val newRot = (oldRot + rotation)

            val center = Offset(state.canvasWidth / 2f, state.canvasHeight / 2f)
            val relPivot = state.cursorPosition - center

            // Screen position of the pivot before transformation (relative to screen center)
            val screenPivotOld = relPivot.rotate(oldRot) * oldScale

            // Screen position of the pivot after scale/rotation change (relative to screen center)
            val screenPivotNew = relPivot.rotate(newRot) * newScale

            // Adjust offset to keep the screen position constant (compensating for the shift)
            val pivotAdjustment = screenPivotNew - screenPivotOld

            state.copy(
                canvasScale = newScale,
                canvasRotation = newRot % 360f,
                canvasOffset = state.canvasOffset - pivotAdjustment + pan
            )
        }
    }

    fun fitToScreen(screenWidth: Float, screenHeight: Float, animate: Boolean = false) {
        val state = session.value
        if (state.canvasWidth <= 0 || state.canvasHeight <= 0) return

        val scaleX = screenWidth / state.canvasWidth
        val scaleY = screenHeight / state.canvasHeight
        val targetScale = min(scaleX, scaleY) * 0.95f

        canvasAnimJob?.cancel()
        if (!animate) {
            session.update { it.copy(
                canvasScale = targetScale,
                canvasOffset = Offset.Zero,
                canvasRotation = 0f,
                fitToScreenTrigger = 0 // Reset trigger
            ) }
            return
        }

        val startScale = state.canvasScale
        val startOffset = state.canvasOffset
        val startRotation = state.canvasRotation
        // Rotate back along the shortest path (350° eases to 360°, not all the way back to 0°)
        var rotNorm = startRotation % 360f
        if (rotNorm > 180f) rotNorm -= 360f
        if (rotNorm < -180f) rotNorm += 360f
        val targetRotation = startRotation - rotNorm

        session.update { it.copy(fitToScreenTrigger = 0) }
        canvasAnimJob = scope.launch {
            // Time-driven loop: Animatable.animateTo needs a Compose MonotonicFrameClock,
            // which viewModelScope doesn't have (it crashes). The easing itself is pure math.
            val durationMs = 400L
            val startTime = android.os.SystemClock.uptimeMillis()
            while (true) {
                val fraction = ((android.os.SystemClock.uptimeMillis() - startTime).toFloat() / durationMs).coerceIn(0f, 1f)
                val t = FastOutSlowInEasing.transform(fraction)
                session.update { it.copy(
                    canvasScale = startScale + (targetScale - startScale) * t,
                    canvasOffset = startOffset * (1f - t),
                    canvasRotation = startRotation + (targetRotation - startRotation) * t
                ) }
                if (fraction >= 1f) break
                delay(16)
            }
            // Land exactly on the target, with rotation normalized to a clean 0
            session.update { it.copy(canvasScale = targetScale, canvasOffset = Offset.Zero, canvasRotation = 0f) }
        }
    }

    fun requestFitToScreen() {
        session.update { it.copy(fitToScreenTrigger = it.fitToScreenTrigger + 1) }
    }
}
