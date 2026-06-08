package com.wuxianggujun.tinaide.core.compile

import java.io.File

/**
 * Make 项目的输出定位器。
 *
 * Android 公有目录上的 ELF 产物通常没有可执行位，直接依赖 [File.canExecute]
 * 会把真实产物误判成“未找到输出”。这里统一按 ELF 头和常见目标命名规则判断。
 */
internal object MakeBuildOutputLocator {

    private val excludedNames = setOf("Makefile", "makefile", "GNUmakefile", ".gitignore")
    private val excludedExtensions = setOf(
        "c", "cc", "cpp", "cxx",
        "h", "hh", "hpp", "hxx",
        "s", "asm",
        "o", "obj", "a", "so",
        "d", "mk", "cmake", "ninja",
        "txt", "md", "json", "xml", "gradle", "kts", "properties"
    )

    fun findExecutable(
        projectRoot: File,
        buildDir: File,
        target: String?,
        makefile: File?
    ): String? {
        val candidates = linkedSetOf<File>()

        if (!target.isNullOrBlank()) {
            candidates += File(buildDir, target)
            candidates += File(buildDir, File(target).name)
            candidates += File(projectRoot, target)
            candidates += File(projectRoot, File(target).name)
        }

        parseTargetFromMakefile(makefile)?.let { parsedTarget ->
            candidates += File(buildDir, parsedTarget)
            candidates += File(buildDir, File(parsedTarget).name)
            candidates += File(projectRoot, parsedTarget)
            candidates += File(projectRoot, File(parsedTarget).name)
        }

        candidates.firstNotNullOfOrNull(::matchArtifact)?.let { return it.absolutePath }

        findNewestArtifact(buildDir, recursive = true)?.let { return it.absolutePath }
        findNewestArtifact(projectRoot)?.let { return it.absolutePath }
        return null
    }

    internal fun parseTargetFromMakefile(makefile: File?): String? {
        if (makefile == null || !makefile.isFile) return null
        return runCatching {
            val content = makefile.readText()
            Regex("""^\s*TARGET\s*[:?]?=\s*(\S+)""", RegexOption.MULTILINE)
                .find(content)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    internal fun isRunnableArtifact(file: File): Boolean {
        if (!file.isFile || !file.exists()) return false
        if (file.name in excludedNames || file.name.startsWith(".")) return false
        if (file.extension.lowercase() in excludedExtensions) return false
        return file.canExecute() || hasElfMagic(file)
    }

    private fun matchArtifact(file: File): File? = file.takeIf(::isRunnableArtifact)

    private fun findNewestArtifact(dir: File, recursive: Boolean = false): File? {
        if (!dir.isDirectory) return null
        val files = if (recursive) {
            dir.walkTopDown()
                .maxDepth(4)
                .filter { it.isFile }
        } else {
            dir.listFiles()?.asSequence().orEmpty()
        }
        return files
            .filter(::isRunnableArtifact)
            .maxByOrNull { it.lastModified() }
    }

    private fun hasElfMagic(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            if (input.read(header) != 4) {
                false
            } else {
                header[0] == 0x7F.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        }
    }.getOrDefault(false)
}
