package com.wuxianggujun.tinaide.core.format

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * Clang-Format 配置文件管理器
 *
 * 负责管理内置的格式化配置文件，包括：
 * - 从 assets 加载配置文件内容（带缓存）
 * - 将配置文件部署到指定目录
 * - 获取可用的配置文件列表
 */
class ClangFormatConfigManager(private val context: Context) {

    companion object {
        private const val TAG = "ClangFormatConfigMgr"
        private const val ASSETS_DIR = "clang-format"
        private const val CONFIG_FILE_NAME = ".clang-format"
    }

    /**
     * 内置配置文件信息
     */
    data class ConfigInfo(
        val name: String,
        val displayName: String,
        val description: String,
        val assetPath: String
    )

    /**
     * 配置内容缓存（线程安全）
     */
    private val configCache = ConcurrentHashMap<String, String>()

    /**
     * 可用配置列表（懒加载）
     */
    val availableConfigs: List<ConfigInfo> by lazy {
        listOf(
            ConfigInfo(
                name = "llvm",
                displayName = "LLVM",
                description = Strings.clang_format_style_llvm_desc.strOr(context),
                assetPath = "$ASSETS_DIR/llvm.clang-format"
            ),
            ConfigInfo(
                name = "google",
                displayName = "Google",
                description = Strings.clang_format_style_google_desc.strOr(context),
                assetPath = "$ASSETS_DIR/google.clang-format"
            ),
            ConfigInfo(
                name = "chromium",
                displayName = "Chromium",
                description = Strings.clang_format_style_chromium_desc.strOr(context),
                assetPath = "$ASSETS_DIR/chromium.clang-format"
            ),
            ConfigInfo(
                name = "mozilla",
                displayName = "Mozilla",
                description = Strings.clang_format_style_mozilla_desc.strOr(context),
                assetPath = "$ASSETS_DIR/mozilla.clang-format"
            ),
            ConfigInfo(
                name = "webkit",
                displayName = "WebKit",
                description = Strings.clang_format_style_webkit_desc.strOr(context),
                assetPath = "$ASSETS_DIR/webkit.clang-format"
            ),
            ConfigInfo(
                name = "microsoft",
                displayName = "Microsoft",
                description = Strings.clang_format_style_microsoft_desc.strOr(context),
                assetPath = "$ASSETS_DIR/microsoft.clang-format"
            ),
            ConfigInfo(
                name = "gnu",
                displayName = "GNU",
                description = Strings.clang_format_style_gnu_desc.strOr(context),
                assetPath = "$ASSETS_DIR/gnu.clang-format"
            )
        )
    }

    /**
     * 根据风格名称获取配置信息
     */
    fun getConfigByName(name: String): ConfigInfo? = availableConfigs.find { it.name.equals(name, ignoreCase = true) }

    /**
     * 根据 FormatStyle 获取配置信息
     */
    fun getConfigByStyle(style: FormatStyle): ConfigInfo? {
        val name = when (style) {
            FormatStyle.LLVM -> "llvm"
            FormatStyle.GOOGLE -> "google"
            FormatStyle.CHROMIUM -> "chromium"
            FormatStyle.MOZILLA -> "mozilla"
            FormatStyle.WEBKIT -> "webkit"
            FormatStyle.MICROSOFT -> "microsoft"
            FormatStyle.GNU -> "gnu"
            else -> return null
        }
        return getConfigByName(name)
    }

    /**
     * 从 assets 读取配置文件内容（带缓存）
     *
     * @param configName 配置名称（如 "llvm", "google" 等）
     * @return 配置文件内容，如果读取失败返回 null
     */
    fun readConfigContent(configName: String): String? {
        val config = getConfigByName(configName) ?: return null
        return readConfigContentCached(config.assetPath)
    }

    /**
     * 从 assets 读取配置文件内容（带缓存）
     *
     * @param style 格式化风格
     * @return 配置文件内容，如果读取失败返回 null
     */
    fun readConfigContent(style: FormatStyle): String? {
        val config = getConfigByStyle(style) ?: return null
        return readConfigContentCached(config.assetPath)
    }

    /**
     * 从 asset 路径读取配置内容（带缓存）
     */
    private fun readConfigContentCached(assetPath: String): String? {
        // 先检查缓存
        configCache[assetPath]?.let { return it }

        // 从 assets 读取
        return try {
            val content = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            // 存入缓存
            configCache[assetPath] = content
            content
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Failed to read config from assets: $assetPath")
            null
        }
    }

    /**
     * 将配置文件部署到指定目录
     *
     * @param configName 配置名称
     * @param targetDir 目标目录
     * @param overwrite 是否覆盖已存在的文件
     * @return 部署是否成功
     */
    fun deployConfig(configName: String, targetDir: File, overwrite: Boolean = false): Boolean {
        val content = readConfigContent(configName) ?: return false
        return deployConfigContent(content, targetDir, overwrite)
    }

    /**
     * 将配置文件部署到指定目录
     *
     * @param style 格式化风格
     * @param targetDir 目标目录
     * @param overwrite 是否覆盖已存在的文件
     * @return 部署是否成功
     */
    fun deployConfig(style: FormatStyle, targetDir: File, overwrite: Boolean = false): Boolean {
        val content = readConfigContent(style) ?: return false
        return deployConfigContent(content, targetDir, overwrite)
    }

    /**
     * 将配置内容写入目标目录
     */
    private fun deployConfigContent(content: String, targetDir: File, overwrite: Boolean): Boolean {
        val targetFile = File(targetDir, CONFIG_FILE_NAME)

        if (targetFile.exists() && !overwrite) {
            Timber.tag(TAG).w("Config file already exists and overwrite is false: ${targetFile.absolutePath}")
            return false
        }

        return try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            targetFile.writeText(content)
            Timber.tag(TAG).i("Config deployed to: ${targetFile.absolutePath}")
            true
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Failed to deploy config to: ${targetFile.absolutePath}")
            false
        }
    }

    /**
     * 检查目录中是否存在 .clang-format 文件
     */
    fun hasConfigFile(directory: File): Boolean = File(directory, CONFIG_FILE_NAME).let { it.exists() && it.isFile }

    /**
     * 读取目录中的 .clang-format 文件内容
     */
    fun readProjectConfig(directory: File): String? {
        val configFile = File(directory, CONFIG_FILE_NAME)
        return if (configFile.exists() && configFile.isFile) {
            try {
                configFile.readText()
            } catch (e: IOException) {
                Timber.tag(TAG).e(e, "Failed to read project config: ${configFile.absolutePath}")
                null
            }
        } else {
            null
        }
    }

    /**
     * 删除目录中的 .clang-format 文件
     */
    fun removeConfigFile(directory: File): Boolean {
        val configFile = File(directory, CONFIG_FILE_NAME)
        return if (configFile.exists()) {
            try {
                configFile.delete()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to delete config file: ${configFile.absolutePath}")
                false
            }
        } else {
            true
        }
    }

    /**
     * 获取配置文件的预览内容（前 N 行）
     */
    fun getConfigPreview(configName: String, maxLines: Int = 20): String? {
        val content = readConfigContent(configName) ?: return null
        return content.lines().take(maxLines).joinToString("\n")
    }

    /**
     * 清除配置缓存
     */
    fun clearCache() {
        configCache.clear()
    }
}
