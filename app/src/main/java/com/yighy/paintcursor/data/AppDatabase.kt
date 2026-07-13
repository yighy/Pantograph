package com.yighy.paintcursor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProjectEntity::class, LayerEntity::class, StrokeEntity::class, CustomBrushEntity::class],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Now that builds are distributed (v0.1.0+), every schema change needs a real
        // Migration - the destructive fallback below only remains as a last resort for
        // pre-release database versions.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN lastCursorSensitivity REAL NOT NULL DEFAULT 0.6")
                db.execSQL("ALTER TABLE projects ADD COLUMN lastActiveLayerId INTEGER NOT NULL DEFAULT -1")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "drawing_database"
                )
                .addMigrations(MIGRATION_10_11)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
