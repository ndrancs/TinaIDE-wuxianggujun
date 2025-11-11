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
}
