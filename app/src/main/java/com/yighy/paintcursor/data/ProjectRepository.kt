package com.yighy.paintcursor.data

import kotlinx.coroutines.flow.Flow

class ProjectRepository(private val projectDao: ProjectDao) {
    val allProjects: Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    suspend fun getProjectById(id: Long): ProjectEntity? = projectDao.getProjectById(id)

    suspend fun createProject(name: String, width: Int, height: Int): Long {
        val projectId = projectDao.insertProject(ProjectEntity(name = name, width = width, height = height))
        // Create a default layer
        projectDao.insertLayer(LayerEntity(projectId = projectId, name = "Layer", zIndex = 0))
        return projectId
    }

    suspend fun deleteProject(project: ProjectEntity) = projectDao.deleteProject(project)

    fun getLayersForProject(projectId: Long): Flow<List<LayerEntity>> = projectDao.getLayersForProject(projectId)

    suspend fun insertLayer(layer: LayerEntity): Long = projectDao.insertLayer(layer)
    
    suspend fun updateLayer(layer: LayerEntity) = projectDao.updateLayer(layer)

    suspend fun updateProject(project: ProjectEntity) = projectDao.updateProject(project)
    
    suspend fun deleteLayer(layer: LayerEntity) = projectDao.deleteLayer(layer)

    suspend fun getStrokesForLayer(layerId: Long): List<StrokeEntity> = projectDao.getStrokesForLayer(layerId)

    suspend fun saveStrokesForLayer(layerId: Long, strokes: List<StrokeEntity>) = 
        projectDao.saveStrokesForLayer(layerId, strokes)

    suspend fun updateProjectThumbnail(projectId: Long, thumbnailPath: String) =
        projectDao.updateProjectThumbnail(projectId, thumbnailPath)

    suspend fun updateProjectName(projectId: Long, name: String) =
        projectDao.updateProjectName(projectId, name)

    suspend fun updateProjectTimestamp(projectId: Long) =
        projectDao.updateProjectTimestamp(projectId)

    suspend fun updateLayerZIndex(layerId: Long, zIndex: Int) =
        projectDao.updateLayerZIndex(layerId, zIndex)
    
    suspend fun getStrokesForLayerSync(layerId: Long) =
        projectDao.getStrokesForLayerSync(layerId)

    // Custom Brushes
    val allCustomBrushes: Flow<List<CustomBrushEntity>> = projectDao.getAllCustomBrushes()
    suspend fun insertCustomBrush(brush: CustomBrushEntity) = projectDao.insertCustomBrush(brush)
    suspend fun renameCustomBrush(brushId: Long, newName: String) = projectDao.renameCustomBrush(brushId, newName)
    suspend fun deleteCustomBrush(brush: CustomBrushEntity) = projectDao.deleteCustomBrush(brush)
}
