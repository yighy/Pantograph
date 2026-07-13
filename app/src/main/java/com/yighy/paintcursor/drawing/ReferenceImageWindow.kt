package com.yighy.paintcursor.drawing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.yighy.paintcursor.data.LayerEntity
import com.yighy.paintcursor.data.PreferenceManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ReferenceImageOverlay(viewModel: DrawingViewModel, viewportSize: IntSize) {
    val referenceImage by remember(viewModel) { viewModel.uiState.map { it.referenceImage }.distinctUntilChanged() }.collectAsState(null)
    val isEyeDropperMode by remember(viewModel) { viewModel.uiState.map { it.isEyeDropperMode }.distinctUntilChanged() }.collectAsState(false)

    // Keep the last non-null reference so the exit fade can still draw it
    var latestRef by remember { mutableStateOf<ReferenceImage?>(null) }
    if (referenceImage != null) latestRef = referenceImage

    AnimatedVisibility(
        visible = referenceImage != null,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150))
    ) {
    latestRef?.let { ref ->
        var windowSize by remember { mutableStateOf(IntSize.Zero) }
        var isMinimized by remember { mutableStateOf(false) }
        val latestOffset = rememberUpdatedState(ref.offset)
        val statusBarPx = with(LocalDensity.current) {
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
        }

        // The window's offset is stored in raw screen pixels and isn't recalculated on its own,
        // so rotating the device (or any other viewport size change) can leave it positioned
        // outside the visible area. Whenever the viewport or the window's own measured size
        // changes, pull it back within bounds (below the status bar) and persist the correction.
        LaunchedEffect(viewportSize, windowSize, isMinimized) {
            if (isMinimized || viewportSize == IntSize.Zero || windowSize == IntSize.Zero) return@LaunchedEffect
            val offset = latestOffset.value
            val maxX = (viewportSize.width - windowSize.width).toFloat().coerceAtLeast(0f)
            val maxY = (viewportSize.height - windowSize.height).toFloat().coerceAtLeast(statusBarPx)
            val clampedX = offset.x.coerceIn(0f, maxX)
            val clampedY = offset.y.coerceIn(statusBarPx, maxY)
            if (clampedX != offset.x || clampedY != offset.y) {
                viewModel.updateReferenceImage(Offset(clampedX - offset.x, clampedY - offset.y), zoom = 1f, rotation = 0f)
            }
        }

        Crossfade(targetState = isMinimized, animationSpec = tween(200), label = "refMode") { minimized ->
            if (minimized) {
                MinimizedReferenceChip(
                    offset = ref.offset,
                    viewportSize = viewportSize,
                    statusBarPx = statusBarPx,
                    onExpand = { isMinimized = false },
                    onDrag = { viewModel.updateReferenceImage(it, zoom = 1f, rotation = 0f) }
                )
            } else {
                FloatingReferenceImage(
                    reference = ref,
                    isEyeDropperActive = isEyeDropperMode,
                    viewportSize = viewportSize,
                    onPickColor = { viewModel.pickColorFromReference(it) },
                    onUpdate = { pan, zoom, rot -> viewModel.updateReferenceImage(pan, zoom, rot) },
                    onClose = { viewModel.removeReferenceImage() },
                    onMinimize = { isMinimized = true },
                    onSizeChanged = { windowSize = it }
                )
            }
        }
    }
    }
}

@Composable
fun MinimizedReferenceChip(
    offset: Offset,
    viewportSize: IntSize,
    statusBarPx: Float,
    onExpand: () -> Unit,
    onDrag: (Offset) -> Unit
) {
    val chipSizePx = with(LocalDensity.current) { 48.dp.toPx() }
    Surface(
        modifier = Modifier
            .offset {
                IntOffset(
                    offset.x.coerceIn(0f, (viewportSize.width - chipSizePx).coerceAtLeast(0f)).roundToInt(),
                    offset.y.coerceIn(statusBarPx, (viewportSize.height - chipSizePx).coerceAtLeast(statusBarPx)).roundToInt()
                )
            }
            .size(48.dp)
            .pointerInput(Unit) { detectTapGestures { onExpand() } }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Image,
                contentDescription = "Expand reference image",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun FloatingReferenceImage(
    reference: ReferenceImage,
    isEyeDropperActive: Boolean,
    viewportSize: IntSize,
    onPickColor: (Offset) -> Unit,
    onUpdate: (Offset, Float, Float) -> Unit,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onSizeChanged: (IntSize) -> Unit = {}
) {
    val baseWidth = 300f
    val density = LocalDensity.current
    val headerHeightPx = with(density) { 32.dp.toPx() }
    val aspectRatio = if (reference.aspectRatio > 0f) reference.aspectRatio else 1f

    // Constrain the window to the viewport (both axes) so the header and its buttons can
    // never end up off-screen - the user's zoom saturates once the window fills the screen.
    val effectiveWidth = with(density) {
        var widthDp = baseWidth * reference.scale
        if (viewportSize != IntSize.Zero) {
            val maxWidthDp = (viewportSize.width * 0.9f).toDp().value
            val maxImageHeightPx = viewportSize.height * 0.85f - headerHeightPx
            val maxWidthByHeightDp = (maxImageHeightPx * aspectRatio).toDp().value
            widthDp = minOf(widthDp, maxWidthDp, maxWidthByHeightDp.coerceAtLeast(80f))
        }
        widthDp.coerceAtLeast(80f).dp
    }

    // Double-tap on the header: back to default size, centered in the viewport
    val resetTransform = {
        if (viewportSize != IntSize.Zero) {
            with(density) {
                val targetWidthPx = minOf(baseWidth.dp.toPx(), viewportSize.width * 0.9f)
                val targetHeightPx = headerHeightPx + targetWidthPx / aspectRatio
                val target = Offset(
                    (viewportSize.width - targetWidthPx) / 2f,
                    ((viewportSize.height - targetHeightPx) / 2f).coerceAtLeast(0f)
                )
                onUpdate(target - reference.offset, 1f / reference.scale, -reference.rotation)
            }
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(reference.offset.x.roundToInt(), reference.offset.y.roundToInt()) }
            .width(effectiveWidth)
            .shadow(12.dp, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .onSizeChanged(onSizeChanged)
            .pointerInput(isEyeDropperActive) {
                if (isEyeDropperActive) {
                    detectTapGestures { offset ->
                        if (offset.y > headerHeightPx) {
                            val imgY = offset.y - headerHeightPx
                            val imgX = offset.x
                            val relX = (imgX / (size.width)).coerceIn(0f, 1f)
                            val relY = (imgY / (size.height - headerHeightPx)).coerceIn(0f, 1f)
                            onPickColor(Offset(relX, relY))
                        }
                    }
                } else {
                    detectTransformGestures { _, pan, zoom, rotation -> onUpdate(pan, zoom, rotation) }
                }
            }
            .clip(RoundedCornerShape(12.dp))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { resetTransform() }) },
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMinimize, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Rounded.Remove, contentDescription = "Minimize", modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
            AsyncImage(
                model = reference.uri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio),
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

