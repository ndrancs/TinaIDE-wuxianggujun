package com.wuxianggujun.tinaide.database.user

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wuxianggujun.tinaide.core.ai.db.AiChannelDao
import com.wuxianggujun.tinaide.core.ai.db.AiChannelEntity
import com.wuxianggujun.tinaide.core.ai.db.ChatMessageDao
import com.wuxianggujun.tinaide.core.ai.db.ChatMessageEntity
import com.wuxianggujun.tinaide.core.ai.db.ConversationDao
import com.wuxianggujun.tinaide.core.ai.db.ConversationEntity

/**
 * 用户内容数据库（收藏、下载历史）
 *
 * 架构说明：
 * - 数据库实现在 core:database 层
 * - 通过 IUserContentRepository 接口对外暴露（定义在 core:common）
 * - 遵循依赖倒置原则（DIP）
 */
@Database(
    entities = [
        FavoriteEntity::class,
        DownloadHistoryEntity::class,
        ConversationEntity::class,
        ChatMessageEntity::class,
        AiChannelEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class UserContentDatabase : RoomDatabase() {

    abstract fun favoriteDao(): FavoriteDao
    abstract fun downloadHistoryDao(): DownloadHistoryDao
    abstract fun conversationDao(): ConversationDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun aiChannelDao(): AiChannelDao

    companion object {
        private fun createTableIfMissing(
            database: SupportSQLiteDatabase,
            tableName: String,
            createSql: String
        ) {
            val cursor = database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(tableName)
            )
            cursor.use {
                if (cursor.moveToFirst()) {
                    return
                }
            }
            database.execSQL(createSql)
        }

        private fun addColumnIfMissing(
            database: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            alterSql: String
        ) {
            val cursor = database.query("PRAGMA table_info(`$tableName`)")
            cursor.use {
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == columnName) {
                        return
                    }
                }
            }
            database.execSQL(alterSql)
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createTableIfMissing(
                    database = db,
                    tableName = "conversations",
                    createSql = """
                        CREATE TABLE IF NOT EXISTS `conversations` (
                            `id` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `created_at` INTEGER NOT NULL,
                            `updated_at` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent()
                )

                createTableIfMissing(
                    database = db,
                    tableName = "chat_messages",
                    createSql = """
                        CREATE TABLE IF NOT EXISTS `chat_messages` (
                            `id` TEXT NOT NULL,
                            `conversation_id` TEXT NOT NULL,
                            `role` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `reasoning_content` TEXT,
                            `timestamp` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_messages_conversation_id` ON `chat_messages` (`conversation_id`)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    database = db,
                    tableName = "conversations",
                    columnName = "tool_execution_mode",
                    alterSql = "ALTER TABLE conversations ADD COLUMN tool_execution_mode TEXT NOT NULL DEFAULT 'AUTO'"
                )

                addColumnIfMissing(
                    database = db,
                    tableName = "chat_messages",
                    columnName = "content_parts_json",
                    alterSql = "ALTER TABLE chat_messages ADD COLUMN content_parts_json TEXT"
                )
                addColumnIfMissing(
                    database = db,
                    tableName = "chat_messages",
                    columnName = "tool_calls_json",
                    alterSql = "ALTER TABLE chat_messages ADD COLUMN tool_calls_json TEXT"
                )
                addColumnIfMissing(
                    database = db,
                    tableName = "chat_messages",
                    columnName = "tool_call_id",
                    alterSql = "ALTER TABLE chat_messages ADD COLUMN tool_call_id TEXT"
                )
                addColumnIfMissing(
                    database = db,
                    tableName = "chat_messages",
                    columnName = "usage_json",
                    alterSql = "ALTER TABLE chat_messages ADD COLUMN usage_json TEXT"
                )
                addColumnIfMissing(
                    database = db,
                    tableName = "chat_messages",
                    columnName = "tool_execution_states_json",
                    alterSql = "ALTER TABLE chat_messages ADD COLUMN tool_execution_states_json TEXT"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createTableIfMissing(
                    database = db,
                    tableName = "ai_channels",
                    createSql = """
                        CREATE TABLE IF NOT EXISTS `ai_channels` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `provider` TEXT NOT NULL,
                            `base_url` TEXT NOT NULL,
                            `model` TEXT NOT NULL,
                            `created_at` INTEGER NOT NULL,
                            `updated_at` INTEGER NOT NULL,
                            `last_used_at` INTEGER,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_channels_last_used_at` ON `ai_channels` (`last_used_at`)"
                )
            }
        }

        @Volatile
        private var instanceRef: UserContentDatabase? = null

        fun getInstance(context: Context): UserContentDatabase = instanceRef ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                UserContentDatabase::class.java,
                "user_content_database"
            )
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .build()
            instanceRef = instance
            instance
        }

        /**
         * 用于测试的实例清理
         */
        fun clearInstance() {
            instanceRef = null
        }
    }
}
