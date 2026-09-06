package com.yighy.pantograph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yighy.pantograph.data.ProjectEntity
import com.yighy.pantograph.data.BrushFolderEntity
import com.yighy.pantograph.data.PreferenceManager
import com.yighy.pantograph.data.ProjectRepository
import com.yighy.pantograph.drawing.DefaultBrushes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeViewModel(
    private val repository: ProjectRepository,
    /** Where the drawing screen keeps layer pixels and thumbnails, so deletes can reclaim them. */
    private val internalFilesDir: File,
    private val preferenceManager: PreferenceManager
) : ViewModel() {
    val projects: StateFlow<List<ProjectEntity>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        sweepOrphanedFiles()
        seedDefaultBrushes()
    }

    /**
     * Installs the starter presets, once ever.
     *
     * Here rather than in the drawing screen because it should have happened before the first
     * project is opened - a brush library that fills itself in behind you the first time you
     * reach for it is worse than one that was always there.
     */
    private fun seedDefaultBrushes() {
        viewModelScope.launch {
            if (preferenceManager.defaultBrushesSeeded.first()) return@launch
            val folderId = repository.insertBrushFolder(BrushFolderEntity(name = DefaultBrushes.FOLDER_NAME))
            DefaultBrushes.entities(folderId).forEach { repository.insertCustomBrush(it) }
            preferenceManager.setDefaultBrushesSeeded(true)
        }
    }

    /**
     * Deletes layer and thumbnail images that no row claims any more.
     *
     * Nothing reclaimed them before this, so an install of any age is carrying the pixels of
     * every project and layer it has ever deleted. Going forward the delete paths clean up
     * after themselves; this is the one-off for what they already left behind, plus the cases
     * that still slip through - redoing a layer delete drops its row from inside the history
     * manager, which has no idea this directory exists.
     *
     * Deliberately narrow. Only names of the exact shape "layer_<digits>.png" and
     * "thumb_<digits>.png" are even considered, so the preferences datastore beside them, and
     * anything else that ever moves in here, is not something this has to be told about.
     */
    private fun sweepOrphanedFiles() {
        if (!sweptThisProcess.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            val layerIds = repository.getAllLayerIds().toHashSet()
            val projectIds = repository.getAllProjectIds().toHashSet()
            internalFilesDir.listFiles()?.forEach { file ->
                val name = file.name
                val orphan = when {
                    name.startsWith("layer_") && name.endsWith(".png") ->
                        name.removeSurrounding("layer_", ".png").toLongOrNull()?.let { it !in layerIds }
                    name.startsWith("thumb_") && name.endsWith(".png") ->
                        name.removeSurrounding("thumb_", ".png").toLongOrNull()?.let { it !in projectIds }
                    // A name that does not parse as an id is not ours to judge.
                    else -> null
                }
                if (orphan == true) file.delete()
            }
        }
    }

    companion object {
        /** The home screen is rebuilt every time you come back from a project; the sweep is not. */
        private val sweptThisProcess = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    fun createProject(name: String, width: Int, height: Int, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.createProject(name, width, height)
            onCreated(id)
        }
    }

    /**
     * Deleting the row cascades to the layers, but nothing has ever reclaimed the pixels those
     * layers left in internal storage - a deleted project went on costing its full size on
     * disk forever. The ids have to be read before the delete, since afterwards there is
     * nothing left to ask.
     */
    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch {
            val layerIds = repository.getLayersForProject(project.id).first().map { it.id }
            repository.deleteProject(project)
            withContext(Dispatchers.IO) {
                layerIds.forEach { File(internalFilesDir, "layer_$it.png").delete() }
                File(internalFilesDir, "thumb_${project.id}.png").delete()
            }
        }
    }

    fun renameProject(project: ProjectEntity, newName: String) {
        viewModelScope.launch {
            repository.updateProjectName(project.id, newName)
        }
    }
}
