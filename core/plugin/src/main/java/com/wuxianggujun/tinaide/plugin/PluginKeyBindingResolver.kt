package com.wuxianggujun.tinaide.plugin

import android.view.KeyEvent
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.config.KeyboardShortcut
import com.wuxianggujun.tinaide.plugin.script.api.PluginCommandRegistry
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import timber.log.Timber

data class ResolvedPluginKeyBinding(
    val key: String,
    val shortcut: KeyboardShortcut,
    val commandId: String,
    val pluginId: String,
    val whenExpression: String?
) {
    fun matches(
        event: KeyEvent,
        isDirty: Boolean,
        editorFocus: Boolean
    ): Boolean = shortcut.matches(event) &&
        PluginKeyBindingResolver.matchesWhen(
            whenExpression = whenExpression,
            isDirty = isDirty,
            editorFocus = editorFocus
        )
}

@Serializable
private data class KeyBindingFile(
    val keybindings: List<KeyBinding> = emptyList()
)

object PluginKeyBindingResolver {
    private const val TAG = "PluginKeyBindingResolver"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun resolve(installedPlugins: List<InstalledPlugin>): List<ResolvedPluginKeyBinding> {
        val resolved = buildList {
            installedPlugins.asSequence()
                .filter { it.enabled }
                .forEach { plugin ->
                    plugin.manifest.contributions?.keybindings.orEmpty().forEach { path ->
                        val keyBindingPath = path.trim()
                        if (!isSafePluginRelativePath(keyBindingPath)) {
                            Timber.tag(TAG).w(
                                "Skip unsafe keybindings path: plugin=%s path=%s",
                                plugin.manifest.id,
                                path
                            )
                            return@forEach
                        }

                        val keyBindingFile = File(plugin.directory, keyBindingPath)
                        val bindings = readKeyBindingFile(keyBindingFile).getOrElse { throwable ->
                            Timber.tag(TAG).w(
                                throwable,
                                "Skip invalid keybindings file: plugin=%s path=%s",
                                plugin.manifest.id,
                                keyBindingPath
                            )
                            return@forEach
                        }

                        bindings.forEach { binding ->
                            val commandId = binding.command.trim()
                            if (commandId.isBlank()) return@forEach

                            val shortcut = parseShortcut(binding.key)
                            if (shortcut == null) {
                                Timber.tag(TAG).w(
                                    "Skip invalid keybinding key: plugin=%s key=%s command=%s",
                                    plugin.manifest.id,
                                    binding.key,
                                    commandId
                                )
                                return@forEach
                            }

                            add(
                                ResolvedPluginKeyBinding(
                                    key = binding.key.trim(),
                                    shortcut = shortcut,
                                    commandId = commandId,
                                    pluginId = plugin.manifest.id,
                                    whenExpression = binding.`when`?.trim()?.takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                }
        }

        return resolved.distinctBy { binding ->
            listOf(
                binding.pluginId,
                binding.shortcut.keyCode,
                binding.shortcut.ctrl,
                binding.shortcut.shift,
                binding.shortcut.alt,
                binding.commandId,
                binding.whenExpression.orEmpty()
            ).joinToString("#")
        }
    }

    internal fun readKeyBindingFile(file: File): Result<List<KeyBinding>> = runCatching {
        require(file.isFile) { "Keybindings file does not exist: ${file.path}" }
        val content = file.readText()
        val element = json.parseToJsonElement(content)
        when (element) {
            is JsonArray -> json.decodeFromJsonElement<List<KeyBinding>>(element)
            is JsonObject -> {
                element["keybindings"]
                    ?.jsonArray
                    ?.let { json.decodeFromJsonElement<List<KeyBinding>>(it) }
                    ?: json.decodeFromJsonElement<KeyBindingFile>(element).keybindings
            }
            else -> error("Keybindings file must be a JSON array or object: ${file.path}")
        }
    }

    internal fun parseShortcut(key: String): KeyboardShortcut? {
        val tokens = key.split('+')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        var ctrl = false
        var shift = false
        var alt = false
        var keyCode: Int? = null

        tokens.forEach { token ->
            when (token.normalizeKeyToken()) {
                "CTRL",
                "CONTROL" -> ctrl = true
                "SHIFT" -> shift = true
                "ALT",
                "OPTION" -> alt = true
                else -> {
                    if (keyCode != null) return null
                    keyCode = keyCodeForToken(token) ?: return null
                }
            }
        }

        return keyCode?.let {
            KeyboardShortcut(
                keyCode = it,
                ctrl = ctrl,
                shift = shift,
                alt = alt
            )
        }
    }

    internal fun matchesWhen(
        whenExpression: String?,
        isDirty: Boolean,
        editorFocus: Boolean
    ): Boolean = when (whenExpression?.trim().orEmpty()) {
        "" -> true
        "isDirty" -> isDirty
        "!isDirty" -> !isDirty
        "isDirty == true" -> isDirty
        "isDirty == false" -> !isDirty
        "editorFocus" -> editorFocus
        "!editorFocus" -> !editorFocus
        "editorFocus == true" -> editorFocus
        "editorFocus == false" -> !editorFocus
        else -> false
    }

    internal fun isSupportedWhenExpression(whenExpression: String?): Boolean = when (whenExpression?.trim().orEmpty()) {
        "",
        "isDirty",
        "!isDirty",
        "isDirty == true",
        "isDirty == false",
        "editorFocus",
        "!editorFocus",
        "editorFocus == true",
        "editorFocus == false" -> true
        else -> false
    }

    fun isCommandSupported(binding: ResolvedPluginKeyBinding): Boolean = HostCommands.isSupported(binding.commandId) ||
        PluginCommandRegistry.isRegistered(binding.commandId, binding.pluginId)

    private fun keyCodeForToken(token: String): Int? {
        val normalized = token.normalizeKeyToken()
        keyCodeAliases[normalized]?.let { return it }

        if (normalized.length == 1) {
            val char = normalized.single()
            if (char in 'A'..'Z') return KeyEvent.KEYCODE_A + (char - 'A')
            if (char in '0'..'9') return KeyEvent.KEYCODE_0 + (char - '0')
        }

        if (normalized.matches(Regex("F([1-9]|1[0-2])"))) {
            return KeyEvent.KEYCODE_F1 + normalized.removePrefix("F").toInt() - 1
        }

        val androidName = "KEYCODE_$normalized"
        val keyCode = KeyEvent.keyCodeFromString(androidName)
        return keyCode.takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
    }

    private fun String.normalizeKeyToken(): String = trim()
        .replace(" ", "")
        .replace("-", "_")
        .uppercase()

    private val keyCodeAliases = mapOf(
        "TAB" to KeyEvent.KEYCODE_TAB,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "RETURN" to KeyEvent.KEYCODE_ENTER,
        "ESC" to KeyEvent.KEYCODE_ESCAPE,
        "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
        "BACKSPACE" to KeyEvent.KEYCODE_DEL,
        "DELETE" to KeyEvent.KEYCODE_FORWARD_DEL,
        "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
        "SPACE" to KeyEvent.KEYCODE_SPACE,
        "SPACEBAR" to KeyEvent.KEYCODE_SPACE,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "ARROW_LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ARROW_RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "UP" to KeyEvent.KEYCODE_DPAD_UP,
        "ARROW_UP" to KeyEvent.KEYCODE_DPAD_UP,
        "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "ARROW_DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "PAGE_UP" to KeyEvent.KEYCODE_PAGE_UP,
        "PAGEUP" to KeyEvent.KEYCODE_PAGE_UP,
        "PAGE_DOWN" to KeyEvent.KEYCODE_PAGE_DOWN,
        "PAGEDOWN" to KeyEvent.KEYCODE_PAGE_DOWN,
        "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
        "END" to KeyEvent.KEYCODE_MOVE_END
    )
}
