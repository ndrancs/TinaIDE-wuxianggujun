package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.LinuxRunModePolicy
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.ndk.AndroidNativeToolchainManager
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import com.wuxianggujun.tinaide.core.packages.store.LocalInstallStateStore
import com.wuxianggujun.tinaide.core.proot.PRootBootstrap
import com.wuxianggujun.tinaide.core.util.ClangResourceDirLocator
import com.wuxianggujun.tinaide.project.CppStandard
import com.wuxianggujun.tinaide.project.NativeBuildFlagTokenizer
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import timber.log.Timber

/**
 * 编译数据库提供者
 *
 * 职责：确保任何 C/C++ 源文件都有可用的 compile_commands.json（避免 clangd fallback 模式导致大量误报）。
 *
 * 设计要点：
 * - 优先复用项目内已有的 compile_commands.json（通常来自 CMake 构建）。
 * - compile_commands.json 统一保留在项目构建目录内，便于用户直接查看。
 * - clangd 使用的版本直接在项目构建目录内归一化，不再复制到额外的私有构建目录。
 */
class CompileDatabaseProvider(context: Context) {

    companion object {
        private const val TAG = "CompileDbProvider"
        private const val COMPILE_COMMANDS_META_FILE_NAME = "compile_commands.tina.meta.properties"
        private const val META_KEY_CPP_STANDARD = "cppStandard"
        private const val META_KEY_PACKAGE_FINGERPRINT = "packageFingerprint"
        private const val META_KEY_TOOLCHAIN_ID = "toolchainId"

        private val COMPILE_COMMANDS_SEARCH_PATHS = listOf(
            "build/compile_commands.json",
            "build/debug/compile_commands.json",
            "build/release/compile_commands.json",
            "cmake-build-debug/compile_commands.json",
            "cmake-build-release/compile_commands.json",
            "out/build/compile_commands.json",
            "compile_commands.json"
        )

        private fun findClangResourceDir(rootfsDir: File): File? {
            val found = ClangResourceDirLocator.find(rootfsDir)
            if (found != null) {
                Timber.tag(TAG).d("Found clang resource directory: ${found.absolutePath}")
            }
            return found
        }
    }

    private val appContext = context.applicationContext
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider by lazy {
        runCatching {
            org.koin.core.context.GlobalContext.get().getOrNull<LinuxEnvironmentProvider>()
        }.getOrNull() ?: UnavailableLinuxEnvironmentProvider
    }

    enum class ProjectType {
        CMAKE_PROJECT,
        SINGLE_FILE_PROJECT,
        STANDALONE_FILE
    }

    data class Prepared(
        val file: File,
        val workspaceRoot: File,
        val projectType: ProjectType,
        val compileCommandsDir: File,
        val sourceCompileCommandsDir: File,
        val shouldGenerate: Boolean,
        val scanRoot: File,
        val isCxx: Boolean,
        val desiredCppStandard: CppStandard,
        val packageFingerprint: String,
        val toolchainId: String?,
    )

    data class EnsureResult(
        val compileCommandsDir: File,
        /** 是否发生了“生成/重建”（例如首次生成或 C++ 标准变更触发重建） */
        val regenerated: Boolean,
    )

    fun prepare(file: File, projectRootPath: String?, toolchainId: String? = null): Prepared? {
        val workspaceRoot = resolveWorkspaceRoot(file, projectRootPath) ?: return null
        val metadata = ProjectMetadataStore.read(workspaceRoot)
        val buildSystem = metadata?.buildSystem
        val defaultBuildDir = File(File(workspaceRoot, "build"), "debug")
        val existingCompileDir = findExistingCompileCommandsDir(workspaceRoot)
        val compileCommandsDir = existingCompileDir ?: defaultBuildDir
        val baseProjectType = when {
            projectRootPath.isNullOrBlank() -> ProjectType.STANDALONE_FILE
            else -> ProjectType.SINGLE_FILE_PROJECT
        }
        val isCmakeProject =
            buildSystem == ProjectBuildSystem.CMAKE || File(workspaceRoot, "CMakeLists.txt").isFile

        val isCxx = when (file.extension.lowercase()) {
            "c" -> false
            "m" -> false
            else -> true
        }

        val sourceCompileCommandsFile = File(compileCommandsDir, "compile_commands.json")
        val scanRoot = workspaceRoot
        val desiredCppStandard = resolveCppStandard(workspaceRoot)
        val packageFingerprint = resolvePackageFingerprint(workspaceRoot)
        val hasUsableCompileCommands =
            sourceCompileCommandsFile.isFile && sourceCompileCommandsFile.length() > 0
        val shouldReuseExisting = when {
            !hasUsableCompileCommands -> false
            isCmakeProject -> true
            else -> compileCommandsUpToDate(
                compileCommandsFile = sourceCompileCommandsFile,
                isCxx = isCxx,
                compileCommandsDir = compileCommandsDir,
                desiredCppStandard = desiredCppStandard,
                packageFingerprint = packageFingerprint,
                toolchainId = toolchainId,
            )
        }
        val projectType = when {
            isCmakeProject -> ProjectType.CMAKE_PROJECT
            else -> baseProjectType
        }
        val shouldGenerate = !shouldReuseExisting

        if (CompileCommandsDebugLogger.isCompileCommandsSelectionEnabled()) {
            Timber.tag(TAG).i(
                "prepare: file=%s, workspace=%s, buildSystem=%s, projectType=%s, existingCompileDir=%s, compileCommandsDir=%s, shouldGenerate=%s, isCxx=%s, std=%s, toolchainId=%s",
                file.absolutePath,
                workspaceRoot.absolutePath,
                buildSystem?.name ?: "null",
                projectType.name,
                existingCompileDir?.absolutePath ?: "null",
                compileCommandsDir.absolutePath,
                shouldGenerate,
                isCxx,
                desiredCppStandard.flag,
                toolchainId ?: "active"
            )
            if (hasUsableCompileCommands) {
                CompileCommandsDebugLogger.logCompileCommandsSelectionSummary(
                    TAG,
                    "prepare-existing",
                    sourceCompileCommandsFile
                )
            }
        }

        return Prepared(
            file = file,
            workspaceRoot = workspaceRoot,
            projectType = projectType,
            compileCommandsDir = compileCommandsDir,
            sourceCompileCommandsDir = compileCommandsDir,
            shouldGenerate = shouldGenerate,
            scanRoot = scanRoot,
            isCxx = isCxx,
            desiredCppStandard = desiredCppStandard,
            packageFingerprint = packageFingerprint,
            toolchainId = toolchainId,
        )
    }

    private fun ensure(prepared: Prepared): File? {
        val compileCommandsFile = File(prepared.compileCommandsDir, "compile_commands.json")
        val effectiveRunMode = LinuxRunModePolicy.resolve(
            configuredMode = Prefs.clangdRunMode,
            linuxEnvironmentAvailable = linuxEnvironmentProvider.get().isAvailable()
        )
        if (!prepared.shouldGenerate) {
            val sourceCompileCommandsFile = File(
                prepared.sourceCompileCommandsDir,
                "compile_commands.json"
            )
            if (CompileCommandsDebugLogger.isCompileCommandsSelectionEnabled()) {
                Timber.tag(TAG).i(
                    "ensure: reusing compile_commands source=%s target=%s for workspace=%s",
                    sourceCompileCommandsFile.absolutePath,
                    compileCommandsFile.absolutePath,
                    prepared.workspaceRoot.absolutePath
                )
                CompileCommandsDebugLogger.logCompileCommandsSelectionSummary(
                    TAG,
                    "ensure-reuse-source",
                    sourceCompileCommandsFile
                )
            }
            val materialized = materializeCompileCommandsForLsp(
                effectiveRunMode = effectiveRunMode,
                sourceFile = sourceCompileCommandsFile,
                targetFile = compileCommandsFile,
                toolchainId = prepared.toolchainId,
            )
            if (materialized) {
                writeCompileCommandsMetadata(
                    compileCommandsDir = prepared.compileCommandsDir,
                    cppStandard = prepared.desiredCppStandard,
                    packageFingerprint = prepared.packageFingerprint,
                    toolchainId = prepared.toolchainId,
                )
            }
            if (materialized && CompileCommandsDebugLogger.isCompileCommandsSelectionEnabled()) {
                CompileCommandsDebugLogger.logCompileCommandsSelectionSummary(
                    TAG,
                    "ensure-reuse-target",
                    compileCommandsFile
                )
            }
            return prepared.compileCommandsDir.takeIf { materialized }
        }

        return runCatching {
            val isNativeMode = effectiveRunMode == LinuxRunModePolicy.RunMode.NATIVE

            val sysrootDir: File
            val clangLibDir: File
            val clangResourceDir: File?
            val clangPathOverride: String?
            val clangppPathOverride: String?

            if (isNativeMode) {
                // Native 模式：sysroot 来自 AndroidSysrootManager，clang resource 来自 native toolchain
                val sysrootManager = AndroidSysrootManager(appContext)
                sysrootDir = sysrootManager.getSysrootDir()
                Timber.tag(TAG).i("Android sysroot path: ${sysrootDir.absolutePath}, exists: ${sysrootDir.isDirectory}")
                if (!sysrootDir.isDirectory) {
                    Timber.tag(TAG).w("Android sysroot not found: ${sysrootDir.absolutePath}")
                    Timber.tag(TAG).e("LSP cannot start: Android sysroot not installed or path invalid")
                    Timber.tag(TAG).e("Please install the native sysroot/toolchain first, then reopen the file")
                    return@runCatching null
                }

                val toolchainManager = AndroidNativeToolchainManager(appContext)
                val toolchainDir = toolchainManager.getInstallDir(prepared.toolchainId)
                val toolchainBinDir = toolchainManager.getBinDir(prepared.toolchainId)
                clangLibDir = File(toolchainDir, "lib/clang")
                clangResourceDir = findClangResourceDir(toolchainDir)
                clangPathOverride = File(toolchainBinDir, "clang")
                    .takeIf { it.isFile }
                    ?.absolutePath
                clangppPathOverride = File(toolchainBinDir, "clang++")
                    .takeIf { it.isFile }
                    ?.absolutePath
            } else {
                // PRoot 模式：sysroot/headers/clang resource 都来自 rootfs
                sysrootDir = File(PRootBootstrap.getActiveRootfsPath(appContext))
                Timber.tag(TAG).i("Rootfs path: ${sysrootDir.absolutePath}, exists: ${sysrootDir.isDirectory}")

                if (!sysrootDir.isDirectory) {
                    Timber.tag(TAG).w("Rootfs not found: ${sysrootDir.absolutePath}")
                    Timber.tag(TAG).e("LSP cannot start: rootfs not installed or path invalid")
                    Timber.tag(TAG).e("Please finish Linux environment setup first")
                    return@runCatching null
                }

                clangLibDir = File(sysrootDir, "lib/clang")
                clangResourceDir = findClangResourceDir(sysrootDir)
                clangPathOverride = null
                clangppPathOverride = null
            }

            // 验证关键组件是否存在
            val iostream = File(sysrootDir, "usr/include/c++/v1/iostream")

            Timber.tag(TAG).i("Verifying critical components:")
            Timber.tag(TAG).i("  C++ stdlib (iostream): ${iostream.exists()} at ${iostream.absolutePath}")
            Timber.tag(TAG).i("  clang lib dir: ${clangLibDir.exists()} at ${clangLibDir.absolutePath}")
            Timber.tag(TAG).i("  clang resource dir: ${clangResourceDir?.exists() ?: false} at ${clangResourceDir?.absolutePath ?: "not found"}")

            // 检查 clang resource directory（关键！内置头文件 stdarg.h、stddef.h 等）
            if (clangResourceDir == null) {
                Timber.tag(TAG).e("========================================")
                Timber.tag(TAG).e("Error: missing clang resource directory")
                Timber.tag(TAG).e("")
                Timber.tag(TAG).e("This will cause clangd to miss builtin headers (stdarg.h, stddef.h, etc.)")
                Timber.tag(TAG).e("LSP may not work properly and can report many header errors")
                Timber.tag(TAG).e("")
                Timber.tag(TAG).e("Fix steps:")
                if (isNativeMode) {
                    Timber.tag(TAG).e("1. Reinstall native toolchain (Settings -> Toolchain)")
                    Timber.tag(TAG).e("2. Reopen the file")
                } else {
                    val checkedPaths = ClangResourceDirLocator.DEFAULT_LLVM_VERSIONS.joinToString(", ") { "llvm$it" }
                    Timber.tag(TAG).e("Checked versions: $checkedPaths")
                    Timber.tag(TAG).e("1. Open Terminal -> select PRoot environment")
                    Timber.tag(TAG).e("2. Run: /sbin/apk update && /sbin/apk add --no-cache clang llvm")
                    Timber.tag(TAG).e("3. Reopen the file")
                }
                Timber.tag(TAG).e("========================================")
                return@runCatching null
            }

            // 检查 C++ 标准库
            if (!iostream.exists()) {
                Timber.tag(TAG).e("========================================")
                Timber.tag(TAG).e("Error: missing C++ standard library headers")
                Timber.tag(TAG).e("Path: ${iostream.absolutePath}")
                Timber.tag(TAG).e("")
                Timber.tag(TAG).e("Fix steps:")
                if (isNativeMode) {
                    Timber.tag(TAG).e("1. Install native sysroot (Settings -> Toolchain)")
                    Timber.tag(TAG).e("2. Reopen the file")
                } else {
                    Timber.tag(TAG).e("1. Open Terminal -> select PRoot environment")
                    Timber.tag(TAG).e("2. Run: /sbin/apk update && /sbin/apk add --no-cache libc++-dev libc++abi-dev")
                    Timber.tag(TAG).e("3. Reopen the file")
                }
                Timber.tag(TAG).e("========================================")
                return@runCatching null
            }

            val scanMode = when (prepared.projectType) {
                ProjectType.CMAKE_PROJECT -> CppProjectScanner.ScanMode.FULL
                ProjectType.SINGLE_FILE_PROJECT,
                ProjectType.STANDALONE_FILE -> CppProjectScanner.ScanMode.LSP_SHALLOW
            }
            val scanResult = CppProjectScanner.scanProject(
                prepared.scanRoot.absolutePath,
                mode = scanMode,
                primaryFile = prepared.file,
            )
            val sourceFiles = scanResult.sourceFiles.ifEmpty { listOf(prepared.file.absolutePath) }
            // 已安装包的 include 路径也加入，让 clangd 能补全第三方库头文件
            val packagePaths = InstalledPackagePathResolver.resolve(appContext, prepared.workspaceRoot)
            val includeDirs = (scanResult.includeDirs.ifEmpty { listOfNotNull(prepared.file.parentFile?.absolutePath) }) +
                packagePaths.includeDirs.map { it.absolutePath }
            val metadata = ProjectMetadataStore.read(prepared.workspaceRoot)
            val extraCFlags = NativeBuildFlagTokenizer.tokenize(
                metadata?.normalizedNativeCFlags().orEmpty()
            )
            val extraCppFlags = NativeBuildFlagTokenizer.tokenize(
                metadata?.normalizedNativeCppFlags().orEmpty()
            )

            // 项目路径就是 workspaceRoot
            val projectPath = prepared.workspaceRoot.absolutePath

            Timber.tag(TAG).i("Generating compile_commands.json...")
            Timber.tag(TAG).i("  Project path: $projectPath")
            Timber.tag(TAG).i("  Sysroot: ${sysrootDir.absolutePath}")
            Timber.tag(TAG).i("  Source files: ${sourceFiles.size}")
            Timber.tag(TAG).i("  Include dirs: ${includeDirs.size}")
            Timber.tag(TAG).i("  Is C++: ${prepared.isCxx}")

            if (prepared.isCxx) {
                Timber.tag(TAG).i(
                    "  C++ standard: %s (%s)",
                    prepared.desiredCppStandard.flag,
                    prepared.desiredCppStandard.name
                )
            }

            val generatedSourceFile = CompileCommandsGenerator.generate(
                projectPath = projectPath,
                sysrootDir = sysrootDir,
                sourceFiles = sourceFiles,
                includeDirs = includeDirs,
                isCxx = prepared.isCxx,
                cppStandard = prepared.desiredCppStandard,
                extraCFlags = extraCFlags,
                extraCppFlags = extraCppFlags,
                clangPathOverride = clangPathOverride,
                clangppPathOverride = clangppPathOverride,
                resourceDirOverride = clangResourceDir,
                outputFileOverride = File(prepared.sourceCompileCommandsDir, "compile_commands.json"),
            )

            val generatedSource = generatedSourceFile.isFile && generatedSourceFile.length() > 0
            Timber.tag(TAG).i(
                "compile_commands.json generated: %s at %s",
                generatedSource,
                generatedSourceFile.absolutePath
            )
            if (generatedSource) {
                writeCompileCommandsMetadata(
                    compileCommandsDir = prepared.sourceCompileCommandsDir,
                    cppStandard = prepared.desiredCppStandard,
                    packageFingerprint = prepared.packageFingerprint,
                    toolchainId = prepared.toolchainId,
                )
            }

            val generated = if (generatedSource) {
                materializeCompileCommandsForLsp(
                    effectiveRunMode = effectiveRunMode,
                    sourceFile = generatedSourceFile,
                    targetFile = compileCommandsFile,
                    toolchainId = prepared.toolchainId,
                )
            } else {
                false
            }
            Timber.tag(TAG).i("compile_commands.json normalized for clangd: %s at %s", generated, compileCommandsFile.absolutePath)

            if (generated && CompileCommandsDebugLogger.isCompileCommandsSelectionEnabled()) {
                CompileCommandsDebugLogger.logCompileCommandsSelectionSummary(TAG, "ensure-generated", compileCommandsFile)
                val content = compileCommandsFile.readText()
                val preview = if (content.length > 500) content.substring(0, 500) + "..." else content
                Timber.tag(TAG).d("Generated content preview:\n$preview")
            }
            if (generated) {
                writeCompileCommandsMetadata(
                    compileCommandsDir = prepared.compileCommandsDir,
                    cppStandard = prepared.desiredCppStandard,
                    packageFingerprint = prepared.packageFingerprint,
                    toolchainId = prepared.toolchainId,
                )
            }

            prepared.compileCommandsDir.takeIf { generated }
        }.onFailure { t ->
            Timber.tag(TAG).w(t, "Failed to generate compile_commands.json")
        }.getOrNull()
    }

    fun ensureWithResult(prepared: Prepared): EnsureResult? {
        val ensuredDir = ensure(prepared) ?: return null
        return EnsureResult(ensuredDir, regenerated = prepared.shouldGenerate)
    }

    fun prepareProvidedCompileCommandsForLsp(
        sourceCompileCommandsFile: File,
        projectRootPath: String?,
        toolchainId: String? = null,
    ): File? {
        if (!sourceCompileCommandsFile.isFile || sourceCompileCommandsFile.length() <= 0L) return null

        val workspaceRoot = resolveWorkspaceRoot(sourceCompileCommandsFile, projectRootPath) ?: return null
        val compileCommandsDir = sourceCompileCommandsFile.parentFile ?: workspaceRoot
        val effectiveRunMode = LinuxRunModePolicy.resolve(
            configuredMode = Prefs.clangdRunMode,
            linuxEnvironmentAvailable = linuxEnvironmentProvider.get().isAvailable()
        )
        val desiredCppStandard = resolveCppStandard(workspaceRoot)
        val packageFingerprint = resolvePackageFingerprint(workspaceRoot)

        val materialized = materializeCompileCommandsForLsp(
            effectiveRunMode = effectiveRunMode,
            sourceFile = sourceCompileCommandsFile,
            targetFile = sourceCompileCommandsFile,
            toolchainId = toolchainId,
        )
        if (!materialized) return null

        writeCompileCommandsMetadata(
            compileCommandsDir = compileCommandsDir,
            cppStandard = desiredCppStandard,
            packageFingerprint = packageFingerprint,
            toolchainId = toolchainId,
        )
        return compileCommandsDir
    }

    private fun compileCommandsUpToDate(
        compileCommandsFile: File,
        isCxx: Boolean,
        compileCommandsDir: File,
        desiredCppStandard: CppStandard,
        packageFingerprint: String,
        toolchainId: String?
    ): Boolean {
        val standardMatches = !isCxx || compileCommandsMatchesCppStandard(compileCommandsFile, desiredCppStandard)
        if (!standardMatches) return false

        if (!compileCommandsMatchesPackageFingerprint(compileCommandsDir, packageFingerprint)) return false

        return compileCommandsMatchesToolchainId(compileCommandsDir, toolchainId)
    }

    private fun compileCommandsMatchesPackageFingerprint(
        compileCommandsDir: File,
        packageFingerprint: String
    ): Boolean {
        val metadata = readCompileCommandsMetadata(compileCommandsDir) ?: return false
        val storedFingerprint = metadata.getProperty(META_KEY_PACKAGE_FINGERPRINT)?.trim().orEmpty()
        return storedFingerprint.isNotEmpty() && storedFingerprint == packageFingerprint
    }

    private fun compileCommandsMatchesToolchainId(
        compileCommandsDir: File,
        toolchainId: String?
    ): Boolean {
        if (toolchainId == null) return true
        val metadata = readCompileCommandsMetadata(compileCommandsDir) ?: return false
        val storedToolchainId = metadata.getProperty(META_KEY_TOOLCHAIN_ID)?.trim().orEmpty()
        return storedToolchainId == toolchainId
    }

    private fun readCompileCommandsMetadata(compileCommandsDir: File): Properties? {
        val metaFile = File(compileCommandsDir, COMPILE_COMMANDS_META_FILE_NAME)
        if (!metaFile.isFile) return null

        return runCatching {
            Properties().apply {
                FileInputStream(metaFile).use { load(it) }
            }
        }.onFailure { t ->
            Timber.tag(TAG).w(t, "Failed to read compile_commands metadata: %s", metaFile.absolutePath)
        }.getOrNull()
    }

    private fun writeCompileCommandsMetadata(
        compileCommandsDir: File,
        cppStandard: CppStandard,
        packageFingerprint: String,
        toolchainId: String?
    ) {
        val metaFile = File(compileCommandsDir, COMPILE_COMMANDS_META_FILE_NAME)
        runCatching {
            if (!compileCommandsDir.isDirectory) {
                compileCommandsDir.mkdirs()
            }
            val props = Properties().apply {
                setProperty(META_KEY_CPP_STANDARD, cppStandard.flag)
                setProperty(META_KEY_PACKAGE_FINGERPRINT, packageFingerprint)
                toolchainId?.let { setProperty(META_KEY_TOOLCHAIN_ID, it) }
            }
            FileOutputStream(metaFile).use { out ->
                props.store(out, "TinaIDE compile_commands metadata")
            }
        }.onFailure { t ->
            Timber.tag(TAG).w(t, "Failed to write compile_commands metadata: %s", metaFile.absolutePath)
        }
    }

    private fun resolvePackageFingerprint(projectRoot: File?): String {
        val packagePaths = InstalledPackagePathResolver.resolve(appContext, projectRoot)
        val installedPackages = LocalInstallStateStore(appContext).getAllInstalledPackages()

        val tokens = buildList {
            addAll(packagePaths.includeDirs.map { "I:${canonicalPathOrAbs(it)}" })
            addAll(packagePaths.libDirs.map { "L:${canonicalPathOrAbs(it)}" })
            addAll(packagePaths.prefixDirs.map { "P:${canonicalPathOrAbs(it)}" })
            addAll(
                installedPackages.map {
                    "PKG:${it.packageId}|${it.platform.name}|${it.version}|${it.installType.name}"
                }
            )
        }.distinct().sorted()

        val input = if (tokens.isEmpty()) {
            "<empty>"
        } else {
            tokens.joinToString(separator = "\n")
        }

        return sha256Hex(input.toByteArray())
    }

    private fun canonicalPathOrAbs(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { b ->
                append(((b.toInt() ushr 4) and 0xF).toString(16))
                append((b.toInt() and 0xF).toString(16))
            }
        }
    }

    private fun resolveWorkspaceRoot(file: File, projectRootPath: String?): File? {
        val candidate = projectRootPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }

        if (candidate != null) {
            val candidatePath = runCatching { candidate.canonicalPath }.getOrNull()
            val filePath = runCatching { file.canonicalPath }.getOrNull()
            if (candidatePath != null && filePath != null) {
                val inProject = filePath == candidatePath || filePath.startsWith(candidatePath + File.separator)
                if (inProject) return candidate
            }
        }

        return file.parentFile?.takeIf { it.isDirectory }
    }

    private fun findExistingCompileCommandsDir(workspaceRoot: File): File? {
        for (relative in COMPILE_COMMANDS_SEARCH_PATHS) {
            val file = File(workspaceRoot, relative)
            if (file.isFile && file.length() > 0) {
                return file.parentFile
            }
        }
        return null
    }

    private fun resolveCppStandard(workspaceRoot: File): CppStandard = runCatching {
        ProjectMetadataStore.read(workspaceRoot)?.getCppStandard()
    }.getOrNull() ?: CppStandard.DEFAULT

    private fun materializeCompileCommandsForLsp(
        effectiveRunMode: LinuxRunModePolicy.RunMode,
        sourceFile: File,
        targetFile: File,
        toolchainId: String?,
    ): Boolean {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) return false

        val toolchainPaths = resolveNormalizationToolchainPaths(
            effectiveRunMode = effectiveRunMode,
            toolchainId = toolchainId,
        )
        return runCatching {
            CompileCommandsNormalizer.normalizeForClangd(
                sourceFile = sourceFile,
                targetFile = targetFile,
                toolchainPaths = toolchainPaths
            )
        }.onFailure { t ->
            Timber.tag(TAG).w(t, "Failed to normalize compile_commands for clangd: %s", sourceFile.absolutePath)
        }.getOrElse {
            runCatching {
                targetFile.parentFile?.mkdirs()
                if (!sameFile(sourceFile, targetFile)) {
                    sourceFile.copyTo(targetFile, overwrite = true)
                }
                targetFile.isFile && targetFile.length() > 0L
            }.onFailure { copyError ->
                Timber.tag(TAG).w(copyError, "Failed to copy compile_commands for clangd: %s", sourceFile.absolutePath)
            }.getOrDefault(false)
        }
    }

    private fun resolveNormalizationToolchainPaths(
        effectiveRunMode: LinuxRunModePolicy.RunMode,
        toolchainId: String?,
    ): CompileCommandsNormalizer.ToolchainPaths {
        if (effectiveRunMode != LinuxRunModePolicy.RunMode.NATIVE) {
            return CompileCommandsNormalizer.ToolchainPaths()
        }

        val toolchainManager = AndroidNativeToolchainManager(appContext)
        val toolchainDir = toolchainManager.getInstallDir(toolchainId)
        val clangResourceDir = findClangResourceDir(toolchainDir)
        val binDir = toolchainManager.getBinDir(toolchainId)

        return CompileCommandsNormalizer.ToolchainPaths(
            clangPath = File(binDir, "clang").takeIf { it.isFile }?.absolutePath,
            clangppPath = File(binDir, "clang++").takeIf { it.isFile }?.absolutePath,
            resourceDir = clangResourceDir
        )
    }

    private fun sameFile(left: File, right: File): Boolean {
        val leftPath = runCatching { left.canonicalPath }.getOrDefault(left.absolutePath)
        val rightPath = runCatching { right.canonicalPath }.getOrDefault(right.absolutePath)
        return leftPath == rightPath
    }

    private fun compileCommandsMatchesCppStandard(file: File, desired: CppStandard): Boolean {
        val want = "\"-std=${desired.flag}\""
        return runCatching { file.readText().contains(want) }.getOrDefault(false)
    }
}
