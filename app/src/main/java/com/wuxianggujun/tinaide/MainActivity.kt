package com.wuxianggujun.tinaide

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.wuxianggujun.tinaide.termux.BootstrapInstaller
import java.io.File
import android.widget.Toast
import android.graphics.Color
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.register
import com.wuxianggujun.tinaide.ui.IUIManager
import com.wuxianggujun.tinaide.ui.PanelType
import com.wuxianggujun.tinaide.ui.UIManager

class MainActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: Toolbar
    private lateinit var uiManager: IUIManager
    private var terminalSession: TerminalSession? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // 初始化服务
        initializeServices()
        
        // 初始化 Toolbar
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(android.R.drawable.ic_menu_sort_by_size)
        
        // 初始化 DrawerLayout
        drawerLayout = findViewById(R.id.drawer_layout)
        
        // 适配系统栏内边距
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        // 恢复 UI 布局状态
        uiManager.restoreLayoutState()
        
        // 初始化终端（延迟加载）
        initializeTerminal()
    }
    
    private fun initializeServices() {
        // 注册 ConfigManager
        val configManager = ConfigManager(this)
        ServiceLocator.register<IConfigManager>(configManager)
        
        // 注册 UIManager
        uiManager = UIManager(this)
        ServiceLocator.register<IUIManager>(uiManager)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 清理服务
        ServiceLocator.clear()
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                drawerLayout.openDrawer(findViewById(R.id.nav_view))
                true
            }
            R.id.action_new_project -> {
                showNewProjectDialog()
                true
            }
            R.id.action_open_project -> {
                showOpenProjectDialog()
                true
            }
            R.id.action_toggle_terminal -> {
                toggleTerminal()
                true
            }
            R.id.action_run -> {
                Toast.makeText(this, "运行功能开发中", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_build -> {
                Toast.makeText(this, "编译功能开发中", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showNewProjectDialog() {
        val dialog = com.wuxianggujun.tinaide.ui.dialog.ProjectDialog(
            com.wuxianggujun.tinaide.ui.dialog.ProjectDialog.Mode.NEW_PROJECT
        ) { projectDir ->
            refreshFileTree()
        }
        dialog.show(supportFragmentManager, "NewProject")
    }
    
    private fun showOpenProjectDialog() {
        val dialog = com.wuxianggujun.tinaide.ui.dialog.ProjectDialog(
            com.wuxianggujun.tinaide.ui.dialog.ProjectDialog.Mode.OPEN_PROJECT
        ) { projectDir ->
            refreshFileTree()
        }
        dialog.show(supportFragmentManager, "OpenProject")
    }
    
    private fun refreshFileTree() {
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val fileTreeFragment = supportFragmentManager.findFragmentById(R.id.file_tree_container) 
            as? com.wuxianggujun.tinaide.ui.fragment.FileTreeFragment
        fileTreeFragment?.refresh()
    }
    
    private fun toggleTerminal() {
        // 使用 UIManager 切换终端面板
        uiManager.togglePanel(PanelType.TERMINAL)
        
        // 如果终端变为可见且还没初始化，现在初始化
        if (uiManager.isPanelVisible(PanelType.TERMINAL) && terminalSession == null) {
            setupTerminalSession()
        }
    }
    
    private fun initializeTerminal() {
        // 终端默认隐藏，由 UIManager 管理
        // 不需要手动设置 visibility
    }
    
    private fun setupTerminalSession() {

        val terminalView = findViewById<TerminalView>(R.id.terminal_view)
        // 默认字体大小（可按需调整）
        var fontSize = 18

        // 提前声明用于回调访问的状态（避免未解析引用）
        var installResult: BootstrapInstaller.Result? = null
        var currentShellPath: String = "/system/bin/sh"
        terminalView.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                // 双指缩放：当缩放幅度明显时调整字号
                return if (scale < 0.9f || scale > 1.1f) {
                    val increase = scale > 1f
                    fontSize = (if (increase) fontSize + 1 else fontSize - 1).coerceIn(8, 48)
                    try { terminalView.setTextSize(fontSize) } catch (_: Throwable) {}
                    1.0f // 重置累计缩放
                } else scale
            }
            override fun onSingleTapUp(e: android.view.MotionEvent) {
                // 点按显示软键盘
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    terminalView.requestFocus()
                    imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
                } catch (_: Throwable) { }
            }
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            // 强制字符输入可避免部分输入法拦截组合输入，提升可输入性
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean = false
            override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
            override fun onLongPress(event: android.view.MotionEvent): Boolean = false
            override fun readControlKey(): Boolean = false
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
            override fun onEmulatorSet() {
                // Emulator is ready; ensure renderer initialized and show fallback output if needed
                try { terminalView.setTextSize(fontSize) } catch (_: Throwable) { }
                // 开启光标闪烁，提升可见性
                try {
                    terminalView.setTerminalCursorBlinkerRate(600)
                    terminalView.setTerminalCursorBlinkerState(true, false)
                } catch (_: Throwable) { }
                // 显示软键盘
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    terminalView.requestFocus()
                    imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
                } catch (_: Throwable) { }
                if (installResult?.installed != true && currentShellPath == "/system/bin/sh") {
                    val s = terminalView.currentSession ?: return
                    try {
                        s.write("echo '[TinaIDE] Terminal ready (sh)'; uname -a; id; pwd\r")
                    } catch (_: Throwable) { }
                }
            }
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        })
        // Initialize renderer before attaching session to avoid NPE in updateSize()
        try {
            terminalView.setTextSize(fontSize)
        } catch (_: Throwable) { }
        // 确保可获取焦点
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true

        // 构建一个最小 TerminalSessionClient，用于刷新绘制等回调
        val sessionClient = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                terminalView.invalidate()
            }
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {}
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
            override fun onPasteTextFromClipboard(session: TerminalSession?) {}
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) { terminalView.invalidate() }
            override fun onTerminalCursorStateChange(state: Boolean) { terminalView.invalidate() }
            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
                // 当子进程真正启动后再写入提示，确保不会丢失
                terminalView.post {
                    if (installResult?.installed != true && currentShellPath == "/system/bin/sh") {
                        try {
                            session.write("echo '[TinaIDE] Fallback to /system/bin/sh'; uname -a; id; pwd\r")
                        } catch (_: Throwable) { }
                    }
                }
            }
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }

        // 安装/检测离线 Termux 环境（assets/bootstrap 下放置对应 ABI 的 bootstrap 包）
        val install = BootstrapInstaller.installIfNeeded(this)
        installResult = install
        if (!install.installed) {
            Toast.makeText(this, install.message ?: "Missing bootstrap in assets/bootstrap", Toast.LENGTH_LONG).show()
        }
        // 准备 Termux 环境变量与 shell 路径
        val shellPath = BootstrapInstaller.resolveShell(this)
        currentShellPath = shellPath
        val homeDir = File(filesDir, "home").apply { if (!exists()) mkdirs() }
        val cwd = homeDir.absolutePath
        val env = if (install.installed) {
            BootstrapInstaller.buildEnv(this)
        } else {
            arrayOf(
                "TERM=xterm-256color",
                "HOME=${homeDir.absolutePath}",
                "PATH=/system/bin:/system/xbin"
            )
        }
        // 根据真实 shell 选择合适的参数
        val args: Array<String> = when {
            shellPath.endsWith("/login") -> emptyArray() // termux login 不需要 -l
            shellPath.endsWith("/bash") -> arrayOf("-l") // bash 登录模式
            shellPath == "/system/bin/sh" -> arrayOf("-i") // sh 交互模式，确保立即可用
            else -> emptyArray()
        }

        terminalSession = TerminalSession(
            shellPath,
            cwd,
            args,
            env,
            /* transcriptRows = */ 10000,
            sessionClient
        )

        // 确保背景为黑色，避免看起来是“白屏”
        terminalView.setBackgroundColor(Color.BLACK)
        terminalView.requestFocus()

        // 等待布局完成后再 attach，保证宽高非 0，从而立即创建 emulator 并绘制
        terminalView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (terminalView.width > 0 && terminalView.height > 0) {
                    terminalView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val attached = terminalView.attachSession(terminalSession)
                    if (!attached) {
                        Toast.makeText(this@MainActivity, "Terminal attach failed", Toast.LENGTH_SHORT).show()
                    } else if (!install.installed && shellPath == "/system/bin/sh") {
                        try {
                            terminalSession?.write("echo '[TinaIDE] Attached (sh)'; uname -a; id; pwd\r")
                        } catch (_: Throwable) { }
                    }
                }
            }
        })
    }
}
