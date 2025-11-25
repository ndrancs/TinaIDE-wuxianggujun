package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler
import com.wuxianggujun.tinaide.core.nativebridge.NativeLoader
import com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller
import com.wuxianggujun.tinaide.core.nativebridge.XmakeRunner
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
 * 支持两种编译模式：
 * 1. xmake 项目 - 使用 XmakeRunner 调用 xmake 构建
 * 2. 单文件项目 - 使用 NativeCompiler 编译单个 cpp 文件
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
    
    /**
     * 检测是否为 xmake 项目
     */
    private fun isXmakeProject(projectRoot: File): Boolean {
        return File(projectRoot, "xmake.lua").exists()
    }

    suspend fun execute(onProgress: (CompileProgress) -> Unit): Result = withContext(Dispatchers.IO) {
        try {
            val project = fileManager.getCurrentProject()
                ?: return@withContext Result.Error("未找到项目", null)

            val projectRoot = File(project.rootPath)
            
            // 根据项目类型选择编译方式
            if (isXmakeProject(projectRoot)) {
                compileXmakeProject(projectRoot, project.name)
            } else {
                compileSingleFileProject(projectRoot, project.name, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.Error(t.message ?: "编译失败", t)
        }
    }
    
    /**
     * 编译 xmake 项目
     */
    private suspend fun compileXmakeProject(
        projectRoot: File,
        projectName: String
    ): Result = withContext(Dispatchers.IO) {
        try {
            outputManager.appendOutput("=== xmake 构建开始 ===\n")
            outputManager.appendOutput("项目: $projectName\n")
            outputManager.appendOutput("路径: ${projectRoot.absolutePath}\n\n")
            
            // 检查 xmake.lua 是否存在
            val xmakeLua = File(projectRoot, "xmake.lua")
            if (!xmakeLua.exists()) {
                return@withContext Result.Error("xmake.lua 不存在", null)
            }
            outputManager.appendOutput("xmake.lua 存在: ${xmakeLua.absolutePath}\n")
            
            // 加载 xmake_runner
            outputManager.appendOutput("加载 xmake_runner...\n")
            XmakeRunner.loadIfNeeded()
            outputManager.appendOutput("xmake_runner 加载成功\n\n")
            
            // 先尝试获取 xmake 版本
            outputManager.appendOutput("检查 xmake 版本...\n")
            val versionResult = XmakeRunner.run("--version")
            outputManager.appendOutput("xmake --version 返回码: $versionResult\n\n")
            
            // 执行 xmake 构建
            outputManager.appendOutput("执行 xmake build...\n")
            outputManager.appendOutput("命令: xmake -P ${projectRoot.absolutePath} -v\n\n")
            val result = XmakeRunner.build(projectRoot.absolutePath, verbose = true)
            
            if (result == 0) {
                val summary = buildString {
                    appendLine("=== xmake 构建成功 ===")
                    appendLine("项目: $projectName")
                    appendLine("返回码: $result")
                }
                outputManager.appendOutput("\n$summary")
                Result.Success(summary)
            } else {
                val msg = "xmake 构建失败，返回码: $result"
                outputManager.appendOutput("\n$msg\n")
                outputManager.appendOutput("提示: 返回码 -1 通常表示 xmake 内部错误\n")
                outputManager.appendOutput("可能原因:\n")
                outputManager.appendOutput("  1. xmake 需要初始化配置\n")
                outputManager.appendOutput("  2. 项目路径权限问题\n")
                outputManager.appendOutput("  3. xmake 在 Android 环境下的兼容性问题\n")
                Result.Error(msg, null)
            }
            
        } catch (e: Exception) {
            val msg = "xmake 编译失败: ${e.message}"
            outputManager.appendOutput("\n$msg\n")
            outputManager.appendOutput("异常堆栈: ${e.stackTraceToString()}\n")
            Result.Error(msg, e)
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
        // 加载本地库和 sysroot
        NativeLoader.loadIfNeeded()
        val sysrootDir = SysrootInstaller.ensureInstalled(appContext)

        outputManager.appendOutput("=== 单文件编译模式 ===\n")

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

        val buildRoot = File(appContext.filesDir, "build/$projectName").apply { mkdirs() }
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
            } catch (_: Throwable) {}
        }

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

        val entrySymbol = "tina_ide_use_main"
        val flags = mutableListOf("-Wall", "-Wextra", "-fexceptions", "-fcxx-exceptions", "-Dmain=$entrySymbol")

        var ok = 0
        var syntaxOk = 0
        val failed = mutableListOf<String>()
        val compiledObjs = mutableListOf<String>()

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

        if (compiledObjs.isNotEmpty()) {
            log("=== 链接阶段 ===")
            val soFile = File(buildRoot, "lib$projectName.so")
            val linkErr = try {
                NativeCompiler.linkSoMany(
                    sysrootDir.absolutePath, compiledObjs.toTypedArray(),
                    soFile.absolutePath, target, true, emptyArray(), emptyArray()
                )
            } catch (t: Throwable) { "link JNI error: ${t.message}" }

            if (linkErr.isEmpty()) {
                log("链接成功: ${soFile.name}")
                try {
                    log("=== 运行阶段 ===")
                    val out = NativeCompiler.runSharedIsolated(soFile.absolutePath, entrySymbol, 15000)
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
            appendLine("生成 .o 成功: $ok, 语法通过: $syntaxOk, 失败: ${failed.size}")
        }
        log("=== 编译结束 ===")
        log(summary)

        Result.Success(summary)
    }
}
