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

    /**
     * 安装 C++ xmake 项目模板
     */
    fun installCppXmakeTemplate(ctx: Context, destDir: File, projectName: String): Boolean {
        return try {
            generateMinimalCppXmake(destDir, projectName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Generate xmake template failed", e)
            false
        }
    }

    /**
     * 创建 C++ 单文件项目（不使用构建系统）
     */
    fun installCppSingleFile(destDir: File, projectName: String): Boolean {
        return try {
            generateCppSingleFile(destDir, projectName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Generate single file template failed", e)
            false
        }
    }

    /**
     * 生成 C++ 单文件项目
     */
    private fun generateCppSingleFile(destDir: File, projectName: String) {
        // 创建一个简单的 main.cpp
        val mainCpp = """
            #include <iostream>
            
            int main() {
                std::cout << "Hello, $projectName!" << std::endl;
                return 0;
            }
        """.trimIndent()
        File(destDir, "main.cpp").writeText(mainCpp)

        // 创建 README.md
        val readme = """
            # $projectName

            这是一个简单的 C++ 单文件项目。

            ## 编译运行

            使用 g++ 或 clang++ 编译：
            ```bash
            g++ main.cpp -o $projectName
            ./$projectName
            ```

            或者使用 TinaIDE 的编译功能直接运行。
        """.trimIndent()
        File(destDir, "README.md").writeText(readme)
    }

    /**
     * 生成最小 xmake C++ 项目
     */
    private fun generateMinimalCppXmake(destDir: File, projectName: String) {
        val src = File(destDir, "src").apply { mkdirs() }
        File(destDir, "include").apply { mkdirs() }

        // 创建 xmake.lua
        val xmakeLua = """
            -- xmake 项目配置
            set_project("$projectName")
            set_version("1.0.0")
            
            -- 设置 C++ 标准
            set_languages("c++17")

            -- 使用 envs 工具链（TinaIDE 内置 clang）
            set_toolchains("envs")
            
            -- 定义目标
            target("$projectName")
                set_kind("binary")
                add_files("src/*.cpp")
                add_includedirs("include")
        """.trimIndent()
        File(destDir, "xmake.lua").writeText(xmakeLua)

        // 创建 main.cpp
        val mainCpp = """
            #include <iostream>
            
            int main() {
                std::cout << "Hello, $projectName!" << std::endl;
                return 0;
            }
        """.trimIndent()
        File(src, "main.cpp").writeText(mainCpp)

        // 创建 README.md
        val readme = """
            # $projectName

            这是由 TinaIDE 生成的 xmake C++ 项目。

            ## 构建
            ```bash
            xmake
            ```

            ## 运行
            ```bash
            xmake run
            ```

            ## 清理
            ```bash
            xmake clean
            ```

            ## 配置
            ```bash
            xmake f --toolchain=envs --cc=clang --cxx=clang++
            ```
        """.trimIndent()
        File(destDir, "README.md").writeText(readme)

        // 创建 .gitignore
        val gitignore = """
            # xmake
            .xmake/
            build/
            
            # IDE
            .vscode/
            .idea/
            *.swp
            *.swo
            *~
        """.trimIndent()
        File(destDir, ".gitignore").writeText(gitignore)
    }

}
