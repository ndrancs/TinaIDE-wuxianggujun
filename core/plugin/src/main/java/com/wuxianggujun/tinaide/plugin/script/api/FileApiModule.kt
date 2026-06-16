package com.wuxianggujun.tinaide.plugin.script.api

import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import party.iroiro.luajava.Lua

class FileApiModule internal constructor(
    private val fileAccess: PluginWorkspaceFileAccess
) : PluginApiModule {
    override val namespace = "fs"

    private var runtime: ScriptPluginRuntime? = null

    override fun register(runtime: ScriptPluginRuntime, lua: Lua) {
        this.runtime = runtime

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "readFile", PluginPermission.FILE_READ) {
                val rt = this.runtime ?: return@withPermission 0
                if (!rt.checkFileOpLimit()) {
                    L.pushNil()
                    L.push("File operation rate limit exceeded")
                    return@withPermission 2
                }
                val path = L.getStringArg(1)
                if (path == null) {
                    L.pushNil()
                    L.push("Path is required")
                    return@withPermission 2
                }
                val file = fileAccess.resolveSafePath(path)
                if (file == null) {
                    L.pushNil()
                    L.push("Invalid path or access denied")
                    return@withPermission 2
                }
                try {
                    if (!file.exists()) {
                        L.pushNil()
                        L.push("File not found: $path")
                        return@withPermission 2
                    }
                    L.push(file.readText(Charsets.UTF_8))
                    1
                } catch (e: Exception) {
                    L.pushNil()
                    L.push("Failed to read file: ${e.message}")
                    2
                }
            }
        }
        lua.setField(-2, "readFile")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "writeFile", PluginPermission.FILE_WRITE) {
                val rt = this.runtime ?: return@withPermission 0
                if (!rt.checkFileOpLimit()) {
                    L.push(false)
                    L.push("File operation rate limit exceeded")
                    return@withPermission 2
                }
                val path = L.getStringArg(1)
                val content = L.getStringArg(2)
                if (path == null || content == null) {
                    L.push(false)
                    L.push("Path and content are required")
                    return@withPermission 2
                }
                val file = fileAccess.resolveSafePath(path)
                if (file == null) {
                    L.push(false)
                    L.push("Invalid path or access denied")
                    return@withPermission 2
                }
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(content, Charsets.UTF_8)
                    L.push(true)
                    1
                } catch (e: Exception) {
                    L.push(false)
                    L.push("Failed to write file: ${e.message}")
                    2
                }
            }
        }
        lua.setField(-2, "writeFile")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "exists", PluginPermission.FILE_READ) {
                val path = L.getStringArg(1)
                if (path == null) {
                    L.push(false)
                    return@withPermission 1
                }
                val file = fileAccess.resolveSafePath(path)
                L.push(file?.exists() == true)
                1
            }
        }
        lua.setField(-2, "exists")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "isDirectory", PluginPermission.FILE_READ) {
                val path = L.getStringArg(1)
                if (path == null) {
                    L.push(false)
                    return@withPermission 1
                }
                val file = fileAccess.resolveSafePath(path)
                L.push(file?.isDirectory == true)
                1
            }
        }
        lua.setField(-2, "isDirectory")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "listDir", PluginPermission.FILE_READ) {
                val path = L.getStringArg(1)
                if (path == null) {
                    L.pushNil()
                    return@withPermission 1
                }
                val file = fileAccess.resolveSafePath(path)
                if (file == null || !file.exists() || !file.isDirectory) {
                    L.pushNil()
                    return@withPermission 1
                }
                val files = file.listFiles()?.map { it.name } ?: emptyList()
                L.pushStringList(files)
            }
        }
        lua.setField(-2, "listDir")

        lua.push { L: Lua ->
            withPermission(this.runtime, L, namespace, "mkdir", PluginPermission.FILE_WRITE) {
                val path = L.getStringArg(1)
                if (path == null) {
                    L.push(false)
                    return@withPermission 1
                }
                val file = fileAccess.resolveSafePath(path) ?: run {
                    L.push(false)
                    return@withPermission 1
                }
                L.push(file.mkdirs() || file.exists())
                1
            }
        }
        lua.setField(-2, "mkdir")
    }

    override fun unregister() {
        runtime = null
    }
}
