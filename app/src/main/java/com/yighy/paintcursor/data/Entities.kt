package com.yighy.paintcursor.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val width: Int,
    val height: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val thumbnailPath: String? = null,
    
    // Project-specific brush settings
    val lastBrushSize: Float = 20f,
    val lastBrushSoftness: Float = 0f,
    val lastBrushOpacity: Float = 1.0f,
    val lastBrushFlow: Float = 1.0f,
    val lastBrushSpacing: Float = 0.1f,
    val lastBrushSmoothing: Float = 0.5f,
    val lastBrushColor: Int = -16777216, // Black
    val lastBrushRotation: Float = 0f,
    val lastBrushRotationDynamics: Boolean = false,
    val lastBrushRotationJitter: Float = 0f,
    val lastSizeJitter: Float = 0f,
    val lastBrushTipUri: String? = null,
    val lastBrushTextureUri: String? = null,

    // Per-project workspace state (added in v11; defaults must match the migration)
    @ColumnInfo(defaultValue = "0.6")
    val lastCursorSensitivity: Float = 0.6f,
    @ColumnInfo(defaultValue = "-1")
    val lastActiveLayerId: Long = -1,

    // Reference Image settings
    val referenceImageUri: String? = null,
    val referenceImageOffsetX: Float = 0f,
    val referenceImageOffsetY: Float = 0f,
    val referenceImageScale: Float = 1f,
    val referenceImageRotation: Float = 0f
)

@Entity(
    tableName = "layers",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class LayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val isVisible: Boolean = true,
    val zIndex: Int,
    val opacity: Float = 1.0f,
    val imagePath: String? = null
)

@Entity(
    tableName = "strokes",
    foreignKeys = [
        ForeignKey(
            entity = LayerEntity::class,
            parentColumns = ["id"],
            childColumns = ["layerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("layerId")]
)
data class StrokeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val layerId: Long,
    val pointsJson: String,
    val colorArgb: Int,
    val width: Float,
    val mode: String // "Freehand" or "StraightLine"
)

@Entity(tableName = "custom_brushes")
data class CustomBrushEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val size: Float,
    val softness: Float = 0f,
    val opacity: Float = 1.0f,
    val flow: Float,
    val spacing: Float,
    val smoothing: Float,
    val colorArgb: Int,
    val rotation: Float,
    val rotationDynamics: Boolean = false,
    val rotationJitter: Float = 0f,
    val sizeJitter: Float,
    val tipUri: String? = null,
    val textureUri: String? = null,
    val velocityEnabled: Boolean = false,
    val velocitySize: Float = 0f,
    val velocityFlow: Float = 0f,
    val velocityScatter: Float = 0f
)
