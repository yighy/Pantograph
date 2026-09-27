package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yighy.pantograph.ui.theme.MotionTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
fun QuickBrushPanel(
    viewModel: DrawingViewModel,
    onOpenStudio: () -> Unit
) {
    val customBrushes by remember(viewModel) { viewModel.uiState.map { it.customBrushes }.distinctUntilChanged() }.collectAsState(emptyList())
    var showNewPresetDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    val folders by remember(viewModel) { viewModel.uiState.map { it.brushFolders }.distinctUntilChanged() }.collectAsState(emptyList())
    val selectedBrushId by remember(viewModel) { viewModel.uiState.map { it.selectedCustomBrushId }.distinctUntilChanged() }.collectAsState(null)
    val selectedBrush = customBrushes.find { it.id == selectedBrushId }

    // Shared by the header button and each row's long-press menu. Selecting first is what makes
    // the studio open *on* this preset rather than on whatever was in hand.
    val onEditBrush: (BrushConfig) -> Unit = { brush ->
        viewModel.selectCustomBrush(brush)
        onOpenStudio()
    }
    var brushToDelete by remember { mutableStateOf<BrushConfig?>(null) }

    Column {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Size/Softness/Opacity/Flow sliders live on the FAB's right satellite gate
            // (see HoverDrawButton). There is no button into the studio here any more: the
            // studio is where you edit *a preset*, so it is reached from the preset you want
            // to work on rather than from a heading that names none of them.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Presets",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                // Icon-only: the row is three short actions and the labels were costing more
                // width than they explained. Each keeps its spoken name for screen readers.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showNewFolderDialog = true }) {
                        Icon(
                            Icons.Rounded.CreateNewFolder,
                            "New folder",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Both act on the preset selected in the list below, so they are absent
                    // rather than greyed out when there is none: a disabled control asks the
                    // user to work out why, an absent one poses no question at all.
                    AnimatedVisibility(
                        visible = selectedBrush != null,
                        enter = fadeIn(MotionTokens.expressiveEnter) + expandHorizontally(MotionTokens.panelTransition),
                        exit = fadeOut(MotionTokens.expressiveExit) + shrinkHorizontally(MotionTokens.panelTransition)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedBrush?.let(onEditBrush) }) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    "Edit selected preset in studio",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { brushToDelete = selectedBrush }) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    "Delete selected preset",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    // Captures whatever the brush is set to right now, so a brush arrived at by
                    // feel with the satellite gate can be kept without a detour through the studio.
                    IconButton(onClick = { showNewPresetDialog = true }) {
                        Icon(
                            Icons.Rounded.Add,
                            "New preset",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            PresetsListContent(viewModel = viewModel)
        }
    }

    if (showNewPresetDialog) {
        NamePromptDialog(
            title = "New preset",
            initial = "",
            confirmLabel = "Save",
            takenNames = customBrushes.map { it.name },
            onDismiss = { showNewPresetDialog = false },
            onConfirm = { name ->
                // Straight into the studio on the preset just created: naming a brush is
                // nearly always the first half of sitting down to work on it. Opening is
                // deferred until the save has landed - fired now, the studio would come up on
                // whatever was selected beforehand, and flag the new brush as unsaved edits
                // against it.
                viewModel.saveCurrentAsCustomBrush(name) { onOpenStudio() }
                showNewPresetDialog = false
            }
        )
    }

    if (brushToDelete != null) {
        AlertDialog(
            onDismissRequest = { brushToDelete = null },
            title = { Text("Delete Brush") },
            text = { Text("Are you sure you want to delete '${brushToDelete!!.name}'?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCustomBrush(brushToDelete!!)
                    brushToDelete = null
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { brushToDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showNewFolderDialog) {
        NamePromptDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            takenNames = folders.map { it.name },
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                viewModel.createBrushFolder(name)
                showNewFolderDialog = false
            }
        )
    }
}


@Composable
fun PresetsListContent(viewModel: DrawingViewModel) {
    val customBrushes by remember(viewModel) { viewModel.uiState.map { it.customBrushes }.distinctUntilChanged() }.collectAsState(emptyList())
    val folders by remember(viewModel) { viewModel.uiState.map { it.brushFolders }.distinctUntilChanged() }.collectAsState(emptyList())
    val selectedCustomBrushId by remember(viewModel) { viewModel.uiState.map { it.selectedCustomBrushId }.distinctUntilChanged() }.collectAsState(null)

    var folderToRename by remember { mutableStateOf<BrushFolder?>(null) }
    var folderToDelete by remember { mutableStateOf<BrushFolder?>(null) }
    // Collapsed by id, so a folder that disappears takes its entry with it and a new folder
    // starts open rather than inheriting a stale collapsed flag from a recycled position.
    val collapsed = remember { mutableStateListOf<Long>() }

    val loose = customBrushes.filter { it.folderId == null }

    Card(
        // Rows are taller now that each one carries a full-width stroke, so the cap is raised
        // to keep three and a bit of them in view - the part-row being the cue that it scrolls.
        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        if (customBrushes.isEmpty() && folders.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("No saved brushes", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                folders.forEach { folder ->
                    val contents = customBrushes.filter { it.folderId == folder.id }
                    val isCollapsed = collapsed.contains(folder.id)
                    FolderHeaderRow(
                        folder = folder,
                        count = contents.size,
                        collapsed = isCollapsed,
                        onToggle = {
                            if (isCollapsed) collapsed.remove(folder.id) else collapsed.add(folder.id)
                        },
                        onRename = { folderToRename = folder },
                        onDelete = { folderToDelete = folder }
                    )
                    // animateContentSize, not AnimatedVisibility: this Column has no spacing to
                    // leave behind, and animating the wrapper keeps the rows below sliding
                    // rather than jumping when a folder opens.
                    Column(Modifier.animateContentSize(animationSpec = MotionTokens.panelTransition)) {
                        if (!isCollapsed) {
                            if (contents.isEmpty()) {
                                Text(
                                    "Empty",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 8.dp)
                                )
                            }
                            contents.forEach { brush ->
                                PresetRow(
                                    viewModel = viewModel,
                                    brush = brush,
                                    isActive = brush.id == selectedCustomBrushId,
                                    indented = true,
                                    onSelect = { viewModel.selectCustomBrush(brush) }
                                )
                            }
                        }
                    }
                }

                // Presets outside any folder come last: folders are the structure, and these
                // are what has not been filed yet.
                if (loose.isNotEmpty() && folders.isNotEmpty()) {
                    Text(
                        "Unfiled",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp)
                    )
                }
                loose.forEach { brush ->
                    PresetRow(
                        viewModel = viewModel,
                        brush = brush,
                        isActive = brush.id == selectedCustomBrushId,
                        indented = false,
                        onSelect = { viewModel.selectCustomBrush(brush) }
                    )
                }
            }
        }
    }

    folderToRename?.let { folder ->
        NamePromptDialog(
            title = "Rename folder",
            initial = folder.name,
            confirmLabel = "Rename",
            takenNames = folders.filter { it.id != folder.id }.map { it.name },
            onDismiss = { folderToRename = null },
            onConfirm = {
                viewModel.renameBrushFolder(folder, it)
                folderToRename = null
            }
        )
    }

    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("Delete folder") },
            text = { Text("'${folder.name}' will be removed. The presets inside it are kept and move back out of any folder.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBrushFolder(folder)
                        folderToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text("Cancel") } }
        )
    }

}

/** Long enough for the card to have finished opening before the first thumbnail is drawn. */
private const val PREVIEW_AFTER_OPEN_MS = 300L

/** Thumbnails queue on this to be drawn one a frame, rather than all within the same one. */
private val presetPreviewTurn = Mutex()

/**
 * One saved preset: the stroke itself, with its name underneath.
 *
 * Tapping only ever selects. Editing and deleting moved to the panel header, where they appear
 * once something is selected - a long-press menu here meant the two most useful actions on a
 * preset were invisible until you happened to try holding one.
 */
@Composable
private fun PresetRow(
    viewModel: DrawingViewModel,
    brush: BrushConfig,
    isActive: Boolean,
    indented: Boolean,
    onSelect: () -> Unit
) {
    val density = LocalDensity.current
    val strokeHeight = 48.dp
    val strokeColor = MaterialTheme.colorScheme.onSurface

    // Re-renders once a custom tip or texture finishes decoding, not on every recomposition -
    // this walks the real stamp engine and is not a cheap draw.
    val assetsVersion by remember(viewModel) {
        viewModel.uiState.map { it.brushAssetsVersion }.distinctUntilChanged()
    }.collectAsState(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .padding(start = if (indented) 24.dp else 10.dp, end = 10.dp)
            .padding(vertical = 6.dp)
            .semantics { selected = isActive }
    ) {
        // The stroke is rendered at the width it will actually occupy, so it reads as a real
        // mark rather than a scaled-down sample.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val widthPx = constraints.maxWidth
            val heightPx = with(density) { strokeHeight.roundToPx() }
            // Drawn by an effect rather than inside remember, which put it in the composition:
            // each one walks the real stamp engine, and a list of them all had to finish before
            // the card holding them could show its first frame. One already drawn is shown at
            // once; the others wait for the card to have opened and then come in one a frame,
            // so neither the opening nor a scroll stalls on them.
            val thumbnail by produceState(
                viewModel.cachedPresetPreview(brush, strokeColor, widthPx, heightPx),
                brush, strokeColor, assetsVersion, widthPx
            ) {
                viewModel.cachedPresetPreview(brush, strokeColor, widthPx, heightPx)?.let {
                    value = it
                    return@produceState
                }
                delay(PREVIEW_AFTER_OPEN_MS)
                presetPreviewTurn.withLock {
                    withFrameNanos { }
                    value = viewModel.renderPresetPreview(brush, strokeColor, widthPx, heightPx)
                }
            }
            // The strip keeps its height while it waits, so the card opens at its final size.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(strokeHeight)
                    .clip(MaterialTheme.shapes.extraSmall)
            ) {
                thumbnail?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        Text(
            brush.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        modifier = Modifier.padding(horizontal = 8.dp),
        color = if (isActive) Color.Transparent else DividerDefaults.color
    )
}

/** Folder title bar: tap to fold, overflow to rename or remove. */
@Composable
private fun FolderHeaderRow(
    folder: BrushFolder,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val chevronTurn by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = MotionTokens.pulse,
        label = "folderChevron"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (collapsed) "Expand folder" else "Collapse folder",
            modifier = Modifier.size(18.dp).rotate(chevronTurn),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            folder.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Rounded.MoreVert, "Folder actions", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = { showMenu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { showMenu = false; onDelete() }
                )
            }
        }
    }
}

/** Shared name prompt: rejects blanks and names already in use, case-insensitively. */
@Composable
private fun NamePromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    takenNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val clashes = takenNames.any { it.equals(trimmed, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = clashes,
                    shape = MaterialTheme.shapes.medium
                )
                if (clashes) {
                    Text(
                        "That name is already in use",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty() && !clashes
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
