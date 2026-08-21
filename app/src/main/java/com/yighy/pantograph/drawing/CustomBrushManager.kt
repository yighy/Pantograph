package com.yighy.pantograph.drawing

import androidx.compose.ui.graphics.toArgb
import com.yighy.pantograph.data.CustomBrushEntity
import com.yighy.pantograph.data.ProjectRepository

/** The stored row as the preset shape the rest of the app works with. */
fun CustomBrushEntity.toBrushConfig() = BrushConfig(
    id = id.toString(),
    name = name,
    size = size,
    softness = softness,
    opacity = opacity,
    flow = flow,
    spacing = spacing,
    smoothing = smoothing,
    rotation = rotation,
    rotationJitter = rotationJitter,
    sizeJitter = sizeJitter,
    scatterJitter = scatterJitter,
    flowJitter = flowJitter,
    rotationFollow = rotationFollow,
    tipUri = tipUri,
    textureUri = textureUri,
    velocityEnabled = velocityEnabled,
    velocitySize = velocitySize,
    velocityFlow = velocityFlow,
    velocityScatter = velocityScatter,
    sizeMultiplier = sizeMultiplier,
    folderId = folderId
)

/**
 * Custom-brush CRUD for [DrawingViewModel]: persisting the current brush settings as a saved
 * preset, updating/renaming/deleting existing presets. Pure repository calls - the ViewModel
 * still owns reading/writing `DrawingState`.
 */
class CustomBrushManager(private val repository: ProjectRepository) {

    /** Returns the new brush's id, or null if [name] is already taken. */
    suspend fun saveAsNew(name: String, state: DrawingState): Long? {
        if (state.customBrushes.any { it.name.equals(name, ignoreCase = true) }) return null
        return repository.insertCustomBrush(entityFromState(0, name, state))
    }

    /**
     * [folderId] has to be carried in: this rebuilds the whole row from the live brush
     * settings and upserts it, and DrawingState knows nothing about filing - leaving it out
     * would quietly tip the preset out of its folder every time it was saved.
     */
    suspend fun updateExisting(brushId: Long, currentName: String, folderId: Long?, state: DrawingState) {
        repository.insertCustomBrush(entityFromState(brushId, currentName, state, folderId))
    }

    private fun entityFromState(
        id: Long,
        name: String,
        state: DrawingState,
        folderId: Long? = null
    ) = CustomBrushEntity(
        id = id,
        name = name,
        size = state.selectedWidth,
        softness = state.brushSoftness,
        opacity = state.brushOpacity,
        flow = state.brushFlow,
        spacing = state.brushSpacing,
        smoothing = state.brushSmoothing,
        colorArgb = state.selectedColor.toArgb(),
        rotation = state.brushRotation,
        rotationJitter = state.brushRotationJitter,
        sizeJitter = state.sizeJitter,
        scatterJitter = state.scatterJitter,
        flowJitter = state.flowJitter,
        rotationFollow = state.rotationFollow,
        folderId = folderId,
        tipUri = state.brushTipUri,
        textureUri = state.brushTextureUri,
        velocityEnabled = state.velocityEnabled,
        velocitySize = state.velocitySizeAmount,
        velocityFlow = state.velocityFlowAmount,
        velocityScatter = state.velocityScatterAmount,
        sizeMultiplier = state.sizeMultiplier
    )

    /**
     * Returns false without renaming if [newName] is blank or already used by another preset.
     * Updates only the name column so the brush's other settings (color, texture, jitter, ...)
     * are left untouched - [BrushConfig] doesn't carry every [CustomBrushEntity] field (e.g.
     * color), so rebuilding and upserting a whole entity from it would silently reset the rest.
     */
    suspend fun rename(brush: BrushConfig, newName: String, existingBrushes: List<BrushConfig>): Boolean {
        if (newName.isBlank() || existingBrushes.any { it.name.equals(newName, ignoreCase = true) }) return false
        repository.renameCustomBrush(brush.id.toLong(), newName)
        return true
    }

    suspend fun delete(brush: BrushConfig) {
        repository.deleteCustomBrush(CustomBrushEntity(
            id = brush.id.toLong(),
            name = brush.name,
            size = brush.size,
            softness = brush.softness,
            flow = brush.flow,
            spacing = brush.spacing,
            smoothing = brush.smoothing,
            colorArgb = 0,
            rotation = brush.rotation,
            sizeJitter = brush.sizeJitter,
            scatterJitter = brush.scatterJitter,
            flowJitter = brush.flowJitter,
            rotationFollow = brush.rotationFollow
        ))
    }
}
