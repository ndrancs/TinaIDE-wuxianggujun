package com.wuxianggujun.tinaide.core.ndk

import android.content.Context
import com.wuxianggujun.tinaide.core.common.io.TarExtractor
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties

class AndroidNativeToolchainManager(private val context: Context) {

    companion object {
        private const val TAG = "NativeToolchainManager"

        private const val ASSET_DIR = "tina-toolchain"
        private const val ASSET_SPEC_PATH = "$ASSET_DIR/current.properties"
        private const val ASSET_VARIANTS_DIR = "$ASSET_DIR/variants"
        private const val BUILTIN_ID_PREFIX = "builtin-"

        private const val INSTALL_METADATA_FILE = "install-metadata.properties"

        private const val META_KEY_VERSION = "version"
        private const val META_KEY_VARIANT = "variant"
        private const val META_KEY_BASE_ASSET = "baseAsset"
        private const val META_KEY_BASE_SHA256 = "baseSha256"
        private const val META_KEY_TOOLS_ASSET = "toolsAsset"
        private const val META_KEY_TOOLS_SHA256 = "toolsSha256"
    }

    private val configManager = ToolchainConfigManager(context)

    data class AssetSpec(
        val version: String,
        val arch: String,
        val baseTarXz: String,
        val toolsTarXz: String?,
        val sha256: String?,
        val usesFullArchive: Boolean,
        val variantId: String? = null,
    )

    private data class InstallFingerprint(
        val variantId: String?,
        val baseAssetName: String,
        val baseSha256: String,
        val toolsAssetName: String?,
        val toolsSha256: String?,
    )

    private data class InstalledMetadata(
        val version: String?,
        val variantId: String?,
        val baseAssetName: String?,
        val baseSha256: String?,
        val toolsAssetName: String?,
        val toolsSha256: String?,
    ) {
        fun matches(expected: InstallFingerprint): Boolean {
            if (variantId != expected.variantId) return false
            if (baseAssetName != expected.baseAssetName) return false
            if (!baseSha256.equals(expected.baseSha256, ignoreCase = true)) return false

            val expectedToolsAsset = expected.toolsAssetName
            val installedToolsAsset = toolsAssetName
            if (expectedToolsAsset != installedToolsAsset) return false

            val expectedToolsSha = expected.toolsSha256
            val installedToolsSha = toolsSha256
            return when {
                expectedToolsSha == null && installedToolsSha == null -> true
                expectedToolsSha != null && installedToolsSha != null ->
                    installedToolsSha.equals(expectedToolsSha, ignoreCase = true)
                else -> false
            }
        }
    }

    private val installDirInternal: File
        get() {
            // 优先激活工具链；若配置丢失/损坏，回退到已安装目录，避免直接崩溃。
            return resolvePreferredInstallDirOrNull()
                ?: throw IllegalStateException(Strings.toolchain_error_no_active.strOr(context))
        }

    fun getInstallDir(): File = installDirInternal

    fun getBinDir(): File = File(installDirInternal, "bin")

    /**
     * 获取指定工具链的安装目录
     *
     * @param toolchainId 工具链 ID，null 表示使用全局激活的工具链
     * @return 工具链安装目录
     */
    fun getInstallDir(toolchainId: String?): File {
        return if (toolchainId == null) {
            installDirInternal
        } else {
            configManager.getToolchainDir(toolchainId).takeIf { it.exists() }
                ?: throw IllegalStateException(
                    Strings.toolchain_error_not_found.strOr(context, toolchainId)
                )
        }
    }

    /**
     * 获取指定工具链的 bin 目录
     *
     * @param toolchainId 工具链 ID，null 表示使用全局激活的工具链
     * @return bin 目录
     */
    fun getBinDir(toolchainId: String?): File = File(getInstallDir(toolchainId), "bin")

    /**
     * 获取配置管理器
     */
    fun getConfigManager(): ToolchainConfigManager = configManager

    fun isInstalled(expectedVersion: String? = null): Boolean {
        return isInstalledInternal(expectedVersion = expectedVersion, expectedFingerprint = null)
    }

    /**
     * 检查“当前 assets 规范”对应的工具链是否已安装。
     *
     * 判定条件：
     * 1) VERSION 中版本号匹配
     * 2) install-metadata 中记录的主包/tools 资产名与 SHA256 匹配
     */
    fun isInstalledForCurrentAssets(): Boolean = isInstalledForAssetSpec(assetVariantId = null)

    fun isReadyForCurrentAssets(): Boolean {
        val config = configManager.readConfig()
        val activeId = config.activeToolchain ?: return false
        val activeToolchain = config.toolchains.firstOrNull { it.id == activeId } ?: return false
        val activeDir = File(context.filesDir, activeToolchain.path)
        if (!activeDir.isDirectory) return false

        if (activeToolchain.type == ToolchainType.CUSTOM) return isInstalled()

        val spec = readAssetSpec(assetVariantId = null) ?: return false
        return activeId == buildBuiltinToolchainId(spec) && isInstalledForCurrentAssets()
    }

    fun isInstalledForAssetVariant(variantId: String): Boolean {
        return isInstalledForAssetSpec(assetVariantId = variantId.trim().takeIf { it.isNotBlank() })
    }

    private fun isInstalledForAssetSpec(assetVariantId: String?): Boolean {
        val spec = readAssetSpec(assetVariantId) ?: return false
        val expectedShaMap = if (spec.sha256 != null) {
            val map = readExpectedSha256Map(spec)
            if (map.isEmpty()) {
                Timber.tag(TAG).w("Toolchain sha256 file is empty/unreadable: %s", spec.sha256)
                return false
            }
            map
        } else {
            emptyMap()
        }

        val expectedFingerprint = runCatching {
            buildExpectedFingerprint(spec, expectedShaMap)
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "Failed to build expected toolchain fingerprint")
        }.getOrNull()

        return isInstalledInternal(
            expectedVersion = spec.version,
            expectedFingerprint = expectedFingerprint,
            expectedToolchainId = buildBuiltinToolchainId(spec)
        )
    }

    private fun isInstalledInternal(
        expectedVersion: String?,
        expectedFingerprint: InstallFingerprint?,
        expectedToolchainId: String? = null
    ): Boolean {
        val dir = expectedToolchainId
            ?.let { configManager.getToolchainDir(it) }
            ?: resolveInstallDirForCheck(expectedVersion)
        if (dir == null) {
            Timber.tag(TAG).d("Toolchain not installed: no active/fallback install dir found")
            return false
        }
        if (!dir.isDirectory) {
            Timber.tag(TAG).d("Toolchain not installed: dir missing %s", dir.absolutePath)
            return false
        }
        if (expectedVersion != null) {
            val installedVersion = readInstalledToolchainVersion(dir)
            if (installedVersion == null || installedVersion != expectedVersion) {
                Timber.tag(TAG).w(
                    "Toolchain version mismatch: installed=%s expected=%s dir=%s",
                    installedVersion,
                    expectedVersion,
                    dir.absolutePath
                )
                return false
            }
        }
        val clang = File(dir, "bin/clang")
        val clangxx = File(dir, "bin/clang++")
        if (!clang.isFile || !clangxx.exists()) {
            Timber.tag(TAG).w(
                "Toolchain missing compiler binaries: clang=%s (exists=%s), clang++=%s (exists=%s)",
                clang.absolutePath,
                clang.exists(),
                clangxx.absolutePath,
                clangxx.exists()
            )
            return false
        }

        // 部分机型/文件系统可能导致可执行位丢失：
        // 如果二进制存在但不可执行，尝试就地修复权限；仍不可执行则视为未安装，触发重新解压。
        if (!clang.canExecute() || !clangxx.canExecute()) {
            Timber.tag(TAG).w(
                "Toolchain compiler not executable, attempting chmod: clangExec=%s clangxxExec=%s",
                clang.canExecute(),
                clangxx.canExecute()
            )
            ensureExecutables(dir)
        }
        if (!clang.canExecute() || !clangxx.canExecute()) {
            Timber.tag(TAG).w(
                "Toolchain compiler still not executable after chmod: clangExec=%s clangxxExec=%s",
                clang.canExecute(),
                clangxx.canExecute()
            )
            return false
        }

        // 仅检查工具链自身的文件（clang + clang resource dir），
        // sysroot 由 AndroidSysrootManager 独立管理，不在此处检查。
        val clangResource = File(dir, "lib/clang")
        if (!clangResource.isDirectory) {
            Timber.tag(TAG).w("Toolchain clang resource dir missing: %s", clangResource.absolutePath)
            return false
        }

        if (expectedFingerprint != null) {
            val installedMetadata = readInstalledMetadata(dir)
            if (installedMetadata == null) {
                Timber.tag(TAG).w(
                    "Toolchain fingerprint metadata missing: %s",
                    File(dir, INSTALL_METADATA_FILE).absolutePath
                )
                return false
            }
            if (!installedMetadata.matches(expectedFingerprint)) {
                Timber.tag(TAG).w(
                    "Toolchain fingerprint mismatch: installed(base=%s, baseSha=%s, tools=%s, toolsSha=%s) " +
                        "expected(base=%s, baseSha=%s, tools=%s, toolsSha=%s)",
                    installedMetadata.baseAssetName,
                    installedMetadata.baseSha256,
                    installedMetadata.toolsAssetName,
                    installedMetadata.toolsSha256,
                    expectedFingerprint.baseAssetName,
                    expectedFingerprint.baseSha256,
                    expectedFingerprint.toolsAssetName,
                    expectedFingerprint.toolsSha256
                )
                return false
            }
        }
        return true
    }

    fun readAssetSpec(assetVariantId: String? = null): AssetSpec? {
        return runCatching {
            val props = Properties()
            val specPath = assetSpecPath(assetVariantId)
            context.assets.open(specPath).use { props.load(it) }

            val version = props.getProperty("version")?.trim().orEmpty()
            val arch = props.getProperty("arch")?.trim().orEmpty()
            val full = props.getProperty("full")?.trim()?.takeIf { it.isNotBlank() }
            val base = props.getProperty("base")?.trim()?.takeIf { it.isNotBlank() }
            val tools = props.getProperty("tools")?.trim()?.takeIf { it.isNotBlank() }
            val sha256 = props.getProperty("sha256")?.trim()?.takeIf { it.isNotBlank() }
            val variant = props.getProperty("variant")?.trim()?.takeIf { it.isNotBlank() }
                ?: assetVariantId?.trim()?.takeIf { it.isNotBlank() }

            if (version.isBlank() || arch.isBlank()) return@runCatching null

            val mainArchive = full ?: base ?: return@runCatching null
            val usesFullArchive = full != null

            AssetSpec(
                version = version,
                arch = arch,
                baseTarXz = "$ASSET_DIR/$mainArchive",
                toolsTarXz = if (usesFullArchive) null else tools?.let { "$ASSET_DIR/$it" },
                sha256 = sha256?.let { "$ASSET_DIR/$it" },
                usesFullArchive = usesFullArchive,
                variantId = variant
            )
        }.getOrNull()
    }

    fun builtinToolchainIdForAssetVariant(variantId: String): String? {
        return readAssetSpec(variantId)?.let(::buildBuiltinToolchainId)
    }

    private fun assetSpecPath(assetVariantId: String?): String {
        val normalized = assetVariantId?.trim()?.takeIf { it.isNotBlank() }
        return if (normalized == null) ASSET_SPEC_PATH else "$ASSET_VARIANTS_DIR/$normalized.properties"
    }

    private fun buildBuiltinToolchainId(spec: AssetSpec): String {
        val variantSuffix = spec.variantId
            ?.replace(Regex("[^a-zA-Z0-9.-]"), "-")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        return listOfNotNull(BUILTIN_ID_PREFIX + spec.version, variantSuffix).joinToString("-")
    }

    private fun builtinToolchainName(spec: AssetSpec): String {
        return when (spec.variantId) {
            "patched" -> Strings.toolchain_builtin_patched_name.strOr(context)
            else -> Strings.toolchain_builtin_name.strOr(context)
        }
    }

    /**
     * 从外部文件导入工具链
     *
     * @param archiveFile 工具链压缩包文件（支持 .tar.xz / .tar.gz / .tar）
     * @param toolchainName 工具链名称（可选，默认使用版本号）
     * @param onProgress 进度回调 (0.0 - 1.0)
     * @return 导入结果，包含工具链 ID
     */
    suspend fun importFromFile(
        archiveFile: File,
        toolchainName: String? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!archiveFile.exists() || !archiveFile.isFile) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        Strings.toolchain_error_archive_missing.strOr(context, archiveFile.absolutePath)
                    )
                )
            }

            Timber.tag(TAG).i("Importing toolchain from file: %s", archiveFile.absolutePath)

            val tempDir = File(context.cacheDir, "toolchain-import-${System.currentTimeMillis()}")
            tempDir.mkdirs()

            try {
                onProgress?.invoke(0.1f)

                // 解压到临时目录
                TarExtractor.extract(archiveFile, tempDir) { p ->
                    onProgress?.invoke(0.1f + p * 0.6f)
                }

                onProgress?.invoke(0.7f)

            // 查找工具链根目录
            val extractedRoot = findExtractedToolchainRoot(tempDir)
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        Strings.toolchain_import_error_invalid_archive.strOr(context)
                    )
                )

                // 验证工具链完整性
                val clang = File(extractedRoot, "bin/clang")
                val clangxx = File(extractedRoot, "bin/clang++")
                val clangResource = File(extractedRoot, "lib/clang")

            if (!clang.isFile || !clangxx.isFile || !clangResource.isDirectory) {
                return@withContext Result.failure(
                    IllegalStateException(
                        Strings.toolchain_import_error_missing_files.strOr(context)
                    )
                )
            }

                // 读取版本信息（如果存在）
                val version = readToolchainVersion(extractedRoot)
                Timber.tag(TAG).i("Importing toolchain version: %s", version ?: "unknown")

                onProgress?.invoke(0.8f)

                // 生成工具链 ID
                val toolchainId = "custom-${version ?: System.currentTimeMillis()}"
                    .replace(Regex("[^a-zA-Z0-9.-]"), "-")
                    .lowercase()

                // 移动到新的多工具链目录结构
                val targetDir = configManager.getToolchainDir(toolchainId)
                targetDir.parentFile?.mkdirs()

                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }

                if (!extractedRoot.renameTo(targetDir)) {
                    extractedRoot.copyRecursively(targetDir, overwrite = true)
                    extractedRoot.deleteRecursively()
                }

                ensureExecutables(targetDir)

                // 注册到配置管理器
                val toolchainInfo = ToolchainInfo(
                    id = toolchainId,
                    name = toolchainName ?: version ?: Strings.toolchain_custom_name.strOr(context),
                    version = version,
                    type = ToolchainType.CUSTOM,
                    path = "toolchains/$toolchainId",
                    installedAt = System.currentTimeMillis()
                )
                configManager.registerToolchain(toolchainInfo).getOrThrow()

                onProgress?.invoke(1.0f)

                Timber.tag(TAG).i("Toolchain imported successfully: $toolchainId at ${targetDir.absolutePath}")
                Result.success(toolchainId)
            } finally {
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Toolchain import failed")
            Result.failure(e)
        }
    }

    suspend fun install(
        onProgress: ((Float) -> Unit)? = null,
    ): Result<Unit> {
        return installFromAssetSpec(
            assetVariantId = null,
            activateIfNeeded = true,
            onProgress = onProgress
        ).map { Unit }
    }

    suspend fun installAssetVariant(
        variantId: String,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<String> {
        return installFromAssetSpec(
            assetVariantId = variantId.trim().takeIf { it.isNotBlank() },
            activateIfNeeded = false,
            onProgress = onProgress
        )
    }

    private suspend fun installFromAssetSpec(
        assetVariantId: String?,
        activateIfNeeded: Boolean,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        val specPath = assetSpecPath(assetVariantId)
        try {
            val spec = readAssetSpec(assetVariantId)
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        Strings.toolchain_error_asset_spec_missing.strOr(context, specPath)
                    )
                )

            val currentArch = currentArchName()
            if (spec.arch != currentArch) {
                return@withContext Result.failure(
                    IllegalStateException(
                        Strings.toolchain_error_arch_mismatch.strOr(context, spec.arch, currentArch)
                    )
                )
            }

            fun assetExists(path: String): Boolean {
                return try {
                    context.assets.open(path).use { true }
                } catch (_: java.io.IOException) {
                    false
                }
            }

            if (!assetExists(spec.baseTarXz)) {
                val missingType = if (spec.usesFullArchive) "full" else "base"
                return@withContext Result.failure(
                    IllegalStateException(
                        Strings.toolchain_error_asset_missing.strOr(context, missingType, spec.baseTarXz)
                    )
                )
            }
            if (spec.toolsTarXz != null && !assetExists(spec.toolsTarXz)) {
                return@withContext Result.failure(
                    IllegalStateException(
                        Strings.toolchain_error_tools_asset_missing.strOr(context, spec.toolsTarXz)
                    )
                )
            }
            if (spec.sha256 != null && !assetExists(spec.sha256)) {
                return@withContext Result.failure(
                    IllegalStateException(
                        Strings.toolchain_error_sha256_asset_missing.strOr(context, spec.sha256)
                    )
                )
            }

            val expectedShaMap = if (spec.sha256 != null) {
                val map = readExpectedSha256Map(spec)
                if (map.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            Strings.toolchain_error_sha256_invalid.strOr(context, spec.sha256)
                        )
                    )
                }
                map
            } else {
                emptyMap()
            }
            val expectedFingerprint = buildExpectedFingerprint(spec, expectedShaMap)
            val toolchainId = buildBuiltinToolchainId(spec)

            Timber.tag(TAG).i(
                "Installing toolchain from assets (id=%s, version=%s, arch=%s, variant=%s, mode=%s)",
                toolchainId,
                spec.version,
                spec.arch,
                spec.variantId ?: "default",
                if (spec.usesFullArchive) "full" else "split"
            )

            if (
                isInstalledInternal(
                    expectedVersion = spec.version,
                    expectedFingerprint = expectedFingerprint,
                    expectedToolchainId = toolchainId
                )
            ) {
                if (activateIfNeeded && shouldActivateInstalledBuiltin(toolchainId)) {
                    configManager.switchToolchain(toolchainId).getOrThrow()
                }
                Timber.tag(TAG).i("Toolchain already installed (id=%s, version+sha256 matched)", toolchainId)
                return@withContext Result.success(toolchainId)
            }

            val tempDir = File(context.cacheDir, "toolchain-install-${System.currentTimeMillis()}")
            tempDir.mkdirs()

            try {
                onProgress?.invoke(0.05f)

                val baseTar = File(tempDir, File(spec.baseTarXz).name)
                context.assets.open(spec.baseTarXz).use { input ->
                    FileOutputStream(baseTar).use { output -> input.copyTo(output) }
                }
                verifySha256IfPresent(baseTar, expectedShaMap)

                onProgress?.invoke(0.15f)
                TarExtractor.extract(baseTar, tempDir) { p ->
                    onProgress?.invoke(0.15f + p * 0.6f)
                }

                spec.toolsTarXz?.let { toolsAsset ->
                    val toolsTar = File(tempDir, File(toolsAsset).name)
                    context.assets.open(toolsAsset).use { input ->
                        FileOutputStream(toolsTar).use { output -> input.copyTo(output) }
                    }
                    verifySha256IfPresent(toolsTar, expectedShaMap)
                    TarExtractor.extract(toolsTar, tempDir) { p ->
                        onProgress?.invoke(0.75f + p * 0.15f)
                    }
                }

                onProgress?.invoke(0.92f)
                val extractedRoot = findExtractedToolchainRoot(tempDir)
                    ?: return@withContext Result.failure(
                        IllegalStateException(
                            Strings.toolchain_error_root_missing.strOr(context)
                        )
                    )

                val targetDir = configManager.getToolchainDir(toolchainId)
                targetDir.parentFile?.mkdirs()
                if (targetDir.exists()) targetDir.deleteRecursively()

                if (!extractedRoot.renameTo(targetDir)) {
                    extractedRoot.copyRecursively(targetDir, overwrite = true)
                    extractedRoot.deleteRecursively()
                }

                ensureExecutables(targetDir)
                writeInstalledMetadata(targetDir, spec.version, expectedFingerprint)

                val toolchainInfo = ToolchainInfo(
                    id = toolchainId,
                    name = builtinToolchainName(spec),
                    version = spec.version,
                    type = ToolchainType.BUILTIN,
                    path = "toolchains/$toolchainId",
                    installedAt = System.currentTimeMillis()
                )
                val configBeforeRegister = configManager.readConfig()
                if (configBeforeRegister.toolchains.none { it.id == toolchainId }) {
                    configManager.registerToolchain(toolchainInfo).getOrThrow()
                }

                if (activateIfNeeded && shouldActivateInstalledBuiltin(toolchainId)) {
                    configManager.switchToolchain(toolchainId).getOrThrow()
                }

                onProgress?.invoke(1.0f)
                Timber.tag(TAG).i("Toolchain installed: %s", targetDir.absolutePath)
                Result.success(toolchainId)
            } finally {
                tempDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Toolchain installation failed")
            Result.failure(e)
        }
    }

    private fun currentArchName(): String {
        val triple = AndroidSysrootManager.Companion.Arch.current().triple
        return when {
            triple.startsWith("aarch64") -> "aarch64"
            triple.startsWith("x86_64") -> "x86_64"
            else -> "aarch64"
        }
    }

    private fun readInstalledToolchainVersion(toolchainDir: File): String? {
        return readToolchainVersion(toolchainDir)
    }

    private fun resolveInstallDirForCheck(expectedVersion: String?): File? {
        configManager.getActiveToolchainDir()?.takeIf { it.isDirectory }?.let { return it }

        if (!expectedVersion.isNullOrBlank()) {
            val expectedBuiltinDir = configManager.getToolchainDir("$BUILTIN_ID_PREFIX$expectedVersion")
            if (expectedBuiltinDir.isDirectory) return expectedBuiltinDir
        }

        return resolvePreferredInstallDirOrNull()
    }

    private fun resolvePreferredInstallDirOrNull(): File? {
        configManager.getActiveToolchainDir()?.takeIf { it.isDirectory }?.let { return it }

        val config = configManager.readConfig()
        val bestFromConfig = config.toolchains
            .sortedWith(
                compareByDescending<ToolchainInfo> { it.type == ToolchainType.BUILTIN }
                    .thenByDescending { it.installedAt }
            )
            .map { File(context.filesDir, it.path) }
            .firstOrNull { it.isDirectory }
        if (bestFromConfig != null) return bestFromConfig

        return null
    }

    private fun shouldActivateInstalledBuiltin(toolchainId: String): Boolean {
        val config = configManager.readConfig()
        val activeId = config.activeToolchain ?: return true
        if (activeId == toolchainId) return true

        val activeToolchain = config.toolchains.firstOrNull { it.id == activeId } ?: return true
        val activeDir = File(context.filesDir, activeToolchain.path)
        if (!activeDir.isDirectory) return true

        return activeToolchain.type == ToolchainType.BUILTIN
    }

    private fun readInstalledMetadata(toolchainDir: File): InstalledMetadata? {
        val metadataFile = File(toolchainDir, INSTALL_METADATA_FILE)
        if (!metadataFile.isFile) return null
        val props = runCatching {
            Properties().apply {
                metadataFile.inputStream().buffered().use { load(it) }
            }
        }.getOrNull() ?: return null
        return InstalledMetadata(
            version = props.getProperty(META_KEY_VERSION)?.trim()?.takeIf { it.isNotBlank() },
            variantId = props.getProperty(META_KEY_VARIANT)?.trim()?.takeIf { it.isNotBlank() },
            baseAssetName = props.getProperty(META_KEY_BASE_ASSET)?.trim()?.takeIf { it.isNotBlank() },
            baseSha256 = props.getProperty(META_KEY_BASE_SHA256)?.trim()?.takeIf { it.isNotBlank() },
            toolsAssetName = props.getProperty(META_KEY_TOOLS_ASSET)?.trim()?.takeIf { it.isNotBlank() },
            toolsSha256 = props.getProperty(META_KEY_TOOLS_SHA256)?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun writeInstalledMetadata(
        toolchainDir: File,
        version: String,
        fingerprint: InstallFingerprint?
    ) {
        val metadataFile = File(toolchainDir, INSTALL_METADATA_FILE)
        val props = Properties().apply {
            setProperty(META_KEY_VERSION, version)
            if (fingerprint != null) {
                fingerprint.variantId?.let { setProperty(META_KEY_VARIANT, it) }
                setProperty(META_KEY_BASE_ASSET, fingerprint.baseAssetName)
                setProperty(META_KEY_BASE_SHA256, fingerprint.baseSha256.lowercase())
                fingerprint.toolsAssetName?.let { setProperty(META_KEY_TOOLS_ASSET, it) }
                fingerprint.toolsSha256?.let { setProperty(META_KEY_TOOLS_SHA256, it.lowercase()) }
            }
        }
        runCatching {
            metadataFile.outputStream().buffered().use { output ->
                props.store(output, "TinaIDE toolchain install metadata")
            }
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "Failed to write toolchain install metadata: %s", metadataFile.absolutePath)
        }
    }

    private fun readToolchainVersion(toolchainDir: File): String? {
        val versionFile = File(toolchainDir, "VERSION")
        if (!versionFile.isFile) return null
        val text = runCatching { versionFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val match = Regex("""(?m)^Toolchain Version:\s*([^\s]+)\s*$""").find(text) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun buildExpectedFingerprint(
        spec: AssetSpec,
        expectedShaMap: Map<String, String>
    ): InstallFingerprint? {
        if (spec.sha256 == null) return null

        val baseAssetName = File(spec.baseTarXz).name
        val baseSha = expectedShaMap[baseAssetName]?.trim()?.lowercase()
            ?: throw IllegalStateException(
                Strings.toolchain_error_sha256_missing_entry.strOr(context, baseAssetName)
            )

        val toolsAssetName = spec.toolsTarXz?.let { File(it).name }
        val toolsSha = toolsAssetName?.let { name ->
            expectedShaMap[name]?.trim()?.lowercase()
                ?: throw IllegalStateException(
                    Strings.toolchain_error_sha256_missing_entry.strOr(context, name)
                )
        }

        return InstallFingerprint(
            variantId = spec.variantId,
            baseAssetName = baseAssetName,
            baseSha256 = baseSha,
            toolsAssetName = toolsAssetName,
            toolsSha256 = toolsSha
        )
    }

    private fun readExpectedSha256Map(spec: AssetSpec): Map<String, String> {
        val shaAsset = spec.sha256 ?: return emptyMap()
        return runCatching {
            context.assets.open(shaAsset).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                    val parts = trimmed.split(Regex("\\s+"), limit = 2)
                    if (parts.size < 2) return@mapNotNull null
                    val hash = parts[0].trim().lowercase()
                    val name = parts[1].trim()
                    if (hash.length != 64 || name.isBlank()) return@mapNotNull null
                    name to hash
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun verifySha256IfPresent(file: File, expectedMap: Map<String, String>) {
        if (expectedMap.isEmpty()) return

        val expected = expectedMap[file.name]
            ?: throw IllegalStateException(
                Strings.toolchain_error_sha256_missing_entry.strOr(context, file.name)
            )
        val actual = sha256Hex(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IllegalStateException(
                Strings.toolchain_error_sha256_mismatch.strOr(context, file.name, expected, actual)
            )
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun ensureExecutables(toolchainDir: File) {
        val binDir = File(toolchainDir, "bin")
        if (!binDir.isDirectory) return
        binDir.listFiles().orEmpty()
            .filter { it.isFile }
            .forEach { runCatching { it.setExecutable(true, false) } }
    }

    private fun findExtractedToolchainRoot(tempDir: File): File? {
        val candidates = tempDir.listFiles().orEmpty()
            .filter { it.isDirectory }

        // toolchain 包不再包含 android-sysroot，仅通过 bin/clang 识别根目录
        val byMarker = candidates.firstOrNull { dir ->
            File(dir, "bin/clang").isFile
        }
        if (byMarker != null) return byMarker

        // 保守兜底：取唯一的目录
        return candidates.singleOrNull()
    }
}
