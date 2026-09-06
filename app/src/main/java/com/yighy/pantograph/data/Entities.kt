package com.yighy.pantograph.data

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
    val lastBrushRotationJitter: Float = 0f,
    val lastSizeJitter: Float = 0f,
    val lastScatterJitter: Float = 0f,
    val lastFlowJitter: Float = 0f,
    val lastRotationFollow: Float = 0f,
    val lastBrushTipUri: String? = null,
    val lastBrushTextureUri: String? = null,

    // Per-project workspace state
    @ColumnInfo(defaultValue = "0.6")
    val lastCursorSensitivity: Float = 0.6f,
    @ColumnInfo(defaultValue = "-1")
    val lastActiveLayerId: Long = -1,

    // Scales the brush size past the slider's ceiling
    @ColumnInfo(defaultValue = "1")
    val lastSizeMultiplier: Float = 1f,

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
    val imagePath: String? = null,
    /**
     * A working layer rather than part of the drawing: it renders on the canvas but is left
     * out of exports and of the home-grid thumbnail. Undone strokes are collected here when
     * the setting asks for it, and it is an ordinary layer in every other respect - erase it,
     * hide it, delete it.
     */
    val isTrace: Boolean = false,
    /**
     * Protects the pixels, not the layer. A locked layer still shows, still moves up and down
     * the stack, still renames and takes an opacity - what it refuses is anything that would
     * change what is drawn on it, which is the thing you cannot get back by flipping the flag
     * again.
     */
    val isLocked: Boolean = false
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

/**
 * A named grouping for brush presets. Presets point at a folder rather than folders holding a
 * list, so a folder can sit empty and renaming one touches a single row.
 */
@Entity(tableName = "brush_folders")
data class BrushFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
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
    val rotationJitter: Float = 0f,
    val sizeJitter: Float,
    val scatterJitter: Float = 0f,
    val flowJitter: Float = 0f,
    /** How much of the path's angle the stamp adopts, 0..1. */
    val rotationFollow: Float = 0f,
    /** Null means the preset sits outside any folder. */
    val folderId: Long? = null,
    val tipUri: String? = null,
    val textureUri: String? = null,
    val velocityEnabled: Boolean = false,
    val velocitySize: Float = 0f,
    val velocityFlow: Float = 0f,
    val velocityScatter: Float = 0f,

    // Scales `size` past the slider's ceiling
    @ColumnInfo(defaultValue = "1")
    val sizeMultiplier: Float = 1f,

    /** Name of a [com.yighy.pantograph.drawing.TipShape]; unknown values read as Round. */
    @ColumnInfo(defaultValue = "Round")
    val tipShape: String = "Round",
    @ColumnInfo(defaultValue = "1")
    val tipRatio: Float = 1f,

    @ColumnInfo(defaultValue = "1")
    val antiAlias: Boolean = true,

    @ColumnInfo(defaultValue = "0")
    val hueJitter: Float = 0f,
    @ColumnInfo(defaultValue = "0")
    val saturationJitter: Float = 0f,
    @ColumnInfo(defaultValue = "0")
    val valueJitter: Float = 0f,

    @ColumnInfo(defaultValue = "0")
    val smudge: Float = 0f,
    @ColumnInfo(defaultValue = "0.5")
    val smudgeLength: Float = 0.5f
)
