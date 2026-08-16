package com.yighy.paintcursor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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

        // No migrations are carried while the app is pre-1.0. A schema change bumps the
        // version and the database is thrown away and rebuilt, which is the trade SchemaPolicy
        // already describes: nobody's work is in that file yet, and a migration per experiment
        // costs more than it protects. Once 1.0 ships the fallback disappears on its own and
        // the first real Migration will start from whatever version ships with it.

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "drawing_database"
                )

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
