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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.yighy.paintcursor.data.LayerEntity
import com.yighy.paintcursor.data.PreferenceManager
import com.yighy.paintcursor.ui.theme.MotionTokens
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun AdvancedBrushStudioWrapper(viewModel: DrawingViewModel, onDismiss: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    AdvancedBrushStudio(uiState = uiState, viewModel = viewModel, onDismiss = onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AdvancedBrushStudio(uiState: DrawingState, viewModel: DrawingViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // OpenDocument, not GetContent: these uris are stored with the project and read again on
    // the next launch, and only OpenDocument returns one whose read access can be made to
    // outlive the task. With GetContent the tip and texture went missing on every restart.
    val tipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.setBrushTip(context, it.toString()) }
    }
    val texturePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.setBrushTexture(context, it.toString()) }
    }

    val selectedBrush = uiState.customBrushes.find { it.id == uiState.selectedCustomBrushId }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showFolderMenu by remember { mutableStateOf(false) }
    // Whether the brush in hand still matches the preset it came from. Without this the Save
    // button looked identical whether or not there was anything to save, so there was no way
    // to tell a preset that is up to date from one you have been editing.
    val hasUnsavedChanges = selectedBrush != null && !uiState.toBrushConfig().paintsSameAs(selectedBrush)

    var showDiscardDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        // A swipe or a scrim tap has already animated the sheet away by the time this runs, so
        // holding it open means bringing it back: otherwise the confirmation would sit over an
        // empty screen, and cancelling would leave the sheet composed but invisible.
        onDismissRequest = {
            if (hasUnsavedChanges) {
                showDiscardDialog = true
                scope.launch { sheetState.show() }
            } else {
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            // Pinned header: title + live preview stay visible while any slider below is adjusted
            stickyHeader {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(bottom = 12.dp)
                ) {
                    // The studio edits one preset, so that preset names the screen. Tapping
                    // the name renames it - there is no list here to rename things from any
                    // more, and a heading that says "Brush Studio" would waste the one line
                    // that can say which brush you are actually working on.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable(enabled = selectedBrush != null) { showRenameDialog = true }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                selectedBrush?.name ?: "Unsaved brush",
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (selectedBrush != null) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = "Rename preset",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // Filing sits with the name rather than in the presets panel: both say
                        // which preset this is, and the panel is where folders are made, not
                        // where a single brush is assigned to one.
                        if (selectedBrush != null) {
                            Box {
                                TextButton(
                                    onClick = { showFolderMenu = true },
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        uiState.brushFolders.find { it.id == selectedBrush.folderId }?.name ?: "Unfiled",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                DropdownMenu(
                                    expanded = showFolderMenu,
                                    onDismissRequest = { showFolderMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Unfiled") },
                                        onClick = {
                                            showFolderMenu = false
                                            viewModel.moveBrushToFolder(selectedBrush, null)
                                        },
                                        trailingIcon = {
                                            if (selectedBrush.folderId == null) {
                                                Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    )
                                    uiState.brushFolders.forEach { folder ->
                                        DropdownMenuItem(
                                            text = { Text(folder.name) },
                                            onClick = {
                                                showFolderMenu = false
                                                viewModel.moveBrushToFolder(selectedBrush, folder.id)
                                            },
                                            trailingIcon = {
                                                if (selectedBrush.folderId == folder.id) {
                                                    Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        // Kept in the sticky header rather than after the settings: it is the
                        // point of the screen, and it stays in reach while you scroll them.
                        // Filled while there is something to save, tonal and reading "Saved"
                        // once there isn't - rather than a disabled button, which would say
                        // nothing about why it is unavailable. Pressing it when clean simply
                        // rewrites the same values.
                        // The two states differ in width as well as in fill, so the swap needs a
                        // size transform on top of the cross-fade - without it the button snaps
                        // between widths underneath a fading label. This is the one moving part
                        // that reports back on something the user did, so it gets the enter
                        // spring rather than a flat fade.
                        if (selectedBrush != null) {
                            AnimatedContent(
                                targetState = hasUnsavedChanges,
                                transitionSpec = {
                                    (fadeIn(MotionTokens.expressiveEnter) +
                                        scaleIn(MotionTokens.expressiveEnter, initialScale = 0.85f) togetherWith
                                        fadeOut(MotionTokens.expressiveExit))
                                        .using(SizeTransform(clip = false) { _, _ -> MotionTokens.panelTransition })
                                },
                                label = "saveState"
                            ) { dirty ->
                                if (dirty) {
                                    Button(
                                        onClick = { viewModel.updateSelectedBrush() },
                                        shape = MaterialTheme.shapes.medium,
                                        contentPadding = PaddingValues(horizontal = 16.dp)
                                    ) {
                                        Text("Save", style = MaterialTheme.typography.labelLarge)
                                    }
                                } else {
                                    FilledTonalButton(
                                        onClick = { viewModel.updateSelectedBrush() },
                                        shape = MaterialTheme.shapes.medium,
                                        contentPadding = PaddingValues(horizontal = 16.dp)
                                    ) {
                                        Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Saved", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }

                    // extraLarge against the sections' medium: the preview is what this
                    // screen is about, and with every surface on the same radius nothing stood
                    // out from anything else.
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            // Checkerboard Background (transparency indicator, tinted from the
                            // theme's neutral on-surface-variant tone so it stays visible and
                            // correctly contrasted in both light and dark theme)
                            val checkerColor = MaterialTheme.colorScheme.onSurfaceVariant
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val squareSize = 12.dp.toPx()
                                val cols = (size.width / squareSize).toInt() + 1
                                val rows = (size.height / squareSize).toInt() + 1
                                for (i in 0 until cols) {
                                    for (j in 0 until rows) {
                                        if ((i + j) % 2 == 0) {
                                            drawRect(
                                                color = checkerColor.copy(alpha = 0.08f),
                                                topLeft = Offset(i * squareSize, j * squareSize),
                                                size = androidx.compose.ui.geometry.Size(squareSize, squareSize)
                                            )
                                        }
                                    }
                                }
                            }

                            // The preview is rendered by the actual stamp engine (single code
                            // path with real strokes) - see DrawingViewModel.renderBrushPreview.
                            BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
                                val wPx = constraints.maxWidth
                                val hPx = constraints.maxHeight
                                val previewKey = listOf(
                                    uiState.selectedWidth, uiState.brushSoftness, uiState.brushOpacity,
                                    uiState.brushFlow, uiState.brushSpacing, uiState.selectedColor,
                                    uiState.sizeJitter, uiState.scatterJitter,
                                    uiState.flowJitter, uiState.rotationFollow,
                                    uiState.brushRotation, uiState.brushRotationJitter,
                                    uiState.brushTipBitmap,
                                    uiState.brushTextureMask, uiState.velocityEnabled,
                                    uiState.velocitySizeAmount, uiState.velocityFlowAmount,
                                    uiState.velocityScatterAmount, wPx, hPx
                                )
                                val previewBitmap = remember(previewKey) {
                                    if (wPx > 0 && hPx > 0) viewModel.renderBrushPreview(wPx, hPx) else null
                                }
                                previewBitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Core Properties
            item {
                StudioSection(title = "Core Properties", icon = Icons.Rounded.Brush) {
                    DrawingSettingRow("Size", "${uiState.selectedWidth.toInt()}px", uiState.selectedWidth, { viewModel.selectWidth(it) }, 1f..300f)
                    DrawingSettingRow("Opacity", "${(uiState.brushOpacity * 100).toInt()}%", uiState.brushOpacity, { viewModel.setBrushOpacity(it) }, 0f..1f)
                    DrawingSettingRow("Flow", "${(uiState.brushFlow * 100).toInt()}%", uiState.brushFlow, { viewModel.setBrushFlow(it) }, 0f..1f)
                    DrawingSettingRow("Softness", "${(uiState.brushSoftness * 100).toInt()}%", uiState.brushSoftness, { viewModel.setBrushSoftness(it) }, 0f..1f)
                    DrawingSettingRow("Smoothing", "${(uiState.brushSmoothing * 100).toInt()}%", uiState.brushSmoothing, { viewModel.setBrushSmoothing(it) }, 0f..1f)
                }
            }

            // Dynamics & Jitter
            item {
                StudioSection(title = "Dynamics", icon = Icons.Rounded.Tune) {
                    DrawingSettingRow("Spacing", "${(uiState.brushSpacing * 100).toInt()}%", uiState.brushSpacing, { viewModel.setBrushSpacing(it) }, 0.01f..4f)
                    DrawingSettingRow("Rotation", "${uiState.brushRotation.toInt()}\u00B0", uiState.brushRotation, { viewModel.setBrushRotation(it) }, 0f..360f)

                    DrawingSettingRow("Follow Direction", "${(uiState.rotationFollow * 100).toInt()}%", uiState.rotationFollow, { viewModel.setRotationFollow(it) }, 0f..1f)

                    DrawingSettingRow("Size Jitter", "${(uiState.sizeJitter * 100).toInt()}%", uiState.sizeJitter, { viewModel.setSizeJitter(it) }, 0f..1f)
                    DrawingSettingRow("Rotation Jitter", "${uiState.brushRotationJitter.toInt()}\u00B0", uiState.brushRotationJitter, { viewModel.setRotationJitter(it) }, 0f..180f)
                    DrawingSettingRow("Scatter Jitter", "${(uiState.scatterJitter * 100).toInt()}%", uiState.scatterJitter, { viewModel.setScatterJitter(it) }, 0f..1f)
                    DrawingSettingRow("Flow Jitter", "${(uiState.flowJitter * 100).toInt()}%", uiState.flowJitter, { viewModel.setFlowJitter(it) }, 0f..1f)

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Velocity Dynamics", style = MaterialTheme.typography.titleMedium)
                            Text("React to stroke speed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = uiState.velocityEnabled, onCheckedChange = { viewModel.setVelocityEnabled(it) })
                    }

                    // animateContentSize rather than AnimatedVisibility: this Column spaces its
                    // children by 16dp, and a collapsing AnimatedVisibility keeps its slot until
                    // the exit finishes - so that gap would sit at full height the whole way down
                    // and then vanish in a single frame. Animating this wrapper own height leaves
                    // no phantom slot behind.
                    Column(
                        modifier = Modifier.animateContentSize(animationSpec = MotionTokens.panelTransition),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (uiState.velocityEnabled) {
                            DrawingSettingRow("Velocity Size", "${if (uiState.velocitySizeAmount > 0) "+" else ""}${(uiState.velocitySizeAmount * 100).toInt()}%", uiState.velocitySizeAmount, { viewModel.setVelocitySize(it) }, -2f..2f)
                            DrawingSettingRow("Velocity Flow", "${if (uiState.velocityFlowAmount > 0) "+" else ""}${(uiState.velocityFlowAmount * 100).toInt()}%", uiState.velocityFlowAmount, { viewModel.setVelocityFlow(it) }, -2f..2f)
                            DrawingSettingRow("Velocity Scatter", "${if (uiState.velocityScatterAmount > 0) "+" else ""}${(uiState.velocityScatterAmount * 100).toInt()}%", uiState.velocityScatterAmount, { viewModel.setVelocityScatter(it) }, -2f..2f)
                        }
                    }
                }
            }

            // Custom Assets
            item {
                StudioSection(title = "Custom Assets", icon = Icons.Rounded.Texture) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AssetPickerCard(
                            label = "Brush Tip",
                            icon = Icons.Rounded.FilterTiltShift,
                            bitmap = uiState.brushTipBitmap,
                            onPick = { tipPicker.launch(arrayOf("image/*")) },
                            onClear = { viewModel.setBrushTip(context, null) },
                            modifier = Modifier.weight(1f)
                        )
                        AssetPickerCard(
                            label = "Texture",
                            icon = Icons.Rounded.Texture,
                            bitmap = uiState.brushTextureBitmap,
                            onPick = { texturePicker.launch(arrayOf("image/*")) },
                            onClear = { viewModel.setBrushTexture(context, null) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "Tap to pick an image \u2022 Texture: white keeps paint, dark cuts it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            // Tapping outside backs out of the decision entirely and leaves the sheet open,
            // which is the safe reading of an accidental tap.
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Unsaved changes") },
            text = { Text("This preset has been edited. Save the changes before closing?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateSelectedBrush()
                    showDiscardDialog = false
                    onDismiss()
                }) { Text("Save and close") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Discard") }
            }
        )
    }

    if (showRenameDialog && selectedBrush != null) {
        var renameText by remember(selectedBrush.id) { mutableStateOf(selectedBrush.name) }
        val trimmed = renameText.trim()
        val clashes = uiState.customBrushes.any {
            it.name.equals(trimmed, ignoreCase = true) && it.id != selectedBrush.id
        }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename preset") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("Name") },
                        singleLine = true,
                        isError = clashes,
                        shape = MaterialTheme.shapes.medium
                    )
                    if (clashes) {
                        Text(
                            "A preset already goes by that name",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = trimmed.isNotEmpty() && !clashes,
                    onClick = {
                        viewModel.renameCustomBrush(selectedBrush, trimmed)
                        showRenameDialog = false
                    }
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } }
        )
    }
}

/** Asset slot showing the loaded bitmap (with a clear badge) or a placeholder to pick one. */
@Composable
fun AssetPickerCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bitmap: android.graphics.Bitmap?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onPick,
        modifier = modifier.height(92.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (bitmap != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Box {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Picking or clearing an asset swaps a 48dp thumbnail for a 28dp glyph. A
                // cross-fade keeps that from reading as a flicker inside a card that itself
                // stays put.
                AnimatedContent(
                    targetState = bitmap,
                    transitionSpec = {
                        fadeIn(MotionTokens.expressiveEnter) +
                            scaleIn(MotionTokens.expressiveEnter, initialScale = 0.8f) togetherWith
                            fadeOut(MotionTokens.expressiveExit)
                    },
                    label = "assetThumb"
                ) { current ->
                    if (current != null) {
                        Image(
                            bitmap = current.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.small)
                                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(icon, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bitmap != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (bitmap != null) {
                Surface(
                    onClick = onClear,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // Stays a 22dp badge visually, but reserves the full 48dp of touch
                        // area around itself. The explicit padding is gone because that
                        // reserved box already insets the badge from the tile corner.
                        .minimumInteractiveComponentSize()
                        .size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear $label",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StudioSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun DrawingSettingRow(label: String, valueLabel: String, value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Was labelSmall for both - 11sp, the smallest step on the scale, for the
                // screen's actual content, which flattened ten rows into one weight and left
                // no distinction between a parameter and its value.
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(value = value, onValueChange = onValueChange, valueRange = range)
        }
    }
}

