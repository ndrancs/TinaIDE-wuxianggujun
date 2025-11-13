package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler
import com.wuxianggujun.tinaide.core.nativebridge.NativeLoader
import com.wuxianggujun.tinaide.core.nativebridge.SysrootCMakeRunner
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
 * 按 AI 方案封装的编译用例
 *
 * 负责：
 * - 加载本地编译工具链
 * - 确保 sysroot 安装
 * - 收集源文件并编译 / 链接 / 运行
 * - 通过 OutputManager 写入编译日志
 *
 * 不负责：
 * - UI 交互（Toast、Activity 跳转），由 ViewModel / Activity 处理
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
            // 加载本地库和 sysroot
            NativeLoader.loadIfNeeded()
            val sysrootDir = SysrootInstaller.ensureInstalled(appContext)

            val project = fileManager.getCurrentProject()
                ?: return@withContext Result.Error("未找到项目", null)

            // 记录 CMake/Ninja 探测结果
            try {
                val probe = SysrootCMakeRunner.probe()
                outputManager.appendOutput(probe + "\n")
            } catch (t: Throwable) {
                outputManager.appendOutput("Sysroot CMake/Ninja probe failed: ${t.message}\n")
            }

            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            val target = when {
                abi.contains("arm64", ignoreCase = true) -> "aarch64-linux-android28"
                abi.contains("x86_64", ignoreCase = true) -> "x86_64-linux-android28"
                else -> "aarch64-linux-android28"
            }

            val root = File(project.rootPath)
            val sources = root.walkTopDown()
                .filter { it.isFile && (it.extension.equals("c", true) || it.extension.equals("cc", true) || it.extension.equals("cpp", true) || it.extension.equals("cxx", true)) }
                .toList()

            if (sources.isEmpty()) {
                return@withContext Result.Error("未找到 C/C++ 源文件", null)
            }

            val buildRoot = File(appContext.filesDir, "build/${project.name}").apply { mkdirs() }
            val buildDir = File(buildRoot, "obj").apply { mkdirs() }
            val logFile = File(buildRoot, "build.log").apply {
                parentFile?.mkdirs()
                if (!exists()) createNewFile()
            }

            fun log(line: String) {
                try {
                    android.util.Log.i("Compile", line)
                    logFile.appendText(line + "\n")
                    outputManager.appendOutput(line + "\n")
                } catch (_: Throwable) {
                    // 忽略日志写入错误
                }
            }

            log("=== 编译开始 ===")
            log("目标: $target")
            log("sysroot: ${sysrootDir.absolutePath}")
            log("工程: ${project.name} @ ${project.rootPath}")
            log("源文件数: ${sources.size}")

            // 组装 include 目录：sysroot + 项目常见 include 位置
            val includeDirs = mutableListOf<String>()
            includeDirs += File(sysrootDir, "usr/include").absolutePath
            includeDirs += File(sysrootDir, "usr/include/c++/v1").absolutePath
            listOf("include", "includes", "src").forEach { sub ->
                val d = File(root, sub)
                if (d.exists()) includeDirs += d.absolutePath
            }

            // 固定入口符号：把用户 main 重命名为稳定符号，避免冲突
            val entrySymbol = "tina_ide_use_main"

            val flags = mutableListOf<String>()
            flags += listOf("-Wall", "-Wextra")
            // 启用 C++ 异常
            flags += listOf("-fexceptions", "-fcxx-exceptions")
            // 重命名 main
            flags += listOf("-Dmain=$entrySymbol")

            var ok = 0
            var syntaxOk = 0
            val failed = mutableListOf<String>()
            val compiledObjs = mutableListOf<String>()

            for ((index, src) in sources.withIndex()) {
                coroutineContext.ensureActive()

                val isCxx = true
                val rel = src.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)
                val objName = rel.replace(File.separatorChar, '_') + ".o"
                val objFile = File(buildDir, objName)

                onProgress(CompileProgress(index + 1, sources.size, src.name))
                log("[${if (isCxx) "C++" else "C"}] 编译 ${src.name} -> ${objFile.name}")

                val err = try {
                    NativeCompiler.emitObj(
                        sysrootDir.absolutePath,
                        src.absolutePath,
                        objFile.absolutePath,
                        target,
                        isCxx,
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
                    // fallback: syntax-only
                    val syn = try {
                        NativeCompiler.syntaxCheck(
                            sysrootDir.absolutePath,
                            src.absolutePath,
                            target,
                            isCxx
                        )
                    } catch (t: Throwable) {
                        "syntax JNI error: ${t.message}"
                    }

                    if (syn.isEmpty()) {
                        syntaxOk++
                        val reason = err.ifEmpty { "(no diagnostics)" }
                        log("语法通过(未生成.o): ${src.name}; 原因: ${reason}")
                    } else {
                        val fmsg = "${src.name}: $err | $syn"
                        failed += fmsg
                        log("失败: $fmsg")
                    }
                }
            }

            if (compiledObjs.isNotEmpty()) {
                log("=== 链接阶段 ===")
                val soFile = File(buildRoot, "lib${project.name}.so")
                val linkErr = try {
                    NativeCompiler.linkSoMany(
                        sysrootDir.absolutePath,
                        compiledObjs.toTypedArray(),
                        soFile.absolutePath,
                        target,
                        true,
                        emptyArray(),
                        emptyArray()
                    )
                } catch (t: Throwable) {
                    "link JNI error: ${t.message}"
                }

                if (linkErr.isEmpty()) {
                    log("链接成功: ${soFile.name}")
                    try {
                        log("=== 运行阶段 ===")
                        val out = NativeCompiler.runSharedIsolated(
                            soFile.absolutePath,
                            entrySymbol,
                            15000
                        )
                        log(out)
                    } catch (t: Throwable) {
                        log("运行失败: ${t.message}")
                    }
                } else {
                    log("链接失败: $linkErr")
                }
            }

            val summary = buildString {
                appendLine("目标: $target")
                appendLine("sysroot: ${sysrootDir.absolutePath}")
                appendLine("输出: ${buildDir.absolutePath}")
                appendLine("生成 .o 成功: $ok, 语法通过(回退): $syntaxOk, 失败: ${failed.size}")
                if (failed.isNotEmpty()) {
                    appendLine()
                    appendLine("失败样例: ")
                    appendLine(failed.take(5).joinToString("\n"))
                }
            }

            log("=== 编译结束 ===")
            log(summary)
            log("\n日志文件: ${logFile.absolutePath}")

            Result.Success(summary)
        } catch (e: CancellationException) {
            // 取消编译，直接抛出让上层处理中止
            throw e
        } catch (t: Throwable) {
            Result.Error(t.message ?: "编译失败", t)
        }
    }
}
