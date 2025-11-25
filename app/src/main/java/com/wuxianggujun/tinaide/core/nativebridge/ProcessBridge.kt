package com.wuxianggujun.tinaide.core.nativebridge

import android.util.Log
import java.io.File

/**
 * 进程桥接器 - 供 tbox Native 层调用
 *
 * 在 Android 上，将 xmake 的"子进程调用"转换为 NativeCompiler 的进程内调用。
 * 这样就不需要 fork/exec，直接使用已加载的 libclang-cpp.so 和 liblld*.a。
 *
 * 支持的命令：
 * - clang/clang++/cc/c++ → NativeCompiler.emitObj()
 * - ld/lld → NativeCompiler.linkExe() / linkSo()
 * - ar → 暂不支持（返回错误）
 */
object ProcessBridge {
    private const val TAG = "ProcessBridge"

    /**
     * sysroot 路径，用于编译器查找头文件和库
     */
    @Volatile
    @JvmField
    var sysrootDir: String = ""

    /**
     * nativeLibraryDir 路径（保留，用于 fallback）
     */
    @Volatile
    @JvmField
    var nativeLibDir: String = ""

    /**
     * 默认 target
     */
    @Volatile
    @JvmField
    var defaultTarget: String = "aarch64-linux-android28"

    /**
     * 启动进程（供 JNI 调用）
     *
     * 将命令行参数解析后，调用 NativeCompiler 的对应方法。
     *
     * @param command 命令名（如 "clang", "lld"）或完整路径
     * @param args 参数数组（不包含命令本身）
     * @param workDir 工作目录，可为 null
     * @param envVars 环境变量数组，格式 "KEY=VALUE"，可为 null
     * @return JSON 格式结果: {"code":0,"out":"...","err":"..."}
     */
    @JvmStatic
    fun startProcess(
        command: String,
        args: Array<String>?,
        workDir: String?,
        envVars: Array<String>?
    ): String {
        val cmdName = File(command).name
        Log.d(TAG, "startProcess: command=$cmdName, args=${args?.joinToString(" ")}")

        return try {
            when {
                isCompilerCommand(cmdName) -> handleCompiler(cmdName, args ?: emptyArray())
                isLinkerCommand(cmdName) -> handleLinker(cmdName, args ?: emptyArray())
                isArCommand(cmdName) -> handleAr(args ?: emptyArray())
                else -> handleOtherCommand(command, args, workDir, envVars)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startProcess failed", e)
            buildJsonResult(-1, "", "ProcessBridge error: ${e.message}")
        }
    }


    // ==================== 命令识别 ====================

    private fun isCompilerCommand(name: String): Boolean {
        return name.contains("clang") || name == "cc" || name == "c++" ||
                name == "gcc" || name == "g++"
    }

    private fun isLinkerCommand(name: String): Boolean {
        return name == "ld" || name.contains("lld") || name == "link"
    }

    private fun isArCommand(name: String): Boolean {
        return name == "ar" || name == "llvm-ar"
    }

    // ==================== 编译器处理 ====================

    /**
     * 处理编译器调用 (clang/clang++/gcc/g++)
     *
     * 解析参数，调用 NativeCompiler.emitObj()
     */
    private fun handleCompiler(cmdName: String, args: Array<String>): String {
        Log.d(TAG, "handleCompiler: $cmdName")

        // 解析参数
        val parsed = parseCompilerArgs(args)

        // 检查是否是编译模式 (-c)
        if (!parsed.compileOnly) {
            // 编译+链接模式，需要分两步
            return handleCompileAndLink(cmdName, parsed)
        }

        // 纯编译模式
        if (parsed.sourceFile == null) {
            return buildJsonResult(-1, "", "No source file specified")
        }
        if (parsed.outputFile == null) {
            return buildJsonResult(-1, "", "No output file specified (-o)")
        }

        val isCxx = cmdName.contains("++") || cmdName == "c++" || cmdName == "g++" ||
                parsed.sourceFile!!.endsWith(".cpp") ||
                parsed.sourceFile!!.endsWith(".cc") ||
                parsed.sourceFile!!.endsWith(".cxx")

        val target = parsed.target ?: defaultTarget

        Log.d(TAG, "  Compiling: ${parsed.sourceFile} -> ${parsed.outputFile}")
        Log.d(TAG, "  target=$target, isCxx=$isCxx")
        Log.d(TAG, "  flags=${parsed.flags.joinToString(" ")}")
        Log.d(TAG, "  includes=${parsed.includeDirs.joinToString(" ")}")

        // 确保 NativeLoader 已加载
        try {
            NativeLoader.loadIfNeeded()
        } catch (e: Exception) {
            return buildJsonResult(-1, "", "Failed to load NativeCompiler: ${e.message}")
        }

        // 调用 NativeCompiler.emitObj()
        val error = NativeCompiler.emitObj(
            sysrootDir,
            parsed.sourceFile!!,
            parsed.outputFile!!,
            target,
            isCxx,
            parsed.flags.toTypedArray(),
            parsed.includeDirs.toTypedArray()
        )

        return if (error.isEmpty()) {
            Log.d(TAG, "  Compile success")
            buildJsonResult(0, "", "")
        } else {
            Log.w(TAG, "  Compile failed: $error")
            buildJsonResult(1, "", error)
        }
    }

    /**
     * 处理编译+链接模式（没有 -c 参数）
     */
    private fun handleCompileAndLink(cmdName: String, parsed: CompilerArgs): String {
        // TODO: 实现编译+链接模式
        // 目前 xmake 通常会分开调用，所以这个分支可能不常用
        return buildJsonResult(-1, "", "Compile-and-link mode not yet supported. Use -c flag.")
    }

    // ==================== 链接器处理 ====================

    /**
     * 处理链接器调用 (ld/lld)
     */
    private fun handleLinker(cmdName: String, args: Array<String>): String {
        Log.d(TAG, "handleLinker: $cmdName")

        val parsed = parseLinkerArgs(args)

        if (parsed.objectFiles.isEmpty()) {
            return buildJsonResult(-1, "", "No object files specified")
        }
        if (parsed.outputFile == null) {
            return buildJsonResult(-1, "", "No output file specified (-o)")
        }

        val target = defaultTarget

        Log.d(TAG, "  Linking: ${parsed.objectFiles.size} files -> ${parsed.outputFile}")
        Log.d(TAG, "  shared=${parsed.shared}, libDirs=${parsed.libDirs}, libs=${parsed.libs}")

        // 确保 NativeLoader 已加载
        try {
            NativeLoader.loadIfNeeded()
        } catch (e: Exception) {
            return buildJsonResult(-1, "", "Failed to load NativeCompiler: ${e.message}")
        }

        // 调用 NativeCompiler
        val error = if (parsed.shared) {
            NativeCompiler.linkSoMany(
                sysrootDir,
                parsed.objectFiles.toTypedArray(),
                parsed.outputFile!!,
                target,
                true,  // isCxx
                parsed.libDirs.toTypedArray(),
                parsed.libs.toTypedArray()
            )
        } else {
            NativeCompiler.linkExeMany(
                sysrootDir,
                parsed.objectFiles.toTypedArray(),
                parsed.outputFile!!,
                target,
                true,  // isCxx
                parsed.libDirs.toTypedArray(),
                parsed.libs.toTypedArray()
            )
        }

        return if (error.isEmpty()) {
            Log.d(TAG, "  Link success")
            buildJsonResult(0, "", "")
        } else {
            Log.w(TAG, "  Link failed: $error")
            buildJsonResult(1, "", error)
        }
    }

    // ==================== AR 处理 ====================

    private fun handleAr(args: Array<String>): String {
        // TODO: 实现 ar 功能（创建静态库）
        return buildJsonResult(-1, "", "ar command not yet supported")
    }

    // ==================== 其他命令 ====================

    /**
     * 处理其他命令（尝试使用 ProcessBuilder）
     */
    private fun handleOtherCommand(
        command: String,
        args: Array<String>?,
        workDir: String?,
        envVars: Array<String>?
    ): String {
        Log.d(TAG, "handleOtherCommand: $command")

        // 对于非编译器命令，尝试使用 ProcessBuilder
        // 这可能会失败，取决于命令是否在可执行路径
        return try {
            val cmdList = mutableListOf(command)
            args?.let { cmdList.addAll(it) }

            val pb = ProcessBuilder(cmdList)
            workDir?.let { pb.directory(File(it)) }
            envVars?.forEach { env ->
                val idx = env.indexOf('=')
                if (idx > 0) {
                    pb.environment()[env.substring(0, idx)] = env.substring(idx + 1)
                }
            }

            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            buildJsonResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            buildJsonResult(-1, "", "Failed to execute: $command - ${e.message}")
        }
    }


    // ==================== 参数解析 ====================

    data class CompilerArgs(
        var sourceFile: String? = null,
        var outputFile: String? = null,
        var target: String? = null,
        var compileOnly: Boolean = false,
        val flags: MutableList<String> = mutableListOf(),
        val includeDirs: MutableList<String> = mutableListOf(),
        val defines: MutableList<String> = mutableListOf()
    )

    /**
     * 解析编译器参数
     */
    private fun parseCompilerArgs(args: Array<String>): CompilerArgs {
        val result = CompilerArgs()
        var i = 0

        while (i < args.size) {
            val arg = args[i]

            when {
                arg == "-c" -> result.compileOnly = true
                arg == "-o" && i + 1 < args.size -> {
                    result.outputFile = args[++i]
                }
                arg.startsWith("-o") -> {
                    result.outputFile = arg.substring(2)
                }
                arg == "-I" && i + 1 < args.size -> {
                    result.includeDirs.add(args[++i])
                }
                arg.startsWith("-I") -> {
                    result.includeDirs.add(arg.substring(2))
                }
                arg == "-D" && i + 1 < args.size -> {
                    result.defines.add(args[++i])
                    result.flags.add("-D${args[i]}")
                }
                arg.startsWith("-D") -> {
                    result.defines.add(arg.substring(2))
                    result.flags.add(arg)
                }
                arg.startsWith("--target=") -> {
                    result.target = arg.substring(9)
                }
                arg == "-target" && i + 1 < args.size -> {
                    result.target = args[++i]
                }
                arg.startsWith("-") -> {
                    // 其他标志，传递给编译器
                    result.flags.add(arg)
                }
                else -> {
                    // 源文件
                    if (result.sourceFile == null && isSourceFile(arg)) {
                        result.sourceFile = arg
                    }
                }
            }
            i++
        }

        return result
    }

    private fun isSourceFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in listOf("c", "cc", "cpp", "cxx", "c++", "m", "mm", "s", "S")
    }

    data class LinkerArgs(
        var outputFile: String? = null,
        var shared: Boolean = false,
        val objectFiles: MutableList<String> = mutableListOf(),
        val libDirs: MutableList<String> = mutableListOf(),
        val libs: MutableList<String> = mutableListOf()
    )

    /**
     * 解析链接器参数
     */
    private fun parseLinkerArgs(args: Array<String>): LinkerArgs {
        val result = LinkerArgs()
        var i = 0

        while (i < args.size) {
            val arg = args[i]

            when {
                arg == "-o" && i + 1 < args.size -> {
                    result.outputFile = args[++i]
                }
                arg.startsWith("-o") -> {
                    result.outputFile = arg.substring(2)
                }
                arg == "-shared" || arg == "--shared" -> {
                    result.shared = true
                }
                arg == "-L" && i + 1 < args.size -> {
                    result.libDirs.add(args[++i])
                }
                arg.startsWith("-L") -> {
                    result.libDirs.add(arg.substring(2))
                }
                arg == "-l" && i + 1 < args.size -> {
                    result.libs.add(args[++i])
                }
                arg.startsWith("-l") -> {
                    result.libs.add(arg.substring(2))
                }
                arg.endsWith(".o") || arg.endsWith(".a") -> {
                    result.objectFiles.add(arg)
                }
                arg.startsWith("-") -> {
                    // 忽略其他标志
                }
                else -> {
                    // 可能是目标文件
                    if (File(arg).exists()) {
                        result.objectFiles.add(arg)
                    }
                }
            }
            i++
        }

        return result
    }

    // ==================== 工具方法 ====================

    /**
     * 构建 JSON 结果
     */
    private fun buildJsonResult(code: Int, out: String, err: String): String {
        fun escape(s: String) = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

        return """{"code":$code,"out":"${escape(out)}","err":"${escape(err)}"}"""
    }
}
