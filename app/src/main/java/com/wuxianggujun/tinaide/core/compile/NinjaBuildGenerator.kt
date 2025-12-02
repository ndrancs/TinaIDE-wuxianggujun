package com.wuxianggujun.tinaide.core.compile

import java.io.File

/**
 * 自动生成 build.ninja 文件，用于构建 C/C++ 项目
 * 
 * 这个类可以替代 CMake 的配置步骤，直接生成 Ninja 构建文件
 */
class NinjaBuildGenerator {
    
    data class BuildConfig(
        val projectDir: File,
        val buildDir: File,
        val targetName: String,
        val abi: String = "arm64-v8a",
        val apiLevel: Int = 28,
        val cppStandard: String = "c++17",
        val includeDirs: List<File> = emptyList(),
        val libraries: List<String> = emptyList(),
        val cflags: List<String> = emptyList(),
        val cxxflags: List<String> = emptyList(),
        val ldflags: List<String> = emptyList()
    )
    
    /**
     * 生成 build.ninja 文件
     */
    fun generateBuildNinja(config: BuildConfig): File {
        val sourceFiles = findSourceFiles(config.projectDir)
        if (sourceFiles.isEmpty()) {
            throw IllegalStateException("No source files found in ${config.projectDir}")
        }
        
        val ndkPath = findNdkPath()
        val content = buildNinjaContent(config, sourceFiles, ndkPath)
        
        return File(config.buildDir, "build.ninja").apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }
    
    /**
     * 查找项目中的所有源文件
     */
    private fun findSourceFiles(projectDir: File): List<File> {
        val sourceExtensions = setOf("c", "cpp", "cc", "cxx", "c++")
        return projectDir.walkTopDown()
            .filter { it.isFile && it.extension in sourceExtensions }
            .toList()
    }
    
    /**
     * 查找 NDK 路径
     */
    private fun findNdkPath(): String {
        // 尝试从环境变量获取
        System.getenv("ANDROID_NDK_HOME")?.let { return it }
        System.getenv("NDK_HOME")?.let { return it }
        
        // 尝试从 SDK 目录获取
        val sdkPath = System.getenv("ANDROID_SDK_ROOT") 
            ?: System.getenv("ANDROID_HOME")
            ?: "/opt/android-sdk"
        
        val ndkDir = File(sdkPath, "ndk")
        if (ndkDir.exists()) {
            // 使用最新版本的 NDK
            val latestNdk = ndkDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }
            
            if (latestNdk != null) {
                return latestNdk.absolutePath
            }
        }
        
        throw IllegalStateException("NDK not found. Please set ANDROID_NDK_HOME environment variable.")
    }
    
    /**
     * 生成 build.ninja 文件内容
     */
    private fun buildNinjaContent(
        config: BuildConfig,
        sourceFiles: List<File>,
        ndkPath: String
    ): String = buildString {
        appendLine("# Generated build.ninja for ${config.targetName}")
        appendLine("# Generated at: ${System.currentTimeMillis()}")
        appendLine()
        
        // Variables
        appendLine("# Paths")
        appendLine("project_dir = ${config.projectDir.absolutePath}")
        appendLine("build_dir = ${config.buildDir.absolutePath}")
        appendLine("ndk_path = $ndkPath")
        appendLine()
        
        // Toolchain
        val triple = when (config.abi) {
            "arm64-v8a" -> "aarch64-linux-android"
            "armeabi-v7a" -> "armv7a-linux-androideabi"
            "x86" -> "i686-linux-android"
            "x86_64" -> "x86_64-linux-android"
            else -> throw IllegalArgumentException("Unsupported ABI: ${config.abi}")
        }
        
        appendLine("# Toolchain")
        appendLine("cc = \$ndk_path/toolchains/llvm/prebuilt/linux-x86_64/bin/$triple${config.apiLevel}-clang")
        appendLine("cxx = \$ndk_path/toolchains/llvm/prebuilt/linux-x86_64/bin/$triple${config.apiLevel}-clang++")
        appendLine("ar = \$ndk_path/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar")
        appendLine()
        
        // Flags
        val includePaths = config.includeDirs.joinToString(" ") { "-I${it.absolutePath}" }
        val baseFlags = listOf("-fPIC", "-ffunction-sections", "-fdata-sections") + config.cflags
        val baseCxxFlags = baseFlags + listOf("-std=${config.cppStandard}") + config.cxxflags
        val linkLibs = config.libraries.joinToString(" ") { "-l$it" }
        val linkFlags = listOf("-shared", "-Wl,--gc-sections") + config.ldflags
        
        appendLine("# Compiler flags")
        appendLine("cflags = ${baseFlags.joinToString(" ")} $includePaths")
        appendLine("cxxflags = ${baseCxxFlags.joinToString(" ")} $includePaths")
        appendLine("ldflags = ${linkFlags.joinToString(" ")} $linkLibs")
        appendLine()
        
        // Rules
        appendLine("# Build rules")
        appendLine("rule cc")
        appendLine("  command = \$cc \$cflags -MMD -MF \$out.d -c \$in -o \$out")
        appendLine("  description = CC \$out")
        appendLine("  depfile = \$out.d")
        appendLine("  deps = gcc")
        appendLine()
        
        appendLine("rule cxx")
        appendLine("  command = \$cxx \$cxxflags -MMD -MF \$out.d -c \$in -o \$out")
        appendLine("  description = CXX \$out")
        appendLine("  depfile = \$out.d")
        appendLine("  deps = gcc")
        appendLine()
        
        appendLine("rule link")
        appendLine("  command = \$cxx \$ldflags \$in -o \$out")
        appendLine("  description = LINK \$out")
        appendLine()
        
        // Build targets
        appendLine("# Build targets")
        val objFiles = mutableListOf<String>()
        
        sourceFiles.forEach { src ->
            val relativePath = src.relativeTo(config.projectDir).path
            val objName = relativePath.replace(File.separatorChar, '_').replace('.', '_') + ".o"
            val objPath = "\$build_dir/obj/$objName"
            objFiles.add(objPath)
            
            val rule = when (src.extension) {
                "c" -> "cc"
                else -> "cxx"
            }
            
            appendLine("build $objPath: $rule ${src.absolutePath}")
        }
        appendLine()
        
        // Link
        val outputLib = "\$build_dir/lib${config.targetName}.so"
        appendLine("build $outputLib: link ${objFiles.joinToString(" ")}")
        appendLine()
        
        // Default target
        appendLine("# Default target")
        appendLine("default $outputLib")
    }
}
