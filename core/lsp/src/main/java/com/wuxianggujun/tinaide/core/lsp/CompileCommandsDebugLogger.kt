package com.wuxianggujun.tinaide.core.lsp

import com.wuxianggujun.tinaide.core.config.Prefs
import com.wuxianggujun.tinaide.project.NativeBuildFlagTokenizer
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * 只做诊断，不修改 compile_commands。
 */
object CompileCommandsDebugLogger {

    private val json = Json

    fun isCompileCommandsSelectionEnabled(): Boolean = runCatching {
        Prefs.developerOptionsEnabled &&
            Prefs.devDiagnosticsEnabled &&
            Prefs.devLspCompileCommandsSelectionLogEnabled
    }.getOrDefault(false)

    fun isClangdStartupEnabled(): Boolean = runCatching {
        Prefs.developerOptionsEnabled &&
            Prefs.devDiagnosticsEnabled &&
            Prefs.devLspClangdStartupLogEnabled
    }.getOrDefault(false)

    fun logCompileCommandsSelectionSummary(tag: String, label: String, file: File) {
        if (!isCompileCommandsSelectionEnabled()) return
        logSummary(tag, label, file)
    }

    fun logClangdStartupSummary(tag: String, label: String, file: File) {
        if (!isClangdStartupEnabled()) return
        logSummary(tag, label, file)
    }

    private fun logSummary(tag: String, label: String, file: File) {
        if (!file.isFile) {
            Timber.tag(tag).w("%s: compile_commands missing: %s", label, file.absolutePath)
            return
        }

        val content = runCatching { file.readText() }
            .onFailure { t ->
                Timber.tag(tag).w(t, "%s: failed to read compile_commands: %s", label, file.absolutePath)
            }
            .getOrNull() ?: return

        val entries = runCatching { json.parseToJsonElement(content).jsonArray }
            .onFailure { t ->
                Timber.tag(tag).w(t, "%s: failed to parse compile_commands JSON: %s", label, file.absolutePath)
            }
            .getOrNull() ?: return

        val firstEntry = entries.firstOrNull()?.jsonObject
        val firstFile = firstEntry?.get("file")?.jsonPrimitive?.contentOrNull.orEmpty()
        val firstArguments = firstEntry?.let(::extractArguments).orEmpty()
        val firstCompiler = firstArguments.firstOrNull().orEmpty()
        val preview = firstArguments.take(8).joinToString(" ")

        val hasLinker64 = content.contains("linker64")
        val hasResourceDir = content.contains("-resource-dir")
        val hasLibcxxV1 = content.contains("/usr/include/c++/v1")
        val hasSysroot = content.contains("--sysroot=") || content.contains("-isysroot")

        Timber.tag(tag).i(
            "%s: path=%s, entries=%d, firstFile=%s, firstCompiler=%s, linker64=%s, resourceDir=%s, libcxxV1=%s, sysroot=%s, firstArgs=%s",
            label,
            file.absolutePath,
            entries.size,
            firstFile,
            firstCompiler,
            hasLinker64,
            hasResourceDir,
            hasLibcxxV1,
            hasSysroot,
            preview
        )
    }

    private fun extractArguments(entry: kotlinx.serialization.json.JsonObject): List<String> {
        val arguments = entry["arguments"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
        if (arguments != null) {
            return arguments
        }

        val command = entry["command"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        return NativeBuildFlagTokenizer.tokenize(command)
    }
}
