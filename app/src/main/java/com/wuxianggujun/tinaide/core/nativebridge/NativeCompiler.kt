package com.wuxianggujun.tinaide.core.nativebridge

object NativeCompiler {
    external fun getClangVersion(): String
    external fun syntaxCheck(sysroot: String, srcPath: String, target: String, isCxx: Boolean): String

    /**
     * 使用 clang in-process 编译单个源文件为目标文件（.o）。
     * 返回空字符串表示成功；非空字符串为诊断输出/错误信息。
     */
    external fun emitObj(
        sysroot: String,
        srcPath: String,
        objOut: String,
        target: String,
        isCxx: Boolean,
        flags: Array<String>,
        includeDirs: Array<String>
    ): String

    /**
     * 使用 LLD 在进程内把 .o 链接为可执行文件（PIE）。
     * 返回空字符串为成功；否则返回诊断文本。
     */
    external fun linkExe(
        sysroot: String,
        objPath: String,
        outExe: String,
        target: String,
        isCxx: Boolean
    ): String

    /**
     * 使用 LLD 在进程内把多个 .o 链接为可执行文件（PIE）。
     * - objPaths: 所有目标文件路径
     * - libDirs: 额外库搜索路径（可为空）
     * - libs:    额外库名（不含 -l 前缀，例如 "log"）
     */
    external fun linkExeMany(
        sysroot: String,
        objPaths: Array<String>,
        outExe: String,
        target: String,
        isCxx: Boolean,
        libDirs: Array<String>,
        libs: Array<String>
    ): String

    // New: link shared objects for in-process loading
    external fun linkSo(
        sysroot: String,
        objPath: String,
        outSo: String,
        target: String,
        isCxx: Boolean
    ): String

    external fun linkSoMany(
        sysroot: String,
        objPaths: Array<String>,
        outSo: String,
        target: String,
        isCxx: Boolean,
        libDirs: Array<String>,
        libs: Array<String>
    ): String

    // New: dlopen a shared library and call an entry symbol (default: "run_main")
    external fun runShared(
        soPath: String,
        symbol: String
    ): Int

    // Run shared library in an isolated child process; returns combined output with RC prefix
    external fun runSharedIsolated(
        soPath: String,
        symbol: String,
        timeoutMs: Int
    ): String
}
