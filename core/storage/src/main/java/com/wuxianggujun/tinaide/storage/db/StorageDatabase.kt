package com.wuxianggujun.tinaide.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 存储数据库（项目位置）
 */
@Database(
    entities = [
        ProjectLocationEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class StorageDatabase : RoomDatabase() {

    abstract fun projectLocationDao(): ProjectLocationDao

    companion object {
        @Volatile
        private var instanceRef: StorageDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE project_locations ADD COLUMN source_root_path TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `project_locations_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `project_id` TEXT NOT NULL,
                        `project_dir_name` TEXT NOT NULL,
                        `source_root_path` TEXT NOT NULL,
                        `registered` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `project_locations_new` (
                        `project_id`,
                        `project_dir_name`,
                        `source_root_path`,
                        `registered`
                    )
                    SELECT
                        `project_id`,
                        `project_dir_name`,
                        CASE
                            WHEN TRIM(COALESCE(`source_root_path`, '')) = ''
                                THEN '${ProjectLocationEntity.LEGACY_PENDING_SOURCE_ROOT_PREFIX}' || `project_id`
                            ELSE `source_root_path`
                        END,
                        `registered`
                    FROM `project_locations`
                    WHERE TRIM(COALESCE(`project_id`, '')) != ''
                    ORDER BY `registered` ASC
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `project_locations`")
                db.execSQL("ALTER TABLE `project_locations_new` RENAME TO `project_locations`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_project_locations_project_id` ON `project_locations` (`project_id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_project_locations_source_root_path` ON `project_locations` (`source_root_path`)"
                )
            }
        }

        fun getInstance(context: Context): StorageDatabase = instanceRef ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                StorageDatabase::class.java,
                "tinaide_storage.db"
            )
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .build()
            instanceRef = instance
            instance
        }
    }
}
