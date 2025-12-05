package com.wuxianggujun.tinaide.output

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

/**
 * 自定义日志视图
 * 类似 Android Studio Logcat 风格：
 * - 时间（白色）
 * - TAG（高亮色）
 * - 等级字母带颜色方框
 * - 消息（高亮色）
 * - 支持文本选择和复制
 * - 支持滚动和缩放
 */
class LogTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 日志数据
    private val logEntries = mutableListOf<LogEntry>()
    private val maxLogCount = 5000
    
    // 画笔
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()  // 灰白色
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }
    private val selectionPaint = Paint().apply {
        color = 0x60448AFF  // 蓝色半透明
    }
    
    // 尺寸参数
    private var badgeSize = 24f
    private var badgeRadius = 4f
    private var itemSpacing = 6f
    private var lineSpacing = 2f
    private var horizontalPadding = 6f
    private var verticalPadding = 6f
    private var tagColumnWidth = 0f  // TAG 列的固定宽度，用于对齐
    
    // 滚动相关
    private val scroller = OverScroller(context)
    private var scrollX = 0f
    private var scrollY = 0f
    private var maxScrollX = 0f
    private var maxScrollY = 0f
    
    // 文本选择相关
    private var isSelecting = false
    private var isDraggingSelection = false
    private var selectionAnchorLine = -1
    private var selectionAnchorCol = -1
    private var selectionStartLine = -1
    private var selectionStartCol = -1
    private var selectionEndLine = -1
    private var selectionEndCol = -1
    
    // ActionMode 用于显示复制菜单
    private var actionMode: ActionMode? = null
    
    // 缓存的行信息
    private data class LineInfo(
        val entryIndex: Int,
        val fullText: String,
        val time: String,
        val tag: String,
        val level: LogLevel,
        val message: String,
        val y: Float
    )
    private val lineInfoCache = mutableListOf<LineInfo>()
    private var totalContentHeight = 0f
    private var maxLineWidth = 0f
    
    // 手势检测
    private val gestureDetector = GestureDetector(context, GestureListener())
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    
    private val badgeRect = RectF()

    init {
        setBackgroundColor(0xFF1E1E1E.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        isLongClickable = true
    }

    // 批量更新相关
    private val pendingEntries = mutableListOf<LogEntry>()
    private var updatePending = false
    private var isViewVisible = false  // 追踪视图是否可见
    private var hasPendingRefresh = false  // 是否有待刷新的数据
    
    private val updateRunnable = Runnable {
        updatePending = false
        processPendingEntries()
    }
    
    /**
     * 追加结构化日志（批量优化版本）
     * 当视图不可见时，只缓存数据，不触发 UI 更新
     */
    fun appendLog(level: LogLevel, timestamp: String, tag: String, message: String) {
        synchronized(pendingEntries) {
            pendingEntries.add(LogEntry(level, timestamp, tag, message))
        }
        
        // 如果视图不可见，只标记有待刷新数据，不触发 UI 更新
        if (!isViewVisible) {
            hasPendingRefresh = true
            return
        }
        
        // 使用节流，避免频繁刷新
        if (!updatePending) {
            updatePending = true
            postDelayed(updateRunnable, 50) // 50ms 批量处理一次
        }
    }
    
    /**
     * 当视图变为可见时调用，刷新待处理的日志
     */
    fun onBecomeVisible() {
        isViewVisible = true
        if (hasPendingRefresh) {
            hasPendingRefresh = false
            // 立即处理待刷新的数据
            if (!updatePending) {
                updatePending = true
                post(updateRunnable)
            }
        }
    }
    
    /**
     * 当视图变为不可见时调用
     */
    fun onBecomeInvisible() {
        isViewVisible = false
        // 取消待处理的 UI 更新
        removeCallbacks(updateRunnable)
        updatePending = false
    }
    
    private fun processPendingEntries() {
        val entries: List<LogEntry>
        synchronized(pendingEntries) {
            if (pendingEntries.isEmpty()) return
            entries = pendingEntries.toList()
            pendingEntries.clear()
        }
        
        // 检查是否需要删除旧条目
        var needFullRebuild = false
        val totalAfterAdd = logEntries.size + entries.size
        if (totalAfterAdd > maxLogCount) {
            val removeCount = totalAfterAdd - maxLogCount
            repeat(removeCount.coerceAtMost(logEntries.size)) {
                logEntries.removeAt(0)
            }
            needFullRebuild = true // 删除了旧条目，需要完全重建
        }
        
        // 批量添加
        logEntries.addAll(entries)
        
        // 根据情况选择重建方式
        if (needFullRebuild) {
            lineInfoCache.clear() // 强制完全重建
        }
        rebuildLineCache()
        
        // 只有在没有选择文本时才自动滚动到底部
        if (!hasSelection()) {
            scrollToBottom()
        }
        invalidate()
    }

    /**
     * 追加简单日志（兼容旧接口）
     */
    fun appendLog(level: LogLevel, message: String) {
        appendLog(level, "", "", message)
    }

    /**
     * 清空日志
     */
    fun clearLog() {
        // 取消待处理的更新
        removeCallbacks(updateRunnable)
        updatePending = false
        synchronized(pendingEntries) {
            pendingEntries.clear()
        }
        
        logEntries.clear()
        lineInfoCache.clear()
        scrollX = 0f
        scrollY = 0f
        clearSelection()
        invalidate()
    }

    /**
     * 获取日志内容
     */
    fun getLogContent(): String {
        return lineInfoCache.joinToString("\n") { it.fullText }
    }

    /**
     * 获取选中的文本
     */
    fun getSelectedText(): String {
        if (!hasSelection()) return ""
        
        // 确保 start <= end
        val (startLine, startCol, endLine, endCol) = normalizeSelection()
        
        val sb = StringBuilder()
        for (i in startLine..endLine) {
            if (i >= lineInfoCache.size) break
            val line = lineInfoCache[i]
            val text = line.fullText
            
            val colStart = if (i == startLine) startCol.coerceIn(0, text.length) else 0
            val colEnd = if (i == endLine) endCol.coerceIn(0, text.length) else text.length
            
            if (colStart <= colEnd) {
                if (sb.isNotEmpty()) sb.append("\n")
                if (colStart < text.length) {
                    sb.append(text.substring(colStart, colEnd.coerceAtMost(text.length)))
                }
            }
        }
        return sb.toString()
    }

    /**
     * 复制选中文本到剪贴板
     */
    fun copySelection() {
        val text = getSelectedText()
        if (text.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("log", text))
            Toast.makeText(context, "已复制 ${text.lines().size} 行", Toast.LENGTH_SHORT).show()
        }
        clearSelection()
        actionMode?.finish()
    }

    /**
     * 全选
     */
    fun selectAll() {
        if (lineInfoCache.isEmpty()) return
        selectionStartLine = 0
        selectionStartCol = 0
        selectionEndLine = lineInfoCache.size - 1
        selectionEndCol = lineInfoCache.last().fullText.length
        selectionAnchorLine = 0
        selectionAnchorCol = 0
        invalidate()
        startActionMode()
    }

    private fun hasSelection(): Boolean {
        return selectionStartLine >= 0 && selectionEndLine >= 0
    }

    /**
     * 规范化选择范围，确保 start <= end
     */
    private fun normalizeSelection(): SelectionRange {
        if (selectionStartLine < selectionEndLine ||
            (selectionStartLine == selectionEndLine && selectionStartCol <= selectionEndCol)) {
            return SelectionRange(selectionStartLine, selectionStartCol, selectionEndLine, selectionEndCol)
        }
        return SelectionRange(selectionEndLine, selectionEndCol, selectionStartLine, selectionStartCol)
    }

    private data class SelectionRange(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)

    private fun clearSelection() {
        selectionStartLine = -1
        selectionStartCol = -1
        selectionEndLine = -1
        selectionEndCol = -1
        selectionAnchorLine = -1
        selectionAnchorCol = -1
        isSelecting = false
        isDraggingSelection = false
        actionMode?.finish()
        actionMode = null
    }

    private fun scrollToBottom() {
        scrollY = max(0f, totalContentHeight - height)
    }

    private fun rebuildLineCache() {
        val lineHeight = messagePaint.fontMetrics.let { it.descent - it.ascent } + lineSpacing
        
        // 如果日志被清空或缓存需要完全重建
        val needFullRebuild = lineInfoCache.size > logEntries.size || lineInfoCache.isEmpty()
        
        if (needFullRebuild) {
            lineInfoCache.clear()
            maxLineWidth = 0f
            tagColumnWidth = 0f
            
            // 计算最大 TAG 宽度
            for (entry in logEntries) {
                if (entry.tag.isNotEmpty()) {
                    val tagWidth = tagPaint.measureText(entry.tag)
                    tagColumnWidth = max(tagColumnWidth, tagWidth)
                }
            }
            
            var y = verticalPadding
            for ((index, entry) in logEntries.withIndex()) {
                val fullText = buildFullText(entry)
                val lineWidth = calculateLineWidth(entry.timestamp, entry.tag, entry.message)
                maxLineWidth = max(maxLineWidth, lineWidth + horizontalPadding * 2)
                
                lineInfoCache.add(LineInfo(
                    entryIndex = index,
                    fullText = fullText,
                    time = entry.timestamp,
                    tag = entry.tag,
                    level = entry.level,
                    message = entry.message,
                    y = y
                ))
                y += lineHeight
            }
            totalContentHeight = y + verticalPadding
        } else {
            // 增量更新：只添加新条目
            val startIndex = lineInfoCache.size
            var y = if (lineInfoCache.isEmpty()) verticalPadding else lineInfoCache.last().y + lineHeight
            
            for (i in startIndex until logEntries.size) {
                val entry = logEntries[i]
                
                // 更新 TAG 列宽度
                if (entry.tag.isNotEmpty()) {
                    val tagWidth = tagPaint.measureText(entry.tag)
                    tagColumnWidth = max(tagColumnWidth, tagWidth)
                }
                
                val fullText = buildFullText(entry)
                val lineWidth = calculateLineWidth(entry.timestamp, entry.tag, entry.message)
                maxLineWidth = max(maxLineWidth, lineWidth + horizontalPadding * 2)
                
                lineInfoCache.add(LineInfo(
                    entryIndex = i,
                    fullText = fullText,
                    time = entry.timestamp,
                    tag = entry.tag,
                    level = entry.level,
                    message = entry.message,
                    y = y
                ))
                y += lineHeight
            }
            totalContentHeight = y + verticalPadding
        }
        
        updateScrollBounds()
    }

    private fun buildFullText(entry: LogEntry): String {
        val sb = StringBuilder()
        if (entry.timestamp.isNotEmpty()) {
            sb.append(entry.timestamp).append(" ")
        }
        if (entry.tag.isNotEmpty()) {
            sb.append(entry.tag).append(" ")
        }
        sb.append("[${entry.level.prefix.first()}] ")
        sb.append(entry.message)
        return sb.toString()
    }

    private fun calculateLineWidth(time: String, tag: String, message: String): Float {
        var width = horizontalPadding
        if (time.isNotEmpty()) {
            width += timePaint.measureText(time) + itemSpacing
        }
        // 使用固定的 TAG 列宽度
        width += tagColumnWidth + itemSpacing
        width += badgeSize + itemSpacing
        width += messagePaint.measureText(message)
        return width
    }

    private fun updateScrollBounds() {
        maxScrollX = max(0f, maxLineWidth - width)
        maxScrollY = max(0f, totalContentHeight - height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScrollBounds()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (lineInfoCache.isEmpty()) return
        
        canvas.save()
        canvas.translate(-scrollX, -scrollY)
        
        val lineHeight = messagePaint.fontMetrics.let { it.descent - it.ascent } + lineSpacing
        val visibleTop = scrollY - lineHeight
        val visibleBottom = scrollY + height + lineHeight
        
        for ((lineIndex, line) in lineInfoCache.withIndex()) {
            if (line.y < visibleTop || line.y > visibleBottom) continue
            
            val level = line.level
            val textY = line.y - messagePaint.fontMetrics.ascent
            var x = horizontalPadding
            
            // 绘制选中背景
            if (isLineInSelection(lineIndex)) {
                drawSelectionBackground(canvas, lineIndex, line, line.y, lineHeight)
            }
            
            // 1. 绘制时间（灰白色）
            if (line.time.isNotEmpty()) {
                canvas.drawText(line.time, x, textY, timePaint)
                x += timePaint.measureText(line.time) + itemSpacing
            }
            
            // 2. 绘制 TAG（高亮色，固定宽度对齐）
            if (line.tag.isNotEmpty()) {
                tagPaint.color = level.color
                canvas.drawText(line.tag, x, textY, tagPaint)
            }
            // TAG 使用固定宽度，保证等级方框对齐
            x += tagColumnWidth + itemSpacing
            
            // 3. 绘制等级标签方框
            val badgeTop = line.y + (lineHeight - badgeSize) / 2 - lineSpacing / 2
            val badgeBottom = badgeTop + badgeSize
            badgeRect.set(x, badgeTop, x + badgeSize, badgeBottom)
            badgePaint.color = level.color
            canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgePaint)
            
            // 绘制等级字母
            val letter = level.prefix.first().toString()
            val letterY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(letter, badgeRect.centerX(), letterY, badgeTextPaint)
            x += badgeSize + itemSpacing
            
            // 4. 绘制消息（高亮色）
            messagePaint.color = level.color
            canvas.drawText(line.message, x, textY, messagePaint)
        }
        
        canvas.restore()
        drawScrollbars(canvas)
    }

    private fun isLineInSelection(lineIndex: Int): Boolean {
        if (!hasSelection()) return false
        val (startLine, _, endLine, _) = normalizeSelection()
        return lineIndex in startLine..endLine
    }

    private fun drawSelectionBackground(canvas: Canvas, lineIndex: Int, line: LineInfo, top: Float, lineHeight: Float) {
        val text = line.fullText
        val (startLine, startCol, endLine, endCol) = normalizeSelection()
        
        val colStart = when {
            lineIndex < startLine -> return
            lineIndex > endLine -> return
            lineIndex == startLine -> startCol.coerceIn(0, text.length)
            else -> 0
        }
        
        val colEnd = when {
            lineIndex == endLine -> endCol.coerceIn(0, text.length)
            else -> text.length
        }
        
        if (colStart > colEnd) return
        
        val startX = horizontalPadding + timePaint.measureText(text.substring(0, colStart))
        val endX = horizontalPadding + timePaint.measureText(text.substring(0, colEnd))
        
        canvas.drawRect(startX, top, endX, top + lineHeight, selectionPaint)
    }

    private fun drawScrollbars(canvas: Canvas) {
        if (maxScrollY > 0) {
            val scrollbarHeight = (height * height / totalContentHeight).coerceAtLeast(40f)
            val scrollbarY = scrollY / maxScrollY * (height - scrollbarHeight)
            
            val scrollbarPaint = Paint().apply { color = 0x60FFFFFF }
            canvas.drawRoundRect(
                width - 8f, scrollbarY,
                width - 2f, scrollbarY + scrollbarHeight,
                3f, 3f, scrollbarPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 如果正在选择，处理拖动
        if (isDraggingSelection && event.action == MotionEvent.ACTION_MOVE) {
            val pos = getLineAndColumnAt(event.x, event.y)
            if (pos != null) {
                selectionEndLine = pos.first
                selectionEndCol = pos.second
                invalidate()
            }
            return true
        }
        
        if (isDraggingSelection && (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)) {
            isDraggingSelection = false
            if (hasSelection() && getSelectedText().isNotEmpty()) {
                startActionMode()
            }
            return true
        }
        
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        
        return true
    }

    private fun getLineAndColumnAt(x: Float, y: Float): Pair<Int, Int>? {
        val adjustedX = x + scrollX
        val adjustedY = y + scrollY
        
        val lineHeight = messagePaint.fontMetrics.let { it.descent - it.ascent } + lineSpacing
        
        // 如果在内容上方，返回第一行第一列
        if (adjustedY < verticalPadding && lineInfoCache.isNotEmpty()) {
            return Pair(0, 0)
        }
        
        for ((index, line) in lineInfoCache.withIndex()) {
            if (adjustedY >= line.y && adjustedY < line.y + lineHeight) {
                val relativeX = adjustedX - horizontalPadding
                if (relativeX < 0) return Pair(index, 0)
                
                val text = line.fullText
                var col = 0
                for (i in text.indices) {
                    val charWidth = timePaint.measureText(text.substring(0, i + 1))
                    if (charWidth > relativeX) {
                        col = i
                        break
                    }
                    col = i + 1
                }
                return Pair(index, col.coerceAtMost(text.length))
            }
        }
        
        // 如果在内容下方，返回最后一行最后一列
        if (lineInfoCache.isNotEmpty()) {
            val lastLine = lineInfoCache.last()
            return Pair(lineInfoCache.size - 1, lastLine.fullText.length)
        }
        
        return null
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat()
            scrollY = scroller.currY.toFloat()
            invalidate()
        }
    }

    /**
     * 启动悬浮式 ActionMode 显示复制菜单
     */
    private fun startActionMode() {
        if (actionMode != null) return
        
        val callback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, "复制")
                menu.add(0, 2, 1, "全选")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return when (item.itemId) {
                    1 -> {
                        copySelection()
                        true
                    }
                    2 -> {
                        selectAll()
                        true
                    }
                    else -> false
                }
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
            }
            
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
                // 计算选中区域的位置，让悬浮菜单显示在选中文本附近
                // 使用屏幕坐标，确保在可见区域内
                if (hasSelection()) {
                    val (startLine, _, _, _) = normalizeSelection()
                    val line = lineInfoCache.getOrNull(startLine)
                    if (line != null) {
                        val lineHeight = messagePaint.fontMetrics.let { it.descent - it.ascent } + lineSpacing
                        val top = (line.y - scrollY).toInt().coerceIn(0, height)
                        val bottom = (top + lineHeight.toInt()).coerceIn(0, height)
                        // 确保矩形在视图范围内，避免触发滚动
                        if (top >= 0 && bottom <= height) {
                            outRect.set(horizontalPadding.toInt(), top, width - horizontalPadding.toInt(), bottom)
                            return
                        }
                    }
                }
                // 默认显示在视图中央
                val centerY = height / 2
                outRect.set(horizontalPadding.toInt(), centerY - 20, width - horizontalPadding.toInt(), centerY + 20)
            }
        }
        
        // 使用悬浮式 ActionMode
        actionMode = startActionMode(callback, ActionMode.TYPE_FLOATING)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!isDraggingSelection) {
                scrollX = (scrollX + distanceX).coerceIn(0f, maxScrollX)
                scrollY = (scrollY + distanceY).coerceIn(0f, maxScrollY)
                invalidate()
            }
            return true
        }
        
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!isDraggingSelection) {
                scroller.fling(
                    scrollX.toInt(), scrollY.toInt(),
                    -velocityX.toInt(), -velocityY.toInt(),
                    0, maxScrollX.toInt(),
                    0, maxScrollY.toInt()
                )
                invalidate()
            }
            return true
        }
        
        override fun onLongPress(e: MotionEvent) {
            val pos = getLineAndColumnAt(e.x, e.y) ?: return
            
            // 开始选择：先选中当前单词
            val line = lineInfoCache.getOrNull(pos.first) ?: return
            val text = line.fullText
            val col = pos.second.coerceIn(0, text.length)
            
            // 找到单词边界
            var start = col
            var end = col
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            while (end < text.length && !text[end].isWhitespace()) end++
            
            selectionAnchorLine = pos.first
            selectionAnchorCol = start
            selectionStartLine = pos.first
            selectionStartCol = start
            selectionEndLine = pos.first
            selectionEndCol = end
            
            isDraggingSelection = true
            isSelecting = true
            
            // 震动反馈
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            
            invalidate()
        }
        
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val pos = getLineAndColumnAt(e.x, e.y) ?: return false
            val line = lineInfoCache.getOrNull(pos.first) ?: return false
            val text = line.fullText
            val col = pos.second.coerceIn(0, text.length)
            
            // 双击选择整行
            selectionStartLine = pos.first
            selectionStartCol = 0
            selectionEndLine = pos.first
            selectionEndCol = text.length
            selectionAnchorLine = pos.first
            selectionAnchorCol = 0
            
            invalidate()
            startActionMode()
            return true
        }
        
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            clearSelection()
            invalidate()
            return true
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newSize = (messagePaint.textSize * scaleFactor).coerceIn(16f, 40f)
            timePaint.textSize = newSize
            tagPaint.textSize = newSize
            messagePaint.textSize = newSize
            badgeTextPaint.textSize = newSize * 0.75f
            badgeSize = newSize
            rebuildLineCache()
            invalidate()
            return true
        }
    }

    data class LogEntry(
        val level: LogLevel,
        val timestamp: String,
        val tag: String,
        val message: String
    )
}
