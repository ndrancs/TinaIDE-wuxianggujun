package com.wuxianggujun.tinaide.core.config

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * KeyboardShortcut 单元测试（Robolectric）
 *
 * 验证快捷键的序列化/反序列化、显示文本、按键匹配逻辑
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardShortcutTest {

    // ==================== toJson / fromJson 往返测试 ====================

    @Test
    fun `toJson and fromJson roundtrip for simple shortcut`() {
        val original = KeyboardShortcut(keyCode = KeyEvent.KEYCODE_S, ctrl = true)
        val json = original.toJson()
        val restored = KeyboardShortcut.fromJson(json)

        assertThat(restored).isNotNull()
        assertThat(restored!!.keyCode).isEqualTo(KeyEvent.KEYCODE_S)
        assertThat(restored.ctrl).isTrue()
        assertThat(restored.shift).isFalse()
        assertThat(restored.alt).isFalse()
    }

    @Test
    fun `toJson and fromJson roundtrip with all modifiers`() {
        val original = KeyboardShortcut(
            keyCode = KeyEvent.KEYCODE_Z,
            ctrl = true,
            shift = true,
            alt = true
        )
        val json = original.toJson()
        val restored = KeyboardShortcut.fromJson(json)

        assertThat(restored).isNotNull()
        assertThat(restored!!.keyCode).isEqualTo(KeyEvent.KEYCODE_Z)
        assertThat(restored.ctrl).isTrue()
        assertThat(restored.shift).isTrue()
        assertThat(restored.alt).isTrue()
    }

    @Test
    fun `fromJson returns null for invalid json`() {
        assertThat(KeyboardShortcut.fromJson("not-json")).isNull()
    }

    @Test
    fun `fromJson returns null for empty string`() {
        assertThat(KeyboardShortcut.fromJson("")).isNull()
    }

    // ==================== toDisplayString 测试 ====================

    @Test
    fun `toDisplayString for Ctrl+S`() {
        val shortcut = KeyboardShortcut(keyCode = KeyEvent.KEYCODE_S, ctrl = true)
        assertThat(shortcut.toDisplayString()).isEqualTo("Ctrl + S")
    }

    @Test
    fun `toDisplayString for Ctrl+Shift+Z`() {
        val shortcut = KeyboardShortcut(
            keyCode = KeyEvent.KEYCODE_Z,
            ctrl = true,
            shift = true
        )
        assertThat(shortcut.toDisplayString()).isEqualTo("Ctrl + Shift + Z")
    }

    @Test
    fun `toDisplayString for Alt+Tab`() {
        val shortcut = KeyboardShortcut(keyCode = KeyEvent.KEYCODE_TAB, alt = true)
        assertThat(shortcut.toDisplayString()).isEqualTo("Alt + Tab")
    }

    @Test
    fun `toDisplayString for key without modifiers`() {
        val shortcut = KeyboardShortcut(keyCode = KeyEvent.KEYCODE_F5)
        // 无修饰键时只显示键名
        val display = shortcut.toDisplayString()
        assertThat(display).doesNotContain("Ctrl")
        assertThat(display).doesNotContain("Shift")
        assertThat(display).doesNotContain("Alt")
    }

    // ==================== matches 测试 ====================

    @Test
    fun `matches returns true for matching KeyEvent`() {
        val shortcut = KeyboardShortcut(keyCode = KeyEvent.KEYCODE_S, ctrl = true)
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, 0, KeyEvent.META_CTRL_ON)
        assertThat(shortcut.matches(event)).isTrue()
    }

    @Test
    fun `matches returns false for wrong keyCode`() {
        val shortcut = KeyboardShortcut(keyCode = KeyEvent.KEYCODE_S, ctrl = true)
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON)
        assertThat(shortcut.matches(event)).isFalse()
    }

    @Test
    fun `matches returns false when modifier mismatch`() {
        val shortcut = KeyboardShortcut(keyCode = KeyEvent.KEYCODE_S, ctrl = true)
        // 没有 Ctrl 修饰键
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, 0, 0)
        assertThat(shortcut.matches(event)).isFalse()
    }

    @Test
    fun `matches returns false for key up event`() {
        val shortcut = KeyboardShortcut(keyCode = KeyEvent.KEYCODE_S, ctrl = true)
        val event = KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_S, 0, KeyEvent.META_CTRL_ON)

        assertThat(shortcut.matches(event)).isFalse()
    }

    // ==================== getKeyName 测试 ====================

    @Test
    fun `getKeyName returns correct name for letter keys`() {
        assertThat(KeyboardShortcut.getKeyName(KeyEvent.KEYCODE_A)).isEqualTo("A")
        assertThat(KeyboardShortcut.getKeyName(KeyEvent.KEYCODE_Z)).isEqualTo("Z")
    }

    @Test
    fun `getKeyName returns non-empty for Tab`() {
        val name = KeyboardShortcut.getKeyName(KeyEvent.KEYCODE_TAB)
        assertThat(name).isNotEmpty()
    }
}
