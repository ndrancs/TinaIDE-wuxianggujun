package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler
import com.wuxianggujun.tinaide.core.nativebridge.NativeLoader
import com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller
import com.wuxianggujun.tinaide.file.IFileManager
import com.wuxianggujun.tinaide.output.IOutputManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File

/**
 * 编译用例
 *
 * 当前仅支持**单文件/轻量项目**的直接编译，未来会扩展自定义构建流程。
 */
class CompileProjectUseCase(
    private val appContext: Context,
    private val fileManager: IFileManager,
    private val outputManager: IOutputManager
) {

    data class CompileProgress(
        val current: Int,
        val total: Int,
        val fileName: String
    )

    sealed class Result {
        data class Success(val summary: String) : Result()
        data class Error(val userMessage: String, val throwable: Throwable?) : Result()
    }
    
    suspend fun execute(onProgress: (CompileProgress) -> Unit): Result = withContext(Dispatchers.IO) {
        try {
            val project = fileManager.getCurrentProject()
                ?: return@withContext Result.Error("未找到项目", null)

            val projectRoot = File(project.rootPath)

            if (File(projectRoot, "xmake.lua").exists()) {
                return@withContext Result.Error("xmake 项目已不再受支持，请迁移到自定义构建流程。", null)
            }

            compileSingleFileProject(projectRoot, project.name, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.Error(t.message ?: "编译失败", t)
        }
    }
    
    /**
     * 编译单文件项目
     */
    private suspend fun compileSingleFileProject(
        projectRoot: File,
        projectName: String,
        onProgress: (CompileProgress) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        // 加载本地库、确保链接服务器存活，然后准备 sysroot
        NativeLoader.loadIfNeeded()
        // 如果 IDE 从后台恢复后守护进程被系统杀掉，确保在真正编译前重新 fork
        NativeLoader.startLinkServerIfNeeded()
        val sysrootDir = SysrootInstaller.ensureInstalled(appContext)

        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val target = when {
            abi.contains("arm64", ignoreCase = true) -> "aarch64-linux-android28"
            abi.contains("x86_64", ignoreCase = true) -> "x86_64-linux-android28"
            else -> "aarch64-linux-android28"
        }

        val sources = projectRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in listOf("c", "cc", "cpp", "cxx") }
            .toList()

        if (sources.isEmpty()) {
            return@withContext Result.Error("未找到 C/C++ 源文件", null)
        }

        // 构建产物放在项目目录下的 build/debug 子目录
        // 目录结构: build/debug/obj/*.o, build/debug/lib*.so, build/debug/build.log
        // TODO: 未来支持 release 构建时添加优化选项并输出到 build/release
        val buildRoot = File(projectRoot, "build/debug").apply { mkdirs() }
        val buildDir = File(buildRoot, "obj").apply { mkdirs() }
        val logFile = File(buildRoot, "build.log").apply {
            parentFile?.mkdirs()
            if (!exists()) createNewFile()
        }
        // 每次编译前清空运行输出，避免输出界面残留历史日志
        outputManager.clearOutput(IOutputManager.OutputChannel.RUN)

        fun log(line: String, channel: IOutputManager.OutputChannel = IOutputManager.OutputChannel.BUILD) {
            try {
                android.util.Log.i("Compile", line)
                logFile.appendText(line + "\n")
                outputManager.appendOutput(line + "\n", channel)
            } catch (_: Throwable) {}
        }

        fun logLines(text: String, channel: IOutputManager.OutputChannel = IOutputManager.OutputChannel.BUILD) {
            if (text.isEmpty()) {
                log("", channel)
                return
            }
            text.lineSequence().forEach { line ->
                log(line, channel)
            }
        }

        log("=== 单文件编译模式 ===")

        log("=== 编译开始 ===")
        log("目标: $target")
        log("sysroot: ${sysrootDir.absolutePath}")
        log("工程: $projectName @ ${projectRoot.absolutePath}")
        log("源文件数: ${sources.size}")

        // 组装 include 目录
        val includeDirs = mutableListOf<String>()
        includeDirs += File(sysrootDir, "usr/include").absolutePath
        includeDirs += File(sysrootDir, "usr/include/c++/v1").absolutePath
        listOf("include", "includes", "src").forEach { sub ->
            val d = File(projectRoot, sub)
            if (d.exists()) includeDirs += d.absolutePath
        }

        val entrySymbol = "main"
        val flags = mutableListOf("-Wall", "-Wextra", "-fexceptions", "-fcxx-exceptions")

        var ok = 0
        var syntaxOk = 0
        val failed = mutableListOf<String>()
        val compiledObjs = mutableListOf<String>()
        var linkErrorMessage: String? = null
        var runErrorMessage: String? = null

        for ((index, src) in sources.withIndex()) {
            coroutineContext.ensureActive()

            val rel = src.absolutePath.removePrefix(projectRoot.absolutePath).trimStart(File.separatorChar)
            val objName = rel.replace(File.separatorChar, '_') + ".o"
            val objFile = File(buildDir, objName)

            onProgress(CompileProgress(index + 1, sources.size, src.name))
            log("[C++] 编译 ${src.name} -> ${objFile.name}")

            val err = try {
                NativeCompiler.emitObj(
                    sysrootDir.absolutePath,
                    src.absolutePath,
                    objFile.absolutePath,
                    target,
                    true,
                    flags.toTypedArray(),
                    includeDirs.toTypedArray()
                )
            } catch (t: Throwable) {
                "JNI error: ${t.message}"
            }

            if (err.isEmpty()) {
                ok++
                compiledObjs += objFile.absolutePath
                log("成功: ${src.name}")
            } else {
                val syn = try {
                    NativeCompiler.syntaxCheck(sysrootDir.absolutePath, src.absolutePath, target, true)
                } catch (t: Throwable) { "syntax JNI error: ${t.message}" }

                if (syn.isEmpty()) {
                    syntaxOk++
                    log("语法通过(未生成.o): ${src.name}")
                } else {
                    failed += "${src.name}: $err | $syn"
                    log("失败: ${src.name}")
                }
            }
        }

        if (failed.isNotEmpty()) {
            log("=== 编译失败文件列表 ===")
            failed.forEach { detail -> log(detail) }
        }

        val soFile = File(buildRoot, "lib$projectName.so")
        if (compiledObjs.isNotEmpty()) {
            log("=== 链接阶段 ===")
            val linkErr = run {
                fun invokeLink(): String = try {
                    NativeCompiler.linkSoMany(
                        sysrootDir.absolutePath, compiledObjs.toTypedArray(),
                        soFile.absolutePath, target, true, emptyArray(), emptyArray()
                    )
                } catch (t: Throwable) { "link JNI error: ${t.message}" }

                fun shouldRestartLinkServer(err: String): Boolean {
                    val normalized = err.lowercase()
                    return normalized.contains("broken pipe") ||
                        normalized.contains("failed to send link request") ||
                        normalized.contains("disconnected from link server")
                }

                val first = invokeLink()
                if (first.isEmpty() || !shouldRestartLinkServer(first)) {
                    first
                } else {
                    log("é“¾æŽ¥æœåŠ¡å™¨å·²ä¸­æ–­ï¼Œé‡å¯å¹¶å†è¯•...\\n$first")
                    NativeLoader.stopLinkServer()
                    NativeLoader.startLinkServerIfNeeded()
                    val retry = invokeLink()
                    if (retry.isEmpty()) "" else "$first\\nRetry failed: $retry"
                }
            }

            if (linkErr.isEmpty()) {
                log("链接成功: ${soFile.name}")
                try {
                    log("=== 运行阶段 ===")
                    val runResult = NativeCompiler.runSharedIsolated(soFile.absolutePath, entrySymbol, 15000)
                    val normalizedOutput = runResult.output
                        .replace("\r\n", "\n")
                        .replace("\r", "\n")
                    if (normalizedOutput.isNotBlank()) {
                        logLines(normalizedOutput)
                        outputManager.appendOutput(normalizedOutput, IOutputManager.OutputChannel.RUN)
                    }
                    if (runResult.returnCode == 0) {
                        log("运行返回码: 0")
                    } else {
                        log("运行失败: 返回码 ${runResult.returnCode}")
                        runErrorMessage = "运行失败：返回码 ${runResult.returnCode}"
                    }
                } catch (t: Throwable) {
                    val msg = t.message ?: "未知错误"
                    log("运行失败: $msg")
                    runErrorMessage = "运行失败：$msg"
                }
            } else {
                log("链接失败: $linkErr")
                linkErrorMessage = linkErr
            }
        }

        val summary = buildString {
            appendLine("目标: $target")
            appendLine("生成 .o 成功: $ok, 语法通过: $syntaxOk, 失败: ${failed.size}")
            if (compiledObjs.isNotEmpty()) {
                appendLine("产物: ${soFile.absolutePath}")
            }
        }
        log("=== 编译结束 ===")
        logLines(summary)

        val compileErrorMessage = when {
            failed.isNotEmpty() -> "编译失败：${failed.size} 个源文件出错"
            compiledObjs.isEmpty() -> "没有成功的目标文件，无法继续链接"
            else -> null
        }

        return@withContext when {
            compileErrorMessage != null -> Result.Error(compileErrorMessage, null)
            linkErrorMessage != null -> Result.Error("链接失败：$linkErrorMessage", null)
            runErrorMessage != null -> Result.Error(runErrorMessage!!, null)
            else -> Result.Success(summary)
        }
    }
}
