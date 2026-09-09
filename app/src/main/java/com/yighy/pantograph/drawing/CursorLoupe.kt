package com.yighy.pantograph.drawing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Side of the window. Small enough to sit beside the drawing rather than in front of it. */
private val LOUPE_SIZE = 132.dp

/** Gap between the window and the edge of the viewport. */
private val LOUPE_MARGIN = 12.dp

/** How close the brush gets before the window moves out of its way. */
private val LOUPE_REACH = 24.dp

/**
 * A magnified window onto the canvas under the brush.
 *
 * It is the canvas box again, at a different transform and clipped small: the same box sized to
 * the canvas, the same graphicsLayer scale-and-translate, holding the same layer composables the
 * real one holds. Nothing about the picture is reimplemented here, so the loupe cannot drift
 * from what it is magnifying - the live stroke, the eraser's blend, layer opacity, the texture
 * mask, the selection outline and a selection being moved all arrive because they are drawn by
 * the composables below rather than by this file.
 *
 * That is also why the transform lives on the box rather than inside one layer: a loupe that
 * magnifies only the composable it was told about is a loupe that silently omits every overlay
 * nobody remembered, which is what the first version of this did to the selection.
 *
 * Centred on the brush rather than the cursor: in lazy mode the two are apart, and the brush is
 * the one laying down paint.
 *
 * [CursorLayer] is deliberately left out. At this magnification the cursor ring would fill the
 * window and hide the thing it is pointing at; the crosshair below marks the same spot in a way
 * that leaves the picture visible.
 */
@Composable
fun CursorLoupe(viewModel: DrawingViewModel, viewport: Size) {
    val isActive by remember(viewModel) {
        viewModel.uiState.map { it.isLoupeActive }.distinctUntilChanged()
    }.collectAsState(false)
    if (!isActive) return

    val brushPosition by remember(viewModel) {
        viewModel.uiState.map { it.brushPosition }.distinctUntilChanged()
    }.collectAsState(Offset.Zero)
    val zoom by remember(viewModel) {
        viewModel.uiState.map { it.loupeZoom }.distinctUntilChanged()
    }.collectAsState(4f)
    val canvasWidth by remember(viewModel) {
        viewModel.uiState.map { it.canvasWidth }.distinctUntilChanged()
    }.collectAsState(1080)
    val canvasHeight by remember(viewModel) {
        viewModel.uiState.map { it.canvasHeight }.distinctUntilChanged()
    }.collectAsState(1920)
    val canvasScale by remember(viewModel) {
        viewModel.uiState.map { it.canvasScale }.distinctUntilChanged()
    }.collectAsState(1f)
    val canvasOffset by remember(viewModel) {
        viewModel.uiState.map { it.canvasOffset }.distinctUntilChanged()
    }.collectAsState(Offset.Zero)
    val canvasRotation by remember(viewModel) {
        viewModel.uiState.map { it.canvasRotation }.distinctUntilChanged()
    }.collectAsState(0f)

    val density = LocalDensity.current
    val sizePx = with(density) { LOUPE_SIZE.toPx() }
    val marginPx = with(density) { LOUPE_MARGIN.toPx() }
    val reachPx = with(density) { LOUPE_REACH.toPx() }

    // Which edge the window is parked against. Held rather than derived, because the rule is
    // "leave when the brush reaches you", which needs to know where you were standing.
    var onLeft by remember { mutableStateOf(true) }
    val brushOnScreen = canvasToScreen(
        brushPosition, canvasWidth, canvasHeight, viewport, canvasScale, canvasOffset, canvasRotation
    )
    LaunchedEffect(brushOnScreen, onLeft, viewport, sizePx) {
        onLeft = LoupePlacement.nextSideIsLeft(onLeft, brushOnScreen, viewport, sizePx, marginPx, reachPx)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = LOUPE_MARGIN)
                .size(LOUPE_SIZE),
            shape = MaterialTheme.shapes.large,
            // The colour the canvas surround uses, so what shows past the canvas edge reads as
            // "off the paper" rather than as part of the drawing.
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 3.dp
        ) {
            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                Box(
                    modifier = Modifier
                        .wrapContentSize(unbounded = true)
                        .requiredSize(
                            (canvasWidth / density.density).dp,
                            (canvasHeight / density.density).dp
                        )
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            // The same relationship the canvas box has with the viewport: the
                            // box is centred in its parent and graphicsLayer scales it about its
                            // own centre, so a translation of -(point - canvasCentre) * zoom is
                            // what carries that point to the middle of the window.
                            translationX = -(brushPosition.x - canvasWidth / 2f) * zoom
                            translationY = -(brushPosition.y - canvasHeight / 2f) * zoom
                        }
                        .background(Color.White)
                ) {
                    // crisp: nearest-neighbour sampling. A loupe that interpolates is a
                    // blurred one, and blur is the one thing it cannot afford - reading
                    // individual pixels is what it is for. ToolPreviewLayer draws no bitmaps,
                    // so it has nothing to sample.
                    CanvasLayer(viewModel, crisp = true)
                    // viewScale: both size their lines by the inverse of the scale they are
                    // drawn at, so they have to be told this window's rather than the canvas's -
                    // otherwise a selection outline reads several times too thick in here.
                    ToolPreviewLayer(viewModel, viewScale = zoom)
                    SelectionLayer(viewModel, viewScale = zoom, crisp = true)
                }

                LoupeCrosshair()
            }
        }
    }
}

/**
 * Marks the middle of the window, which is where the brush is.
 *
 * A gap in the centre rather than crossed lines: the one pixel the crosshair is pointing at is
 * the one pixel it must not cover.
 */
@Composable
private fun LoupeCrosshair() {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val gap = 4.dp.toPx()
        val arm = 9.dp.toPx()
        val w = 1.dp.toPx()
        listOf(Offset(-1f, 0f), Offset(1f, 0f), Offset(0f, -1f), Offset(0f, 1f)).forEach { dir ->
            drawLine(
                color = ink.copy(alpha = 0.75f),
                start = c + dir * gap,
                end = c + dir * (gap + arm),
                strokeWidth = w
            )
        }
    }
}
