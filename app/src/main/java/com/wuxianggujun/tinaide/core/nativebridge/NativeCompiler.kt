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

    // Run shared library in an isolated child process; returns structured execution result
    external fun runSharedIsolated(
        soPath: String,
        symbol: String,
        timeoutMs: Int
    ): RunExecutionResult

    // ============================================================================
    // Clangd LSP Server Support
    // ============================================================================

    /**
     * 启动 clangd 服务器（从共享库加载）
     * @param libPath libclangd.so 的完整路径
     * @return 空字符串表示成功，否则返回错误信息
     */
    external fun startClangd(
        libPath: String,
        args: Array<String> = emptyArray()
    ): String

    /**
     * 停止 clangd 服务器
     */
    external fun stopClangd()

    /**
     * 检查 clangd 是否正在运行
     * @return true 如果 clangd 正在运行
     */
    external fun isClangdRunning(): Boolean

    /**
     * 向 clangd 写入数据
     * @param data 要写入的字节数组
     * @return 写入的字节数，错误时返回 -1
     */
    external fun writeToClangd(data: ByteArray): Int

    /**
     * 从 clangd 读取数据（非阻塞）
     * @param maxBytes 最大读取字节数
     * @return 读取的数据，如果没有数据可用则返回 null
     */
    external fun readFromClangd(maxBytes: Int): ByteArray?

    /**
     * 从 clangd 读取数据（带超时）
     * @param maxBytes 最大读取字节数
     * @param timeoutMs 超时时间（毫秒）
     * @return 读取的数据，如果超时或错误则返回 null
     */
    external fun readFromClangdWithTimeout(maxBytes: Int, timeoutMs: Int): ByteArray?
}
