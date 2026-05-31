package com.wuxianggujun.tinaide.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Generates `GeneratedTreeSitterLanguageRegistry.kt` that enumerates the
 * Tree-sitter grammars pulled in by the app module and binds them to the
 * corresponding `TSLanguage` factories.
 *
 * The task source used to live inline in `app/build.gradle.kts`; it is
 * now a proper typed task hosted in `build-logic/convention` so that it
 * can be registered by [TinaAndroidAppTreeSitterPlugin] and reused from
 * other Android application modules if needed.
 *
 * Inputs:
 * - [grammarLanguages]: grammar identifiers (e.g. `cpp`, `kotlin`). The
 *   plugin populates this by inspecting the `implementation` dependency
 *   configuration at apply time.
 * - [bindingSourceFiles]: `src/main/java/.../TSLanguage*.java` files under
 *   `external/tina-android-tree-sitter/grammar-modules`, used for Gradle input
 *   tracking without snapshotting native build scratch directories like `.cxx`.
 *
 * Output:
 * - [outputDir]: Kotlin source root that the consumer registers as an
 *   additional `srcDir` for the main source set.
 */
abstract class TinaGenerateTreeSitterLanguageRegistryTask : DefaultTask() {

    @get:Input
    abstract val grammarLanguages: SetProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val grammarModulesRoot: DirectoryProperty

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bindingSourceFiles: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val langs = grammarLanguages.get().toSortedSet()
        val grammarRoot = grammarModulesRoot.get().asFile.takeIf { it.isDirectory }
            ?: throw GradleException(
                "Missing grammar-modules dir: ${grammarModulesRoot.get().asFile.absolutePath}",
            )

        val bindings = langs.map { lang -> resolveBinding(grammarRoot, lang) }

        val outRoot = outputDir.get().asFile
        val pkgDir = outRoot.resolve("com/wuxianggujun/tinaide/editor/language/treesitter")
            .apply { mkdirs() }
        val outFile = pkgDir.resolve("GeneratedTreeSitterLanguageRegistry.kt")

        val bindingImports = bindings
            .map { it.fqcn }
            .distinct()
            .sorted()
            .joinToString("\n") { "import $it" }
        val imports = buildString {
            appendLine("import com.itsaky.androidide.treesitter.TSLanguage")
            if (bindingImports.isNotEmpty()) {
                appendLine(bindingImports)
            }
        }.trimEnd()

        val entries = bindings.joinToString(",\n") { binding ->
            val cls = binding.fqcn.substringAfterLast('.')
            """
            |        Entry(
            |            langName = "${binding.lang}",
            |            displayName = "${binding.displayName}",
            |            factory = { $cls.getInstance() },
            |        )
            """.trimMargin()
        }
        val entriesExpression = if (entries.isEmpty()) {
            "emptyList()"
        } else {
            """
            |listOf(
            |$entries,
            |    )
            """.trimMargin()
        }

        outFile.writeText(
            """
            |package com.wuxianggujun.tinaide.editor.language.treesitter
            |
            |$imports
            |
            |/**
            | * Auto-generated. DO NOT EDIT.
            | *
            | * Generated from:
            | * - app module dependencies (com.itsaky.androidide.treesitter:tree-sitter-*)
            | * - external/tina-android-tree-sitter/grammar-modules/<lang> binding sources
            | */
            |object GeneratedTreeSitterLanguageRegistry {
            |    data class Entry(
            |        val langName: String,
            |        val displayName: String,
            |        val factory: () -> TSLanguage,
            |    )
            |
            |    val entries: List<Entry> = $entriesExpression
            |
            |    fun find(langName: String): Entry? = entries.firstOrNull { it.langName == langName }
            |}
            |""".trimMargin(),
            Charsets.UTF_8,
        )
    }

    private data class GrammarBinding(
        val lang: String,
        val displayName: String,
        val fqcn: String,
    )

    private fun resolveBinding(grammarModulesRoot: File, lang: String): GrammarBinding {
        val javaRoot = grammarModulesRoot.resolve(lang).resolve("src/main/java")
        val bindingFile = javaRoot
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.firstOrNull { it.isFile && it.name.startsWith("TSLanguage") && it.extension == "java" }
            ?: throw GradleException(
                "TSLanguage binding not found for '$lang' under: ${javaRoot.absolutePath}",
            )

        val pkg = bindingFile.useLines { lines ->
            lines.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                    trimmed.removePrefix("package ").removeSuffix(";").trim()
                } else {
                    null
                }
            }.firstOrNull()
        } ?: throw GradleException(
            "Failed to parse package declaration in: ${bindingFile.absolutePath}",
        )

        val className = bindingFile.nameWithoutExtension
        return GrammarBinding(
            lang = lang,
            displayName = displayName(lang),
            fqcn = "$pkg.$className",
        )
    }

    private fun displayName(lang: String): String = when (lang) {
        "aidl" -> "AIDL"
        "c" -> "C"
        "cpp" -> "C++"
        "cmake" -> "CMake"
        "json" -> "JSON"
        "toml" -> "TOML"
        "xml" -> "XML"
        "yaml" -> "YAML"
        else -> lang.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
