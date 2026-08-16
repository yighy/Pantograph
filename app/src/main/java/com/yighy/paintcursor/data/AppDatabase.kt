package com.yighy.paintcursor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yighy.paintcursor.BuildConfig

@Database(
    entities = [ProjectEntity::class, LayerEntity::class, StrokeEntity::class, CustomBrushEntity::class, BrushFolderEntity::class],
    version = 17,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Migrations are written as they go, but while the app is pre-1.0 a missing one still
        // falls back to wiping the database. SchemaPolicy turns that off automatically at 1.0,
        // so the safety net disappears the moment real users' work is on the line.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN lastCursorSensitivity REAL NOT NULL DEFAULT 0.6")
                db.execSQL("ALTER TABLE projects ADD COLUMN lastActiveLayerId INTEGER NOT NULL DEFAULT -1")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_brushes ADD COLUMN scatterJitter REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE projects ADD COLUMN lastScatterJitter REAL NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS brush_folders (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL)"
                )
                // Nullable with no default: existing presets start outside any folder.
                db.execSQL("ALTER TABLE custom_brushes ADD COLUMN folderId INTEGER")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("flowJitter", "taperStart", "rotationFollow").forEach {
                    db.execSQL("ALTER TABLE custom_brushes ADD COLUMN $it REAL NOT NULL DEFAULT 0")
                }
                // Follow-direction was a switch; carry each preset's setting over as 0 or 1 so
                // existing brushes keep behaving exactly as they did.
                db.execSQL("UPDATE custom_brushes SET rotationFollow = 1.0 WHERE rotationDynamics != 0")
                listOf("lastFlowJitter", "lastTaperStart", "lastRotationFollow").forEach {
                    db.execSQL("ALTER TABLE projects ADD COLUMN $it REAL NOT NULL DEFAULT 0")
                }
            }
        }

        /**
         * Drops the two retired brush parameters - the withdrawn taper, and the follow-direction
         * boolean that `rotationFollow` replaced.
         *
         * By table rebuild rather than DROP COLUMN: that arrived in SQLite 3.35, and minSdk 31
         * ships 3.32. The column lists below are written out in full because the copy step
         * cannot use `SELECT *` - a mismatched column would be silently written into the wrong
         * field rather than failing.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE custom_brushes_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, size REAL NOT NULL, softness REAL NOT NULL, " +
                        "opacity REAL NOT NULL, flow REAL NOT NULL, spacing REAL NOT NULL, " +
                        "smoothing REAL NOT NULL, colorArgb INTEGER NOT NULL, rotation REAL NOT NULL, " +
                        "rotationJitter REAL NOT NULL, sizeJitter REAL NOT NULL, " +
                        "scatterJitter REAL NOT NULL, flowJitter REAL NOT NULL, " +
                        "rotationFollow REAL NOT NULL, folderId INTEGER, " +
                        "tipUri TEXT, textureUri TEXT, velocityEnabled INTEGER NOT NULL, " +
                        "velocitySize REAL NOT NULL, velocityFlow REAL NOT NULL, " +
                        "velocityScatter REAL NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO custom_brushes_new SELECT id, name, size, softness, opacity, " +
                        "flow, spacing, smoothing, colorArgb, rotation, rotationJitter, sizeJitter, " +
                        "scatterJitter, flowJitter, rotationFollow, folderId, tipUri, textureUri, " +
                        "velocityEnabled, velocitySize, velocityFlow, velocityScatter " +
                        "FROM custom_brushes"
                )
                db.execSQL("DROP TABLE custom_brushes")
                db.execSQL("ALTER TABLE custom_brushes_new RENAME TO custom_brushes")

                db.execSQL(
                    "CREATE TABLE projects_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, thumbnailPath TEXT, " +
                        "lastBrushSize REAL NOT NULL, lastBrushSoftness REAL NOT NULL, " +
                        "lastBrushOpacity REAL NOT NULL, lastBrushFlow REAL NOT NULL, " +
                        "lastBrushSpacing REAL NOT NULL, lastBrushSmoothing REAL NOT NULL, " +
                        "lastBrushColor INTEGER NOT NULL, lastBrushRotation REAL NOT NULL, " +
                        "lastBrushRotationJitter REAL NOT NULL, lastSizeJitter REAL NOT NULL, " +
                        "lastScatterJitter REAL NOT NULL, lastFlowJitter REAL NOT NULL, " +
                        "lastRotationFollow REAL NOT NULL, lastBrushTipUri TEXT, " +
                        "lastBrushTextureUri TEXT, " +
                        "lastCursorSensitivity REAL NOT NULL DEFAULT 0.6, " +
                        "lastActiveLayerId INTEGER NOT NULL DEFAULT -1, " +
                        "referenceImageUri TEXT, referenceImageOffsetX REAL NOT NULL, " +
                        "referenceImageOffsetY REAL NOT NULL, referenceImageScale REAL NOT NULL, " +
                        "referenceImageRotation REAL NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO projects_new SELECT id, name, width, height, createdAt, updatedAt, " +
                        "thumbnailPath, lastBrushSize, lastBrushSoftness, lastBrushOpacity, " +
                        "lastBrushFlow, lastBrushSpacing, lastBrushSmoothing, lastBrushColor, " +
                        "lastBrushRotation, lastBrushRotationJitter, lastSizeJitter, lastScatterJitter, " +
                        "lastFlowJitter, lastRotationFollow, lastBrushTipUri, lastBrushTextureUri, " +
                        "lastCursorSensitivity, lastActiveLayerId, referenceImageUri, " +
                        "referenceImageOffsetX, referenceImageOffsetY, referenceImageScale, " +
                        "referenceImageRotation FROM projects"
                )
                db.execSQL("DROP TABLE projects")
                db.execSQL("ALTER TABLE projects_new RENAME TO projects")
            }
        }

        /**
         * Size range: a floor and a ceiling on what the size jitter and velocity response may
         * produce together. Plain ADD COLUMNs, so nothing has to be copied.
         *
         * The ceiling defaults to 9 rather than to something tidier: that is the widest the
         * velocity response reaches (3^2), so every existing preset keeps painting exactly as
         * it did until someone narrows the range by hand. A default of, say, 3 would have
         * quietly re-scaled every brush running velocity size above +100%.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_brushes ADD COLUMN sizeRangeMin REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE custom_brushes ADD COLUMN sizeRangeMax REAL NOT NULL DEFAULT 9")
                db.execSQL("ALTER TABLE projects ADD COLUMN lastSizeRangeMin REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE projects ADD COLUMN lastSizeRangeMax REAL NOT NULL DEFAULT 9")
            }
        }

        /**
         * Replaces the short-lived size *range* with a single size *multiplier*.
         *
         * v16's two range columns are dropped rather than left behind: Room validates the whole
         * table, so a column the entity no longer declares fails the identity check. That means
         * a rebuild (SQLite gained DROP COLUMN in 3.35; minSdk 31 ships 3.32), with the column
         * lists written out in full because the copy cannot use `SELECT *`.
         *
         * 15 -> 16 is deliberately left in place above rather than rewritten. A device that
         * already ran the v16 build has those columns, and quietly redefining what v16 means
         * would leave it with a table Room cannot verify - which, with the pre-1.0 destructive
         * fallback still armed, is the difference between a migration and a wiped project list.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE custom_brushes_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, size REAL NOT NULL, softness REAL NOT NULL, " +
                        "opacity REAL NOT NULL, flow REAL NOT NULL, spacing REAL NOT NULL, " +
                        "smoothing REAL NOT NULL, colorArgb INTEGER NOT NULL, rotation REAL NOT NULL, " +
                        "rotationJitter REAL NOT NULL, sizeJitter REAL NOT NULL, " +
                        "scatterJitter REAL NOT NULL, flowJitter REAL NOT NULL, " +
                        "rotationFollow REAL NOT NULL, folderId INTEGER, " +
                        "tipUri TEXT, textureUri TEXT, velocityEnabled INTEGER NOT NULL, " +
                        "velocitySize REAL NOT NULL, velocityFlow REAL NOT NULL, " +
                        "velocityScatter REAL NOT NULL, " +
                        "sizeMultiplier REAL NOT NULL DEFAULT 1)"
                )
                // 1 for the new column: presets keep painting at the size they were saved with.
                db.execSQL(
                    "INSERT INTO custom_brushes_new SELECT id, name, size, softness, opacity, " +
                        "flow, spacing, smoothing, colorArgb, rotation, rotationJitter, sizeJitter, " +
                        "scatterJitter, flowJitter, rotationFollow, folderId, tipUri, textureUri, " +
                        "velocityEnabled, velocitySize, velocityFlow, velocityScatter, 1 " +
                        "FROM custom_brushes"
                )
                db.execSQL("DROP TABLE custom_brushes")
                db.execSQL("ALTER TABLE custom_brushes_new RENAME TO custom_brushes")

                db.execSQL(
                    "CREATE TABLE projects_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, thumbnailPath TEXT, " +
                        "lastBrushSize REAL NOT NULL, lastBrushSoftness REAL NOT NULL, " +
                        "lastBrushOpacity REAL NOT NULL, lastBrushFlow REAL NOT NULL, " +
                        "lastBrushSpacing REAL NOT NULL, lastBrushSmoothing REAL NOT NULL, " +
                        "lastBrushColor INTEGER NOT NULL, lastBrushRotation REAL NOT NULL, " +
                        "lastBrushRotationJitter REAL NOT NULL, lastSizeJitter REAL NOT NULL, " +
                        "lastScatterJitter REAL NOT NULL, lastFlowJitter REAL NOT NULL, " +
                        "lastRotationFollow REAL NOT NULL, lastBrushTipUri TEXT, " +
                        "lastBrushTextureUri TEXT, " +
                        "lastCursorSensitivity REAL NOT NULL DEFAULT 0.6, " +
                        "lastActiveLayerId INTEGER NOT NULL DEFAULT -1, " +
                        "lastSizeMultiplier REAL NOT NULL DEFAULT 1, " +
                        "referenceImageUri TEXT, referenceImageOffsetX REAL NOT NULL, " +
                        "referenceImageOffsetY REAL NOT NULL, referenceImageScale REAL NOT NULL, " +
                        "referenceImageRotation REAL NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO projects_new SELECT id, name, width, height, createdAt, updatedAt, " +
                        "thumbnailPath, lastBrushSize, lastBrushSoftness, lastBrushOpacity, " +
                        "lastBrushFlow, lastBrushSpacing, lastBrushSmoothing, lastBrushColor, " +
                        "lastBrushRotation, lastBrushRotationJitter, lastSizeJitter, lastScatterJitter, " +
                        "lastFlowJitter, lastRotationFollow, lastBrushTipUri, lastBrushTextureUri, " +
                        "lastCursorSensitivity, lastActiveLayerId, 1, referenceImageUri, " +
                        "referenceImageOffsetX, referenceImageOffsetY, referenceImageScale, " +
                        "referenceImageRotation FROM projects"
                )
                db.execSQL("DROP TABLE projects")
                db.execSQL("ALTER TABLE projects_new RENAME TO projects")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "drawing_database"
                ).addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)

                if (SchemaPolicy.allowsDestructiveFallback(BuildConfig.VERSION_NAME)) {
                    builder.fallbackToDestructiveMigration(dropAllTables = true)
                }

                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }
    }
}
