package com.wuxianggujun.tinaide.core.debug

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 断点存储
 *
 * 独立于调试会话的断点管理，支持:
 * - 在代码编辑时添加/删除断点
 * - 调试会话启动时同步到调试器
 * - 持久化断点配置
 */
class BreakpointStore {

    private val _breakpoints = MutableStateFlow<List<Breakpoint>>(emptyList())
    val breakpoints: StateFlow<List<Breakpoint>> = _breakpoints.asStateFlow()

    private var nextId = AtomicInteger(1)

    /**
     * 添加断点
     */
    fun add(file: String, line: Int, condition: String? = null): Breakpoint {
        val bp = Breakpoint(
            id = nextId.getAndIncrement(),
            file = file,
            line = line,
            condition = condition
        )
        _breakpoints.update { it + bp }
        return bp
    }

    /**
     * 移除断点
     */
    fun remove(id: Int): Boolean {
        var found = false
        _breakpoints.update { list ->
            val newList = list.filter { it.id != id }
            found = newList.size < list.size
            newList
        }
        return found
    }

    /**
     * 按文件和行号移除断点
     */
    fun removeByLocation(file: String, line: Int): Boolean {
        var found = false
        _breakpoints.update { list ->
            val newList = list.filter { !(it.file == file && it.line == line) }
            found = newList.size < list.size
            newList
        }
        return found
    }

    /**
     * 切换指定位置的断点
     * 如果存在则删除，不存在则添加
     */
    fun toggle(file: String, line: Int): Breakpoint? {
        val existing = _breakpoints.value.find { it.file == file && it.line == line }
        return if (existing != null) {
            remove(existing.id)
            null
        } else {
            add(file, line)
        }
    }

    /**
     * 切换断点启用状态
     */
    fun toggleEnabled(id: Int): Boolean {
        var found = false
        _breakpoints.update { list ->
            list.map { bp ->
                if (bp.id == id) {
                    found = true
                    bp.copy(enabled = !bp.enabled)
                } else {
                    bp
                }
            }
        }
        return found
    }

    /**
     * 获取指定文件的所有断点
     */
    fun getForFile(file: String): List<Breakpoint> = _breakpoints.value.filter { it.file == file }

    /**
     * 获取指定位置的断点
     */
    fun getAt(file: String, line: Int): Breakpoint? = _breakpoints.value.find { it.file == file && it.line == line }

    /**
     * 检查指定位置是否有断点
     */
    fun hasBreakpoint(file: String, line: Int): Boolean = _breakpoints.value.any { it.file == file && it.line == line }

    /**
     * 更新断点的验证状态和地址
     */
    fun updateVerified(id: Int, address: Long, verified: Boolean) {
        _breakpoints.update { list ->
            list.map { bp ->
                if (bp.id == id) {
                    bp.copy(address = address, verified = verified)
                } else {
                    bp
                }
            }
        }
    }

    /**
     * 更新断点命中次数
     */
    fun incrementHitCount(id: Int) {
        _breakpoints.update { list ->
            list.map { bp ->
                if (bp.id == id) {
                    bp.copy(hitCount = bp.hitCount + 1)
                } else {
                    bp
                }
            }
        }
    }

    /**
     * 清除所有断点的验证状态
     * 在调试会话结束时调用
     */
    fun clearVerification() {
        _breakpoints.update { list ->
            list.map { bp ->
                bp.copy(verified = false, address = 0)
            }
        }
    }

    /**
     * 清除所有断点
     */
    fun clear() {
        _breakpoints.value = emptyList()
        nextId.set(1)
    }

    /**
     * 获取所有启用的断点
     */
    fun getEnabled(): List<Breakpoint> = _breakpoints.value.filter { it.enabled }
}
