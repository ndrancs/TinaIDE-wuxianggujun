package com.wuxianggujun.tinaide.plugin.script.api

import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import party.iroiro.luajava.Lua

class WorkspaceApiModule internal constructor(
    private val fileAccess: PluginWorkspaceFileAccess
) : PluginApiModule {
    override val namespace = "workspace"

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
                    if (!file.exists() || !file.isFile) {
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
            withPermission(this.runtime, L, namespace, "findFiles", PluginPermission.FILE_READ) {
                val rt = this.runtime ?: return@withPermission 0
                if (!rt.checkFileOpLimit()) {
                    L.pushNil()
                    L.push("File operation rate limit exceeded")
                    return@withPermission 2
                }
                val pattern = L.getStringArg(1)
                val maxResults = L.getIntArg(2) ?: PluginWorkspaceFileAccess.DEFAULT_FIND_FILES_LIMIT
                val files = fileAccess.findFiles(pattern = pattern, maxResults = maxResults)
                L.pushStringList(files)
            }
        }
        lua.setField(-2, "findFiles")
    }

    override fun unregister() {
        runtime = null
    }
}
