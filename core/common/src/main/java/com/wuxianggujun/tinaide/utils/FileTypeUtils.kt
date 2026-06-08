package com.wuxianggujun.tinaide.utils

import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import java.io.File

/**
 * 文件类型判断工具类
 */
object FileTypeUtils {

    /**
     * 图片文件扩展名
     */
    private val IMAGE_EXTENSIONS = setOf(
        "png",
        "jpg",
        "jpeg",
        "gif",
        "webp",
        "bmp",
        "svg",
        "ico"
    )

    /**
     * JSON 文件扩展名
     */
    private val JSON_EXTENSIONS = setOf("json")

    /**
     * 代码文件扩展名
     */
    private val CODE_EXTENSIONS: Set<String> =
        CxxFileSupport.editorRelatedExtensions + setOf(
            "java", "kt", "kts",
            "xml", "yaml", "yml", "toml",
            "md", "txt", "log",
            "gradle", "properties",
            "sh", "bash", "zsh",
            "py", "js", "ts", "html", "css",
            "cmake"
        )

    /**
     * 判断文件类型
     */
    fun getFileType(file: File): FileType {
        val ext = file.extension.lowercase()
        val fileName = file.name.lowercase()

        return when {
            ext in IMAGE_EXTENSIONS -> FileType.IMAGE
            ext in JSON_EXTENSIONS -> FileType.JSON
            ext in CODE_EXTENSIONS -> FileType.CODE
            fileName == "cmakelists.txt" -> FileType.CODE
            isCppStandardHeader(file) -> FileType.CODE // C++ 标准库头文件
            isTextFile(file) -> FileType.CODE // 文本文件用代码编辑器打开
            else -> FileType.BINARY
        }
    }

    /**
     * 检查文件是否是 C++ 标准库头文件（无后缀）
     *
     * C++ 标准库头文件通常没有扩展名，如 iostream, vector, string 等
     * 这些文件通常位于系统 include 目录中
     */
    fun isCppStandardHeader(file: File): Boolean {
        // 没有扩展名
        if (file.extension.isNotEmpty()) return false

        val fileName = file.name
        val parentPath = file.parent?.lowercase() ?: ""

        // 检查是否在系统 include 目录中
        val isInIncludeDir = parentPath.contains("/include/") ||
            parentPath.contains("/c++/") ||
            parentPath.contains("/bits/") ||
            parentPath.contains("/ext/") ||
            parentPath.endsWith("/include")

        if (!isInIncludeDir) return false

        // 常见的 C++ 标准库头文件名
        val cppStandardHeaders = setOf(
            // I/O 流
            "iostream", "istream", "ostream", "fstream", "sstream", "streambuf",
            "ios", "iosfwd", "iomanip",
            // 容器
            "vector", "list", "deque", "array", "forward_list",
            "set", "map", "unordered_set", "unordered_map",
            "stack", "queue", "bitset",
            // 字符串
            "string", "string_view", "cstring", "cwchar", "cctype", "cwctype",
            // 算法和迭代器
            "algorithm", "iterator", "functional", "numeric",
            // 内存
            "memory", "new", "scoped_allocator", "memory_resource",
            // 工具
            "utility", "tuple", "optional", "variant", "any",
            "type_traits", "typeinfo", "typeindex",
            "initializer_list", "compare",
            // 数值
            "cmath", "complex", "valarray", "random", "ratio", "cfloat", "climits", "cstdint",
            // 时间
            "chrono", "ctime",
            // 异常
            "exception", "stdexcept", "system_error", "cerrno",
            // 线程
            "thread", "mutex", "shared_mutex", "condition_variable", "future", "atomic",
            // 文件系统
            "filesystem",
            // 其他
            "limits", "locale", "codecvt", "regex", "cassert", "csignal", "csetjmp",
            "cstdlib", "cstddef", "cstdarg", "cstdio", "cinttypes", "cuchar",
            // C++ 20
            "concepts", "coroutine", "span", "ranges", "numbers", "bit", "version", "source_location",
            // 内部头文件
            "stl_vector", "stl_list", "stl_map", "stl_set", "stl_deque",
            "stl_iterator", "stl_algo", "stl_function", "stl_pair", "stl_tree",
            "basic_string", "char_traits", "allocator", "move"
        )

        // 检查文件名是否匹配（不区分大小写）
        if (fileName.lowercase() in cppStandardHeaders) return true

        // 检查是否以 c 开头（C 兼容头文件，如 cstdio, cstring）
        if (fileName.startsWith("c") && fileName.length > 1 && !fileName.contains(".")) {
            return true
        }

        // 检查是否以 stl_ 或 __开头（内部实现头文件）
        if (fileName.startsWith("stl_") || fileName.startsWith("__")) {
            return true
        }

        return false
    }

    /**
     * 检测文件是否为文本文件
     * 读取前 8KB 检测是否包含 NULL 字节
     */
    fun isTextFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (file.length() == 0L) return true

        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(minOf(8192, file.length().toInt()))
                val bytesRead = input.read(buffer)
                if (bytesRead <= 0) return true

                // 检查是否包含 NULL 字节（二进制文件的特征）
                for (i in 0 until bytesRead) {
                    if (buffer[i] == 0.toByte()) return false
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 文件类型枚举
     */
    enum class FileType {
        CODE, // 代码/文本文件
        IMAGE, // 图片文件
        JSON, // JSON 文件
        BINARY // 二进制文件
    }
}
