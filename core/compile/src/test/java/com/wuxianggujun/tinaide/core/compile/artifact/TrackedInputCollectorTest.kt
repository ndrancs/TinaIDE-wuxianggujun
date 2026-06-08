package com.wuxianggujun.tinaide.core.compile.artifact

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackedInputCollectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `make collector includes makefile and deep source while excluding build outputs`() {
        val projectRoot = tempFolder.newFolder("make-project")
        val makefile = File(projectRoot, "Makefile").apply { writeText("all:\n\t@true\n") }
        val deepSource = File(projectRoot, "src/deep/nested/main.c").apply {
            parentFile?.mkdirs()
            writeText("int main() { return 0; }")
        }
        File(projectRoot, "build/generated.c").apply {
            parentFile?.mkdirs()
            writeText("int generated() { return 1; }")
        }

        val tracked = TrackedInputCollector.collectMakeInputs(projectRoot, setOf("c", "h"))

        assertThat(tracked).contains(makefile)
        assertThat(tracked).contains(deepSource)
        assertThat(tracked).doesNotContain(File(projectRoot, "build/generated.c"))
    }

    @Test
    fun `cmake collector includes target source and cmake scripts`() {
        val projectRoot = tempFolder.newFolder("cmake-project")
        val targetSource = File(projectRoot, "src/main.cpp").apply {
            parentFile?.mkdirs()
            writeText("int main() { return 0; }")
        }
        val rootCmake = File(projectRoot, "CMakeLists.txt").apply { writeText("add_executable(app src/main.cpp)") }
        val moduleCmake = File(projectRoot, "cmake/toolchain/custom.cmake").apply {
            parentFile?.mkdirs()
            writeText("set(CMAKE_CXX_STANDARD 20)")
        }

        val tracked = TrackedInputCollector.collectCMakeInputs(projectRoot, listOf(targetSource))

        assertThat(tracked).contains(targetSource)
        assertThat(tracked).contains(rootCmake)
        assertThat(tracked).contains(moduleCmake)
    }
}
