package com.wuxianggujun.tinaide.core.compile.toolchain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolchainLinker64ShimManagerTest {

    @Test
    fun `buildShimScript exports real binary path for LLVM self detection`() {
        val script = ToolchainLinker64ShimManager.buildShimScript(
            realBinaryPath = "/data/user/0/com.example/files/toolchain/bin/clang++",
            linker64Path = "/system/bin/linker64"
        )

        assertThat(script).contains("REAL_BIN='/data/user/0/com.example/files/toolchain/bin/clang++'")
        assertThat(script).contains("export ${ToolchainLinker64ShimManager.ENV_TINA_PROC_SELF_EXE}=\"\$REAL_BIN\"")
        assertThat(script).contains("exec \"\$LINKER64\" \"\$REAL_BIN\" \"\$@\"")
    }

    @Test
    fun `buildShimScript shell-quotes paths with single quote`() {
        val script = ToolchainLinker64ShimManager.buildShimScript(
            realBinaryPath = "/data/user/0/com.example/files/tool'chain/bin/clang",
            linker64Path = "/system/bin/linker64"
        )

        assertThat(script).contains("REAL_BIN='/data/user/0/com.example/files/tool'\"'\"'chain/bin/clang'")
    }

    @Test
    fun `buildDirectShellShimScript execs real binary without linker64`() {
        val script = ToolchainLinker64ShimManager.buildDirectShellShimScript(
            realBinaryPath = "/data/user/0/com.example/files/toolchain/bin/ninja"
        )

        assertThat(script).contains("#!/system/bin/sh")
        assertThat(script).contains("REAL_BIN='/data/user/0/com.example/files/toolchain/bin/ninja'")
        assertThat(script).contains("exec \"\$REAL_BIN\" \"\$@\"")
        assertThat(script).doesNotContain("linker64")
    }
}
