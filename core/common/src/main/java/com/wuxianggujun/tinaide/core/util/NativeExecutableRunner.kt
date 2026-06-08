package com.wuxianggujun.tinaide.core.util

import android.content.Context
import android.os.Build
import com.wuxianggujun.tinaide.exec.TinaExecPreloadMode
import com.wuxianggujun.tinaide.exec.TinaExecRuntime
import com.wuxianggujun.tinaide.exec.TinaExecSystemLinkerMode
import java.io.File
import timber.log.Timber

/**
 * Native 可执行文件启动器
 *
 * 统一处理 Android 上启动 native ELF 可执行文件的逻辑，自动选择最佳启动方式：
 * - Android 10+ (API 29+)：优先使用 linker64 启动，规避 app 私有目录
 *   direct exec 的 W^X/权限限制
 * - Android 9 及更低版本：保持直接执行，避免部分 OEM ROM 的 linker64 helper
 *   无法按 `linker64 <elf>` 形式启动工具链
 *
 * 使用场景：
 * - 编译器（clang/clang++/gcc）
 * - 代码格式化工具（clang-format）
 * - LSP 服务器（clangd）
 * - 其他 native 工具链二进制
 */
object NativeExecutableRunner {

    private const val TAG = "NativeExecutableRunner"
    private const val LINKER64_PREFERRED_API_LEVEL = 29
    internal const val ENV_LLVM_WRAP_EXEC_LINKER64 = "TINAIDE_LLVM_WRAP_EXEC_LINKER64"
    internal const val ENV_LLVM_EXEC_TRACE = "TINAIDE_LLVM_EXEC_TRACE"
    private val VERSIONED_CLANG_REGEX = Regex("""^clang-\d+$""")
    private val PROBE_TOOL_NAMES = listOf(
        "clang",
        "clang++",
        "ld.lld",
        "lld",
        "cmake",
        "make",
        "ninja"
    )

    /**
     * 可执行文件分类：决定启动外壳。
     *
     * - [ELF]：普通 PIE ELF。Android 10+ 通过 linker64 启动，规避 app 私有目录
     *   direct exec 的 EACCES；低版本或非绝对路径则保持原样。
     * - [SHEBANG_SCRIPT]：`#!` 起头的脚本。必须通过 `/system/bin/sh` 启动，
     *   不能交给 linker64（后者只认 ET_DYN ELF，对脚本会 crash）。
     *
     * 用 [probe] 根据真实文件内容判定；调用方在"源文件 ≠ 启动目标"（如先
     * 拷贝到 run-bin 再启动）时必须对**源文件**探测，然后把结果传给
     * 带 `kind` 参数的 [buildCommand] / [buildShellSnippet] 重载。
     */
    enum class ExecutableKind {
        ELF,
        SHEBANG_SCRIPT;

        companion object {
            /**
             * 读取文件前两个字节判断是否为 `#!` 脚本。
             * 文件不存在、不可读或读取出错时一律返回 [ELF]（保守默认）。
             */
            fun probe(file: File): ExecutableKind {
                if (!file.isFile) return ELF
                val isShebang = runCatching {
                    file.inputStream().buffered().use { input ->
                        input.read() == '#'.code && input.read() == '!'.code
                    }
                }.getOrDefault(false)
                return if (isShebang) SHEBANG_SCRIPT else ELF
            }
        }
    }

    /**
     * 构建用于 ProcessBuilder 的完整命令。
     *
     * 自动根据 [executable] 路径的文件内容探测 [ExecutableKind]；若调用方在
     * "源文件 ≠ 启动目标"场景下（如 stage-copy），请用接收显式 `kind` 的重载。
     */
    fun buildCommand(
        executable: String,
        args: List<String> = emptyList(),
        preferLinker64: Boolean = shouldPreferLinker64()
    ): List<String> = buildCommand(
        executable = executable,
        args = args,
        kind = ExecutableKind.probe(File(executable)),
        preferLinker64 = preferLinker64
    )

    /**
     * 构建用于 ProcessBuilder 的完整命令（显式 kind 版本）。
     *
     * 当启动目标路径的文件尚未创建（如 run-bin 复制前）时，调用方需对真实存在
     * 的源文件调用 [ExecutableKind.probe] 得到 `kind`，再用本重载生成以目标
     * 路径为参数的命令。
     */
    fun buildCommand(
        executable: String,
        args: List<String> = emptyList(),
        kind: ExecutableKind,
        preferLinker64: Boolean = shouldPreferLinker64()
    ): List<String> {
        if (!preferLinker64 || !executable.startsWith("/")) {
            // 不需要 linker64 或非绝对路径：保持原行为
            return listOf(executable) + args
        }
        if (isAlreadyLinkerWrapped(executable, args)) {
            return listOf(executable) + args
        }
        return when (kind) {
            ExecutableKind.SHEBANG_SCRIPT ->
                listOf("/system/bin/sh", executable) + args

            ExecutableKind.ELF ->
                listOf(AndroidSystemLinker.resolve64BitPreferred(), executable) + args
        }
    }

    /**
     * 构建用于 ProcessBuilder 的完整命令（File 重载，自动探测 kind）。
     */
    fun buildCommand(
        executable: File,
        args: List<String> = emptyList(),
        preferLinker64: Boolean = shouldPreferLinker64()
    ): List<String> = buildCommand(
        executable = executable.absolutePath,
        args = args,
        kind = ExecutableKind.probe(executable),
        preferLinker64 = preferLinker64
    )

    /**
     * 判断是否应该优先使用 linker64。
     *
     * 统一策略（整个 app 只认这一条）：Android 10+ (API 29+) 一律通过 linker64
     * 启动 app 私有目录下的 ELF，规避 W^X、OEM/SELinux 策略差异
     * （vivo/OPPO 等 ROM 在 Android 14 已出现 direct exec EACCES）。Android 9
     * 仍保持 direct，避免 OPPO PBBT00 等 ROM 的 `/system/bin/linker64 <elf>`
     * 只打印 helper 文本但不执行目标 ELF。
     *
     * 任何调用方都不应再自行根据 SDK 判定；要启动 ELF 请走 [buildCommand] /
     * [buildShellSnippet]，要判断是否需要设置 linker64 相关环境请查询本函数。
     */
    fun shouldPreferLinker64(): Boolean = shouldPreferLinker64(Build.VERSION.SDK_INT)

    internal fun shouldPreferLinker64(sdkInt: Int): Boolean = sdkInt >= LINKER64_PREFERRED_API_LEVEL

    internal fun describeLinker64PreferenceDecision(sdkInt: Int): String = if (shouldPreferLinker64(sdkInt)) {
        "sdk>=$LINKER64_PREFERRED_API_LEVEL prefers linker64 for app-private ELF toolchain processes"
    } else {
        "sdk<$LINKER64_PREFERRED_API_LEVEL keeps direct exec unless caller overrides"
    }

    fun describeLaunchMode(executable: String, fullCommand: List<String>): String {
        val launcher = fullCommand.firstOrNull().orEmpty()
        if (launcher.isBlank()) return "unknown"
        return when {
            launcher == executable -> "direct"
            launcher == "/system/bin/sh" -> "shell"
            isSystemLinkerLauncher(launcher) -> "linker64"
            else -> "custom-wrapper"
        }
    }

    /**
     * 构建可拼入 shell 命令字符串的启动片段。
     *
     * 与 [buildCommand] 共用同一份决策（shebang 脚本走 /system/bin/sh；普通 ELF
     * 在 Android 10+ 走 linker64）。Run/Terminal 等通过 shell 启动 native ELF 的
     * 链路必须走这里，避免再把 SDK 门槛散落到业务代码。
     *
     * @param executable ELF 或脚本的绝对路径
     * @param args 命令行参数（按 POSIX 规则进行 shell 转义）
     * @param preferLinker64 是否优先 linker64（默认跟随 [shouldPreferLinker64]）
     * @return 已 shell-quote 的命令字符串
     */
    fun buildShellSnippet(
        executable: String,
        args: List<String> = emptyList(),
        preferLinker64: Boolean = shouldPreferLinker64()
    ): String {
        val command = buildCommand(executable, args, preferLinker64)
        return command.joinToString(" ") { shellQuotePosix(it) }
    }

    /**
     * [buildShellSnippet] 的 File 重载（自动探测 kind）。
     */
    fun buildShellSnippet(
        executable: File,
        args: List<String> = emptyList(),
        preferLinker64: Boolean = shouldPreferLinker64()
    ): String = buildShellSnippet(executable.absolutePath, args, preferLinker64)

    /**
     * [buildShellSnippet] 的显式 kind 重载。
     *
     * 用于 stage-copy 场景：先对源文件 [ExecutableKind.probe] 得到类型，再对
     * 尚未创建的目标路径生成启动片段。自动探测版本会在目标文件不存在时退化为 ELF
     * 判定，导致脚本产物被误套 linker64。
     */
    fun buildShellSnippet(
        executable: String,
        args: List<String> = emptyList(),
        kind: ExecutableKind,
        preferLinker64: Boolean = shouldPreferLinker64()
    ): String {
        val command = buildCommand(executable, args, kind, preferLinker64)
        return command.joinToString(" ") { shellQuotePosix(it) }
    }

    /**
     * POSIX 单引号 shell 转义：用单引号包裹，内部单引号按 `'"'"'` 方式转义。
     *
     * 历史上多个模块各自实现过一份，统一到这里避免继续发散。
     */
    fun shellQuotePosix(value: String): String {
        if (value.isEmpty()) return "''"
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    fun isCompilerLauncher(path: String): Boolean = isSystemLinkerLauncher(path)

    /**
     * 为 ProcessBuilder 配置环境变量
     *
     * @param builder ProcessBuilder 实例
     * @param nativeLibDir native 库目录路径（通常是 applicationInfo.nativeLibraryDir）
     * @param toolchainBinDir 工具链 bin 目录路径（可选）
     * @param tmpDir 临时目录路径（可选，默认使用系统临时目录）
     * @param homeDir HOME 目录路径（可选）
     * @param llvmWrapExecLinker64 是否允许 LLVM 内部子进程通过 linker64 包装执行
     */
    fun configureEnvironment(
        builder: ProcessBuilder,
        nativeLibDir: String,
        toolchainBinDir: String? = null,
        tmpDir: String? = null,
        homeDir: String? = null,
        tinaExecContext: Context? = null,
        tinaExecMode: TinaExecPreloadMode? = null,
        tinaExecSystemLinkerMode: TinaExecSystemLinkerMode? = null,
        llvmWrapExecLinker64: Boolean = shouldPreferLinker64()
    ) {
        val env = builder.environment()

        // 设置 LD_LIBRARY_PATH，让 native 可执行文件能找到依赖的共享库
        // 添加系统库路径，确保 lld 等工具能找到 libc.so, libm.so 等
        env["LD_LIBRARY_PATH"] = "$nativeLibDir:/system/lib64:/system/lib"

        // 如果提供了工具链 bin 目录，添加到 PATH
        if (toolchainBinDir != null) {
            val currentPath = env["PATH"] ?: ""
            env["PATH"] = "$toolchainBinDir:$currentPath"

            // clang 驱动在解析 ld.lld/lld 时会参考 COMPILER_PATH。
            // 这里显式注入工具链 bin，避免仅依赖 argv[0] 推导出的 InstalledDir。
            val currentCompilerPath = env["COMPILER_PATH"] ?: ""
            env["COMPILER_PATH"] = if (currentCompilerPath.isBlank()) {
                toolchainBinDir
            } else {
                "$toolchainBinDir:$currentCompilerPath"
            }
        }

        // 设置 TMPDIR，clang 等工具在编译时需要创建临时文件
        if (tmpDir != null) {
            env["TMPDIR"] = tmpDir
        }

        // 设置 HOME 目录（某些工具可能需要）
        if (homeDir != null) {
            env["HOME"] = homeDir
        }

        // LLVM 工具链补丁会读取该变量决定内部 Execute/ExecuteAndWait 是否包 linker64。
        // 这里强制覆盖继承环境，保证 clang -> cc1/lld 与 App 顶层启动策略一致。
        env[ENV_LLVM_WRAP_EXEC_LINKER64] = if (llvmWrapExecLinker64) "1" else "0"
        env.putIfAbsent(ENV_LLVM_EXEC_TRACE, "0")

        if (tinaExecContext != null && tinaExecMode != null) {
            TinaExecRuntime.applyLdPreload(
                environment = env,
                context = tinaExecContext,
                mode = tinaExecMode,
                systemLinkerMode = tinaExecSystemLinkerMode
            )
        }
    }

    /**
     * 根据实际启动命令推断应该使用的 preload 变体。
     *
     * 说明：
     * - 如果初始启动就是通过 system linker 执行，则使用 LINKER 变体
     * - 其他情况使用 DIRECT 变体
     */
    fun resolveTinaExecPreloadMode(fullCommand: List<String>): TinaExecPreloadMode {
        val launcher = fullCommand.firstOrNull().orEmpty()
        return if (isSystemLinkerLauncher(launcher)) {
            TinaExecPreloadMode.LINKER
        } else {
            TinaExecPreloadMode.DIRECT
        }
    }

    /**
     * 为当前即将启动的 native 进程注入推荐的 tina-exec 环境。
     */
    fun applyRecommendedTinaExec(
        environment: MutableMap<String, String>,
        context: Context,
        fullCommand: List<String>,
        systemLinkerMode: TinaExecSystemLinkerMode = TinaExecSystemLinkerMode.ENABLE
    ): Boolean = TinaExecRuntime.applyLdPreload(
        environment = environment,
        context = context,
        mode = resolveTinaExecPreloadMode(fullCommand),
        systemLinkerMode = systemLinkerMode
    )

    /**
     * 创建并配置 ProcessBuilder
     *
     * @param executable 可执行文件路径
     * @param args 命令行参数
     * @param workingDir 工作目录
     * @param nativeLibDir native 库目录路径
     * @param toolchainBinDir 工具链 bin 目录路径（可选）
     * @param tmpDir 临时目录路径（可选）
     * @param homeDir HOME 目录路径（可选）
     * @param preferLinker64 是否优先使用 linker64
     * @return 配置好的 ProcessBuilder
     */
    fun createProcessBuilder(
        executable: String,
        args: List<String> = emptyList(),
        workingDir: File,
        nativeLibDir: String,
        toolchainBinDir: String? = null,
        tmpDir: String? = null,
        homeDir: String? = null,
        preferLinker64: Boolean = shouldPreferLinker64(),
        tinaExecContext: Context? = null,
        tinaExecMode: TinaExecPreloadMode? = null,
        tinaExecSystemLinkerMode: TinaExecSystemLinkerMode? = null
    ): ProcessBuilder {
        val command = buildCommand(executable, args, preferLinker64)
        val resolvedTinaExecMode = when {
            tinaExecContext == null -> null
            tinaExecMode != null -> tinaExecMode
            else -> resolveTinaExecPreloadMode(command)
        }

        Timber.tag(TAG).d(
            "Creating process: executable=%s, preferLinker64=%s, sdk=%d",
            executable,
            preferLinker64,
            Build.VERSION.SDK_INT
        )
        Timber.tag(TAG).v("Full command: %s", command.joinToString(" "))

        return ProcessBuilder(command).apply {
            directory(workingDir)
            configureEnvironment(
                builder = this,
                nativeLibDir = nativeLibDir,
                toolchainBinDir = toolchainBinDir,
                tmpDir = tmpDir,
                homeDir = homeDir,
                tinaExecContext = tinaExecContext,
                tinaExecMode = resolvedTinaExecMode,
                tinaExecSystemLinkerMode = tinaExecSystemLinkerMode
            )
        }
    }

    /**
     * 创建并配置 ProcessBuilder（重载版本，接受 File 参数）
     */
    fun createProcessBuilder(
        executable: File,
        args: List<String> = emptyList(),
        workingDir: File,
        nativeLibDir: String,
        toolchainBinDir: String? = null,
        tmpDir: String? = null,
        homeDir: String? = null,
        preferLinker64: Boolean = shouldPreferLinker64(),
        tinaExecContext: Context? = null,
        tinaExecMode: TinaExecPreloadMode? = null,
        tinaExecSystemLinkerMode: TinaExecSystemLinkerMode? = null
    ): ProcessBuilder = createProcessBuilder(
        executable.absolutePath,
        args,
        workingDir,
        nativeLibDir,
        toolchainBinDir,
        tmpDir,
        homeDir,
        preferLinker64,
        tinaExecContext,
        tinaExecMode,
        tinaExecSystemLinkerMode
    )

    /**
     * 打印 native 命令执行诊断信息（用于定位 ENOENT / 路径 / 环境变量问题）。
     */
    fun logExecutionDiagnostics(
        tag: String,
        executable: String,
        args: List<String>,
        fullCommand: List<String>,
        workingDir: File,
        environment: Map<String, String>,
        toolchainBinDir: String? = null
    ) {
        val sdkInt = Build.VERSION.SDK_INT
        Timber.tag(tag).d(
            buildExecutionDiagnosticSummary(
                sdkInt = sdkInt,
                executable = executable,
                args = args,
                fullCommand = fullCommand,
                workingDir = workingDir
            )
        )
        Timber.tag(tag).d("Executable: %s", describeFileState(File(executable)))

        val launcher = fullCommand.firstOrNull()
        if (!launcher.isNullOrBlank() && launcher != executable) {
            Timber.tag(tag).d("Launcher: %s", describeFileState(File(launcher)))
        }

        Timber.tag(tag).v("Command: %s", fullCommand.joinToString(" "))
        Timber.tag(tag).d("Env PATH=%s", clippedValue(environment["PATH"]))
        Timber.tag(tag).d("Env COMPILER_PATH=%s", clippedValue(environment["COMPILER_PATH"]))
        Timber.tag(tag).d("Env LD_LIBRARY_PATH=%s", clippedValue(environment["LD_LIBRARY_PATH"]))
        Timber.tag(tag).d("Env LD_PRELOAD=%s", clippedValue(environment["LD_PRELOAD"]))
        Timber.tag(tag).d("Env TMPDIR=%s", clippedValue(environment["TMPDIR"]))
        Timber.tag(tag).d("Env HOME=%s", clippedValue(environment["HOME"]))
        Timber.tag(tag).d(
            "Env %s=%s",
            ENV_LLVM_WRAP_EXEC_LINKER64,
            clippedValue(environment[ENV_LLVM_WRAP_EXEC_LINKER64])
        )

        if (!toolchainBinDir.isNullOrBlank()) {
            logToolchainProbe(tag, File(toolchainBinDir), args)
        }
    }

    internal fun buildExecutionDiagnosticSummary(
        sdkInt: Int,
        executable: String,
        args: List<String>,
        fullCommand: List<String>,
        workingDir: File,
        maxCommandChars: Int = 240
    ): String {
        val launcher = fullCommand.firstOrNull()?.ifBlank { null } ?: "<empty>"
        val commandText = fullCommand.joinToString(" ")
            .ifBlank { "<empty>" }
            .let { clippedValue(it, maxCommandChars) }
        return DiagnosticLogFormatter.format(
            prefix = "Native exec diag",
            "sdk" to sdkInt,
            "preferLinker64" to shouldPreferLinker64(sdkInt),
            "launchMode" to describeLaunchMode(executable, fullCommand),
            "executableKind" to ExecutableKind.probe(File(executable)),
            "launcher" to launcher,
            "executable" to executable,
            "argsCount" to args.size,
            "decision" to describeLinker64PreferenceDecision(sdkInt),
            "cwd" to workingDir.absolutePath,
            "fullCommand" to commandText
        )
    }

    /**
     * 在命令执行失败后输出额外诊断信息。
     */
    fun logFailureDiagnostics(
        tag: String,
        executable: String,
        output: String,
        toolchainBinDir: String? = null
    ) {
        val looksLikeExecFailure = output.contains("unable to execute command", ignoreCase = true) ||
            output.contains("No such file or directory", ignoreCase = true) ||
            output.contains("clang frontend command failed", ignoreCase = true)
        if (!looksLikeExecFailure) return

        Timber.tag(tag).w("Detected exec-style failure; collecting extra diagnostics.")
        Timber.tag(tag).w("Executable (failure): %s", describeFileState(File(executable)))

        // 检查 ELF 依赖
        checkElfDependencies(tag, File(executable))

        Timber.tag(tag).w(
            "System linker(64 preferred): %s",
            describeFileState(File(AndroidSystemLinker.resolve64BitPreferred()))
        )
        if (!toolchainBinDir.isNullOrBlank()) {
            logToolchainProbe(tag, File(toolchainBinDir), emptyList(), warnLevel = true)

            // 检查工具链中关键二进制的依赖
            val binDir = File(toolchainBinDir)
            if (binDir.isDirectory) {
                val criticalNames = buildList {
                    add("clang")
                    addAll(listVersionedClangBinaries(binDir))
                    add("ld.lld")
                    add("lld")
                }.distinct()
                criticalNames.forEach { name ->
                    val file = File(binDir, name)
                    if (file.isFile) {
                        checkElfDependencies(tag, file)
                    }
                }
            }
        }
    }

    private fun describeFileState(file: File): String {
        val exists = file.exists()
        val isFile = file.isFile
        val canExec = file.canExecute()
        val size = runCatching { file.length() }.getOrDefault(0L)
        val resolved = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        return "path=${file.absolutePath}, resolved=$resolved, exists=$exists, isFile=$isFile, canExec=$canExec, size=$size"
    }

    private fun logToolchainProbe(
        tag: String,
        toolchainBinDir: File,
        args: List<String>,
        warnLevel: Boolean = false
    ) {
        if (!toolchainBinDir.isDirectory) {
            if (warnLevel) {
                Timber.tag(tag).w("Toolchain bin missing: %s", toolchainBinDir.absolutePath)
            } else {
                Timber.tag(tag).d("Toolchain bin missing: %s", toolchainBinDir.absolutePath)
            }
            return
        }

        val versionedClangs = listVersionedClangBinaries(toolchainBinDir)
        val names = (PROBE_TOOL_NAMES + versionedClangs + inferLikelyToolNames(args)).distinct()
        names.forEach { name ->
            val state = describeFileState(File(toolchainBinDir, name))
            if (warnLevel) {
                Timber.tag(tag).w("Tool probe[%s]: %s", name, state)
            } else {
                Timber.tag(tag).d("Tool probe[%s]: %s", name, state)
            }
        }
    }

    private fun inferLikelyToolNames(args: List<String>): List<String> {
        // clang 报错时常由 cc1/lld 触发，补充常见候选，便于快速定位缺失文件。
        val result = mutableListOf<String>()
        if (args.any { it.endsWith(".cpp") || it.endsWith(".cc") || it.endsWith(".c") }) {
            result += "clang"
            result += "ld.lld"
            result += "lld"
        }
        return result
    }

    private fun listVersionedClangBinaries(toolchainBinDir: File): List<String> {
        if (!toolchainBinDir.isDirectory) return emptyList()
        return toolchainBinDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.filter { VERSIONED_CLANG_REGEX.matches(it) }
            ?.sorted()
            ?.toList()
            ?: emptyList()
    }

    private fun clippedValue(value: String?, maxLen: Int = 512): String {
        if (value.isNullOrEmpty()) return "<empty>"
        if (value.length <= maxLen) return value
        return value.take(maxLen) + "...(truncated)"
    }

    private fun isSystemLinkerLauncher(path: String): Boolean = path == "/system/bin/linker64" ||
        path == "/system/bin/linker" ||
        path == "/apex/com.android.runtime/bin/linker64" ||
        path == "/apex/com.android.runtime/bin/linker"

    private fun isAlreadyLinkerWrapped(executable: String, args: List<String>): Boolean {
        if (!isSystemLinkerLauncher(executable) || args.isEmpty()) return false
        val target = args.first()
        return target.startsWith("/") && !isSystemLinkerLauncher(target)
    }

    /**
     * 检查 ELF 可执行文件的依赖库（使用 readelf）
     */
    fun checkElfDependencies(tag: String, elfFile: File) {
        if (!elfFile.isFile) {
            Timber.tag(tag).w("checkElfDependencies: file not found: %s", elfFile.absolutePath)
            return
        }

        try {
            // 尝试使用 readelf 检查依赖
            val readelfPath = findReadelf()
            if (readelfPath == null) {
                Timber.tag(tag).d("readelf not available, skipping dependency check")
                return
            }

            val readelfCommand = buildCommand(readelfPath, listOf("-d", elfFile.absolutePath))
            val process = ProcessBuilder(readelfCommand)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                // 提取 NEEDED 库
                val neededLibs = output.lines()
                    .filter { it.contains("NEEDED") }
                    .mapNotNull { line ->
                        // 格式: 0x0000000000000001 (NEEDED)             Shared library: [libc.so]
                        val match = Regex("""\[([^\]]+)\]""").find(line)
                        match?.groupValues?.get(1)
                    }

                if (neededLibs.isNotEmpty()) {
                    Timber.tag(tag).d("ELF dependencies for %s:", elfFile.name)
                    neededLibs.forEach { lib ->
                        Timber.tag(tag).d("  NEEDED: %s", lib)
                    }
                } else {
                    Timber.tag(tag).d("No dynamic dependencies found for %s", elfFile.name)
                }
            } else {
                Timber.tag(tag).w("readelf failed with exit code %d", exitCode)
            }
        } catch (e: Exception) {
            Timber.tag(tag).w(e, "Failed to check ELF dependencies")
        }
    }

    /**
     * 查找 readelf 工具
     */
    private fun findReadelf(): String? {
        // 尝试常见路径
        val candidates = listOf(
            "/system/bin/readelf",
            "/system/xbin/readelf",
            "readelf" // 依赖 PATH
        )

        for (path in candidates) {
            try {
                val probeCommand = buildCommand(path, listOf("--version"))
                val process = ProcessBuilder(probeCommand)
                    .redirectErrorStream(true)
                    .start()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    return path
                }
            } catch (e: Exception) {
                // 继续尝试下一个
            }
        }
        return null
    }
}
