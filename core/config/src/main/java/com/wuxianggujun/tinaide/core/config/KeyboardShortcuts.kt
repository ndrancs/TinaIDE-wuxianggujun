package com.wuxianggujun.tinaide.core.config

import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings
import org.json.JSONObject

/**
 * 快捷键动作枚举
 */
enum class ShortcutAction(
    @param:StringRes @get:StringRes val displayNameRes: Int,
    @param:StringRes @get:StringRes val descriptionRes: Int
) {
    SAVE(Strings.cmd_editor_save, Strings.shortcut_desc_save_current_file),
    SAVE_ALL(Strings.cmd_editor_save_all, Strings.shortcut_desc_save_all_files),
    CLOSE_TAB(Strings.action_close_current_tab, Strings.shortcut_desc_close_current_tab),
    CLOSE_ALL_TABS(Strings.action_close_all_tabs, Strings.shortcut_desc_close_all_tabs),
    UNDO(Strings.cmd_editor_undo, Strings.shortcut_desc_undo),
    REDO(Strings.cmd_editor_redo, Strings.shortcut_desc_redo),
    NEXT_TAB(Strings.shortcut_action_next_tab, Strings.shortcut_desc_next_tab),
    PREV_TAB(Strings.shortcut_action_prev_tab, Strings.shortcut_desc_prev_tab),
    TOGGLE_BOOKMARK(Strings.content_desc_toggle_bookmark, Strings.shortcut_desc_toggle_bookmark),
    NEXT_BOOKMARK(Strings.content_desc_next_bookmark, Strings.shortcut_desc_next_bookmark),
    PREV_BOOKMARK(Strings.content_desc_prev_bookmark, Strings.shortcut_desc_prev_bookmark),
    NAVIGATE_BACK(Strings.cmd_editor_navigate_back, Strings.shortcut_desc_navigate_back),
    NAVIGATE_FORWARD(Strings.cmd_editor_navigate_forward, Strings.shortcut_desc_navigate_forward),
    PEEK_DEFINITION(Strings.lsp_peek_definition, Strings.shortcut_desc_peek_definition),
    GOTO_DEFINITION(Strings.lsp_goto_definition, Strings.shortcut_desc_goto_definition),
    FIND_REFERENCES(Strings.lsp_find_references, Strings.shortcut_desc_find_references),
    GOTO_TYPE_DEFINITION(Strings.lsp_goto_type_definition, Strings.shortcut_desc_goto_type_definition),
    GOTO_IMPLEMENTATION(Strings.lsp_goto_implementation, Strings.shortcut_desc_goto_implementation),
    CODE_ACTIONS(Strings.code_actions_title, Strings.shortcut_desc_code_actions),
    RENAME_SYMBOL(Strings.lsp_template_rename, Strings.shortcut_desc_rename_symbol),
    SWITCH_HEADER_SOURCE(Strings.cmd_editor_switch_header_source, Strings.shortcut_desc_switch_header_source)
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
    fun matches(event: KeyEvent): Boolean {
        return event.action == KeyEvent.ACTION_DOWN &&
                event.keyCode == keyCode &&
                event.isCtrlPressed == ctrl &&
                event.isShiftPressed == shift &&
                event.isAltPressed == alt
    }

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
    fun toJson(): String {
        return JSONObject().apply {
            put("keyCode", keyCode)
            put("ctrl", ctrl)
            put("shift", shift)
            put("alt", alt)
        }.toString()
    }

    companion object {
        /**
         * 从 JSON 字符串反序列化
         */
        fun fromJson(json: String): KeyboardShortcut? {
            return try {
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
        }

        /**
         * 获取按键名称
         */
        fun getKeyName(keyCode: Int): String {
            return when (keyCode) {
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
    private val defaultShortcuts: Map<ShortcutAction, KeyboardShortcut> = mapOf(
        ShortcutAction.SAVE to KeyboardShortcut(KeyEvent.KEYCODE_S, ctrl = true),
        ShortcutAction.SAVE_ALL to KeyboardShortcut(KeyEvent.KEYCODE_S, ctrl = true, shift = true),
        ShortcutAction.CLOSE_TAB to KeyboardShortcut(KeyEvent.KEYCODE_W, ctrl = true),
        ShortcutAction.CLOSE_ALL_TABS to KeyboardShortcut(KeyEvent.KEYCODE_W, ctrl = true, shift = true),
        ShortcutAction.UNDO to KeyboardShortcut(KeyEvent.KEYCODE_Z, ctrl = true),
        ShortcutAction.REDO to KeyboardShortcut(KeyEvent.KEYCODE_Z, ctrl = true, shift = true),
        ShortcutAction.NEXT_TAB to KeyboardShortcut(KeyEvent.KEYCODE_TAB, ctrl = true),
        ShortcutAction.PREV_TAB to KeyboardShortcut(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true),
        ShortcutAction.TOGGLE_BOOKMARK to KeyboardShortcut(KeyEvent.KEYCODE_F2, ctrl = true),
        ShortcutAction.NEXT_BOOKMARK to KeyboardShortcut(KeyEvent.KEYCODE_F2),
        ShortcutAction.PREV_BOOKMARK to KeyboardShortcut(KeyEvent.KEYCODE_F2, shift = true),
        ShortcutAction.NAVIGATE_BACK to KeyboardShortcut(KeyEvent.KEYCODE_DPAD_LEFT, alt = true),
        ShortcutAction.NAVIGATE_FORWARD to KeyboardShortcut(KeyEvent.KEYCODE_DPAD_RIGHT, alt = true),
        ShortcutAction.PEEK_DEFINITION to KeyboardShortcut(KeyEvent.KEYCODE_F12, alt = true),
        ShortcutAction.GOTO_DEFINITION to KeyboardShortcut(KeyEvent.KEYCODE_F12),
        ShortcutAction.FIND_REFERENCES to KeyboardShortcut(KeyEvent.KEYCODE_F12, shift = true),
        ShortcutAction.GOTO_TYPE_DEFINITION to KeyboardShortcut(KeyEvent.KEYCODE_F12, ctrl = true, shift = true),
        ShortcutAction.GOTO_IMPLEMENTATION to KeyboardShortcut(KeyEvent.KEYCODE_F12, ctrl = true),
        ShortcutAction.CODE_ACTIONS to KeyboardShortcut(KeyEvent.KEYCODE_ENTER, alt = true),
        ShortcutAction.RENAME_SYMBOL to KeyboardShortcut(KeyEvent.KEYCODE_F6, shift = true),
        ShortcutAction.SWITCH_HEADER_SOURCE to KeyboardShortcut(KeyEvent.KEYCODE_O, alt = true)
    )

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
    fun getAllShortcuts(): Map<ShortcutAction, KeyboardShortcut> {
        return ShortcutAction.entries.associateWith { getShortcut(it) }
    }

    /**
     * 根据按键事件查找匹配的动作
     */
    fun findActionForEvent(event: KeyEvent): ShortcutAction? {
        return ShortcutAction.entries.find { action ->
            getShortcut(action).matches(event)
        }
    }

    /**
     * 检查快捷键是否与其他动作冲突
     */
    fun hasConflict(shortcut: KeyboardShortcut, excludeAction: ShortcutAction? = null): ShortcutAction? {
        return ShortcutAction.entries.find { action ->
            action != excludeAction && getShortcut(action) == shortcut
        }
    }

    /**
     * 获取默认快捷键
     */
    fun getDefaultShortcut(action: ShortcutAction): KeyboardShortcut {
        return defaultShortcuts[action]!!
    }

    /**
     * 检查指定动作的快捷键是否已被修改
     */
    fun isModified(action: ShortcutAction): Boolean {
        return getShortcut(action) != defaultShortcuts[action]
    }
}
