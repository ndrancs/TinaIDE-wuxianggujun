package com.wuxianggujun.tinaide.core.lsp

import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition

/**
 * clangd 语言服务器定义
 * 用于 C/C++ 文件的 LSP 支持
 */
class ClangdServerDefinition(
    private val clangdPath: String,
    private val extraArgs: List<String> = emptyList()
) : CustomLanguageServerDefinition(
    ext = "cpp",
    languageIds = mapOf(
        "c" to "c",
        "h" to "c",
        "cpp" to "cpp",
        "cc" to "cpp",
        "cxx" to "cpp",
        "hpp" to "cpp",
        "hxx" to "cpp",
        "h++" to "cpp"
    ),
    serverConnectProvider = ServerConnectProvider { workingDir ->
        ClangdConnectionProvider(clangdPath, workingDir, extraArgs)
    }
) {
    companion object {
        /**
         * 支持的 C/C++ 文件扩展名
         */
        val SUPPORTED_EXTENSIONS = setOf("c", "h", "cpp", "cc", "cxx", "hpp", "hxx", "h++")

        /**
         * 判断文件是否为 C/C++ 文件
         */
        fun isCppFile(path: String): Boolean {
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext in SUPPORTED_EXTENSIONS
        }
    }

    override fun createConnectionProvider(workingDir: String): StreamConnectionProvider {
        return ClangdConnectionProvider(clangdPath, workingDir, extraArgs)
    }
}