package com.wuxianggujun.tinaide.project

import java.io.File

object ProjectApkExportSupportResolver {

    private const val MAX_SCANNED_TEXT_FILES = 160
    private val terminalSourceExtensions = setOf("c", "cc", "cpp", "cxx")
    private val terminalMainEntryRegex = Regex("""(?m)^\s*(?:int|auto|void)\s+main\s*\(""")
    private val excludedDirNames = setOf(
        ".git",
        ".gradle",
        ".idea",
        ".tinaide",
        ".vscode",
        "build",
        "out",
        "cmake-build-debug",
        "cmake-build-release"
    )
    private val candidateFileNames = setOf(
        "CMakeLists.txt",
        "Makefile",
        "makefile",
        "Android.mk",
        "Application.mk",
        "AndroidManifest.xml",
        "build.gradle",
        "build.gradle.kts"
    )
    private val candidateExtensions = setOf("c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx", "mk", "txt")
    private val sdl3Markers = listOf(
        "find_package(SDL3",
        "SDL3::SDL3",
        "#include <SDL3/",
        "SDL_MAIN_USE_CALLBACKS",
        "-lSDL3",
        "org.libsdl.app.SDLActivity"
    )
    private val nativeActivityMarkers = listOf(
        "android.app.NativeActivity",
        "android.app.lib_name",
        "ANativeActivity_onCreate",
        "android_main(",
        "android_native_app_glue",
        "#include <android/native_activity.h>",
        "#include <android_native_app_glue.h>",
        "#include <android/native_app_glue/android_native_app_glue.h>"
    )
    private val libmainMarkers = listOf(
        "add_library(main SHARED",
        "OUTPUT_NAME \"main\"",
        "OUTPUT_NAME main",
        "libmain.so",
        "LOCAL_MODULE := main",
        "LOCAL_MODULE:=main"
    )
    private val terminalExcludedArtifactNames = setOf("Makefile", "makefile", "GNUmakefile", ".gitignore")
    private val terminalExcludedArtifactExtensions = setOf(
        "c", "cc", "cpp", "cxx",
        "h", "hh", "hpp", "hxx",
        "s", "asm",
        "o", "obj", "a", "so",
        "d", "mk", "cmake", "ninja",
        "txt", "md", "json", "xml", "gradle", "kts", "properties"
    )

    private data class CandidateText(
        val file: File,
        val text: String
    )

    fun resolve(projectRoot: File, buildDir: File? = null): ProjectApkExportType? {
        val metadata = ProjectMetadataStore.read(projectRoot)
        return metadata?.apkExportType ?: detect(projectRoot, buildDir)
    }

    fun ensureDetected(projectRoot: File, buildDir: File? = null): ProjectApkExportType? {
        val metadata = ProjectMetadataStore.read(projectRoot)
        metadata?.apkExportType?.let { return it }

        val detected = detect(projectRoot, buildDir)
        if (metadata != null && detected != null) {
            ProjectMetadataStore.updateApkExportType(projectRoot, detected)
        }
        return detected
    }

    internal fun detect(projectRoot: File, buildDir: File? = null): ProjectApkExportType? {
        val textMatches = collectCandidateFiles(projectRoot)
            .mapNotNull(::readTextSafely)

        val hasLibMainMarker = containsAnyMarker(textMatches, libmainMarkers) || hasCompiledLibMain(projectRoot, buildDir)
        val hasSdl3Marker = containsAnyMarker(textMatches, sdl3Markers)
        if (hasLibMainMarker && hasSdl3Marker) {
            return ProjectApkExportType.SDL3
        }

        return if (hasLibMainMarker && containsAnyMarker(textMatches, nativeActivityMarkers)) {
            ProjectApkExportType.NATIVE_ACTIVITY
        } else if (!hasLibMainMarker &&
            (
                hasTerminalMainEntry(textMatches) ||
                    hasCompiledTerminalExecutable(projectRoot, buildDir)
                )
        ) {
            ProjectApkExportType.TERMINAL
        } else {
            null
        }
    }

    private fun collectCandidateFiles(projectRoot: File): List<File> {
        if (!projectRoot.isDirectory) return emptyList()

        return projectRoot.walkTopDown()
            .onEnter { dir -> dir == projectRoot || dir.name !in excludedDirNames }
            .filter { file ->
                file.isFile &&
                    (
                        file.name in candidateFileNames ||
                            file.extension.lowercase() in candidateExtensions
                        )
            }
            .take(MAX_SCANNED_TEXT_FILES)
            .toList()
    }

    private fun readTextSafely(file: File): CandidateText? = runCatching {
        CandidateText(
            file = file,
            text = file.readText(Charsets.UTF_8)
        )
    }.getOrNull()

    private fun containsAnyMarker(textMatches: List<CandidateText>, markers: List<String>): Boolean = textMatches.any { candidate -> markers.any(candidate.text::contains) }

    private fun hasTerminalMainEntry(textMatches: List<CandidateText>): Boolean = textMatches.any { candidate ->
        candidate.file.extension.lowercase() in terminalSourceExtensions &&
            terminalMainEntryRegex.containsMatchIn(candidate.text)
    }

    private fun hasCompiledLibMain(projectRoot: File, buildDir: File?): Boolean {
        val candidates = buildList {
            buildDir?.let { add(it) }
            add(File(projectRoot, "build"))
        }.distinctBy { it.absolutePath }

        return candidates.any { candidate ->
            candidate.isDirectory &&
                candidate.walkTopDown()
                    .onEnter { dir -> dir == candidate || dir.name !in excludedDirNames }
                    .any { file -> file.isFile && file.name == "libmain.so" }
        }
    }

    private fun hasCompiledTerminalExecutable(projectRoot: File, buildDir: File?): Boolean {
        val candidates = buildList {
            buildDir?.let { add(it) }
            add(File(projectRoot, "build"))
        }.distinctBy { it.absolutePath }

        return candidates.any { candidate ->
            candidate.isDirectory &&
                candidate.walkTopDown()
                    .onEnter { dir -> dir == candidate || dir.name !in excludedDirNames }
                    .any(::isRunnableTerminalArtifact)
        }
    }

    private fun isRunnableTerminalArtifact(file: File): Boolean {
        if (!file.isFile || !file.exists()) return false
        if (file.name in terminalExcludedArtifactNames || file.name.startsWith(".")) return false
        if (file.extension.lowercase() in terminalExcludedArtifactExtensions) return false
        return file.canExecute() || hasElfMagic(file)
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
