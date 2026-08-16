package com.yighy.paintcursor.drawing

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The mutable core every drawing controller shares: the one [DrawingState] the UI observes, and
 * the live layer pixels behind it.
 *
 * The bitmaps are deliberately *not* copied into the state on every change - they are mutated in
 * place by the tools, and [bumpRender] is what tells Compose to redraw. Only structural changes
 * (a layer added, removed or restored) republish the map through [publishLayerBitmaps].
 *
 * This is a shared mutable holder, not an attempt to hide the state: the controllers each own a
 * slice of the behaviour, while [DrawingViewModel] stays the only thing the UI talks to.
 */
class DrawingSession(val projectId: Long) {

    private val _state = MutableStateFlow(DrawingState())
    val state: StateFlow<DrawingState> = _state.asStateFlow()

    /** Current snapshot. Read it once per operation rather than re-reading mid-way. */
    val value: DrawingState get() = _state.value

    /** Live pixels of every layer, keyed by layer id. */
    val layerBitmaps = mutableMapOf<Long, Bitmap>()

    fun update(transform: (DrawingState) -> DrawingState) = _state.update(transform)

    /** Marks the canvas dirty so the Compose layers redraw. */
    fun bumpRender() = update { it.copy(renderVersion = it.renderVersion + 1) }

    /** Republishes the layer map into the state, after a layer was added, removed or restored. */
    fun publishLayerBitmaps() = update { it.copy(layerBitmaps = layerBitmaps.toMap()) }

    val activeLayerBitmap: Bitmap? get() = layerBitmaps[value.activeLayerId]
}
