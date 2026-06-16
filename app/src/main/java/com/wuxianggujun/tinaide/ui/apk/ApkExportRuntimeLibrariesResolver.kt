package com.wuxianggujun.tinaide.ui.apk

import android.content.Context
import com.wuxianggujun.tinaide.core.ndk.AndroidSysrootManager
import com.wuxianggujun.tinaide.core.packages.InstalledPackagePathResolver
import com.wuxianggujun.tinaide.ui.runtime.AndroidSystemLibraries
import com.wuxianggujun.tinaide.ui.sdl.SdlRuntimeResolver
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import timber.log.Timber

/**
 * 为 APK 导出收集需要一起打包的 native 运行库。
 *
 * 目标：
 * - 保留构建目录里的项目产物；
 * - 自动补齐已安装包/runtime 目录中的依赖 so；
 * - 在导出 APK 场景下额外补上 libc++_shared.so。
 */
object ApkExportRuntimeLibrariesResolver {
    private const val TAG = "ApkExportLibResolver"
    private const val ASCII_TOKEN_LIMIT = 4096
    private const val ASCII_TAIL_LIMIT = 256

    private val sharedLibraryNamePattern =
        Regex("""lib[0-9A-Za-z_+\-.]+\.so(?:\.[0-9A-Za-z_+\-.]+)?""")

    // OS 提供的 NDK 系统库统一走 AndroidSystemLibraries.ndkProvided。
    // 注意：导出 APK 时 libc++_shared.so 需要打进包里，故不在系统库集合中，
    // 由 collectSysrootRuntimeLibraries 单独补齐。
    private val apkSystemLibraryNames = AndroidSystemLibraries.ndkProvided

    data class Resolution(
        val packagedLibraries: List<File>,
        val missingLibraries: List<String>
    )

    internal data class DependencyResolution(
        val libraries: List<File>,
        val missingLibraries: List<String>
    )

    fun resolve(
        context: Context,
        projectRoot: File?,
        buildDir: File?
    ): Resolution {
        val buildLibraries = scanBuildLibraries(buildDir)
        if (buildLibraries.isEmpty()) {
            return Resolution(
                packagedLibraries = emptyList(),
                missingLibraries = emptyList()
            )
        }

        val appContext = context.applicationContext
        val packagePaths = InstalledPackagePathResolver.resolve(appContext, projectRoot)
        val runtimeCandidates = linkedSetOf<File>().apply {
            addAll(buildLibraries)
            addAll(scanRuntimeLibraries(packagePaths.runtimeLibDirs))
            addAll(collectSysrootRuntimeLibraries(appContext))
        }

        val rootLibraries = resolveRootLibraries(buildLibraries)
        val primaryLibrary = rootLibraries.first()
        when (
            val sdlResolveResult = SdlRuntimeResolver.resolve(
                context = appContext,
                mainLibraryPath = primaryLibrary.absolutePath,
                extraRuntimeLibDirs = packagePaths.runtimeLibDirs,
            )
        ) {
            is SdlRuntimeResolver.ResolveResult.Sdl -> {
                File(sdlResolveResult.spec.sdlLibraryPath)
                    .takeIf { it.isFile }
                    ?.let(runtimeCandidates::add)
                sdlResolveResult.spec.preloadLibraryPaths
                    .asSequence()
                    .map(::File)
                    .filter { it.isFile }
                    .forEach(runtimeCandidates::add)
            }
            is SdlRuntimeResolver.ResolveResult.Error -> {
                Timber.tag(TAG).i(
                    "Skip SDL runtime injection for APK export: %s",
                    sdlResolveResult.message
                )
            }
            SdlRuntimeResolver.ResolveResult.NonSdl -> Unit
        }

        val dependencyResolution = resolvePackagedLibraries(
            buildLibraries = buildLibraries,
            runtimeCandidates = runtimeCandidates.toList(),
            rootLibraries = rootLibraries
        )
        val packagedLibraries = dependencyResolution.libraries

        Timber.tag(TAG).i(
            "Resolved APK export libraries: roots=%d packaged=%d missing=%d",
            rootLibraries.size,
            packagedLibraries.size,
            dependencyResolution.missingLibraries.size
        )
        if (dependencyResolution.missingLibraries.isNotEmpty()) {
            Timber.tag(TAG).w(
                "Missing APK export libraries: %s",
                dependencyResolution.missingLibraries.joinToString(", ")
            )
        }

        return Resolution(
            packagedLibraries = packagedLibraries,
            missingLibraries = dependencyResolution.missingLibraries
        )
    }

    internal fun resolvePackagedLibraries(
        buildLibraries: List<File>,
        runtimeCandidates: List<File>,
        rootLibraries: List<File> = resolveRootLibraries(buildLibraries),
        dependencyReader: (File) -> Set<String> = { extractNeededLibraryNames(it) }
    ): DependencyResolution {
        if (rootLibraries.isEmpty()) {
            return DependencyResolution(
                libraries = emptyList(),
                missingLibraries = emptyList()
            )
        }

        val runtimeIndex = buildRuntimeLibraryIndex(runtimeCandidates)
        val dependencyResolution = resolveDependencyClosure(
            rootLibraries = rootLibraries,
            runtimeIndex = runtimeIndex,
            dependencyReader = dependencyReader
        )
        val packagedLibraries = linkedSetOf<File>().apply {
            addAll(rootLibraries.map(::canonicalOrAbsolute))
            addAll(dependencyResolution.libraries)
        }
        return DependencyResolution(
            libraries = packagedLibraries.toList(),
            missingLibraries = dependencyResolution.missingLibraries
        )
    }

    internal fun resolveRootLibraries(buildLibraries: List<File>): List<File> {
        if (buildLibraries.isEmpty()) return emptyList()
        return buildLibraries
            .firstOrNull { it.name == "libmain.so" }
            ?.let(::listOf)
            ?: listOf(buildLibraries.first())
    }

    internal fun resolveDependencyClosure(
        rootLibraries: List<File>,
        runtimeIndex: Map<String, File>,
        dependencyReader: (File) -> Set<String> = { extractNeededLibraryNames(it) },
        systemLibraries: Set<String> = apkSystemLibraryNames
    ): DependencyResolution {
        val queue = ArrayDeque<File>()
        queue.addAll(rootLibraries.map(::canonicalOrAbsolute))

        val visited = linkedSetOf<String>()
        val resolvedLibraries = linkedSetOf<File>()
        val missingLibraries = linkedSetOf<String>()

        while (queue.isNotEmpty()) {
            val current = canonicalOrAbsolute(queue.removeFirst())
            if (!visited.add(current.absolutePath)) continue

            val neededLibraries = runCatching { dependencyReader(current) }
                .onFailure { error ->
                    Timber.tag(TAG).w(
                        error,
                        "Failed to inspect dependencies for %s",
                        current.absolutePath
                    )
                }
                .getOrDefault(emptySet())

            neededLibraries.sorted().forEach { needed ->
                val canonicalName = canonicalSoName(needed)
                if (needed in systemLibraries || canonicalName in systemLibraries) {
                    return@forEach
                }

                val resolved = runtimeIndex[needed]
                    ?: canonicalName?.let(runtimeIndex::get)
                    ?: run {
                        missingLibraries += needed
                        return@forEach
                    }

                val resolvedFile = canonicalOrAbsolute(resolved)
                if (resolvedFile.absolutePath == current.absolutePath) {
                    return@forEach
                }

                if (resolvedLibraries.add(resolvedFile)) {
                    queue += resolvedFile
                }
            }
        }

        return DependencyResolution(
            libraries = resolvedLibraries.toList(),
            missingLibraries = missingLibraries.toList()
        )
    }

    internal fun buildRuntimeLibraryIndex(libraries: List<File>): Map<String, File> {
        val index = linkedMapOf<String, File>()
        libraries
            .asSequence()
            .map(::canonicalOrAbsolute)
            .filter { it.isFile && it.name.contains(".so") }
            .sortedBy { it.absolutePath }
            .forEach { library ->
                index.putIfAbsent(library.name, library)
                canonicalSoName(library.name)?.let { canonical ->
                    index.putIfAbsent(canonical, library)
                }
            }
        return index
    }

    internal fun canonicalSoName(name: String): String? {
        val markerIndex = name.indexOf(".so")
        if (markerIndex < 0) return null
        return name.substring(0, markerIndex + 3)
    }

    @Throws(IOException::class)
    internal fun extractNeededLibraryNames(library: File): Set<String> {
        val results = linkedSetOf<String>()
        val tokenBuilder = StringBuilder()
        library.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break

                for (i in 0 until read) {
                    val byteValue = buffer[i].toInt() and 0xFF
                    if (byteValue in 32..126) {
                        tokenBuilder.append(byteValue.toChar())
                        if (tokenBuilder.length > ASCII_TOKEN_LIMIT) {
                            collectLibraryNames(tokenBuilder, results)
                            val tailStart =
                                (tokenBuilder.length - ASCII_TAIL_LIMIT).coerceAtLeast(0)
                            val tail = tokenBuilder.substring(tailStart)
                            tokenBuilder.setLength(0)
                            tokenBuilder.append(tail)
                        }
                    } else {
                        collectLibraryNames(tokenBuilder, results)
                        tokenBuilder.setLength(0)
                    }
                }
            }
        }
        collectLibraryNames(tokenBuilder, results)
        return results
    }

    internal fun scanBuildLibraries(buildDir: File?): List<File> {
        if (buildDir == null || !buildDir.isDirectory) return emptyList()
        return buildDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("so", ignoreCase = true) }
            .map(::canonicalOrAbsolute)
            .distinctBy { it.absolutePath }
            .sortedWith(compareBy<File>({ it.name != "libmain.so" }, { it.absolutePath }))
            .toList()
    }

    internal fun scanRuntimeLibraries(runtimeDirs: List<File>): List<File> = runtimeDirs.asSequence()
        .filter { it.isDirectory }
        .flatMap { dir ->
            dir.listFiles { file -> file.isFile && file.name.contains(".so") }
                ?.asSequence()
                ?: emptySequence()
        }
        .map(::canonicalOrAbsolute)
        .distinctBy { it.absolutePath }
        .sortedBy { it.absolutePath }
        .toList()

    internal fun collectSysrootRuntimeLibraries(context: Context): List<File> {
        val sysrootManager = AndroidSysrootManager(context)
        val arch = AndroidSysrootManager.Companion.Arch.current()
        if (!sysrootManager.isInstalled(arch)) return emptyList()

        val sysrootLibDir = File(sysrootManager.getSysrootDir(arch), "usr/lib/${arch.triple}")
        return listOfNotNull(
            File(sysrootLibDir, "libc++_shared.so").takeIf { it.isFile }
        )
    }

    private fun collectLibraryNames(tokenBuilder: StringBuilder, output: MutableSet<String>) {
        if (tokenBuilder.isEmpty()) return
        val token = tokenBuilder.toString()
        if (!token.contains(".so")) return

        sharedLibraryNamePattern.findAll(token).forEach { match ->
            output += match.value
        }
    }

    internal fun canonicalOrAbsolute(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
}
