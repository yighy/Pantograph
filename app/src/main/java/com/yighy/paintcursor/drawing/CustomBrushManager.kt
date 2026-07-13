package com.yighy.paintcursor.drawing

import androidx.compose.ui.graphics.toArgb
import com.yighy.paintcursor.data.CustomBrushEntity
import com.yighy.paintcursor.data.ProjectRepository

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

    suspend fun updateExisting(brushId: Long, currentName: String, state: DrawingState) {
        repository.insertCustomBrush(entityFromState(brushId, currentName, state))
    }

    private fun entityFromState(id: Long, name: String, state: DrawingState) = CustomBrushEntity(
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
        rotationDynamics = state.brushRotationDynamics,
        rotationJitter = state.brushRotationJitter,
        sizeJitter = state.sizeJitter,
        tipUri = state.brushTipUri,
        textureUri = state.brushTextureUri,
        velocityEnabled = state.velocityEnabled,
        velocitySize = state.velocitySizeAmount,
        velocityFlow = state.velocityFlowAmount,
        velocityScatter = state.velocityScatterAmount
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
            sizeJitter = brush.sizeJitter
        ))
    }
}
