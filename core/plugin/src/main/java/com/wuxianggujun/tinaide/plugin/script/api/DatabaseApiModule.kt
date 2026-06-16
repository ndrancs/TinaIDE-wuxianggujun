package com.wuxianggujun.tinaide.plugin.script.api

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import party.iroiro.luajava.Lua
import timber.log.Timber

/**
 * 插件数据库 API 模块
 *
 * 为 Lua 插件提供 SQLite 数据库访问能力，通过 tina.db 暴露以下方法：
 * - execute(sql, params): 执行写操作（INSERT/UPDATE/DELETE/CREATE）
 * - query(sql, params): 执行查询，返回结果数组
 * - transaction(callback): 事务执行
 * - close(): 关闭数据库连接
 *
 * 每个插件使用独立的数据库文件：plugin_<pluginId>.db
 */
class DatabaseApiModule(private val context: Context) : PluginApiModule {
    override val namespace = "db"

    private var runtime: ScriptPluginRuntime? = null
    private var dbHelper: PluginDatabaseHelper? = null
    private var database: SQLiteDatabase? = null

    override fun register(runtime: ScriptPluginRuntime, lua: Lua) {
        this.runtime = runtime
        val pluginId = runtime.pluginId

        lua.push { L: Lua ->
            val rt = this.runtime ?: return@push 0
            if (!rt.checkPermission(PluginPermission.STORAGE_DATABASE)) {
                rt.reportPermissionDenied(namespace, "execute", PluginPermission.STORAGE_DATABASE)
                L.push(-1)
                return@push 1
            }

            val sql = if (L.top >= 1) L.toString(1) else null
            if (sql == null) {
                L.push(-1)
                return@push 1
            }

            try {
                ensureDatabase(pluginId)
                val params = extractParams(L, 2)
                val db = database ?: throw IllegalStateException("Database not initialized")

                if (params.isEmpty()) {
                    db.execSQL(sql)
                } else {
                    db.execSQL(sql, params.toTypedArray())
                }

                // 获取受影响的行数
                val affectedRows = getAffectedRows(db)
                L.push(affectedRows)
            } catch (e: Exception) {
                Timber.e(e, "Database execute error: $sql")
                L.push(-1)
            }
            1
        }
        lua.setField(-2, "execute")

        // query(sql, params) -> rows[]
        lua.push { L: Lua ->
            val rt = this.runtime ?: return@push 0
            if (!rt.checkPermission(PluginPermission.STORAGE_DATABASE)) {
                rt.reportPermissionDenied(namespace, "query", PluginPermission.STORAGE_DATABASE)
                L.createTable(0, 0)
                return@push 1
            }

            val sql = if (L.top >= 1) L.toString(1) else null
            if (sql == null) {
                L.createTable(0, 0)
                return@push 1
            }

            try {
                ensureDatabase(pluginId)
                val params = extractStringParams(L, 2)
                val db = database ?: throw IllegalStateException("Database not initialized")

                val cursor = db.rawQuery(sql, params.toTypedArray())
                val results = mutableListOf<Map<String, Any?>>()

                cursor.use {
                    val columnNames = it.columnNames
                    while (it.moveToNext()) {
                        val row = mutableMapOf<String, Any?>()
                        for (i in columnNames.indices) {
                            row[columnNames[i]] = when (it.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> null
                                android.database.Cursor.FIELD_TYPE_INTEGER -> it.getLong(i)
                                android.database.Cursor.FIELD_TYPE_FLOAT -> it.getDouble(i)
                                android.database.Cursor.FIELD_TYPE_STRING -> it.getString(i)
                                android.database.Cursor.FIELD_TYPE_BLOB -> it.getBlob(i)
                                else -> it.getString(i)
                            }
                        }
                        results.add(row)
                    }
                }

                pushResultsToLua(L, results)
            } catch (e: Exception) {
                Timber.e(e, "Database query error: $sql")
                L.createTable(0, 0)
            }
            1
        }
        lua.setField(-2, "query")

        // transaction(callback) -> success
        lua.push { L: Lua ->
            val rt = this.runtime ?: return@push 0
            if (!rt.checkPermission(PluginPermission.STORAGE_DATABASE)) {
                rt.reportPermissionDenied(namespace, "transaction", PluginPermission.STORAGE_DATABASE)
                L.push(false)
                return@push 1
            }

            if (L.top < 1 || !L.isFunction(1)) {
                L.push(false)
                return@push 1
            }

            try {
                ensureDatabase(pluginId)
                val db = database ?: throw IllegalStateException("Database not initialized")

                db.beginTransaction()
                try {
                    L.pushValue(1)
                    L.pCall(0, 0)
                    db.setTransactionSuccessful()
                    L.push(true)
                } catch (e: Exception) {
                    Timber.e(e, "Transaction callback exception")
                    L.push(false)
                } finally {
                    db.endTransaction()
                }
            } catch (e: Exception) {
                Timber.e(e, "Transaction error")
                L.push(false)
            }
            1
        }
        lua.setField(-2, "transaction")

        // close()
        lua.push { _: Lua ->
            closeDatabase()
            0
        }
        lua.setField(-2, "close")

        // tableExists(tableName) -> boolean
        lua.push { L: Lua ->
            val rt = this.runtime ?: return@push 0
            if (!rt.checkPermission(PluginPermission.STORAGE_DATABASE)) {
                rt.reportPermissionDenied(namespace, "tableExists", PluginPermission.STORAGE_DATABASE)
                L.push(false)
                return@push 1
            }

            val tableName = if (L.top >= 1) L.toString(1) else null
            if (tableName == null) {
                L.push(false)
                return@push 1
            }

            try {
                ensureDatabase(pluginId)
                val db = database ?: throw IllegalStateException("Database not initialized")

                val cursor = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf(tableName)
                )
                val exists = cursor.use { it.count > 0 }
                L.push(exists)
            } catch (e: Exception) {
                Timber.e(e, "tableExists error")
                L.push(false)
            }
            1
        }
        lua.setField(-2, "tableExists")
    }

    override fun unregister() {
        closeDatabase()
        runtime = null
    }

    private fun ensureDatabase(pluginId: String) {
        if (database == null || database?.isOpen != true) {
            dbHelper = PluginDatabaseHelper(context, pluginId)
            database = dbHelper?.writableDatabase
        }
    }

    private fun closeDatabase() {
        try {
            database?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing database")
        }
        database = null
        try {
            dbHelper?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing dbHelper")
        }
        dbHelper = null
    }

    private fun getAffectedRows(db: SQLiteDatabase): Long = try {
        db.compileStatement("SELECT changes()").use { it.simpleQueryForLong() }
    } catch (e: Exception) {
        0L
    }

    private fun extractParams(L: Lua, index: Int): List<Any> {
        if (L.top < index || L.isNil(index)) return emptyList()

        val params = mutableListOf<Any>()
        if (L.isTable(index)) {
            L.pushNil()
            while (L.next(index) != 0) {
                when {
                    L.isNumber(-1) -> params.add(L.toNumber(-1))
                    L.isString(-1) -> params.add(L.toString(-1) ?: "")
                    L.isBoolean(-1) -> params.add(if (L.toBoolean(-1)) 1 else 0)
                    else -> params.add(L.toString(-1) ?: "")
                }
                L.pop(1)
            }
        }
        return params
    }

    private fun extractStringParams(L: Lua, index: Int): List<String> = extractParams(L, index).map { it.toString() }

    private fun pushResultsToLua(L: Lua, results: List<Map<String, Any?>>) {
        L.createTable(results.size, 0)
        results.forEachIndexed { rowIndex, row ->
            L.createTable(0, row.size)
            row.forEach { (key, value) ->
                when (value) {
                    null -> L.pushNil()
                    is Long -> L.push(value)
                    is Double -> L.push(value)
                    is String -> L.push(value)
                    is ByteArray -> L.push(String(value, Charsets.UTF_8))
                    else -> L.push(value.toString())
                }
                L.setField(-2, key)
            }
            L.rawSetI(-2, rowIndex + 1)
        }
    }

    /**
     * 插件专用数据库 Helper
     * 每个插件使用独立的数据库文件
     */
    private class PluginDatabaseHelper(
        context: Context,
        pluginId: String
    ) : SQLiteOpenHelper(
        context,
        "plugin_${sanitizePluginId(pluginId)}.db",
        null,
        1
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            // 插件自行创建表，这里不做任何操作
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 插件自行管理版本升级
        }

        companion object {
            /**
             * 清理插件 ID，确保可以作为文件名使用
             */
            private fun sanitizePluginId(pluginId: String): String = pluginId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        }
    }
}
