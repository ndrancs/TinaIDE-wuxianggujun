package com.wuxianggujun.tinaide.output

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.appbar.MaterialToolbar
import com.wuxianggujun.tinaide.base.BaseActivity
import com.wuxianggujun.tinaide.databinding.ActivityOutputBinding
import io.github.rosemoe.sora.widget.CodeEditor
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.get
import io.github.rosemoe.sora.lang.EmptyLanguage

/**
 * 独立的编译输出界面（类似AIDE）
 * 使用 sora-editor 的 CodeEditor，支持：
 * - 行号显示
 * - 可选择、可编辑
 * - 高性能
 * - 滚动流畅
 */
class OutputActivity : BaseActivity<ActivityOutputBinding>(ActivityOutputBinding::inflate), IOutputManager.OutputListener {
    
    private lateinit var outputEditor: CodeEditor
    private lateinit var outputManager: IOutputManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)  // BaseActivity 已处理主题和状态栏
        
        val toolbar = binding.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        outputEditor = binding.outputEditor
        setupEditor()
        
        // 获取输出管理器
        outputManager = ServiceLocator.get<IOutputManager>()
        
        // 加载已有的输出内容
        val existingOutput = outputManager.getOutput()
        if (existingOutput.isNotEmpty()) {
            outputEditor.setText(existingOutput)
        }
        
        // 监听新的输出
        outputManager.addOutputListener(this)
    }
    
    private fun setupEditor() {
        // 配置编辑器
        outputEditor.apply {
            // 使用空语言（纯文本）
            setEditorLanguage(EmptyLanguage())
            
            // 设置为只读模式（可选择但不可编辑）
            isEditable = false
            
            // 显示行号
            isLineNumberEnabled = true
            
            // 设置颜色方案（深色主题）
            colorScheme = outputEditor.colorScheme.apply {
                setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND, 0xFF1E1E1E.toInt())
                setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL, 0xFFD4D4D4.toInt())
                setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER, 0xFF858585.toInt())
                setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND, 0xFF252526.toInt())
            }
            
            // 设置文字大小
            textSizePx = 36f
            
            // 禁用自动补全（Sora Editor 新版本可能不需要此属性）
            // isAutoCompletionEnabled = false
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.output_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_clear_output -> {
                outputManager.clearOutput()
                true
            }
            R.id.action_toggle_editable -> {
                outputEditor.isEditable = !outputEditor.isEditable
                item.title = if (outputEditor.isEditable) "锁定编辑" else "允许编辑"
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onOutputAppended(text: String) {
        runOnUiThread {
            // 使用 insert 方法追加到末尾
            val content = outputEditor.text
            val lastLine = content.lineCount - 1  // 0-based 行号
            val lastColumn = content.getColumnCount(lastLine)  // 最后一行的列数
            content.insert(lastLine, lastColumn, text)
            // 滚动到底部
            outputEditor.post {
                val lineCount = content.lineCount
                if (lineCount > 0) {
                    outputEditor.setSelection(lineCount - 1, content.getColumnCount(lineCount - 1))
                }
            }
        }
    }
    
    override fun onOutputCleared() {
        runOnUiThread {
            outputEditor.setText("")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        outputManager.removeOutputListener(this)
    }
}
