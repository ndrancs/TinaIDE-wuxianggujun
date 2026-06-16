package com.wuxianggujun.tinaide.core.lang

import java.io.File

/**
 * C/C++ 相关文件能力注册表（单一数据源）。
 *
 * 目的：
 * - 统一扩展名集合，避免散落在多个模块中导致漏改
 * - 明确不同场景的能力边界（编译/LSP/编辑器特性）
 */
object CxxFileSupport {

    /** C 源文件（translation unit） */
    val cSourceExtensions: Set<String> = setOf("c")

    /** C++ 源文件/模块单元（translation unit） */
    val cxxSourceExtensions: Set<String> = setOf("cpp", "cc", "cxx", "cppm", "ixx", "mpp")

    /** Objective-C 源文件（clangd 支持，但编译链路未必支持） */
    val objcSourceExtensions: Set<String> = setOf("m")

    /** Objective-C++ 源文件（clangd 支持，但编译链路未必支持） */
    val objcxxSourceExtensions: Set<String> = setOf("mm")

    /** 常见头文件扩展名 */
    val headerExtensions: Set<String> = setOf("h", "hpp", "hh", "hxx", "inl")

    /** clangd 可直接作为 translation unit 处理的扩展名（不包含头文件） */
    val clangdTranslationUnitExtensions: Set<String> =
        cSourceExtensions + cxxSourceExtensions + objcSourceExtensions + objcxxSourceExtensions

    /** clangd LSP 支持扩展名（包含头文件） */
    val clangdSupportedExtensions: Set<String> =
        clangdTranslationUnitExtensions + headerExtensions

    /** 编辑器 C/C++ 相关能力（高亮、断点、导航等） */
    val editorRelatedExtensions: Set<String> = clangdSupportedExtensions

    /** 单文件构建策略支持的源文件扩展名（不包含 Objective-C/Objective-C++） */
    val singleFileBuildSourceExtensions: Set<String> = cSourceExtensions + cxxSourceExtensions

    fun extensionOf(file: File): String = file.extension.lowercase()

    fun isHeaderExtension(ext: String): Boolean = ext.lowercase() in headerExtensions

    fun isCSourceExtension(ext: String): Boolean = ext.lowercase() in cSourceExtensions

    fun isCxxSourceExtension(ext: String): Boolean = ext.lowercase() in cxxSourceExtensions

    fun isSingleFileBuildSourceExtension(ext: String): Boolean = ext.lowercase() in singleFileBuildSourceExtensions

    fun isClangdTranslationUnitExtension(ext: String): Boolean = ext.lowercase() in clangdTranslationUnitExtensions

    fun isClangdSupportedExtension(ext: String): Boolean = ext.lowercase() in clangdSupportedExtensions
}
