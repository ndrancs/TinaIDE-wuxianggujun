package com.wuxianggujun.tinaide.model

import com.wuxianggujun.tinaide.ui.PanelType
import com.wuxianggujun.tinaide.ui.Theme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 项目类型枚举
 */
enum class ProjectType {
    CPP,      // C++ 项目
    C,        // C 项目
    MIXED,    // 混合项目
    UNKNOWN   // 未知类型
}

/**
 * 项目配置
 */
data class ProjectConfig(
    val language: String,
    val buildSystem: String = "manual",  // "manual", "cmake", "make"
    val sourceDirectories: List<String> = emptyList(),
    val includeDirectories: List<String> = emptyList(),
    val libraries: List<String> = emptyList(),
    val compilerFlags: List<String> = emptyList()
) {
    companion object
}

/**
 * 项目模型
 */
data class Project(
    val id: String,
    val name: String,
    val rootPath: String,
    val type: ProjectType,
    val config: ProjectConfig,
    val createdAt: Long,
    val lastModified: Long
) {
    companion object
}

/**
 * 文件节点
 */
data class FileNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val extension: String?,
    val size: Long,
    val lastModified: Long,
    val children: List<FileNode>? = null
) {
    companion object
}

/**
 * 编辑器状态
 */
data class EditorState(
    val filePath: String,
    val cursorLine: Int,
    val cursorColumn: Int,
    val scrollX: Int,
    val scrollY: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    val isDirty: Boolean
) {
    companion object
}

/**
 * 工作区状态
 */
data class WorkspaceState(
    val currentProject: String?,
    val openFiles: List<String>,
    val activeFile: String?,
    val editorStates: Map<String, EditorState>,
    val panelVisibility: Map<PanelType, Boolean>,
    val theme: Theme
) {
    companion object
}

/**
 * 插件元数据
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val supportedExtensions: List<String>,
    val dependencies: List<String> = emptyList(),
    val enabled: Boolean = true
) {
    companion object
}

/**
 * 插件配置
 */
data class PluginConfig(
    val pluginId: String,
    val settings: Map<String, Any>
) {
    companion object
}

/**
 * 序列化扩展函数
 */

// Project 序列化
fun Project.toJson(): JSONObject {
    return JSONObject().apply {
        put("id", id)
        put("name", name)
        put("rootPath", rootPath)
        put("type", type.name)
        put("config", config.toJson())
        put("createdAt", createdAt)
        put("lastModified", lastModified)
    }
}

fun Project.Companion.fromJson(json: JSONObject): Project {
    return Project(
        id = json.getString("id"),
        name = json.getString("name"),
        rootPath = json.getString("rootPath"),
        type = ProjectType.valueOf(json.getString("type")),
        config = ProjectConfig.fromJson(json.getJSONObject("config")),
        createdAt = json.getLong("createdAt"),
        lastModified = json.getLong("lastModified")
    )
}

// ProjectConfig 序列化
fun ProjectConfig.toJson(): JSONObject {
    return JSONObject().apply {
        put("language", language)
        put("buildSystem", buildSystem)
        put("sourceDirectories", JSONArray(sourceDirectories))
        put("includeDirectories", JSONArray(includeDirectories))
        put("libraries", JSONArray(libraries))
        put("compilerFlags", JSONArray(compilerFlags))
    }
}

fun ProjectConfig.Companion.fromJson(json: JSONObject): ProjectConfig {
    return ProjectConfig(
        language = json.getString("language"),
        buildSystem = json.optString("buildSystem", "manual"),
        sourceDirectories = json.optJSONArray("sourceDirectories")?.toStringList() ?: emptyList(),
        includeDirectories = json.optJSONArray("includeDirectories")?.toStringList() ?: emptyList(),
        libraries = json.optJSONArray("libraries")?.toStringList() ?: emptyList(),
        compilerFlags = json.optJSONArray("compilerFlags")?.toStringList() ?: emptyList()
    )
}

// FileNode 序列化
fun FileNode.toJson(): JSONObject {
    return JSONObject().apply {
        put("path", path)
        put("name", name)
        put("isDirectory", isDirectory)
        put("extension", extension)
        put("size", size)
        put("lastModified", lastModified)
        children?.let {
            put("children", JSONArray(it.map { child -> child.toJson() }))
        }
    }
}

fun FileNode.Companion.fromJson(json: JSONObject): FileNode {
    return FileNode(
        path = json.getString("path"),
        name = json.getString("name"),
        isDirectory = json.getBoolean("isDirectory"),
        extension = json.optString("extension", "").takeIf { it.isNotEmpty() },
        size = json.getLong("size"),
        lastModified = json.getLong("lastModified"),
        children = json.optJSONArray("children")?.let { array ->
            (0 until array.length()).map { i ->
                fromJson(array.getJSONObject(i))
            }
        }
    )
}

// EditorState 序列化
fun EditorState.toJson(): JSONObject {
    return JSONObject().apply {
        put("filePath", filePath)
        put("cursorLine", cursorLine)
        put("cursorColumn", cursorColumn)
        put("scrollX", scrollX)
        put("scrollY", scrollY)
        put("selectionStart", selectionStart)
        put("selectionEnd", selectionEnd)
        put("isDirty", isDirty)
    }
}

fun EditorState.Companion.fromJson(json: JSONObject): EditorState {
    return EditorState(
        filePath = json.getString("filePath"),
        cursorLine = json.getInt("cursorLine"),
        cursorColumn = json.getInt("cursorColumn"),
        scrollX = json.getInt("scrollX"),
        scrollY = json.getInt("scrollY"),
        selectionStart = json.getInt("selectionStart"),
        selectionEnd = json.getInt("selectionEnd"),
        isDirty = json.getBoolean("isDirty")
    )
}

// WorkspaceState 序列化
fun WorkspaceState.toJson(): JSONObject {
    return JSONObject().apply {
        put("currentProject", currentProject)
        put("openFiles", JSONArray(openFiles))
        put("activeFile", activeFile)
        
        val editorStatesJson = JSONObject()
        editorStates.forEach { (key, value) ->
            editorStatesJson.put(key, value.toJson())
        }
        put("editorStates", editorStatesJson)
        
        val panelVisibilityJson = JSONObject()
        panelVisibility.forEach { (key, value) ->
            panelVisibilityJson.put(key.name, value)
        }
        put("panelVisibility", panelVisibilityJson)
        
        put("theme", theme.name)
    }
}

fun WorkspaceState.Companion.fromJson(json: JSONObject): WorkspaceState {
    val editorStates = mutableMapOf<String, EditorState>()
    json.optJSONObject("editorStates")?.let { obj ->
        obj.keys().forEach { key ->
            editorStates[key] = EditorState.fromJson(obj.getJSONObject(key))
        }
    }
    
    val panelVisibility = mutableMapOf<PanelType, Boolean>()
    json.optJSONObject("panelVisibility")?.let { obj ->
        obj.keys().forEach { key ->
            panelVisibility[PanelType.valueOf(key)] = obj.getBoolean(key)
        }
    }
    
    return WorkspaceState(
        currentProject = json.optString("currentProject", "").takeIf { it.isNotEmpty() },
        openFiles = json.optJSONArray("openFiles")?.toStringList() ?: emptyList(),
        activeFile = json.optString("activeFile", "").takeIf { it.isNotEmpty() },
        editorStates = editorStates,
        panelVisibility = panelVisibility,
        theme = Theme.valueOf(json.optString("theme", Theme.DARK.name))
    )
}

// PluginMetadata 序列化
fun PluginMetadata.toJson(): JSONObject {
    return JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("author", author)
        put("description", description)
        put("supportedExtensions", JSONArray(supportedExtensions))
        put("dependencies", JSONArray(dependencies))
        put("enabled", enabled)
    }
}

fun PluginMetadata.Companion.fromJson(json: JSONObject): PluginMetadata {
    return PluginMetadata(
        id = json.getString("id"),
        name = json.getString("name"),
        version = json.getString("version"),
        author = json.getString("author"),
        description = json.getString("description"),
        supportedExtensions = json.getJSONArray("supportedExtensions").toStringList(),
        dependencies = json.optJSONArray("dependencies")?.toStringList() ?: emptyList(),
        enabled = json.optBoolean("enabled", true)
    )
}

// PluginConfig 序列化
fun PluginConfig.toJson(): JSONObject {
    return JSONObject().apply {
        put("pluginId", pluginId)
        val settingsJson = JSONObject()
        settings.forEach { (key, value) ->
            settingsJson.put(key, value)
        }
        put("settings", settingsJson)
    }
}

fun PluginConfig.Companion.fromJson(json: JSONObject): PluginConfig {
    val settings = mutableMapOf<String, Any>()
    json.optJSONObject("settings")?.let { obj ->
        obj.keys().forEach { key ->
            settings[key] = obj.get(key)
        }
    }
    
    return PluginConfig(
        pluginId = json.getString("pluginId"),
        settings = settings
    )
}

/**
 * 工具函数
 */
private fun JSONArray.toStringList(): List<String> {
    return (0 until length()).map { i -> getString(i) }
}

/**
 * Companion objects for deserialization
 */
fun Project.Companion.create() = Unit
fun ProjectConfig.Companion.create() = Unit
fun FileNode.Companion.create() = Unit
fun EditorState.Companion.create() = Unit
fun WorkspaceState.Companion.create() = Unit
fun PluginMetadata.Companion.create() = Unit
fun PluginConfig.Companion.create() = Unit
