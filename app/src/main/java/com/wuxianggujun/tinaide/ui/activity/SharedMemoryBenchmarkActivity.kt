package com.wuxianggujun.tinaide.ui.activity

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.lsp.SharedMemoryTest
import kotlinx.coroutines.*

/**
 * 共享内存性能测试 Activity
 */
class SharedMemoryBenchmarkActivity : AppCompatActivity() {
    
    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnSimpleTest: Button
    private lateinit var btnFullBenchmark: Button
    private lateinit var btnClear: Button
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 简化版：使用代码创建 UI
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        // 按钮
        btnSimpleTest = Button(this).apply {
            text = "运行简单测试"
            setOnClickListener { runSimpleTest() }
        }
        btnFullBenchmark = Button(this).apply {
            text = "运行完整性能测试"
            setOnClickListener { runFullBenchmark() }
        }
        btnClear = Button(this).apply {
            text = "清空输出"
            setOnClickListener { outputText.text = "" }
        }
        
        layout.addView(btnSimpleTest)
        layout.addView(btnFullBenchmark)
        layout.addView(btnClear)
        
        // 输出
        scrollView = ScrollView(this)
        outputText = TextView(this).apply {
            textSize = 12f
            setPadding(16, 16, 16, 16)
        }
        scrollView.addView(outputText)
        layout.addView(scrollView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))
        
        setContentView(layout)
        
        appendOutput("========= 共享内存性能测试工具 =========")
        appendOutput("目标：验证共享内存相比传统 JNI 的性能提升\n")
    }
    
    private fun runSimpleTest() {
        btnSimpleTest.isEnabled = false
        appendOutput("\n========= 开始简单读写测试 =========")
        
        scope.launch(Dispatchers.IO) {
            try {
                val success = SharedMemoryTest.runSimpleTest(64 * 1024)
                
                withContext(Dispatchers.Main) {
                    if (success) {
                        appendOutput("✅ 测试通过！")
                    } else {
                        appendOutput("❌ 测试失败")
                    }
                    btnSimpleTest.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("❌ 异常: ${e.message}")
                    btnSimpleTest.isEnabled = true
                }
            }
        }
    }
    
    private fun runFullBenchmark() {
        btnFullBenchmark.isEnabled = false
        appendOutput("\n========= 开始完整性能测试 =========")
        
        scope.launch(Dispatchers.IO) {
            try {
                val result = SharedMemoryTest.runFullBenchmark()
                
                withContext(Dispatchers.Main) {
                    appendOutput("\n平均提升: ${"%.1f".format(result.averageImprovement)}%")
                    result.testCases.forEach { tc ->
                        appendOutput("${tc.dataSize / 1024} KB: 提升 ${"%.1f".format(tc.improvementPercent)}%")
                    }
                    btnFullBenchmark.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("❌ 异常: ${e.message}")
                    btnFullBenchmark.isEnabled = true
                }
            }
        }
    }
    
    private fun appendOutput(text: String) {
        outputText.append(text + "\n")
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
