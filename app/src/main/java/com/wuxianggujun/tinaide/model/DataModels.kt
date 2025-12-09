package com.wuxianggujun.tinaide.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 椤圭洰绫诲瀷鏋氫妇
 */
enum class ProjectType {
    CPP,      // C++ 椤圭洰
    C,        // C 椤圭洰
    MIXED,    // 娣峰悎椤圭洰
    UNKNOWN   // 鏈煡绫诲瀷
}

/**
 * 鏂囦欢鑺傜偣
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

// FileNode 搴忓垪鍖?
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
