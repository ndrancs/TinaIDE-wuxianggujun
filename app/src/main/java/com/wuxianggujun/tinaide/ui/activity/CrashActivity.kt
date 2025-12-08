package com.wuxianggujun.tinaide.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wuxianggujun.tinaide.BuildConfig
import com.wuxianggujun.tinaide.databinding.ActivityCrashBinding
import com.wuxianggujun.tinaide.ui.ProjectManagerActivity
import kotlin.system.exitProcess

/**
 * 崩溃信息展示页面。
 * 显示设备信息和崩溃堆栈，支持复制和重启。
 */
class CrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE) ?: "无崩溃信息"
        val crashTime = intent.getStringExtra(EXTRA_CRASH_TIME) ?: "未知"

        // 构建完整报告
        val fullReport = buildCrashReport(stackTrace, crashTime)

        binding.tvCrashInfo.text = fullReport

        binding.btnCopy.setOnClickListener {
            copyToClipboard(fullReport)
        }

        binding.btnRestart.setOnClickListener {
            restartApp()
        }

        binding.btnExit.setOnClickListener {
            finishAffinity()
            exitProcess(0)
        }
    }

    private fun buildCrashReport(stackTrace: String, crashTime: String): String {
        return buildString {
            appendLine("===== 设备信息 =====")
            appendLine("品牌: ${Build.BRAND}")
            appendLine("型号: ${Build.MODEL}")
            appendLine("设备: ${Build.DEVICE}")
            appendLine("Android 版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("CPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            appendLine("===== 应用信息 =====")
            appendLine("包名: ${BuildConfig.APPLICATION_ID}")
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("构建类型: ${BuildConfig.BUILD_TYPE}")
            appendLine()
            appendLine("===== 崩溃时间 =====")
            appendLine(crashTime)
            appendLine()
            appendLine("===== 崩溃堆栈 =====")
            append(stackTrace)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Crash Report", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun restartApp() {
        val intent = Intent(this, ProjectManagerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finishAffinity()
        exitProcess(0)
    }

    override fun onBackPressed() {
        // 禁用返回键，必须选择重启或退出
    }

    companion object {
        const val EXTRA_STACK_TRACE = "extra_stack_trace"
        const val EXTRA_CRASH_TIME = "extra_crash_time"

        fun start(context: Context, stackTrace: String, crashTime: String) {
            val intent = Intent(context, CrashActivity::class.java).apply {
                putExtra(EXTRA_STACK_TRACE, stackTrace)
                putExtra(EXTRA_CRASH_TIME, crashTime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }
    }
}
