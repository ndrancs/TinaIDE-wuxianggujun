package com.wuxianggujun.tinaide.plugin

import android.content.Context
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import com.wuxianggujun.tinaide.core.ServiceLifecycle
import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectTemplateOption
import com.wuxianggujun.tinaide.project.ProjectTemplateSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import timber.log.Timber
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr

class PluginManager(
    private val context: Context
) : ServiceLifecycle {

    companion object {
        private const val TAG = "PluginManager"

        private const val PLUGINS_DIR_NAME = "plugins"
        const val MANIFEST_FILE_NAME: String = "manifest.json"

        private const val PREFS_NAME = "tinaide_plugins"
        private const val PREF_ENABLED_PREFIX = "enabled_"

        private val PLUGIN_ID_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")

        @Volatile
        private var instance: PluginManager? = null

        fun getInstance(context: Context): PluginManager {
            instance?.let { manager ->
                Timber.tag(TAG).d(
                    "Reusing PluginManager singleton instance=%s",
                    manager.instanceId
                )
                manager.logHostDebug("Reusing singleton instance=${manager.instanceId}")
                return manager
            }
            return synchronized(this) {
                instance ?: PluginManager(context.applicationContext).also {
                    it.onCreate()
                    Timber.tag(TAG).i(
                        "Created PluginManager singleton instance=%s",
                        it.instanceId
                    )
                    it.logHostInfo("Created singleton instance=${it.instanceId}")
                    instance = it
                }
            }
        }
    }

    private val json = JsonSerializer.default
    private val pluginsDir = File(context.filesDir, PLUGINS_DIR_NAME)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pluginLogManager = PluginLogManager.getInstance(context)
    val instanceId: String = Integer.toHexString(System.identityHashCode(this))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 插件状态的单一来源：安装态、启用态、版本映射与 capability 都从这里派生。
    private val _pluginStateFlow = MutableStateFlow(PluginStateSnapshot())
    val pluginStateFlow: StateFlow<PluginStateSnapshot> = _pluginStateFlow.asStateFlow()

    // 兼容现有调用方的安装态视图。
    private val _pluginsFlow = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val pluginsFlow: StateFlow<List<InstalledPlugin>> = _pluginsFlow.asStateFlow()

    // 所有会影响宿主行为的模块都应优先消费启用态。
    private val _enabledPluginsFlow = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val enabledPluginsFlow: StateFlow<List<InstalledPlugin>> = _enabledPluginsFlow.asStateFlow()

    private val _enabledCapabilitiesFlow = MutableStateFlow<Set<String>>(emptySet())
    val enabledCapabilitiesFlow: StateFlow<Set<String>> = _enabledCapabilitiesFlow.asStateFlow()
    private val _loadIssuesFlow = MutableStateFlow<List<PluginLoadIssue>>(emptyList())
    val loadIssuesFlow: StateFlow<List<PluginLoadIssue>> = _loadIssuesFlow.asStateFlow()
    private val _pluginHealthReportsFlow = MutableStateFlow<Map<String, PluginHealthReport>>(emptyMap())
    val pluginHealthReportsFlow: StateFlow<Map<String, PluginHealthReport>> =
        _pluginHealthReportsFlow.asStateFlow()

    override fun onCreate() {
        Timber.tag(TAG).i(
            "PluginManager.onCreate instance=%s filesDir=%s",
            instanceId,
            context.filesDir.absolutePath
        )
        logHostInfo("onCreate instance=$instanceId filesDir=${context.filesDir.absolutePath}")
        pluginsDir.mkdirs()
        scope.launchSafe("refresh-onCreate") {
            refreshInstalledPlugins()
        }
        scope.launchSafe("install-bundled-plugins") {
            BundledPluginsInstaller(context, this@PluginManager).installOrUpdateBundledPlugins()
        }
    }

    override fun onDestroy() {
        scope.cancel()
    }

    suspend fun refreshInstalledPlugins() = withContext(Dispatchers.IO) {
        val loadIssues = mutableListOf<PluginLoadIssue>()
        val installed = pluginsDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val manifestFile = File(dir, MANIFEST_FILE_NAME)
                if (!manifestFile.exists()) {
                    loadIssues += PluginLoadIssue(
                        directoryName = dir.name,
                        pluginName = dir.name,
                        type = PluginLoadIssueType.MISSING_MANIFEST,
                        message = Strings.plugin_error_missing_manifest.strOr(context, MANIFEST_FILE_NAME)
                    )
                    return@mapNotNull null
                }

                var manifestForIssue: PluginManifest? = null

                runCatching {
                    val manifest = JsonSerializer.decodeFromFile<PluginManifest>(manifestFile)
                    manifestForIssue = manifest
                    validateManifest(manifest, dir)
                    InstalledPlugin(
                        manifest = manifest,
                        directory = dir,
                        enabled = resolvePluginEnabled(manifest)
                    )
                }.onFailure { t ->
                    Timber.tag(TAG).w(t, "Failed to load plugin manifest: ${manifestFile.path}")
                    pluginLogManager.warn(
                        PluginHostLogSources.PluginManager,
                        "Invalid plugin skipped dir=${dir.name} reason=${t.message.orEmpty()}"
                    )
                    loadIssues += PluginLoadIssue(
                        directoryName = dir.name,
                        pluginId = manifestForIssue?.id,
                        pluginName = manifestForIssue?.name ?: dir.name,
                        type = PluginLoadIssueType.INVALID_MANIFEST,
                        message = t.message ?: Strings.plugin_error_install_failed.strOr(context)
                    )
                }.getOrNull()
            }
            ?.sortedBy { it.manifest.name }
            ?.toList()
            ?: emptyList()

        val snapshot = PluginStateSnapshotFactory.create(installed)
        _pluginStateFlow.value = snapshot
        _pluginsFlow.value = snapshot.installedPlugins
        _enabledPluginsFlow.value = snapshot.enabledPlugins
        _enabledCapabilitiesFlow.value = snapshot.enabledCapabilities
        _loadIssuesFlow.value = loadIssues.toList()
        _pluginHealthReportsFlow.value = installed.associate { plugin ->
            plugin.manifest.id to PluginHealthInspector.inspect(context, plugin)
        }
        Timber.tag(TAG).i(
            "Refreshed plugins instance=%s installed=%s enabled=%s capabilities=%s",
            instanceId,
            snapshot.installedPluginIds.joinToString(","),
            snapshot.enabledPluginIds.joinToString(","),
            snapshot.enabledCapabilities.joinToString(",")
        )
        logHostInfo(
            "refreshInstalledPlugins instance=$instanceId installed=${snapshot.installedPluginIds.joinToString(",")} enabled=${snapshot.enabledPluginIds.joinToString(",")} capabilities=${snapshot.enabledCapabilities.joinToString(",")}"
        )
    }

    suspend fun installPlugin(zipFile: File): Result<PluginManifest> = withContext(Dispatchers.IO) {
        runCatching {
            require(zipFile.exists()) { Strings.plugin_error_file_not_exist.strOr(context, zipFile.path) }

            val tempDir = File(context.cacheDir, "plugin_temp_${UUID.randomUUID()}")
            try {
                ZipUtils.unzipToDirectory(zipFile, tempDir)
                val installed = installPluginFromDirectory(tempDir, allowSkipIfSameVersion = false)
                requireNotNull(installed) { Strings.plugin_error_install_failed.strOr(context) }
                installed
            } finally {
                if (tempDir.exists()) tempDir.deleteRecursively()
            }
        }
    }

    suspend fun uninstallPlugin(pluginId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            validatePluginId(pluginId)
            Timber.tag(TAG).i(
                "Uninstall plugin requested instance=%s pluginId=%s",
                instanceId,
                pluginId
            )
            logHostInfo("uninstall requested pluginId=$pluginId instance=$instanceId")

            val pluginDir = File(pluginsDir, pluginId)
            if (pluginDir.exists()) {
                pluginDir.deleteRecursively()
            }
            prefs.edit().remove(PREF_ENABLED_PREFIX + pluginId).apply()

            refreshInstalledPlugins()
        }
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            validatePluginId(pluginId)
            Timber.tag(TAG).i(
                "Set plugin enabled requested instance=%s pluginId=%s enabled=%s",
                instanceId,
                pluginId,
                enabled
            )
            logHostInfo("setPluginEnabled requested pluginId=$pluginId enabled=$enabled instance=$instanceId")
            setPluginEnabledInternal(pluginId, enabled)
            refreshInstalledPlugins()
        }
    }

    fun isPluginEnabled(pluginId: String): Boolean {
        val manifest = getInstalledManifestOrNull(pluginId)
        return if (manifest != null) {
            resolvePluginEnabled(manifest)
        } else {
            getStoredPluginEnabledOrNull(pluginId) ?: true
        }
    }

    private fun setPluginEnabledInternal(pluginId: String, enabled: Boolean) {
        prefs.edit().putBoolean(PREF_ENABLED_PREFIX + pluginId, enabled).apply()
    }

    private fun resolvePluginEnabled(manifest: PluginManifest): Boolean {
        return getStoredPluginEnabledOrNull(manifest.id)
            ?: getDefaultEnabledValue(manifest)
    }

    private fun getStoredPluginEnabledOrNull(pluginId: String): Boolean? {
        val key = PREF_ENABLED_PREFIX + pluginId
        if (!prefs.contains(key)) return null
        return prefs.getBoolean(key, true)
    }

    private fun getDefaultEnabledValue(manifest: PluginManifest): Boolean {
        return !manifest.type.equals(PluginTypes.SYSTEM, ignoreCase = true)
    }

    private fun validateManifest(manifest: PluginManifest, pluginDir: File) {
        PluginManifestValidator.validate(
            context = context,
            manifest = manifest,
            pluginDir = pluginDir,
        )
    }

    private fun validatePluginId(id: String) {
        require(id.isNotBlank()) { Strings.plugin_error_id_empty.strOr(context) }
        require(PLUGIN_ID_PATTERN.matches(id)) { Strings.plugin_error_id_invalid.strOr(context, id) }
        require(!id.contains("..")) { Strings.plugin_error_id_contains_dotdot.strOr(context, id) }
        require(!id.contains(File.separatorChar)) { Strings.plugin_error_id_contains_separator.strOr(context, id) }
    }

    private fun moveDirectory(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return

        to.deleteRecursively()
        from.copyRecursively(target = to, overwrite = true)
        from.deleteRecursively()
    }

    private fun CoroutineScope.launchSafe(name: String, block: suspend () -> Unit) {
        launch {
            runCatching { block() }.onFailure { t ->
                Timber.tag(TAG).w(t, "PluginManager task failed: $name")
                logHostError(
                    message = "background task failed task=$name instance=$instanceId reason=${t.message.orEmpty()}",
                    throwable = t
                )
            }
        }
    }

    private fun logHostDebug(message: String) {
        pluginLogManager.debug(PluginHostLogSources.PluginManager, message)
    }

    private fun logHostInfo(message: String) {
        pluginLogManager.info(PluginHostLogSources.PluginManager, message)
    }

    private fun logHostError(message: String, throwable: Throwable) {
        pluginLogManager.error(
            source = PluginHostLogSources.PluginManager,
            message = message,
            stackTrace = throwable.stackTraceToString()
        )
    }

    /**
     * 从“已解包目录”安装插件（适用于 zip 解压后的目录，或 assets 复制出的目录）。
     *
     * @return 安装/更新成功返回 manifest；如果 allowSkipIfSameVersion=true 且已安装同版本则返回 null
     */
    internal fun installPluginFromDirectory(
        extractedDir: File,
        allowSkipIfSameVersion: Boolean,
        markAsBundled: Boolean = false
    ): PluginManifest? {
        val manifestFile = File(extractedDir, MANIFEST_FILE_NAME)
        require(manifestFile.exists()) { Strings.plugin_error_missing_manifest.strOr(context, MANIFEST_FILE_NAME) }

        val manifest = JsonSerializer.decodeFromFile<PluginManifest>(manifestFile)
        validateManifest(manifest, extractedDir)

        val existing = getInstalledManifestOrNull(manifest.id)
        if (allowSkipIfSameVersion && existing?.version == manifest.version) {
            return null
        }

        val pluginDir = File(pluginsDir, manifest.id)
        val previousEnabled = resolvePluginEnabled(manifest)

        if (pluginDir.exists()) pluginDir.deleteRecursively()
        moveDirectory(extractedDir, pluginDir)

        // 如果需要标记为内置插件，更新 manifest.json
        if (markAsBundled && !manifest.isBundled) {
            val updatedManifest = manifest.copy(isBundled = true)
            val targetManifestFile = File(pluginDir, MANIFEST_FILE_NAME)
            JsonSerializer.encodeToFile(targetManifestFile, PluginManifest.serializer(), updatedManifest)
        }

        setPluginEnabledInternal(manifest.id, previousEnabled)
        return manifest
    }

    private fun getInstalledManifestOrNull(pluginId: String): PluginManifest? {
        val pluginDir = File(pluginsDir, pluginId)
        val manifestFile = File(pluginDir, MANIFEST_FILE_NAME)
        if (!manifestFile.exists()) return null
        return JsonSerializer.decodeFromFileOrNull<PluginManifest>(manifestFile)
    }

    fun listInstalledPlugins(): List<InstalledPlugin> {
        return _pluginStateFlow.value.installedPlugins
    }

    fun listEnabledPlugins(): List<InstalledPlugin> {
        return _pluginStateFlow.value.enabledPlugins
    }

    fun getInstalledPlugin(pluginId: String): InstalledPlugin? {
        return _pluginStateFlow.value.installedPlugins.find { it.manifest.id == pluginId }
    }

    fun getEnabledPlugin(pluginId: String): InstalledPlugin? {
        return _pluginStateFlow.value.enabledPlugins.find { it.manifest.id == pluginId }
    }

    fun isPluginInstalled(pluginId: String): Boolean {
        return _pluginStateFlow.value.isInstalled(pluginId)
    }

    fun getInstalledVersion(pluginId: String): String? {
        return _pluginStateFlow.value.getInstalledVersion(pluginId)
    }

    fun listProjectTemplateOptions(): List<ProjectTemplateOption> {
        return _pluginStateFlow.value.enabledPlugins.asSequence()
            .flatMap { plugin ->
                plugin.manifest.contributions?.projectTemplates.orEmpty()
                    .asSequence()
                    .mapNotNull { template -> resolveProjectTemplateOption(plugin, template) }
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }

    fun listApkExportOptions(projectType: ProjectApkExportType): List<ResolvedPluginApkExport> {
        val options = _pluginStateFlow.value.enabledPlugins.asSequence()
            .flatMap { plugin ->
                plugin.manifest.contributions?.apkExports.orEmpty()
                    .asSequence()
                    .mapNotNull { export -> resolveApkExportOption(plugin, export, projectType) }
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
        Timber.tag(TAG).d(
            "Resolved apk export options instance=%s projectType=%s options=%s",
            instanceId,
            projectType,
            options.joinToString(",") { "${it.pluginId}:${it.exportId}" }
        )
        logHostDebug(
            "listApkExportOptions instance=$instanceId projectType=$projectType options=${
                options.joinToString(",") { "${it.pluginId}:${it.exportId}" }
            }"
        )
        return options
    }

    fun hasEnabledCapability(capability: String): Boolean {
        if (capability.isBlank()) return false
        return _pluginStateFlow.value.enabledCapabilities.contains(capability)
    }

    suspend fun install(zipFile: File): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        installPlugin(zipFile).map { manifest ->
            refreshInstalledPlugins()
            logHostInfo("install completed pluginId=${manifest.id} version=${manifest.version} instance=$instanceId")
            getInstalledPlugin(manifest.id)
                ?: throw IllegalStateException("Plugin installed but not found: ${manifest.id}")
        }
    }

    fun resolveFileTreeContextMenuItems(
        installedPlugins: List<InstalledPlugin>,
        file: File,
        isDirectory: Boolean
    ): List<ResolvedHostMenuItem> {
        return PluginMenuResolver.resolveFileTreeContextMenuItems(
            context = context,
            installedPlugins = installedPlugins,
            file = file,
            isDirectory = isDirectory
        )
    }

    fun resolveFileTreeIcons(
        installedPlugins: List<InstalledPlugin>
    ): List<ResolvedPluginFileIcon> {
        return PluginFileIconResolver.resolve(installedPlugins)
    }

    fun resolveEditorContextMenuItems(
        installedPlugins: List<InstalledPlugin>,
        file: File,
        isDirty: Boolean
    ): List<ResolvedHostMenuItem> {
        return PluginMenuResolver.resolveEditorContextMenuItems(
            context = context,
            installedPlugins = installedPlugins,
            file = file,
            isDirty = isDirty
        )
    }

    private fun resolveProjectTemplateOption(
        plugin: InstalledPlugin,
        template: PluginProjectTemplate
    ): ProjectTemplateOption? {
        val buildSystem = PluginManifestValidator.parseProjectBuildSystem(template.buildSystem)
        if (buildSystem == null) {
            Timber.tag(TAG).w(
                "Skip plugin template %s/%s: unsupported build system %s",
                plugin.manifest.id,
                template.id,
                template.buildSystem
            )
            return null
        }

        val zipFile = File(plugin.directory, template.templatePath)
        if (!zipFile.isFile) {
            Timber.tag(TAG).w(
                "Skip plugin template %s/%s: missing zip %s",
                plugin.manifest.id,
                template.id,
                zipFile.absolutePath
            )
            return null
        }

        val optionId = "plugin:${plugin.manifest.id}:${template.id}"
        return ProjectTemplateOption(
            id = optionId,
            displayName = template.name,
            description = template.description,
            spec = ProjectTemplateSpec.Zip(
                id = optionId,
                zipFile = zipFile,
                buildSystem = buildSystem,
                primaryLanguage = parseProjectLanguage(template.primaryLanguage),
                isNdkTemplate = template.isNdkTemplate
            )
        )
    }

    private fun resolveApkExportOption(
        plugin: InstalledPlugin,
        export: PluginApkExport,
        projectType: ProjectApkExportType
    ): ResolvedPluginApkExport? {
        val supportedProjectTypes = export.projectTypes
            .mapNotNull(::parseProjectApkExportType)
            .toSet()
        if (projectType !in supportedProjectTypes) return null

        val templateType = normalizePluginApkTemplateType(export.templateType)
        if (templateType == null) {
            Timber.tag(TAG).w(
                "Skip plugin apk export %s/%s: unsupported template type %s",
                plugin.manifest.id,
                export.id,
                export.templateType
            )
            return null
        }

        val templateFile = File(plugin.directory, export.templatePath)
        if (!templateFile.isFile) {
            Timber.tag(TAG).w(
                "Skip plugin apk export %s/%s: missing template %s",
                plugin.manifest.id,
                export.id,
                templateFile.absolutePath
            )
            return null
        }

        val optionId = "plugin:${plugin.manifest.id}:${export.id}"
        return ResolvedPluginApkExport(
            optionId = optionId,
            pluginId = plugin.manifest.id,
            exportId = export.id,
            displayName = export.name,
            description = export.description,
            projectTypes = supportedProjectTypes,
            templateType = templateType,
            templateFile = templateFile
        )
    }

    private fun parseProjectApkExportType(value: String): ProjectApkExportType? {
        return ProjectApkExportType.entries.firstOrNull { entry ->
            entry.name.equals(value.trim(), ignoreCase = true)
        }
    }

    private fun normalizePluginApkTemplateType(value: String): String? {
        return when (value.trim().lowercase()) {
            "native_activity", "native-activity", "nativeactivity" -> "native_activity"
            "sdl3" -> "sdl3"
            "terminal" -> "terminal"
            else -> null
        }
    }

    private fun parseProjectLanguage(value: String?): ProjectLanguage {
        val language = ProjectLanguage.fromString(value)
        return if (language == ProjectLanguage.UNKNOWN) ProjectLanguage.CPP else language
    }
}

