package com.yighy.pantograph.drawing

import androidx.compose.ui.geometry.Offset

/**
 * The path tool: points placed, moved, sharpened and joined with the pen, then stamped onto the
 * active layer as a single stroke.
 *
 * The pen never actually goes down for it. Pressing picks up a point and releasing puts it down,
 * so isPenDown stays false - which keeps the satellites live and the canvas pannable while a path
 * is open, all of which you want, since building one takes a while.
 */
class PathController(
    private val session: DrawingSession,
    private val engine: StrokeEngine,
    private val persistence: ProjectPersistence,
    /**
     * Lands what the engine holds on the active layer the way a finished stroke lands: one
     * history entry, the composite, and the trace undo leaves behind. The view model's, since
     * every stroke goes through it.
     */
    private val landStroke: (DrawingState) -> Unit
) {

    /**
     * Pressing the pen picks up the point under the cursor, or makes one there and picks that
     * up. Releasing puts it down.
     *
     * The point rides the cursor for as long as the button is held, so placing one is press,
     * steer, release - you are never asked to be accurate before you can see the result. A
     * relative cursor is not something you land on a target in one go.
     */
    fun pressPoint(state: DrawingState) {
        val hit = state.hoveredPathPoint
        if (hit >= 0) {
            session.update { it.copy(grabbedPathPoint = hit, pathPointsFromPress = 0) }
            return
        }

        // The very first press drops an anchor and starts the point after it in the same
        // motion, so one hold draws a straight line exactly the way the straight-line tool
        // does. Otherwise that first hold would have nothing to show for itself: a lone point
        // has no curve to preview, and you would be aiming the start of a line you cannot see.
        val added = if (state.pathPoints.isEmpty()) 2 else 1
        val points = state.pathPoints.toMutableList()
        repeat(added) { points.add(PathPoint(state.brushPosition)) }
        session.update { it.copy(
            pathPoints = points,
            grabbedPathPoint = points.lastIndex,
            pathPointsFromPress = added
        ) }
        repaintPreview()
    }

    fun dropPoint() {
        val state = session.value
        if (state.grabbedPathPoint < 0) return

        // Landing one end on the other joins them. The held point is removed rather than left
        // stacked on its twin: two handles at the same spot cannot be told apart or picked up
        // separately, so keeping both would leave one of them permanently out of reach.
        if (state.pathClosingCandidate) {
            val points = state.pathPoints.toMutableList()
            points.removeAt(state.grabbedPathPoint)
            session.update { it.copy(
                pathPoints = points,
                pathClosed = true,
                grabbedPathPoint = -1,
                pathPointsFromPress = 0
            ) }
            repaintPreview()
            return
        }
        session.update { it.copy(grabbedPathPoint = -1, pathPointsFromPress = 0) }
    }

    /**
     * Carries the held point to where the cursor went, if one is held; false if none is.
     *
     * The path tool drags a point rather than painting, and does it with the pen up - so the
     * cursor's move has to ask here before it treats the move as anything else.
     */
    fun dragGrabbedPoint(state: DrawingState, newPosition: Offset, newBrushPosition: Offset): Boolean {
        if (state.drawingMode !is DrawingMode.Path || state.grabbedPathPoint !in state.pathPoints.indices) return false
        val moved = state.pathPoints.toMutableList()
        moved[state.grabbedPathPoint] = moved[state.grabbedPathPoint].copy(position = newBrushPosition)
        session.update { it.copy(
            cursorPosition = newPosition,
            brushPosition = newBrushPosition,
            pathPoints = moved
        ) }
        repaintPreview()
        return true
    }

    /**
     * Joins the two ends, or parts them again.
     *
     * Reopening does not hand back the point the join swallowed - you merged two ends into
     * one, and undoing the join does not split it. It is here mostly so a close is never a
     * trap, and so ends that are nowhere near each other can still be joined without dragging
     * one across the whole drawing to reach the other.
     */
    fun toggleClosed() {
        if (session.value.pathPoints.size < 3) return
        session.update { it.copy(pathClosed = !it.pathClosed) }
        repaintPreview()
    }

    /**
     * Abandons the press, taking the point back with it if the press is what created it.
     *
     * For the button's own drag-to-reposition: that gesture starts as a press like any other,
     * so by the time it declares itself a drag a point has already been placed. Leaving it
     * there would strand one every time the button is moved, and there is no way to remove a
     * single point once it exists.
     */
    fun abortPress() {
        val state = session.value
        if (state.grabbedPathPoint < 0) return
        if (state.pathPointsFromPress <= 0) {
            dropPoint()
            return
        }
        // Everything this press added, which is both points when it was the first one - leaving
        // the anchor behind would strand a path that never got its second end.
        val points = state.pathPoints.toMutableList()
        repeat(state.pathPointsFromPress) { if (points.isNotEmpty()) points.removeAt(points.lastIndex) }
        session.update { it.copy(pathPoints = points, grabbedPathPoint = -1, pathPointsFromPress = 0) }
        repaintPreview()
    }

    /**
     * Flips the point under the cursor between rounded and sharp.
     *
     * Acts on the held point if there is one, otherwise on whatever is under the cursor, so it
     * works the same whether you are mid-drag or just passing over.
     */
    fun togglePointCorner() {
        val state = session.value
        val index = if (state.grabbedPathPoint >= 0) state.grabbedPathPoint else state.hoveredPathPoint
        if (index !in state.pathPoints.indices) return
        val points = state.pathPoints.toMutableList()
        points[index] = points[index].copy(isCorner = !points[index].isCorner)
        session.update { it.copy(pathPoints = points) }
        repaintPreview()
    }

    /** Throws away the pending path without touching the layer. */
    fun cancel() {
        if (session.value.pathPoints.isEmpty()) return
        engine.clearTarget()
        session.update { it.copy(
            pathPoints = emptyList(),
            grabbedPathPoint = -1,
            pathPointsFromPress = 0,
            pathClosed = false,
            strokeBitmap = engine.strokeBitmap,
            renderVersion = it.renderVersion + 1
        ) }
    }

    /**
     * Stamps the curve onto the active layer and clears the path.
     *
     * Deliberately the same tail as a finished stroke - one history entry, one trace for undo
     * to leave behind, one scheduled save. A path is a stroke that took a while to describe,
     * and nothing downstream should be able to tell the difference.
     */
    fun commit() {
        val state = session.value
        if (state.pathPoints.size < 2) return
        if (state.layers.find { it.id == state.activeLayerId }?.isLocked == true) return

        engine.drawPolyline(PathGeometry.flatten(state.pathPoints, state.pathClosed), state)
        landStroke(state)

        engine.clearTarget()
        session.update { it.copy(
            pathPoints = emptyList(),
            grabbedPathPoint = -1,
            pathPointsFromPress = 0,
            pathClosed = false,
            strokeBitmap = engine.strokeBitmap,
            renderVersion = it.renderVersion + 1
        ) }
        persistence.scheduleLayerSave(state.activeLayerId)
        persistence.touchProject()
    }

    private fun repaintPreview() {
        val state = session.value
        if (state.pathPoints.size < 2) {
            engine.clearTarget()
        } else {
            engine.drawPolyline(PathGeometry.flatten(state.pathPoints, state.pathClosed), state)
        }
        session.update { it.copy(
            strokeBitmap = engine.strokeBitmap,
            renderVersion = it.renderVersion + 1
        ) }
    }
}
