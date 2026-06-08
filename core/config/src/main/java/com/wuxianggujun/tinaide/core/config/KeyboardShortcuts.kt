package com.wuxianggujun.tinaide.core.config

import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.commands.HostCommandCatalog
import com.wuxianggujun.tinaide.core.commands.HostCommands
import com.wuxianggujun.tinaide.core.i18n.Strings
import org.json.JSONObject

/**
 * 快捷键动作枚举
 */
enum class ShortcutAction(
    @param:StringRes @get:StringRes val descriptionRes: Int,
    val commandId: String
) {
    COMMAND_PALETTE(
        Strings.shortcut_desc_command_palette,
        HostCommands.VIEW_COMMAND_PALETTE
    ),
    SAVE(
        Strings.shortcut_desc_save_current_file,
        HostCommands.EDITOR_SAVE
    ),
    SAVE_ALL(
        Strings.shortcut_desc_save_all_files,
        HostCommands.EDITOR_SAVE_ALL
    ),
    CLOSE_TAB(
        Strings.shortcut_desc_close_current_tab,
        HostCommands.EDITOR_CLOSE
    ),
    CLOSE_ALL_TABS(
        Strings.shortcut_desc_close_all_tabs,
        HostCommands.EDITOR_CLOSE_ALL
    ),
    UNDO(Strings.shortcut_desc_undo, HostCommands.EDITOR_UNDO),
    REDO(Strings.shortcut_desc_redo, HostCommands.EDITOR_REDO),
    NEXT_TAB(
        Strings.shortcut_desc_next_tab,
        HostCommands.EDITOR_NEXT_TAB
    ),
    PREV_TAB(
        Strings.shortcut_desc_prev_tab,
        HostCommands.EDITOR_PREVIOUS_TAB
    ),
    TOGGLE_BOOKMARK(
        Strings.shortcut_desc_toggle_bookmark,
        HostCommands.EDITOR_TOGGLE_BOOKMARK
    ),
    NEXT_BOOKMARK(
        Strings.shortcut_desc_next_bookmark,
        HostCommands.EDITOR_NEXT_BOOKMARK
    ),
    PREV_BOOKMARK(
        Strings.shortcut_desc_prev_bookmark,
        HostCommands.EDITOR_PREVIOUS_BOOKMARK
    ),
    NAVIGATE_BACK(
        Strings.shortcut_desc_navigate_back,
        HostCommands.EDITOR_NAVIGATE_BACK
    ),
    NAVIGATE_FORWARD(
        Strings.shortcut_desc_navigate_forward,
        HostCommands.EDITOR_NAVIGATE_FORWARD
    ),
    PEEK_DEFINITION(
        Strings.shortcut_desc_peek_definition,
        HostCommands.EDITOR_PEEK_DEFINITION
    ),
    GOTO_DEFINITION(
        Strings.shortcut_desc_goto_definition,
        HostCommands.EDITOR_GOTO_DEFINITION
    ),
    FIND_REFERENCES(
        Strings.shortcut_desc_find_references,
        HostCommands.EDITOR_FIND_REFERENCES
    ),
    GOTO_TYPE_DEFINITION(
        Strings.shortcut_desc_goto_type_definition,
        HostCommands.EDITOR_GOTO_TYPE_DEFINITION
    ),
    GOTO_IMPLEMENTATION(
        Strings.shortcut_desc_goto_implementation,
        HostCommands.EDITOR_GOTO_IMPLEMENTATION
    ),
    CODE_ACTIONS(
        Strings.shortcut_desc_code_actions,
        HostCommands.EDITOR_CODE_ACTIONS
    ),
    RENAME_SYMBOL(
        Strings.shortcut_desc_rename_symbol,
        HostCommands.EDITOR_RENAME_SYMBOL
    ),
    SWITCH_HEADER_SOURCE(
        Strings.shortcut_desc_switch_header_source,
        HostCommands.EDITOR_SWITCH_HEADER_SOURCE
    );

    @get:StringRes
    val displayNameRes: Int
        get() = HostCommandCatalog.requireDescriptor(commandId).titleRes

    companion object {
        fun forCommandId(commandId: String): ShortcutAction? {
            val normalizedCommandId = commandId.trim()
            return entries.firstOrNull { it.commandId == normalizedCommandId }
        }
    }
}

/**
 * 快捷键配置
 *
 * @param keyCode Android KeyEvent keyCode
 * @param ctrl 是否需要 Ctrl 键
 * @param shift 是否需要 Shift 键
 * @param alt 是否需要 Alt 键
 */
data class KeyboardShortcut(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false
) {
    /**
     * 检查按键事件是否匹配此快捷键
     */
    fun matches(event: KeyEvent): Boolean = event.action == KeyEvent.ACTION_DOWN &&
        event.keyCode == keyCode &&
        event.isCtrlPressed == ctrl &&
        event.isShiftPressed == shift &&
        event.isAltPressed == alt

    /**
     * 获取快捷键的显示文本
     */
    fun toDisplayString(): String {
        val parts = mutableListOf<String>()
        if (ctrl) parts.add("Ctrl")
        if (shift) parts.add("Shift")
        if (alt) parts.add("Alt")
        parts.add(getKeyName(keyCode))
        return parts.joinToString(" + ")
    }

    /**
     * 序列化为 JSON 字符串
     */
    fun toJson(): String = JSONObject().apply {
        put("keyCode", keyCode)
        put("ctrl", ctrl)
        put("shift", shift)
        put("alt", alt)
    }.toString()

    companion object {
        /**
         * 从 JSON 字符串反序列化
         */
        fun fromJson(json: String): KeyboardShortcut? = try {
            val obj = JSONObject(json)
            KeyboardShortcut(
                keyCode = obj.getInt("keyCode"),
                ctrl = obj.optBoolean("ctrl", false),
                shift = obj.optBoolean("shift", false),
                alt = obj.optBoolean("alt", false)
            )
        } catch (e: Exception) {
            null
        }

        /**
         * 获取按键名称
         */
        fun getKeyName(keyCode: Int): String = when (keyCode) {
            KeyEvent.KEYCODE_A -> "A"
            KeyEvent.KEYCODE_B -> "B"
            KeyEvent.KEYCODE_C -> "C"
            KeyEvent.KEYCODE_D -> "D"
            KeyEvent.KEYCODE_E -> "E"
            KeyEvent.KEYCODE_F -> "F"
            KeyEvent.KEYCODE_G -> "G"
            KeyEvent.KEYCODE_H -> "H"
            KeyEvent.KEYCODE_I -> "I"
            KeyEvent.KEYCODE_J -> "J"
            KeyEvent.KEYCODE_K -> "K"
            KeyEvent.KEYCODE_L -> "L"
            KeyEvent.KEYCODE_M -> "M"
            KeyEvent.KEYCODE_N -> "N"
            KeyEvent.KEYCODE_O -> "O"
            KeyEvent.KEYCODE_P -> "P"
            KeyEvent.KEYCODE_Q -> "Q"
            KeyEvent.KEYCODE_R -> "R"
            KeyEvent.KEYCODE_S -> "S"
            KeyEvent.KEYCODE_T -> "T"
            KeyEvent.KEYCODE_U -> "U"
            KeyEvent.KEYCODE_V -> "V"
            KeyEvent.KEYCODE_W -> "W"
            KeyEvent.KEYCODE_X -> "X"
            KeyEvent.KEYCODE_Y -> "Y"
            KeyEvent.KEYCODE_Z -> "Z"
            KeyEvent.KEYCODE_0 -> "0"
            KeyEvent.KEYCODE_1 -> "1"
            KeyEvent.KEYCODE_2 -> "2"
            KeyEvent.KEYCODE_3 -> "3"
            KeyEvent.KEYCODE_4 -> "4"
            KeyEvent.KEYCODE_5 -> "5"
            KeyEvent.KEYCODE_6 -> "6"
            KeyEvent.KEYCODE_7 -> "7"
            KeyEvent.KEYCODE_8 -> "8"
            KeyEvent.KEYCODE_9 -> "9"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_ESCAPE -> "Esc"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
            KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
            KeyEvent.KEYCODE_F1 -> "F1"
            KeyEvent.KEYCODE_F2 -> "F2"
            KeyEvent.KEYCODE_F3 -> "F3"
            KeyEvent.KEYCODE_F4 -> "F4"
            KeyEvent.KEYCODE_F5 -> "F5"
            KeyEvent.KEYCODE_F6 -> "F6"
            KeyEvent.KEYCODE_F7 -> "F7"
            KeyEvent.KEYCODE_F8 -> "F8"
            KeyEvent.KEYCODE_F9 -> "F9"
            KeyEvent.KEYCODE_F10 -> "F10"
            KeyEvent.KEYCODE_F11 -> "F11"
            KeyEvent.KEYCODE_F12 -> "F12"
            else -> "Key($keyCode)"
        }
    }
}

/**
 * 快捷键管理器
 *
 * 管理所有快捷键配置，支持自定义和恢复默认。
 */
object KeyboardShortcutManager {
    private const val PREF_PREFIX = "shortcut_"

    private lateinit var sharedPrefs: SharedPreferences

    /**
     * 初始化快捷键管理器
     *
     * 必须在使用其他方法前调用（通常在 Application.onCreate 中）
     */
    fun initialize(prefs: SharedPreferences) {
        sharedPrefs = prefs
    }

    /**
     * 默认快捷键配置
     */
    private val defaultShortcuts: Map<ShortcutAction, KeyboardShortcut> by lazy {
        ShortcutAction.entries.associateWith { action ->
            val shortcut = HostCommandCatalog.requireDescriptor(action.commandId).defaultShortcut
                ?: error("Missing default shortcut for ${action.commandId}")
            KeyboardShortcut(
                keyCode = shortcut.keyCode,
                ctrl = shortcut.ctrl,
                shift = shortcut.shift,
                alt = shortcut.alt
            )
        }
    }

    /**
     * 获取指定动作的快捷键配置
     */
    fun getShortcut(action: ShortcutAction): KeyboardShortcut {
        val json = sharedPrefs.getString(PREF_PREFIX + action.name, null)
        return if (json != null) {
            KeyboardShortcut.fromJson(json) ?: defaultShortcuts[action]!!
        } else {
            defaultShortcuts[action]!!
        }
    }

    /**
     * 设置指定动作的快捷键
     */
    fun setShortcut(action: ShortcutAction, shortcut: KeyboardShortcut) {
        sharedPrefs.edit()
            .putString(PREF_PREFIX + action.name, shortcut.toJson())
            .apply()
    }

    /**
     * 重置指定动作的快捷键为默认值
     */
    fun resetShortcut(action: ShortcutAction) {
        sharedPrefs.edit()
            .remove(PREF_PREFIX + action.name)
            .apply()
    }

    /**
     * 重置所有快捷键为默认值
     */
    fun resetAllShortcuts() {
        val editor = sharedPrefs.edit()
        ShortcutAction.entries.forEach { action ->
            editor.remove(PREF_PREFIX + action.name)
        }
        editor.apply()
    }

    /**
     * 获取所有快捷键配置
     */
    fun getAllShortcuts(): Map<ShortcutAction, KeyboardShortcut> = ShortcutAction.entries.associateWith { getShortcut(it) }

    /**
     * 根据按键事件查找匹配的动作
     */
    fun findActionForEvent(event: KeyEvent): ShortcutAction? = ShortcutAction.entries.find { action ->
        getShortcut(action).matches(event)
    }

    /**
     * 检查快捷键是否与其他动作冲突
     */
    fun hasConflict(shortcut: KeyboardShortcut, excludeAction: ShortcutAction? = null): ShortcutAction? = ShortcutAction.entries.find { action ->
        action != excludeAction && getShortcut(action) == shortcut
    }

    /**
     * 获取默认快捷键
     */
    fun getDefaultShortcut(action: ShortcutAction): KeyboardShortcut = defaultShortcuts[action]!!

    /**
     * 检查指定动作的快捷键是否已被修改
     */
    fun isModified(action: ShortcutAction): Boolean = getShortcut(action) != defaultShortcuts[action]
}
