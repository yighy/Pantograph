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
fun HoverDrawButton(
    viewModel: DrawingViewModel,
    fabSizeSetting: Float,
    initialOffsetX: Float,
    initialOffsetY: Float,
    onPositionChanged: (Float, Float) -> Unit
) {
    val fabDragThreshold by remember(viewModel) { viewModel.uiState.map { it.fabDragThreshold }.distinctUntilChanged() }.collectAsState(100f)
    val isEyeDropperMode by remember(viewModel) { viewModel.uiState.map { it.isEyeDropperMode }.distinctUntilChanged() }.collectAsState(false)
    val isPenDown by remember(viewModel) { viewModel.uiState.map { it.isPenDown }.distinctUntilChanged() }.collectAsState(false)
    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)

    val scale by animateFloatAsState(
        targetValue = if (isPenDown) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f)
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val fabSizePx = with(density) { fabSizeSetting.dp.toPx() }

        var localX by remember { mutableFloatStateOf(initialOffsetX) }
        var localY by remember { mutableFloatStateOf(initialOffsetY) }

        LaunchedEffect(initialOffsetX, initialOffsetY) {
            localX = initialOffsetX
            localY = initialOffsetY
        }

        LaunchedEffect(screenWidth, screenHeight, fabSizePx) {
            val maxX = (screenWidth - fabSizePx).coerceAtLeast(0f)
            val maxY = (screenHeight - fabSizePx).coerceAtLeast(0f)
            val coercedX = localX.coerceIn(0f, maxX)
            val coercedY = localY.coerceIn(0f, maxY)
            if (coercedX != localX || coercedY != localY) {
                localX = coercedX
                localY = coercedY
                viewModel.saveFabPosition(coercedX, coercedY)
                onPositionChanged(coercedX, coercedY)
            }
        }

        Box(
            modifier = Modifier
                .offset { 
                    IntOffset(
                        localX.coerceIn(0f, (screenWidth - fabSizePx).coerceAtLeast(0f)).roundToInt(), 
                        localY.coerceIn(0f, (screenHeight - fabSizePx).coerceAtLeast(0f)).roundToInt()
                    ) 
                }
                .scale(scale)
                .pointerInput(fabDragThreshold) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        viewModel.setPenDown(true)
                        
                        var gestureMode = 0 
                        
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.find { it.id == down.id } ?: break
                            if (!change.pressed) break 
                            
                            val fingerDrag = change.position - down.position
                            val cursorDist = viewModel.getCurrentStrokeDistance()
                            
                            if (gestureMode == 0) {
                                if (fingerDrag.getDistance() > fabDragThreshold) {
                                    if (cursorDist < 10.dp.toPx()) {
                                        gestureMode = 1 
                                        viewModel.setPenDown(false)
                                        // Only abort stroke if we were actually drawing (not picking color or selecting)
                                        val isSelectionMode = drawingMode.isSelectionTool()
                                        if (!isEyeDropperMode && !isSelectionMode) {
                                            viewModel.abortCurrentStroke()
                                        }
                                    } else {
                                        gestureMode = 2 
                                    }
                                } else if (cursorDist > 5.dp.toPx()) {
                                    gestureMode = 2 
                                }
                            }
                            
                            if (gestureMode == 1) {
                                change.consume()
                                localX = (localX + (change.position.x - change.previousPosition.x)).coerceIn(0f, screenWidth - fabSizePx)
                                localY = (localY + (change.position.y - change.previousPosition.y)).coerceIn(0f, screenHeight - fabSizePx)
                                onPositionChanged(localX, localY)
                            }
                        }
                        
                        if (gestureMode == 1) viewModel.saveFabPosition(localX, localY)
                        viewModel.setPenDown(false)
                    }
                }
                .size(fabSizeSetting.dp)
        ) {
            FloatingActionButton(
                onClick = { },
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(28), // Percentage based shape for expressive look at all sizes
                containerColor = when {
                    isEyeDropperMode -> MaterialTheme.colorScheme.secondaryContainer
                    isPenDown -> MaterialTheme.colorScheme.errorContainer 
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                },
                contentColor = when {
                    isEyeDropperMode -> MaterialTheme.colorScheme.onSecondaryContainer
                    isPenDown -> MaterialTheme.colorScheme.onErrorContainer 
                    else -> MaterialTheme.colorScheme.onTertiaryContainer
                }
            ) {
                val iconSize = (fabSizeSetting * 0.45f).dp
                AnimatedContent(
                    targetState = when {
                        isEyeDropperMode -> Icons.Rounded.Colorize
                        drawingMode is DrawingMode.BucketFill -> Icons.Rounded.FormatColorFill
                        isPenDown -> Icons.Default.Edit 
                        else -> Icons.Default.TouchApp
                    },
                    transitionSpec = {
                        scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut()
                    }
                ) { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }
    }
}

