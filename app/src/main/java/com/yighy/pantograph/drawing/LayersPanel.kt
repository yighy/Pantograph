package com.yighy.pantograph.drawing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
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
import com.yighy.pantograph.ui.theme.MotionTokens
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.yighy.pantograph.data.LayerEntity
import com.yighy.pantograph.data.PreferenceManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun LayerOptionsPanel(
    viewModel: DrawingViewModel,
    editingLayerId: Long,
    onDismiss: () -> Unit
) {
    val layers by remember(viewModel) { viewModel.uiState.map { it.layers }.distinctUntilChanged() }.collectAsState(emptyList())
    val layer = layers.find { it.id == editingLayerId } ?: return
    var showRenameDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .width(220.dp)
            .wrapContentHeight()
            // Consume taps so the dismiss scrim behind doesn't close the panel
            .pointerInput(Unit) { detectTapGestures { } },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with Name
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { showRenameDialog = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = layer.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    Icon(Icons.Rounded.Edit, contentDescription = "Rename layer", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close layer options", modifier = Modifier.size(18.dp))
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Opacity Slider (Compact)
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Opacity", style = MaterialTheme.typography.labelSmall)
                    Text("${(layer.opacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = layer.opacity,
                    onValueChange = { viewModel.setLayerOpacity(layer, it) },
                    onValueChangeFinished = { viewModel.persistLayerOpacity(layer.id) }
                )
            }

            // The actions that leave the pixels alone. These stay available on a locked
            // layer, because none of them costs anything the lock could not undo.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallActionChip(
                    icon = if (layer.isVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    label = if (layer.isVisible) "Hide" else "Show",
                    onClick = { viewModel.toggleLayerVisibility(layer) },
                    modifier = Modifier.weight(1f)
                )
                SmallActionChip(
                    icon = if (layer.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    label = if (layer.isLocked) "Unlock" else "Lock",
                    onClick = { viewModel.toggleLayerLock(layer) },
                    modifier = Modifier.weight(1f),
                    containerColor = if (layer.isLocked) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (layer.isLocked) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
                SmallActionChip(
                    icon = Icons.Rounded.ContentCopy,
                    label = "Duplicate",
                    onClick = { viewModel.duplicateLayer(layer); onDismiss() },
                    modifier = Modifier.weight(1f)
                )
            }

            // The three that rewrite or remove pixels. Dropped from the panel entirely while
            // the layer is locked rather than left to be tapped and ignored: a control that
            // does nothing teaches you the app is broken, and the guards in LayerController
            // would refuse them anyway.
            if (!layer.isLocked) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallActionChip(
                        icon = Icons.Rounded.CleaningServices,
                        label = "Clear",
                        onClick = { viewModel.clearLayer(layer.id); onDismiss() },
                        modifier = Modifier.weight(1f)
                    )
                    if (layers.indexOf(layer) > 0) {
                        SmallActionChip(
                            icon = Icons.Rounded.Merge,
                            label = "Merge",
                            onClick = { viewModel.mergeDown(layer); onDismiss() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (layers.size > 1) {
                        SmallActionChip(
                            icon = Icons.Rounded.Delete,
                            label = "Delete",
                            onClick = { viewModel.deleteLayer(layer); onDismiss() },
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Move Buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val idx = layers.indexOf(layer)
                OutlinedIconButton(
                    onClick = { viewModel.reorderLayers(idx, idx - 1) },
                    enabled = idx > 0,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Rounded.ArrowDownward, "Move layer down", modifier = Modifier.size(18.dp))
                }
                OutlinedIconButton(
                    onClick = { viewModel.reorderLayers(idx, idx + 1) },
                    enabled = idx < layers.size - 1,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Rounded.ArrowUpward, "Move layer up", modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(layer.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Layer", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Layer Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        viewModel.renameLayer(layer, renameText)
                        showRenameDialog = false
                    }
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SmallActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    // Spoken name for the icon-only variant, where there's no visible label to read.
    // Defaults to [label] so the labelled variant needs nothing extra.
    contentDescription: String? = label,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(
        onClick = onClick,
        // The icon-only variant was 40dp, under the minimum touch target.
        modifier = modifier.height(if (label != null) 52.dp else 48.dp),
        shape = MaterialTheme.shapes.medium,
        color = containerColor
    ) {
        if (label != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(icon, null, modifier = Modifier.size(18.dp), tint = contentColor)
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = contentColor)
            }
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription, modifier = Modifier.size(20.dp), tint = contentColor)
            }
        }
    }
}



@Composable
fun FloatingLayersPanel(
    layers: List<LayerEntity>,
    activeLayerId: Long,
    layerBitmaps: Map<Long, android.graphics.Bitmap>,
    renderVersion: Int,
    onSelectLayer: (Long) -> Unit,
    onEditLayer: (LayerEntity) -> Unit,
    onAddLayer: () -> Unit,
    onReorder: (Int, Int) -> Unit
) {
    // Long-press drag-and-drop reorder state. During a drag, localOrder holds the live
    // visual order (top of stack first): neighbours are shifted as the finger crosses
    // slot boundaries so the drop position is always exactly what is shown.
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var localOrder by remember { mutableStateOf<List<LayerEntity>?>(null) }
    // Gesture lambdas are cached by pointerInput: always read the CURRENT list through
    // these, or a reorder would leave them computing against a stale snapshot
    val currentLayers by rememberUpdatedState(layers)
    val currentOnReorder by rememberUpdatedState(onReorder)

    // Once the database emission catches up after a drop, the local override retires
    LaunchedEffect(layers) {
        if (draggingId == null) localOrder = null
    }
    Card(
        modifier = Modifier
            .width(70.dp)
            .wrapContentHeight()
            .shadow(8.dp, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(onClick = onAddLayer) {
                Icon(Icons.Default.Add, contentDescription = "Add layer", tint = MaterialTheme.colorScheme.primary)
            }
            
            HorizontalDivider(thickness = 0.5.dp)

            val displayLayers = localOrder ?: layers.reversed()
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(displayLayers, key = { it.id }) { layer ->
                    val isActive = layer.id == activeLayerId
                    val isDragging = draggingId == layer.id
                    // Border weight alone made the active layer a thing you compared rather
                    // than saw: a column of white tiles differing by 1.5dp of edge takes a
                    // second look to read. The tile now lifts as well, with the accent thrown
                    // into its shadow, so the selection carries at a glance and in the dark
                    // scheme - where a thin outline on white had the least to work with.
                    val borderWidth by animateDpAsState(
                        targetValue = if (isActive) 3.dp else 0.5.dp,
                        label = "layerBorder"
                    )
                    val lift by animateDpAsState(
                        targetValue = if (isActive) 8.dp else 0.dp,
                        label = "layerLift"
                    )
                    val borderColor by animateColorAsState(
                        targetValue = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        animationSpec = MotionTokens.colorTransition,
                        label = "layerBorderTint"
                    )
                    val accent = MaterialTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                            // The dragged item follows the finger; placement animation
                            // would fight the compensated translation on slot changes
                            .then(if (isDragging) Modifier else Modifier.animateItem())
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffsetY else 0f
                                val scale = if (isDragging) 1.1f else 1f
                                scaleX = scale
                                scaleY = scale
                            }
                            .size(50.dp)
                            // Tinted rather than the default black: on a rail of white tiles a
                            // grey shadow reads as depth, an accent-coloured one reads as the
                            // selection. Falls back to a plain shadow below Android P, where
                            // the border is still carrying the state on its own.
                            .shadow(
                                elevation = lift,
                                shape = MaterialTheme.shapes.extraSmall,
                                ambientColor = accent,
                                spotColor = accent
                            )
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(Color.White)
                            .border(
                                width = borderWidth,
                                color = borderColor,
                                shape = MaterialTheme.shapes.extraSmall
                            )
                            .pointerInput(layer.id, isActive) {
                                detectTapGestures(
                                    onTap = {
                                        if (isActive) {
                                            onEditLayer(layer)
                                        } else {
                                            onSelectLayer(layer.id)
                                        }
                                    }
                                )
                            }
                            .pointerInput(layer.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        localOrder = currentLayers.reversed()
                                        draggingId = layer.id
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                        // 50dp thumbnail + 6dp spacing per slot. Live reorder:
                                        // crossing half a slot swaps with the neighbour, and the
                                        // translation is compensated so the item stays under the finger.
                                        val slotPx = 56.dp.toPx()
                                        var moved = true
                                        while (moved) {
                                            moved = false
                                            val order = localOrder ?: break
                                            val idx = order.indexOfFirst { it.id == layer.id }
                                            if (idx < 0) break
                                            if (dragOffsetY > slotPx * 0.5f && idx < order.lastIndex) {
                                                localOrder = order.toMutableList().apply { add(idx + 1, removeAt(idx)) }
                                                dragOffsetY -= slotPx
                                                moved = true
                                            } else if (dragOffsetY < -slotPx * 0.5f && idx > 0) {
                                                localOrder = order.toMutableList().apply { add(idx - 1, removeAt(idx)) }
                                                dragOffsetY += slotPx
                                                moved = true
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        val visualFrom = currentLayers.reversed().indexOfFirst { it.id == layer.id }
                                        val visualTo = localOrder?.indexOfFirst { it.id == layer.id } ?: -1
                                        draggingId = null
                                        dragOffsetY = 0f
                                        if (visualFrom >= 0 && visualTo >= 0 && visualTo != visualFrom) {
                                            // The rail is displayed top-of-stack first (reversed);
                                            // localOrder stays visible until the DB emission lands
                                            currentOnReorder(currentLayers.size - 1 - visualFrom, currentLayers.size - 1 - visualTo)
                                        } else {
                                            localOrder = null
                                        }
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffsetY = 0f
                                        localOrder = null
                                    }
                                )
                            }
                    ) {
                        layerBitmaps[layer.id]?.let { bitmap ->
                            // Keyed on renderVersion: the bitmap is mutated in place while
                            // drawing, so the thumbnail must be forced to repaint
                            key(renderVersion) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }

                        if (!layer.isVisible) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.VisibilityOff, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
                            }
                        }

                        // A corner badge rather than a full scrim: hiding a layer is about what
                        // you can see, so it dims the whole tile; locking is about what you can
                        // do to it, and obscuring the artwork to say so would be backwards.
                        if (layer.isLocked) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(15.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Lock,
                                    contentDescription = "Locked",
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.inverseOnSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}



@Composable
fun LayerThumbnail(paths: List<DrawingPath>, canvasWidth: Int, canvasHeight: Int) {
    if (canvasWidth <= 0 || canvasHeight <= 0) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scale = min(size.width / canvasWidth, size.height / canvasHeight)
        paths.forEach { drawingPath ->
            if (drawingPath.mode is DrawingMode.BucketFill) {
                drawRect(color = drawingPath.color.copy(alpha = drawingPath.opacity))
            } else {
                val path = Path()
                if (drawingPath.points.isNotEmpty()) {
                    path.moveTo(drawingPath.points[0].x * scale, drawingPath.points[0].y * scale)
                    for (i in 1 until drawingPath.points.size) { path.lineTo(drawingPath.points[i].x * scale, drawingPath.points[i].y * scale) }
                }
                drawPath(
                    path = path, 
                    color = if (drawingPath.mode is DrawingMode.Eraser) Color.White else drawingPath.color.copy(alpha = drawingPath.opacity * drawingPath.flow), 
                    style = Stroke(width = (drawingPath.width * scale).coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}
