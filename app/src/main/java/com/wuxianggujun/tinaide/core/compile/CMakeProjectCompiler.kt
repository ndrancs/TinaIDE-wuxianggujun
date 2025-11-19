package com.wuxianggujun.tinaide.core.compile

import android.util.Log
import com.wuxianggujun.tinaide.core.nativebridge.NinjaRunner
import com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller
import com.wuxianggujun.tinaide.TinaApplication
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * CMake 项目编译器
 * 
 * 负责：
 * - 检测 CMakeLists.txt
 * - 使用 sysroot 中的 cmake 和 ninja 构建项目
 * - 提供编译进度和日志输出
 */
class CMakeProjectCompiler(
    private val sysrootDir: File,
    private val projectRoot: File,
    private val buildDir: File,
    private val onLog: (String) -> Unit
) {
    
    companion object {
        private const val TAG = "CMakeProjectCompiler"
        
        /**
         * 检测项目是否为 CMake 项目
         */
        fun isCMakeProject(projectRoot: File): Boolean {
            return File(projectRoot, "CMakeLists.txt").exists()
        }
    }
    
    data class CompileResult(
        val success: Boolean,
        val message: String,
        val executable: File? = null
    )
    
    /**
     * 执行 CMake 配置和构建
     */
    suspend fun compile(): CompileResult {
        try {
            onLog("=== CMake 项目编译 ===")
            onLog("项目根目录: ${projectRoot.absolutePath}")
            onLog("构建目录: ${buildDir.absolutePath}")
            onLog("Sysroot: ${sysrootDir.absolutePath}")
            
            // 确保构建目录存在
            buildDir.mkdirs()
            
            // 1. 检查 cmake 和 ninja
            val cmakeSrc = File(sysrootDir, "usr/bin/cmake")
            val ninjaSrc = File(sysrootDir, "usr/bin/ninja")
            
            if (!cmakeSrc.exists()) {
                return CompileResult(false, "CMake 未找到: ${cmakeSrc.absolutePath}")
            }
            if (!ninjaSrc.exists()) {
                return CompileResult(false, "Ninja 未找到: ${ninjaSrc.absolutePath}")
            }
            
            // 2. 直接使用 sysroot 中的工具（通过 sh -c 执行）
            val cmakePath = cmakeSrc
            val ninjaPath = ninjaSrc
            
            onLog("CMake: ${cmakePath.absolutePath}")
            onLog("Ninja: ${ninjaPath.absolutePath}")
            
            // 2. 获取目标架构
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val triple = when {
                abi.contains("arm64", true) -> "aarch64-linux-android"
                abi.contains("x86_64", true) -> "x86_64-linux-android"
                else -> "aarch64-linux-android"
            }
            onLog("目标架构: $abi ($triple)")
            
            // 3. 准备 CMake 工具链文件
            val toolchainFile = createToolchainFile(triple)
            onLog("工具链文件: ${toolchainFile.absolutePath}")
            
            // 4. 运行 CMake 配置
            onLog("\n--- CMake 配置阶段 ---")
            val configResult = runCMakeConfigure(cmakePath, toolchainFile)
            if (!configResult.success) {
                return configResult
            }
            
            // 5. 运行 CMake 构建（使用 Ninja JNI）
            onLog("\n--- CMake 构建阶段 ---")
            val buildResult = if (NinjaRunner.isAvailable()) {
                runNinjaBuild()
            } else {
                onLog("警告: Ninja JNI 不可用，尝试使用 sh -c")
                runCMakeBuild(cmakePath)
            }
            if (!buildResult.success) {
                return buildResult
            }
            
            // 6. 查找生成的可执行文件或库
            val outputs = findBuildOutputs()
            if (outputs.isNotEmpty()) {
                onLog("\n=== 构建成功 ===")
                onLog("生成的文件:")
                outputs.forEach { onLog("  - ${it.absolutePath}") }
                return CompileResult(true, "CMake 构建成功", outputs.firstOrNull())
            } else {
                onLog("\n=== 构建完成（未找到输出文件）===")
                return CompileResult(true, "CMake 构建完成，但未找到输出文件")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "CMake compile failed", e)
            onLog("错误: ${e.message}")
            return CompileResult(false, "编译失败: ${e.message}")
        }
    }
    
    /**
     * 创建 CMake 工具链文件
     */
    private fun createToolchainFile(triple: String): File {
        val toolchainFile = File(buildDir, "android-toolchain.cmake")
        
        val clangPath = File(sysrootDir, "usr/bin/clang")
        val clangxxPath = File(sysrootDir, "usr/bin/clang++")
        
        val content = """
            # Android Toolchain for TinaIDE
            set(CMAKE_SYSTEM_NAME Linux)
            set(CMAKE_SYSTEM_PROCESSOR ${if (triple.startsWith("aarch64")) "aarch64" else "x86_64"})
            
            # Sysroot
            set(CMAKE_SYSROOT "${sysrootDir.absolutePath}")
            
            # Compilers
            set(CMAKE_C_COMPILER "${clangPath.absolutePath}")
            set(CMAKE_CXX_COMPILER "${clangxxPath.absolutePath}")
            
            # Target triple
            set(CMAKE_C_COMPILER_TARGET $triple)
            set(CMAKE_CXX_COMPILER_TARGET $triple)
            
            # Compiler flags
            set(CMAKE_C_FLAGS_INIT "-fPIC")
            set(CMAKE_CXX_FLAGS_INIT "-fPIC -fexceptions -fcxx-exceptions")
            
            # Search paths
            set(CMAKE_FIND_ROOT_PATH "${sysrootDir.absolutePath}")
            set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
            set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
            set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
            set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
        """.trimIndent()
        
        toolchainFile.writeText(content)
        return toolchainFile
    }
    
    /**
     * 运行 CMake 配置
     */
    private fun runCMakeConfigure(cmakePath: File, toolchainFile: File): CompileResult {
        val args = listOf(
            cmakePath.absolutePath,
            "-S", projectRoot.absolutePath,
            "-B", buildDir.absolutePath,
            "-G", "Ninja",
            "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
            "-DCMAKE_BUILD_TYPE=Debug",
            "-DCMAKE_MAKE_PROGRAM=${File(sysrootDir, "usr/bin/ninja").absolutePath}"
        )
        
        return runCommand(args, "CMake 配置")
    }
    
    /**
     * 运行 CMake 构建
     */
    private fun runCMakeBuild(cmakePath: File): CompileResult {
        val args = listOf(
            cmakePath.absolutePath,
            "--build", buildDir.absolutePath,
            "--parallel", "4"
        )
        
        return runCommand(args, "CMake 构建")
    }
    
    /**
     * 执行命令并捕获输出
     */
    private fun runCommand(args: List<String>, stageName: String): CompileResult {
        try {
            // 使用 sh -c 执行命令（绕过 SELinux 限制）
            val command = args.joinToString(" ") { arg ->
                if (arg.contains(" ")) "\"$arg\"" else arg
            }
            onLog("执行: $command")
            
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            pb.directory(projectRoot)
            
            // 设置环境变量
            val env = pb.environment()
            val usrBin = File(sysrootDir, "usr/bin").absolutePath
            env["PATH"] = usrBin + File.pathSeparator + (env["PATH"] ?: "")
            env["LD_LIBRARY_PATH"] = File(sysrootDir, "usr/lib").absolutePath
            
            pb.redirectErrorStream(true)
            
            val process = pb.start()
            
            // 读取输出
            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    onLog(line)
                    output.appendLine(line)
                }
            }
            
            // 等待完成（最多60秒）
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CompileResult(false, "$stageName 超时")
            }
            
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                return CompileResult(false, "$stageName 失败 (退出码: $exitCode)")
            }
            
            return CompileResult(true, "$stageName 成功")
            
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed", e)
            return CompileResult(false, "$stageName 异常: ${e.message}")
        }
    }
    

    /**
     * 使用 Ninja JNI 运行构建
     */
    private fun runNinjaBuild(): CompileResult {
        try {
            onLog("使用 Ninja JNI 运行构建")
            
            NinjaRunner.loadIfNeeded()
            
            val args = arrayOf(
                "ninja",
                "-C", buildDir.absolutePath,
                "-j", "4",
                "-v"  // verbose
            )
            
            onLog("执行: ${args.joinToString(" ")}")
            
            val exitCode = NinjaRunner.runNinja(
                workingDir = buildDir.absolutePath,
                args = args
            )
            
            return if (exitCode == 0) {
                CompileResult(true, "Ninja 构建成功")
            } else {
                CompileResult(false, "Ninja 构建失败 (退出码: $exitCode)")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ninja JNI execution failed", e)
            return CompileResult(false, "Ninja JNI 执行异常: ${e.message}")
        }
    }
    
    /**
     * 查找构建输出文件
     */
    private fun findBuildOutputs(): List<File> {
        val outputs = mutableListOf<File>()
        
        buildDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val name = file.name
                // 查找可执行文件和库文件
                if (name.endsWith(".so") || 
                    name.endsWith(".a") || 
                    (!name.contains(".") && file.canExecute())) {
                    outputs.add(file)
                }
            }
        }
        
        return outputs
    }
}
