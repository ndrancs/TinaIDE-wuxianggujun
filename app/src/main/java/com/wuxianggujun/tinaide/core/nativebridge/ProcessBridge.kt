package com.wuxianggujun.tinaide.core.nativebridge

import android.util.Log
import java.io.File

/**
 * 进程桥接器 - 供 tbox Native 层调用
 * 
 * 用于在 Android 上启动编译器进程，绕过 fork/exec 限制。
 * 通过 ProcessBuilder 启动位于 nativeLibraryDir 的 wrapper 程序。
 * 
 * ⚠️ MVP 方案说明：
 * 当前采用"阻塞等待 + 一次性收集输出"的方式，作为第一阶段实现。
 * 不具备流式输出能力，大项目可能占用较多内存。
 * 将来如需实时日志，可增加回调/管道桥接机制。
 */
object ProcessBridge {
    private const val TAG = "ProcessBridge"
    
    /**
     * nativeLibraryDir 路径，由 NativeEnv 初始化时设置
     */
    @Volatile
    @JvmField
    var nativeLibDir: String = ""
    
    /**
     * sysroot 路径，用于编译器查找头文件和库
     */
    @Volatile
    @JvmField
    var sysrootDir: String = ""
    
    /**
     * 启动进程（供 JNI 调用）
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
        Log.d(TAG, "startProcess: command=$command, args=${args?.joinToString(" ")}")
        Log.d(TAG, "  workDir=$workDir")
        
        return try {
            // 解析实际要执行的命令
            val actualCmd = resolveCommand(command)
            Log.d(TAG, "  resolved command: $actualCmd")
            
            if (actualCmd == null) {
                return buildJsonResult(-1, "", "Cannot resolve command: $command")
            }

            val cmdList = mutableListOf<String>()
            
            // 检查是否需要使用 linker64
            if (actualCmd.endsWith(".so") && File(actualCmd).exists()) {
                // 使用 linker64 执行 .so wrapper
                cmdList.add("/system/bin/linker64")
            }
            cmdList.add(actualCmd)
            
            // 添加参数
            args?.let { cmdList.addAll(it) }
            
            Log.d(TAG, "  final cmdList: ${cmdList.joinToString(" ")}")
            
            val pb = ProcessBuilder(cmdList)
            
            // 设置工作目录
            workDir?.let { 
                val dir = File(it)
                if (dir.exists() && dir.isDirectory) {
                    pb.directory(dir)
                }
            }
            
            // 设置环境变量
            envVars?.forEach { env ->
                val idx = env.indexOf('=')
                if (idx > 0) {
                    val key = env.substring(0, idx)
                    val value = env.substring(idx + 1)
                    pb.environment()[key] = value
                }
            }
            
            // 添加必要的环境变量
            if (sysrootDir.isNotEmpty()) {
                pb.environment()["TINA_SYSROOT"] = sysrootDir
            }
            
            pb.redirectErrorStream(false)
            
            val process = pb.start()
            
            // ⚠️ MVP: 阻塞读取全部输出
            // 注意：需要在单独线程读取 stdout/stderr 避免死锁
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()
            
            val stdoutThread = Thread {
                try {
                    stdoutBuilder.append(process.inputStream.bufferedReader().readText())
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stdout: ${e.message}")
                }
            }
            val stderrThread = Thread {
                try {
                    stderrBuilder.append(process.errorStream.bufferedReader().readText())
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stderr: ${e.message}")
                }
            }
            
            stdoutThread.start()
            stderrThread.start()
            
            // 等待进程结束
            val exitCode = process.waitFor()
            
            // 等待输出线程完成
            stdoutThread.join(5000)
            stderrThread.join(5000)
            
            val stdout = stdoutBuilder.toString()
            val stderr = stderrBuilder.toString()
            
            Log.d(TAG, "  exitCode=$exitCode, stdout.len=${stdout.length}, stderr.len=${stderr.length}")
            if (stderr.isNotEmpty()) {
                Log.w(TAG, "  stderr: ${stderr.take(500)}")
            }
            
            buildJsonResult(exitCode, stdout, stderr)
            
        } catch (e: Exception) {
            Log.e(TAG, "startProcess failed", e)
            buildJsonResult(-1, "", "ProcessBridge error: ${e.message}")
        }
    }
    
    /**
     * 将 clang/lld 等命令解析为实际的 wrapper 路径
     */
    private fun resolveCommand(command: String): String? {
        val name = File(command).name
        
        // 如果是完整路径且存在，直接返回
        if (command.startsWith("/") && File(command).exists()) {
            return command
        }
        
        // 检查 nativeLibDir 是否已设置
        if (nativeLibDir.isEmpty()) {
            Log.e(TAG, "nativeLibDir not set!")
            return null
        }
        
        // 根据命令名解析 wrapper
        val wrapperName = when {
            name.contains("clang++") || name == "c++" -> "libclang_main.so"
            name.contains("clang") || name == "cc" -> "libclang_main.so"
            name == "lld" || name.contains("ld.lld") -> "liblld_main.so"
            name == "ld" -> "liblld_main.so"
            name == "ar" || name == "llvm-ar" -> "libllvm_ar.so"
            else -> null
        }
        
        if (wrapperName != null) {
            val wrapperPath = "$nativeLibDir/$wrapperName"
            if (File(wrapperPath).exists()) {
                return wrapperPath
            }
            Log.w(TAG, "Wrapper not found: $wrapperPath")
        }
        
        // 尝试在 PATH 中查找（系统命令）
        val pathDirs = System.getenv("PATH")?.split(":") ?: emptyList()
        for (dir in pathDirs) {
            val file = File(dir, name)
            if (file.exists() && file.canExecute()) {
                return file.absolutePath
            }
        }
        
        // 最后尝试原样返回（可能是系统命令如 /system/bin/sh）
        return command
    }
    
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
