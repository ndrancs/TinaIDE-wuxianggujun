package com.wuxianggujun.tinaide.core.util

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 启动策略回归测试：防止 SDK 门槛、direct exec、shell 转义这类历史漂移再次出现。
 */
class NativeExecutableRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val nonExistentElf = "/data/user/0/com.example/files/run-bin/main.abcdef"

    @Test
    fun `shouldPreferLinker64 enables linker path from API 29`() {
        assertThat(NativeExecutableRunner.shouldPreferLinker64(28)).isFalse()
        assertThat(NativeExecutableRunner.shouldPreferLinker64(29)).isTrue()
    }

    @Test
    fun `shouldPreferLinker64 keeps direct exec below API 29`() {
        assertThat(NativeExecutableRunner.shouldPreferLinker64(27)).isFalse()
        assertThat(NativeExecutableRunner.shouldPreferLinker64(28)).isFalse()
    }

    @Test
    fun `describeLinker64PreferenceDecision explains API 29 cutoff`() {
        assertThat(NativeExecutableRunner.describeLinker64PreferenceDecision(29))
            .contains("sdk>=29")
        assertThat(NativeExecutableRunner.describeLinker64PreferenceDecision(28))
            .contains("sdk<29")
    }

    @Test
    fun `describeLaunchMode reports linker shell and direct distinctly`() {
        assertThat(
            NativeExecutableRunner.describeLaunchMode(
                executable = nonExistentElf,
                fullCommand = listOf("/system/bin/linker64", nonExistentElf)
            )
        ).isEqualTo("linker64")
        assertThat(
            NativeExecutableRunner.describeLaunchMode(
                executable = nonExistentElf,
                fullCommand = listOf("/system/bin/sh", nonExistentElf)
            )
        ).isEqualTo("shell")
        assertThat(
            NativeExecutableRunner.describeLaunchMode(
                executable = nonExistentElf,
                fullCommand = listOf(nonExistentElf)
            )
        ).isEqualTo("direct")
    }

    @Test
    fun `buildExecutionDiagnosticSummary includes launcher command and executable kind`() {
        val summary = NativeExecutableRunner.buildExecutionDiagnosticSummary(
            sdkInt = 29,
            executable = nonExistentElf,
            args = listOf("--version"),
            fullCommand = listOf("/system/bin/linker64", nonExistentElf, "--version"),
            workingDir = File("/workspace/project")
        )

        assertThat(summary).contains("sdk=29")
        assertThat(summary).contains("preferLinker64=true")
        assertThat(summary).contains("launchMode=linker64")
        assertThat(summary).contains("executableKind=ELF")
        assertThat(summary).contains("launcher=/system/bin/linker64")
        assertThat(summary).contains("executable=$nonExistentElf")
        assertThat(summary).contains("argsCount=1")
        assertThat(summary).contains("fullCommand=/system/bin/linker64 $nonExistentElf --version")
    }

    @Test
    fun `configureEnvironment disables LLVM linker wrapping for API 28 strategy`() {
        val builder = ProcessBuilder("clang++")

        NativeExecutableRunner.configureEnvironment(
            builder = builder,
            nativeLibDir = "/native/lib",
            llvmWrapExecLinker64 = false
        )

        assertThat(builder.environment()[NativeExecutableRunner.ENV_LLVM_WRAP_EXEC_LINKER64])
            .isEqualTo("0")
    }

    @Test
    fun `configureEnvironment enables LLVM linker wrapping when launcher prefers linker64`() {
        val builder = ProcessBuilder("clang++")

        NativeExecutableRunner.configureEnvironment(
            builder = builder,
            nativeLibDir = "/native/lib",
            llvmWrapExecLinker64 = true
        )

        assertThat(builder.environment()[NativeExecutableRunner.ENV_LLVM_WRAP_EXEC_LINKER64])
            .isEqualTo("1")
    }

    @Test
    fun `configureEnvironment disables LLVM exec trace by default`() {
        val builder = ProcessBuilder("clang++")

        NativeExecutableRunner.configureEnvironment(
            builder = builder,
            nativeLibDir = "/native/lib"
        )

        assertThat(builder.environment()[NativeExecutableRunner.ENV_LLVM_EXEC_TRACE])
            .isEqualTo("0")
    }

    @Test
    fun `configureEnvironment keeps explicit LLVM exec trace override`() {
        val builder = ProcessBuilder("clang++")
        builder.environment()[NativeExecutableRunner.ENV_LLVM_EXEC_TRACE] = "1"

        NativeExecutableRunner.configureEnvironment(
            builder = builder,
            nativeLibDir = "/native/lib"
        )

        assertThat(builder.environment()[NativeExecutableRunner.ENV_LLVM_EXEC_TRACE])
            .isEqualTo("1")
    }

    @Test
    fun `buildShellSnippet wraps ELF with linker64 when preferLinker64 is true`() {
        val snippet = NativeExecutableRunner.buildShellSnippet(
            executable = nonExistentElf,
            args = emptyList(),
            preferLinker64 = true
        )

        assertThat(snippet).startsWith("'/system/bin/linker64'")
        assertThat(snippet).contains("'$nonExistentElf'")
    }

    @Test
    fun `buildShellSnippet emits plain path when preferLinker64 is false`() {
        val snippet = NativeExecutableRunner.buildShellSnippet(
            executable = nonExistentElf,
            args = emptyList(),
            preferLinker64 = false
        )

        assertThat(snippet).isEqualTo("'$nonExistentElf'")
        assertThat(snippet).doesNotContain("linker64")
    }

    @Test
    fun `buildShellSnippet shell-quotes every argument`() {
        val snippet = NativeExecutableRunner.buildShellSnippet(
            executable = nonExistentElf,
            args = listOf("--flag", "value with space", "has'quote"),
            preferLinker64 = true
        )

        assertThat(snippet).contains("'--flag'")
        assertThat(snippet).contains("'value with space'")
        // 单引号按 POSIX '"'"' 方式转义，不允许出现裸单引号中断
        assertThat(snippet).contains("'has'\"'\"'quote'")
    }

    @Test
    fun `buildShellSnippet returns same result as buildCommand joined`() {
        val argv = NativeExecutableRunner.buildCommand(
            executable = nonExistentElf,
            args = listOf("a", "b c"),
            preferLinker64 = true
        )
        val snippet = NativeExecutableRunner.buildShellSnippet(
            executable = nonExistentElf,
            args = listOf("a", "b c"),
            preferLinker64 = true
        )

        val expected = argv.joinToString(" ") { NativeExecutableRunner.shellQuotePosix(it) }
        assertThat(snippet).isEqualTo(expected)
    }

    @Test
    fun `shellQuotePosix wraps empty string in quotes`() {
        assertThat(NativeExecutableRunner.shellQuotePosix("")).isEqualTo("''")
    }

    @Test
    fun `shellQuotePosix wraps simple value in single quotes`() {
        assertThat(NativeExecutableRunner.shellQuotePosix("abc")).isEqualTo("'abc'")
    }

    @Test
    fun `shellQuotePosix escapes internal single quote`() {
        assertThat(NativeExecutableRunner.shellQuotePosix("a'b"))
            .isEqualTo("'a'\"'\"'b'")
    }

    @Test
    fun `buildCommand falls through for relative path even with preferLinker64`() {
        // 相对路径不会被 linker64 包裹（buildCommand 里的 !startsWith("/") 早返分支）
        val command = NativeExecutableRunner.buildCommand(
            executable = "clang",
            args = listOf("--version"),
            preferLinker64 = true
        )
        assertThat(command).containsExactly("clang", "--version").inOrder()
    }

    @Test
    fun `buildCommand keeps already linker-wrapped command unchanged`() {
        val command = NativeExecutableRunner.buildCommand(
            executable = "/system/bin/linker64",
            args = listOf(nonExistentElf, "--version"),
            preferLinker64 = true
        )

        assertThat(command).containsExactly(
            "/system/bin/linker64",
            nonExistentElf,
            "--version"
        ).inOrder()
    }

    // -------- ExecutableKind / shebang 源文件探测 --------

    @Test
    fun `ExecutableKind probe returns SHEBANG_SCRIPT for file starting with hash-bang`() {
        val script = tempFolder.newFile("wrapper.sh").apply {
            writeText("#!/bin/sh\necho hi\n")
        }
        assertThat(NativeExecutableRunner.ExecutableKind.probe(script))
            .isEqualTo(NativeExecutableRunner.ExecutableKind.SHEBANG_SCRIPT)
    }

    @Test
    fun `ExecutableKind probe returns ELF for file not starting with hash-bang`() {
        val elf = tempFolder.newFile("main").apply {
            // 模拟 ELF header 起始两字节
            writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        }
        assertThat(NativeExecutableRunner.ExecutableKind.probe(elf))
            .isEqualTo(NativeExecutableRunner.ExecutableKind.ELF)
    }

    @Test
    fun `ExecutableKind probe defaults to ELF for nonexistent file`() {
        val missing = File(tempFolder.root, "does-not-exist")
        assertThat(missing.exists()).isFalse()
        assertThat(NativeExecutableRunner.ExecutableKind.probe(missing))
            .isEqualTo(NativeExecutableRunner.ExecutableKind.ELF)
    }

    @Test
    fun `buildShellSnippet with explicit SHEBANG_SCRIPT kind uses bin sh even if target is non-existent`() {
        // stage-copy 场景：目标路径文件尚未创建，自动探测会退化为 ELF；
        // 显式传入 kind 确保脚本不会被误套 linker64。
        val snippet = NativeExecutableRunner.buildShellSnippet(
            executable = nonExistentElf,
            args = emptyList(),
            kind = NativeExecutableRunner.ExecutableKind.SHEBANG_SCRIPT,
            preferLinker64 = true
        )

        assertThat(snippet).startsWith("'/system/bin/sh'")
        assertThat(snippet).contains("'$nonExistentElf'")
        assertThat(snippet).doesNotContain("linker64")
    }

    @Test
    fun `buildShellSnippet with explicit ELF kind always wraps with linker64 on preferLinker64`() {
        val snippet = NativeExecutableRunner.buildShellSnippet(
            executable = nonExistentElf,
            args = emptyList(),
            kind = NativeExecutableRunner.ExecutableKind.ELF,
            preferLinker64 = true
        )

        assertThat(snippet).startsWith("'/system/bin/linker64'")
        assertThat(snippet).contains("'$nonExistentElf'")
    }

    @Test
    fun `buildCommand with explicit SHEBANG_SCRIPT kind produces sh invocation`() {
        val command = NativeExecutableRunner.buildCommand(
            executable = nonExistentElf,
            args = listOf("--arg"),
            kind = NativeExecutableRunner.ExecutableKind.SHEBANG_SCRIPT,
            preferLinker64 = true
        )
        assertThat(command).containsExactly("/system/bin/sh", nonExistentElf, "--arg").inOrder()
    }
}
