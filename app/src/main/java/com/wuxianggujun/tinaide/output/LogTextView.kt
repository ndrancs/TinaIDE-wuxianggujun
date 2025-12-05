package com.wuxianggujun.tinaide.output

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
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
 * - 左侧显示日志等级字母（I/E/W/D/V/S）带颜色方框
 * - 右侧显示日志文本，颜色与等级对应
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
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }
    private val selectionPaint = Paint().apply {
        color = 0x40FFFFFF
    }
    
    // 尺寸参数
    private var badgeSize = 36f
    private var badgeRadius = 4f
    private var badgeMarginStart = 12f
    private var badgeMarginEnd = 8f
    private var lineSpacing = 4f
    private var verticalPadding = 8f
    
    // 滚动相关
    private val scroller = OverScroller(context)
    private var scrollX = 0f
    private var scrollY = 0f
    private var maxScrollX = 0f
    private var maxScrollY = 0f
    
    // 文本选择相关
    private var selectionStart = -1  // 选择起始位置（字符索引）
    private var selectionEnd = -1    // 选择结束位置（字符索引）
    private var isSelecting = false
    private var selectionStartLine = -1
    private var selectionStartCol = -1
    private var selectionEndLine = -1
    private var selectionEndCol = -1
    
    // 缓存的行信息
    private data class LineInfo(
        val entryIndex: Int,
        val text: String,
        val level: LogLevel,
        val y: Float,
        val textStartX: Float
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

    /**
     * 追加日志
     */
    fun appendLog(level: LogLevel, message: String) {
        if (logEntries.size >= maxLogCount) {
            logEntries.removeAt(0)
        }
        logEntries.add(LogEntry(level, message))
        rebuildLineCache()
        
        // 自动滚动到底部
        post {
            scrollToBottom()
            invalidate()
        }
    }

    /**
     * 清空日志
     */
    fun clearLog() {
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
        return logEntries.joinToString("\n") { "[${it.level.prefix}] ${it.message}" }
    }

    /**
     * 获取选中的文本
     */
    fun getSelectedText(): String {
        if (selectionStartLine < 0 || selectionEndLine < 0) return ""
        
        val sb = StringBuilder()
        for (i in selectionStartLine..selectionEndLine) {
            if (i >= lineInfoCache.size) break
            val line = lineInfoCache[i]
            val text = line.text
            
            val startCol = if (i == selectionStartLine) selectionStartCol.coerceIn(0, text.length) else 0
            val endCol = if (i == selectionEndLine) selectionEndCol.coerceIn(0, text.length) else text.length
            
            if (startCol < endCol) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(text.substring(startCol, endCol))
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
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 全选
     */
    fun selectAll() {
        if (lineInfoCache.isEmpty()) return
        selectionStartLine = 0
        selectionStartCol = 0
        selectionEndLine = lineInfoCache.size - 1
        selectionEndCol = lineInfoCache.last().text.length
        invalidate()
    }

    private fun clearSelection() {
        selectionStartLine = -1
        selectionStartCol = -1
        selectionEndLine = -1
        selectionEndCol = -1
        isSelecting = false
    }

    private fun scrollToBottom() {
        scrollY = max(0f, totalContentHeight - height)
    }

    private fun rebuildLineCache() {
        lineInfoCache.clear()
        
        val textStartX = badgeMarginStart + badgeSize + badgeMarginEnd
        var y = verticalPadding
        val lineHeight = textPaint.fontMetrics.let { it.descent - it.ascent } + lineSpacing
        
        maxLineWidth = 0f
        
        for ((index, entry) in logEntries.withIndex()) {
            val text = entry.message
            val textWidth = textPaint.measureText(text)
            maxLineWidth = max(maxLineWidth, textStartX + textWidth + badgeMarginStart)
            
            lineInfoCache.add(LineInfo(
                entryIndex = index,
                text = text,
                level = entry.level,
                y = y,
                textStartX = textStartX
            ))
            
            y += lineHeight
        }
        
        totalContentHeight = y + verticalPadding
        updateScrollBounds()
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
        
        val lineHeight = textPaint.fontMetrics.let { it.descent - it.ascent } + lineSpacing
        val visibleTop = scrollY - lineHeight
        val visibleBottom = scrollY + height + lineHeight
        
        for ((lineIndex, line) in lineInfoCache.withIndex()) {
            // 跳过不可见的行
            if (line.y < visibleTop || line.y > visibleBottom) continue
            
            val level = line.level
            val textY = line.y - textPaint.fontMetrics.ascent
            
            // 绘制选中背景
            if (isLineSelected(lineIndex)) {
                drawSelectionBackground(canvas, lineIndex, line, textY - textPaint.textSize)
            }
            
            // 绘制等级标签背景
            val badgeTop = line.y
            val badgeBottom = badgeTop + badgeSize
            badgeRect.set(badgeMarginStart, badgeTop, badgeMarginStart + badgeSize, badgeBottom)
            badgePaint.color = level.color
            canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgePaint)
            
            // 绘制等级字母
            val letter = level.prefix.first().toString()
            val letterY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(letter, badgeRect.centerX(), letterY, badgeTextPaint)
            
            // 绘制日志文本
            textPaint.color = level.color
            canvas.drawText(line.text, line.textStartX, textY, textPaint)
        }
        
        canvas.restore()
        
        // 绘制滚动条
        drawScrollbars(canvas)
    }

    private fun isLineSelected(lineIndex: Int): Boolean {
        if (selectionStartLine < 0 || selectionEndLine < 0) return false
        return lineIndex in selectionStartLine..selectionEndLine
    }

    private fun drawSelectionBackground(canvas: Canvas, lineIndex: Int, line: LineInfo, top: Float) {
        val lineHeight = textPaint.fontMetrics.let { it.descent - it.ascent } + lineSpacing
        
        val startCol = if (lineIndex == selectionStartLine) selectionStartCol else 0
        val endCol = if (lineIndex == selectionEndLine) selectionEndCol else line.text.length
        
        if (startCol >= endCol) return
        
        val startX = line.textStartX + textPaint.measureText(line.text.substring(0, startCol.coerceIn(0, line.text.length)))
        val endX = line.textStartX + textPaint.measureText(line.text.substring(0, endCol.coerceIn(0, line.text.length)))
        
        canvas.drawRect(startX, top, endX, top + lineHeight, selectionPaint)
    }

    private fun drawScrollbars(canvas: Canvas) {
        if (maxScrollY > 0) {
            val scrollbarHeight = height * height / totalContentHeight
            val scrollbarY = scrollY / maxScrollY * (height - scrollbarHeight)
            
            val scrollbarPaint = Paint().apply { color = 0x40FFFFFF }
            canvas.drawRect(
                width - 6f, scrollbarY,
                width - 2f, scrollbarY + scrollbarHeight,
                scrollbarPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        
        when (event.action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isSelecting = false
            }
        }
        
        return true
    }

    private fun getLineAndColumnAt(x: Float, y: Float): Pair<Int, Int>? {
        val adjustedX = x + scrollX
        val adjustedY = y + scrollY
        
        val lineHeight = textPaint.fontMetrics.let { it.descent - it.ascent } + lineSpacing
        
        for ((index, line) in lineInfoCache.withIndex()) {
            if (adjustedY >= line.y && adjustedY < line.y + lineHeight) {
                // 找到了行，计算列
                val relativeX = adjustedX - line.textStartX
                if (relativeX < 0) return Pair(index, 0)
                
                // 二分查找列位置
                var col = 0
                for (i in line.text.indices) {
                    val charWidth = textPaint.measureText(line.text.substring(0, i + 1))
                    if (charWidth > relativeX) {
                        col = i
                        break
                    }
                    col = i + 1
                }
                return Pair(index, col)
            }
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

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        
        override fun onDown(e: MotionEvent): Boolean = true
        
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!isSelecting) {
                scrollX = (scrollX + distanceX).coerceIn(0f, maxScrollX)
                scrollY = (scrollY + distanceY).coerceIn(0f, maxScrollY)
                invalidate()
            }
            return true
        }
        
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            scroller.fling(
                scrollX.toInt(), scrollY.toInt(),
                -velocityX.toInt(), -velocityY.toInt(),
                0, maxScrollX.toInt(),
                0, maxScrollY.toInt()
            )
            invalidate()
            return true
        }
        
        override fun onLongPress(e: MotionEvent) {
            // 长按开始选择
            val pos = getLineAndColumnAt(e.x, e.y) ?: return
            selectionStartLine = pos.first
            selectionStartCol = pos.second
            selectionEndLine = pos.first
            selectionEndCol = pos.second
            isSelecting = true
            invalidate()
            
            // 显示复制菜单
            showContextMenu()
        }
        
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // 双击选择单词
            val pos = getLineAndColumnAt(e.x, e.y) ?: return false
            val line = lineInfoCache.getOrNull(pos.first) ?: return false
            val text = line.text
            val col = pos.second.coerceIn(0, text.length)
            
            // 找到单词边界
            var start = col
            var end = col
            
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            while (end < text.length && !text[end].isWhitespace()) end++
            
            selectionStartLine = pos.first
            selectionStartCol = start
            selectionEndLine = pos.first
            selectionEndCol = end
            invalidate()
            
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
            textPaint.textSize = (textPaint.textSize * scaleFactor).coerceIn(20f, 60f)
            badgeTextPaint.textSize = textPaint.textSize * 0.8f
            badgeSize = textPaint.textSize * 1.2f
            rebuildLineCache()
            invalidate()
            return true
        }
    }

    data class LogEntry(
        val level: LogLevel,
        val message: String
    )
}
