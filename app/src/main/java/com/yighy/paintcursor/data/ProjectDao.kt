package com.yighy.paintcursor.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("SELECT * FROM layers WHERE projectId = :projectId ORDER BY zIndex ASC")
    fun getLayersForProject(projectId: Long): Flow<List<LayerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLayer(layer: LayerEntity): Long

    @Update
    suspend fun updateLayer(layer: LayerEntity)
    
    @Delete
    suspend fun deleteLayer(layer: LayerEntity)

    @Query("SELECT * FROM strokes WHERE layerId = :layerId")
    suspend fun getStrokesForLayer(layerId: Long): List<StrokeEntity>

    @Insert
    suspend fun insertStroke(stroke: StrokeEntity): Long

    @Query("DELETE FROM strokes WHERE layerId = :layerId")
    suspend fun clearStrokesForLayer(layerId: Long)
    
    @Transaction
    suspend fun saveStrokesForLayer(layerId: Long, strokes: List<StrokeEntity>) {
        clearStrokesForLayer(layerId)
        strokes.forEach { insertStroke(it) }
    }

    @Query("UPDATE projects SET thumbnailPath = :thumbnailPath WHERE id = :projectId")
    suspend fun updateProjectThumbnail(projectId: Long, thumbnailPath: String)

    @Query("UPDATE projects SET name = :name, updatedAt = :timestamp WHERE id = :projectId")
    suspend fun updateProjectName(projectId: Long, name: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET updatedAt = :timestamp WHERE id = :projectId")
    suspend fun updateProjectTimestamp(projectId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE layers SET zIndex = :zIndex WHERE id = :layerId")
    suspend fun updateLayerZIndex(layerId: Long, zIndex: Int)

    @Query("SELECT * FROM strokes WHERE layerId = :layerId")
    suspend fun getStrokesForLayerSync(layerId: Long): List<StrokeEntity>

    // Custom Brushes
    @Query("SELECT * FROM custom_brushes")
    fun getAllCustomBrushes(): Flow<List<CustomBrushEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomBrush(brush: CustomBrushEntity): Long

    @Query("UPDATE custom_brushes SET name = :newName WHERE id = :brushId")
    suspend fun renameCustomBrush(brushId: Long, newName: String)

    @Delete
    suspend fun deleteCustomBrush(brush: CustomBrushEntity)
}
