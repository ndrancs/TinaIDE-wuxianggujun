package com.wuxianggujun.tinaide.project

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ProjectTemplateInstaller {
    private const val TAG = "ProjectTemplate"

    fun installCppCMakeTemplate(ctx: Context, destDir: File, projectName: String): Boolean {
        // 1) 尝试从 assets 解压模板
        val okFromAssets = try {
            ctx.assets.open("templates/cpp_cmake.zip").use { input ->
                unzipToDir(input, destDir)
            }
            true
        } catch (_: Throwable) { false }

        if (okFromAssets) {
            // 2) 占位符替换
            try {
                replacePlaceholders(destDir, mapOf(
                    "__PROJECT_NAME__" to projectName
                ))
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Placeholder replace failed, fallback to generate", e)
            }
        }

        // 3) 回退：生成最小 CMake 项目
        return try {
            generateMinimalCppCMake(destDir, projectName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Generate minimal template failed", e)
            false
        }
    }

    private fun unzipToDir(input: InputStream, destDir: File) {
        ZipInputStream(input).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            val buffer = ByteArray(64 * 1024)
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var r = zis.read(buffer)
                        while (r > 0) {
                            fos.write(buffer, 0, r)
                            r = zis.read(buffer)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun replacePlaceholders(root: File, replacements: Map<String, String>) {
        if (!root.exists()) return
        root.walkTopDown().forEach { f ->
            if (f.isFile && isTextCandidate(f.name)) {
                runCatching {
                    val content = f.readText()
                    var newContent = content
                    replacements.forEach { (k, v) -> newContent = newContent.replace(k, v) }
                    if (newContent != content) f.writeText(newContent)
                }
            }
        }
    }

    private fun isTextCandidate(name: String): Boolean {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in setOf("txt", "md", "cmake", "cpp", "cc", "cxx", "c", "h", "hpp", "gitignore") ||
               name.equals("CMakeLists.txt", true)
    }

    private fun generateMinimalCppCMake(destDir: File, projectName: String) {
        val src = File(destDir, "src").apply { mkdirs() }
        File(destDir, "include").apply { mkdirs() }
        File(destDir, "build").apply { mkdirs() }

        val cmake = """
            cmake_minimum_required(VERSION 3.10)
            project($projectName)
            add_executable(${projectName} src/main.cpp)
        """.trimIndent()
        File(destDir, "CMakeLists.txt").writeText(cmake)

        val mainCpp = """
            #include <iostream>
            int main() {
                std::cout << "Hello, $projectName!" << std::endl;
                return 0;
            }
        """.trimIndent()
        File(src, "main.cpp").writeText(mainCpp)

        val readme = """
            # $projectName

            这是由 TinaIDE 生成的最小 CMake C++ 项目。

            构建：
            ```bash
            cmake -S . -B build && cmake --build build
            ```
            运行：
            ```bash
            ./build/$projectName
            ```
        """.trimIndent()
        File(destDir, "README.md").writeText(readme)
    }
}

