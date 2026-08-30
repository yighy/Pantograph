package com.yighy.pantograph.drawing

import com.yighy.pantograph.data.BrushFolderEntity
import com.yighy.pantograph.data.PreferenceManager
import com.yighy.pantograph.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The brush library: saved presets, the folders they are filed into, and loading one into the
 * brush in hand.
 *
 * Pairs with [CustomBrushManager] the way [HistoryCoordinator] pairs with
 * [DrawingHistoryManager] - the manager builds and stores the rows, this owns what has to happen
 * around them: mirroring the library into [DrawingState], decoding the tips and textures a
 * preset refers to, and pushing the settings that are *also* global preferences back out when a
 * preset is loaded.
 *
 * Names are unique, case-insensitively, for both presets and folders. A clash is refused rather
 * than allowed to create a second entry the user can't tell apart from the first.
 */
class BrushPresetController(
    private val session: DrawingSession,
    private val repository: ProjectRepository,
    private val assets: BrushAssetLoader,
    private val persistence: ProjectPersistence,
    private val preferenceManager: PreferenceManager,
    private val context: android.content.Context,
    private val scope: CoroutineScope
) {
    private val brushes = CustomBrushManager(repository)

    /**
     * Mirrors the stored library into the state and decodes whatever assets it needs.
     *
     * One collector feeds both: the presets and their tips/textures come off the same query, and
     * a second collection of it would put a redundant observer on the table. The preload itself
     * returns immediately - it decodes on IO and bumps [DrawingState.brushAssetsVersion] when the
     * files land.
     */
    fun observeLibrary() {
        scope.launch {
            repository.allCustomBrushes.collect { stored ->
                session.update { state -> state.copy(customBrushes = stored.map { it.toBrushConfig() }) }
                assets.preloadPresetAssets(context, stored)
            }
        }
        scope.launch {
            repository.allBrushFolders.collect { folders ->
                session.update { state -> state.copy(brushFolders = folders.map { BrushFolder(it.id, it.name) }) }
            }
        }
    }

    // ============================ Presets ============================

    /**
     * Saves the brush in hand as a new preset and makes it the selected one.
     *
     * [onSaved] runs on the main thread once the row exists and the selection points at it -
     * that ordering is what lets a caller open the studio *on the new preset* rather than on
     * whatever happened to be selected before. It does not run if the name was refused.
     */
    fun saveCurrent(name: String, onSaved: () -> Unit = {}) {
        scope.launch {
            val id = brushes.saveAsNew(name, session.value) ?: return@launch
            session.update { it.copy(selectedCustomBrushId = id.toString()) }
            onSaved()
        }
    }

    /** Writes the brush in hand back over the preset it was loaded from. */
    fun updateSelected() {
        val state = session.value
        val brushId = state.selectedCustomBrushId?.toLongOrNull() ?: return
        val currentBrush = state.customBrushes.find { it.id == state.selectedCustomBrushId } ?: return

        scope.launch {
            brushes.updateExisting(brushId, currentBrush.name, currentBrush.folderId, state)
        }
    }

    fun rename(brush: BrushConfig, newName: String) {
        scope.launch { brushes.rename(brush, newName, session.value.customBrushes) }
    }

    fun delete(brush: BrushConfig) {
        scope.launch { brushes.delete(brush) }
    }

    /**
     * Loads [brush] into the brush in hand.
     *
     * The tip and texture are re-decoded from their uris rather than carried in the preset, and
     * every setting that doubles as a global default is written back to the preferences -
     * from [MirroredBrushSetting]'s list, which the observers on the other side walk too, so
     * the two cannot drift apart.
     */
    fun select(brush: BrushConfig) {
        session.update {
            it.copy(
                // Only when the brush actually changes: re-selecting the one already in hand
                // would otherwise point the swap at itself and lose the way back.
                previousBrushId =
                    if (it.selectedCustomBrushId != brush.id) it.selectedCustomBrushId else it.previousBrushId,
                selectedCustomBrushId = brush.id,
                selectedWidth = brush.size,
                brushSoftness = brush.softness,
                brushOpacity = brush.opacity,
                brushFlow = brush.flow,
                brushSpacing = brush.spacing,
                brushSmoothing = brush.smoothing,
                brushRotation = brush.rotation,
                brushRotationJitter = brush.rotationJitter,
                sizeJitter = brush.sizeJitter,
                scatterJitter = brush.scatterJitter,
                flowJitter = brush.flowJitter,
                rotationFollow = brush.rotationFollow,
                brushTipUri = brush.tipUri,
                brushTextureUri = brush.textureUri,
                velocityEnabled = brush.velocityEnabled,
                velocitySizeAmount = brush.velocitySize,
                velocityFlowAmount = brush.velocityFlow,
                velocityScatterAmount = brush.velocityScatter,
                sizeMultiplier = brush.sizeMultiplier,
                isEyeDropperMode = false
            )
        }

        // Reload Bitmaps from the new URIs
        assets.setBrushTip(context, brush.tipUri)
        assets.setBrushTexture(context, brush.textureUri)

        scope.launch {
            MirroredBrushSetting.pushAll(preferenceManager, brush)
            persistence.saveProjectSettings()
        }
    }

    /**
     * Flips between the brush in hand and the one used before it.
     *
     * Repeating it ping-pongs rather than walking back through a history: [select] records the
     * brush being left, so the one this swaps away from becomes the way back. Does nothing
     * before a second preset has been loaded, or if the remembered one has since been deleted.
     */
    fun swapToPrevious() {
        val state = session.value
        val previous = state.previousBrushId ?: return
        val brush = state.customBrushes.find { it.id == previous } ?: return
        select(brush)
    }

    // ============================ Folders ============================

    /** Refuses a blank or duplicate name rather than creating a second folder with it. */
    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (session.value.brushFolders.any { it.name.equals(trimmed, ignoreCase = true) }) return
        scope.launch { repository.insertBrushFolder(BrushFolderEntity(name = trimmed)) }
    }

    fun renameFolder(folder: BrushFolder, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        if (session.value.brushFolders.any { it.name.equals(trimmed, ignoreCase = true) && it.id != folder.id }) return
        scope.launch { repository.renameBrushFolder(folder.id, trimmed) }
    }

    /** The folder goes; its presets stay and fall back to the top level. */
    fun deleteFolder(folder: BrushFolder) {
        scope.launch { repository.deleteBrushFolder(folder.id) }
    }

    fun moveToFolder(brush: BrushConfig, folderId: Long?) {
        val brushId = brush.id.toLongOrNull() ?: return
        scope.launch { repository.moveBrushToFolder(brushId, folderId) }
    }
}
