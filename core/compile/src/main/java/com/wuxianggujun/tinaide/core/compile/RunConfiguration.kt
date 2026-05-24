package com.wuxianggujun.tinaide.core.compile

import kotlinx.serialization.Serializable
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.project.CppStandard
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

private const val RUN_CONFIG_SCHEMA_LEGACY = 1
private const val RUN_CONFIG_SCHEMA_CURRENT = 2

/**
 * 源文件模式 - 决定编译哪个源文件
 */
@Serializable
enum class SourceFileMode {
    /**
     * 自动检测（默认行为）
     * - CMake 项目：使用 CMakeLists.txt 定义的目标
     * - 单文件项目：优先查找 main.cpp/main.c，否则使用第一个源文件
     */
    AUTO,
    
    /**
     * 使用当前编辑的文件
     * 编译并运行当前在编辑器中打开的文件
     */
    CURRENT_FILE,
    
    /**
     * 使用指定的源文件
     * 编译并运行用户指定的源文件路径
     */
    SPECIFIED_FILE
}

/**
 * 运行配置
 *
 * 支持多种源文件选择模式、编译器选择和变量替换。
 *
 * 变量支持（在 args、workDir、sourceFilePath 中可用）：
 * - $ProjectDir$ - 项目根目录
 * - $CurrentFile$ - 当前编辑的文件
 * - $CurrentFileDir$ - 当前文件所在目录
 * - $BuildDir$ - 构建输出目录
 * 等，详见 BuildVariables.kt
 */
@Serializable
data class RunConfiguration(
    val id: String = UUID.randomUUID().toString(),  // 唯一标识
    val name: String = "Debug",                      // 配置名称
    val args: String = "",                           // 命令行参数（支持变量）
    val workDir: String = "",                        // 工作目录（相对路径，支持变量）
    val buildType: BuildType = BuildType.DEBUG,
    val outputMode: OutputMode = OutputMode.TERMINAL,
    val targetName: String = "",                     // CMake 构建目标（留空表示默认目标）
    
    // 单文件编译相关配置
    val sourceFileMode: SourceFileMode = SourceFileMode.AUTO,  // 源文件选择模式
    val sourceFilePath: String = "",                 // 指定的源文件路径（相对于项目根目录，支持变量）
    
    // 编译器配置
    val compilerType: CompilerType = CompilerType.CLANG,  // 编译器类型（默认 Clang）

    /**
     * 工具链 ID（仅当 compilerType == CLANG 时使用）
     *
     * - null: 使用全局激活的工具链
     * - 非 null: 使用指定 ID 的工具链（如 "builtin-0.1.9", "custom-18.1.8"）
     */
    val toolchainId: String? = null,

    /**
     * 自定义 C 编译器路径（仅当 compilerType == CUSTOM 时使用）
     *
     * 路径应为 PRoot guest rootfs 内的可执行文件路径（如 /usr/bin/gcc）。
     */
    val customCCompiler: String? = null,

    /**
     * 自定义 C++ 编译器路径（仅当 compilerType == CUSTOM 时使用）
     *
     * 路径应为 PRoot guest rootfs 内的可执行文件路径（如 /usr/bin/g++）。
     */
    val customCppCompiler: String? = null,

    /**
     * 可选 sysroot API level。
     *
     * - null: 自动读取项目 metadata 的 nativeApiLevel，若无则默认 API 28
     * - 非 null: 传递给构建策略用于拼装 --target/--sysroot
     */
    val sysrootApiLevel: Int? = null,

    /**
     * 单文件构建的 C++ 标准覆盖项（仅 buildSystem == SINGLE_FILE 生效）。
     *
     * 存储值优先使用 [com.wuxianggujun.tinaide.project.CppStandard.name]（如 "CPP_20"）。
     * - null/空字符串: 跟随项目 metadata（若无则使用策略默认 c++17）
     * - 非空: 作为单文件编译时的 `-std=...` 来源
     */
    val singleFileCppStandard: String? = null,

    /**
     * 图形模式的屏幕方向（仅 outputMode == GUI 时生效）。
     */
    val guiOrientation: GuiOrientation = GuiOrientation.AUTO,

    /**
     * 是否在图形模式下显示悬浮日志窗口（仅 outputMode == GUI 时生效）。
     *
     * 启用后可在全屏 SDL/ImGui 等图形程序中实时查看 stdout/stderr 输出。
     */
    val enableFloatingLog: Boolean = false
) {
    fun normalized(): RunConfiguration {
        return copy(
            toolchainId = toolchainId?.trim()?.takeIf { it.isNotEmpty() },
            customCCompiler = normalizeCompilerPath(customCCompiler),
            customCppCompiler = normalizeCompilerPath(customCppCompiler),
            singleFileCppStandard = normalizeSingleFileCppStandard(singleFileCppStandard)
        )
    }

    /**
     * 获取参数列表
     */
    fun getArgsList(): List<String> {
        if (args.isBlank()) return emptyList()
        return args.split(Regex("\\s+")).filter { it.isNotBlank() }
    }
    
    /**
     * 获取参数列表（带变量替换）
     */
    fun getArgsList(context: BuildVariables.BuildContext): List<String> {
        if (args.isBlank()) return emptyList()
        val expandedArgs = BuildVariables.expand(args, context)
        return expandedArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    /**
     * 获取绝对工作目录
     */
    fun getAbsoluteWorkDir(projectPath: String): String {
        return if (workDir.isBlank()) {
            projectPath
        } else {
            File(projectPath, workDir).absolutePath
        }
    }
    
    /**
     * 获取绝对工作目录（带变量替换）
     */
    fun getAbsoluteWorkDir(projectPath: String, context: BuildVariables.BuildContext): String {
        if (workDir.isBlank()) {
            return projectPath
        }
        val expandedWorkDir = BuildVariables.expand(workDir, context)
        return if (File(expandedWorkDir).isAbsolute) {
            expandedWorkDir
        } else {
            File(projectPath, expandedWorkDir).absolutePath
        }
    }
    
    /**
     * 获取要编译的源文件
     *
     * @param projectRoot 项目根目录
     * @param currentFile 当前编辑的文件（可选）
     * @return 源文件，如果无法确定则返回 null
     */
    fun getSourceFile(projectRoot: File, currentFile: File? = null): File? {
        return when (sourceFileMode) {
            SourceFileMode.AUTO -> null  // 返回 null 表示使用默认行为
            
            SourceFileMode.CURRENT_FILE -> {
                currentFile?.takeIf { isSourceFile(it) }
            }
            
            SourceFileMode.SPECIFIED_FILE -> {
                if (sourceFilePath.isBlank()) {
                    null
                } else {
                    // 支持变量替换
                    val context = BuildVariables.BuildContext(
                        projectDir = projectRoot,
                        projectName = projectRoot.name,
                        currentFile = currentFile
                    )
                    val expandedPath = BuildVariables.expand(sourceFilePath, context)
                    val file = if (File(expandedPath).isAbsolute) {
                        File(expandedPath)
                    } else {
                        File(projectRoot, expandedPath)
                    }
                    file.takeIf { it.exists() && isSourceFile(it) }
                }
            }
        }
    }
    
    /**
     * 检查是否为单文件编译模式
     */
    fun isSingleFileMode(): Boolean {
        return sourceFileMode != SourceFileMode.AUTO
    }

    /**
     * 获取显示名称（用于配置选择器）
     */
    fun displayName(): String = name
    
    /**
     * 获取源文件模式的显示名称
     */
    fun sourceFileModeDisplayName(): String {
        return when (sourceFileMode) {
            SourceFileMode.AUTO -> Strings.run_config_auto_detect.str()
            SourceFileMode.CURRENT_FILE -> Strings.run_config_current_file.str()
            SourceFileMode.SPECIFIED_FILE -> Strings.run_config_specified_file.str()
        }
    }

    /**
     * 获取输出模式显示名称
     */
    fun outputModeDisplayName(): String {
        return when (outputMode) {
            OutputMode.LOG -> Strings.run_config_output_build_log.str()
            OutputMode.TERMINAL -> Strings.run_config_output_terminal.str()
            OutputMode.GUI -> Strings.run_config_output_gui.str()
        }
    }
    
    companion object {
        private val SOURCE_EXTENSIONS: Set<String> = CxxFileSupport.singleFileBuildSourceExtensions
        
        /**
         * 检查文件是否为 C/C++ 源文件
         */
        fun isSourceFile(file: File): Boolean {
            return file.isFile && file.extension.lowercase() in SOURCE_EXTENSIONS
        }

        /**
         * 解析单文件 C++ 标准配置（支持 `CPP_20` / `20` / `c++20`）。
         */
        fun parseSingleFileCppStandard(value: String?): CppStandard? {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) return null
            return CppStandard.entries.firstOrNull {
                it.name.equals(normalized, ignoreCase = true) ||
                    it.flag.equals(normalized, ignoreCase = true) ||
                    it.cmakeValue == normalized
            }
        }

        /**
         * 归一化单文件 C++ 标准配置：
         * - 空值 -> null
         * - 已知标准 -> 对应 [CppStandard.name]
         * - 未知值 -> 原样保留（允许高级用户手写标准字符串）
         */
        fun normalizeSingleFileCppStandard(value: String?): String? {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) return null
            return parseSingleFileCppStandard(normalized)?.name ?: normalized
        }

        fun normalizeCompilerPath(value: String?): String? {
            val normalized = value?.trim().orEmpty()
            return normalized.ifBlank { null }
        }
    }
}

/**
 * 运行配置管理器 - 管理多个配置
 */
@Serializable
data class RunConfigurationManager(
    /**
     * run_configs.json schema 版本。
     *
     * - 新项目始终写入 [RUN_CONFIG_SCHEMA_CURRENT]
     * - 旧项目在 [load] 时自动迁移并回写到最新版本
     */
    val schemaVersion: Int = RUN_CONFIG_SCHEMA_CURRENT,
    val configurations: List<RunConfiguration> = listOf(RunConfiguration()),
    val selectedId: String? = null
) {
    companion object {
        private const val TAG = "RunConfigManager"
        private const val CONFIG_FILE = ".tinaide/run_configs.json"
        private val SCHEMA_VERSION_FIELD_REGEX = Regex("\"schemaVersion\"\\s*:")
        private const val MAX_PENDING_MIGRATION_NOTICES_PER_PROJECT = 16
        private val migrationNoticeBuffer =
            ConcurrentHashMap<String, ArrayDeque<MigrationNotice>>()

        data class MigrationNotice(
            val fromSchemaVersion: Int,
            val toSchemaVersion: Int,
            val normalizedConfigCount: Int,
            val filteredInvalidCount: Int,
            val selectedIdAdjusted: Boolean
        )

        private val json = JsonSerializer.pretty

        internal fun configFile(projectPath: String): File = File(projectPath, CONFIG_FILE)

        /**
         * 从项目目录加载配置
         */
        fun load(projectPath: String): RunConfigurationManager {
            val configFile = configFile(projectPath)
            return try {
                if (configFile.exists()) {
                    val rawJson = configFile.readText()
                    val rawManager = decodeManagerWithSchemaCompatibility(rawJson)

                    val validConfigs = rawManager.configurations.filter { config ->
                        config.id.isNotBlank() && config.name.isNotBlank()
                    }
                    if (validConfigs.isEmpty()) {
                        Timber.tag(TAG).w("Run configs has no valid entries, creating default")
                        createDefault(projectPath)
                    } else {
                        val sanitizedManager = rawManager.copy(configurations = validConfigs)
                        val migratedManager = migrateManagerToLatest(sanitizedManager)
                        val normalizedConfigManager = normalizeManager(migratedManager)
                        val normalizedSelectedId = normalizedConfigManager.selectedId
                            ?.takeIf { selected ->
                                normalizedConfigManager.configurations.any { it.id == selected }
                            }
                            ?: normalizedConfigManager.configurations.first().id
                        val normalizedManager =
                            normalizedConfigManager.copy(selectedId = normalizedSelectedId)

                        val filteredInvalidConfigs =
                            validConfigs.size != rawManager.configurations.size
                        val schemaMigrated =
                            migratedManager.schemaVersion != sanitizedManager.schemaVersion
                        val configMigrated =
                            normalizedConfigManager.configurations != sanitizedManager.configurations
                        val normalizedConfigCount = sanitizedManager.configurations.zip(
                            normalizedConfigManager.configurations
                        ).count { (before, after) -> before != after }
                        val selectedIdAdjusted =
                            normalizedSelectedId != normalizedConfigManager.selectedId
                        val filteredInvalidCount =
                            rawManager.configurations.size - validConfigs.size
                        val changed = filteredInvalidConfigs ||
                            schemaMigrated ||
                            configMigrated ||
                            selectedIdAdjusted
                        if (changed) {
                            if (filteredInvalidConfigs) {
                                Timber.tag(TAG).w(
                                    "Filtered ${rawManager.configurations.size - validConfigs.size} invalid configs"
                                )
                            }
                            if (schemaMigrated || configMigrated) {
                                Timber.tag(TAG).i(
                                    "Migrated run config schema ${sanitizedManager.schemaVersion} -> " +
                                        "${migratedManager.schemaVersion}"
                                )
                            }
                            if (selectedIdAdjusted) {
                                Timber.tag(TAG).w(
                                    "Selected run config id was invalid, reset to first config"
                                )
                            }
                            if (schemaMigrated || configMigrated) {
                                recordMigrationNotice(
                                    projectPath = projectPath,
                                    notice = MigrationNotice(
                                        fromSchemaVersion = sanitizedManager.schemaVersion,
                                        toSchemaVersion = normalizedConfigManager.schemaVersion,
                                        normalizedConfigCount = normalizedConfigCount,
                                        filteredInvalidCount = filteredInvalidCount,
                                        selectedIdAdjusted = selectedIdAdjusted
                                    )
                                )
                            }
                            save(projectPath, normalizedManager)
                        }

                        normalizedManager
                    }
                } else {
                    createDefault(projectPath)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to load run configs: ${e.message}")
                createDefault(projectPath)
            }
        }

        /**
         * 保存配置到项目目录
         */
        fun save(projectPath: String, manager: RunConfigurationManager): Boolean {
            val configFile = configFile(projectPath)
            val managerToPersist = normalizeManager(
                if (manager.schemaVersion < RUN_CONFIG_SCHEMA_CURRENT) {
                    migrateManagerToLatest(manager)
                } else {
                    manager
                }
            )
            return try {
                configFile.parentFile?.mkdirs()
                JsonSerializer.encodePrettyToFile(configFile, managerToPersist)
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e("Failed to save run configs: ${e.message}")
                false
            }
        }

        /**
         * 创建默认配置
         */
        private fun createDefault(projectPath: String? = null): RunConfigurationManager {
            val defaultConfig = createDefaultRunConfiguration(projectPath)
            return RunConfigurationManager(
                schemaVersion = RUN_CONFIG_SCHEMA_CURRENT,
                configurations = listOf(defaultConfig),
                selectedId = defaultConfig.id
            )
        }

        private fun createDefaultRunConfiguration(projectPath: String?): RunConfiguration {
            return RunConfiguration(
                name = "Debug",
                outputMode = resolveDefaultOutputMode(projectPath)
            )
        }

        private fun resolveDefaultOutputMode(projectPath: String?): OutputMode {
            val projectRoot = projectPath
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::File)
                ?.takeIf { it.exists() }
                ?: return OutputMode.TERMINAL
            val apkExportType = ProjectMetadataStore.read(projectRoot)?.apkExportType
            return if (apkExportType == ProjectApkExportType.SDL3) {
                OutputMode.GUI
            } else {
                OutputMode.TERMINAL
            }
        }

        private fun decodeManagerWithSchemaCompatibility(rawJson: String): RunConfigurationManager {
            val hasSchemaVersion = SCHEMA_VERSION_FIELD_REGEX.containsMatchIn(rawJson)

            val decoded = json.decodeFromString<RunConfigurationManager>(rawJson)
            return if (hasSchemaVersion) {
                decoded
            } else {
                decoded.copy(schemaVersion = RUN_CONFIG_SCHEMA_LEGACY)
            }
        }

        private fun migrateManagerToLatest(manager: RunConfigurationManager): RunConfigurationManager {
            if (manager.schemaVersion >= RUN_CONFIG_SCHEMA_CURRENT) {
                return manager
            }

            var migrated = manager
            while (migrated.schemaVersion < RUN_CONFIG_SCHEMA_CURRENT) {
                migrated = when {
                    migrated.schemaVersion <= RUN_CONFIG_SCHEMA_LEGACY -> migrateFromV1ToV2(migrated)
                    else -> {
                        Timber.tag(TAG).w(
                            "Unknown run config schema ${migrated.schemaVersion}, " +
                                "force marking as latest"
                        )
                        migrated.copy(schemaVersion = RUN_CONFIG_SCHEMA_CURRENT)
                    }
                }
                // TODO(config-migration): schemaVersion 升级到 3+ 时，在这里追加 v2->v3 等迁移步骤。
            }
            return migrated
        }

        private fun migrateFromV1ToV2(manager: RunConfigurationManager): RunConfigurationManager {
            val migratedConfigs = manager.configurations.map { config ->
                config.copy(
                    singleFileCppStandard = RunConfiguration.normalizeSingleFileCppStandard(
                        config.singleFileCppStandard
                    )
                )
            }
            return manager.copy(
                schemaVersion = RUN_CONFIG_SCHEMA_CURRENT,
                configurations = migratedConfigs
            )
        }

        private fun normalizeManager(manager: RunConfigurationManager): RunConfigurationManager {
            val normalizedConfigs = manager.configurations.map { it.normalized() }
            return if (normalizedConfigs == manager.configurations) {
                manager
            } else {
                manager.copy(configurations = normalizedConfigs)
            }
        }

        private fun recordMigrationNotice(projectPath: String, notice: MigrationNotice) {
            val key = projectPath.trim()
            if (key.isEmpty()) return
            val queue = migrationNoticeBuffer.computeIfAbsent(key) { ArrayDeque() }
            synchronized(queue) {
                if (queue.size >= MAX_PENDING_MIGRATION_NOTICES_PER_PROJECT) {
                    queue.removeFirst()
                }
                queue.addLast(notice)
            }
        }

        fun consumeMigrationNotices(projectPath: String): List<MigrationNotice> {
            val key = projectPath.trim()
            if (key.isEmpty()) return emptyList()
            val queue = migrationNoticeBuffer.remove(key) ?: return emptyList()
            synchronized(queue) {
                if (queue.isEmpty()) return emptyList()
                return queue.toList()
            }
        }
    }

    /**
     * 获取当前选中的配置
     */
    val selectedConfig: RunConfiguration
        get() = configurations.find { it.id == selectedId }
            ?: configurations.firstOrNull()
            ?: RunConfiguration()

    /**
     * 选择配置
     */
    fun selectConfig(id: String): RunConfigurationManager {
        return copy(selectedId = id)
    }

    /**
     * 添加新配置
     */
    fun addConfig(config: RunConfiguration): RunConfigurationManager {
        return copy(
            configurations = configurations + config,
            selectedId = config.id
        )
    }

    /**
     * 更新配置
     */
    fun updateConfig(config: RunConfiguration): RunConfigurationManager {
        return copy(
            configurations = configurations.map {
                if (it.id == config.id) config else it
            }
        )
    }

    /**
     * 删除配置
     */
    fun removeConfig(id: String): RunConfigurationManager {
        val newConfigs = configurations.filter { it.id != id }
        // 确保至少有一个配置
        val finalConfigs = if (newConfigs.isEmpty()) {
            listOf(RunConfiguration(name = "Debug"))
        } else {
            newConfigs
        }
        // 如果删除的是当前选中的，选择第一个
        val newSelectedId = if (selectedId == id) {
            finalConfigs.first().id
        } else {
            selectedId
        }
        return copy(configurations = finalConfigs, selectedId = newSelectedId)
    }

    /**
     * 复制配置
     */
    fun duplicateConfig(id: String): RunConfigurationManager {
        val original = configurations.find { it.id == id } ?: return this
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = original.name + Strings.run_config_name_copy_suffix.str()
        )
        return addConfig(copy)
    }
}

/**
 * 输出模式
 */
@Serializable
enum class OutputMode {
    /**
     * 历史模式（兼容旧配置）
     *
     * 当前实现中会按 TERMINAL 处理。
     */
    LOG,

    /**
     * 在终端中运行
     */
    TERMINAL,

    /**
     * 在 SDL 图形运行时中运行（加载共享库）
     */
    GUI
}

/**
 * 图形运行时屏幕方向
 */
@Serializable
enum class GuiOrientation {
    /** 跟随系统自动旋转 */
    AUTO,
    /** 强制横屏 */
    LANDSCAPE,
    /** 强制竖屏 */
    PORTRAIT
}
