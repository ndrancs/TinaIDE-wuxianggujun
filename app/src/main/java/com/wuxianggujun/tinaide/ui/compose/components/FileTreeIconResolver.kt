package com.wuxianggujun.tinaide.ui.compose.components

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import com.wuxianggujun.tinaide.core.i18n.Drawables
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.plugin.ResolvedPluginFileIcon
import java.io.File

internal sealed interface FileTreeIconSource {
    @Immutable
    data class AppDrawable(
        @param:DrawableRes
        @get:DrawableRes
        val resId: Int
    ) : FileTreeIconSource

    @Immutable
    data class PluginAsset(val file: File) : FileTreeIconSource
}

internal class FileTreeIconResolver(
    pluginIcons: List<ResolvedPluginFileIcon>
) {
    private val rules = pluginIcons
    private val ruleRanks = rules.withIndex().associate { (index, rule) -> rule to index }
    private val rulesByFileName = buildRuleIndex { it.fileNames }
    private val rulesByExtension = buildRuleIndex { it.extensions }
    private val cache = mutableMapOf<CacheKey, FileTreeIconSource>()
    private val appDrawableCache = mutableMapOf<Int, FileTreeIconSource.AppDrawable>()

    fun resolve(node: FileTreeNode): FileTreeIconSource {
        if (node.isDirectory) {
            return if (node.isExpanded) {
                appDrawable(Drawables.ic_folder_open_yellow)
            } else {
                appDrawable(Drawables.ic_folder_closed_blue)
            }
        }

        val nameLower = node.name.lowercase()
        val extension = nameLower.substringAfterLast('.', "")
        val cacheKey = CacheKey(nameLower = nameLower, extension = extension)
        return cache.getOrPut(cacheKey) {
            resolvePluginIcon(nameLower, extension) ?: resolveBuiltinIcon(nameLower, extension)
        }
    }

    private fun resolvePluginIcon(nameLower: String, extension: String): FileTreeIconSource? {
        val matchedRule = selectBestRule(
            fileNameRule = rulesByFileName[nameLower],
            extensionRule = rulesByExtension[extension]
        ) ?: return null

        matchedRule.iconFile?.let { return FileTreeIconSource.PluginAsset(it) }
        return resolveBuiltinSpec(matchedRule.iconSpec)
    }

    private fun selectBestRule(
        fileNameRule: ResolvedPluginFileIcon?,
        extensionRule: ResolvedPluginFileIcon?
    ): ResolvedPluginFileIcon? = when {
        fileNameRule == null -> extensionRule
        extensionRule == null -> fileNameRule
        fileNameRule === extensionRule -> fileNameRule
        ruleRanks.getValue(fileNameRule) <= ruleRanks.getValue(extensionRule) -> fileNameRule
        else -> extensionRule
    }

    private fun resolveBuiltinIcon(nameLower: String, extension: String): FileTreeIconSource.AppDrawable {
        val resId = when {
            nameLower == "cmakelists.txt" || extension == "cmake" -> Drawables.ic_file_cmake
            nameLower.startsWith(".git") ||
                nameLower == ".gitignore" ||
                nameLower == ".gitmodules" -> Drawables.ic_file_config
            nameLower.startsWith(".") ||
                extension in CONFIG_EXTENSIONS -> Drawables.ic_file_config
            extension in CODE_EXTENSIONS -> Drawables.ic_file_code
            else -> Drawables.ic_file_code
        }
        return appDrawable(resId)
    }

    private fun resolveBuiltinSpec(spec: String): FileTreeIconSource.AppDrawable? {
        val normalized = spec.substringAfter("builtin:", spec)
            .trim()
            .lowercase()
            .replace('_', '-')

        val resId = when (normalized) {
            "code", "file-code" -> Drawables.ic_file_code
            "config", "file-config" -> Drawables.ic_file_config
            "text", "file-text" -> Drawables.ic_file_text
            "cmake", "file-cmake" -> Drawables.ic_file_cmake
            "folder-open", "open-folder" -> Drawables.ic_folder_open_yellow
            "folder-closed", "closed-folder" -> Drawables.ic_folder_closed_blue
            else -> return null
        }
        return appDrawable(resId)
    }

    private data class CacheKey(
        val nameLower: String,
        val extension: String
    )

    private fun buildRuleIndex(
        matcher: (ResolvedPluginFileIcon) -> Set<String>
    ): Map<String, ResolvedPluginFileIcon> {
        val indexedRules = LinkedHashMap<String, ResolvedPluginFileIcon>()
        rules.forEach { rule ->
            matcher(rule).forEach { key ->
                indexedRules.putIfAbsent(key, rule)
            }
        }
        return indexedRules
    }

    private fun appDrawable(@DrawableRes resId: Int): FileTreeIconSource.AppDrawable = appDrawableCache.getOrPut(resId) {
        FileTreeIconSource.AppDrawable(resId)
    }

    private companion object {
        val CONFIG_EXTENSIONS = setOf("ini", "cfg", "conf", "yaml", "yml", "toml")
        val CODE_EXTENSIONS = CxxFileSupport.editorRelatedExtensions + setOf("kt", "java", "py", "js", "ts", "rs", "go")
    }
}
