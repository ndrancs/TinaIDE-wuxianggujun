package com.wuxianggujun.tinaide

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.wuxianggujun.tinaide.base.BaseActivity
import com.wuxianggujun.tinaide.extensions.toast
import com.wuxianggujun.tinaide.extensions.toastSuccess
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.extensions.toastWarning
import com.wuxianggujun.tinaide.extensions.handleErrorWithToast
import com.wuxianggujun.tinaide.ui.dialog.MaterialDialogBuilder
import com.wuxianggujun.tinaide.utils.FileUtils
import com.wuxianggujun.tinaide.utils.Logger
import java.io.File

import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.register
import com.wuxianggujun.tinaide.core.registerSingleton
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.file.IFileManager
import com.wuxianggujun.tinaide.file.FileManager
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.editor.EditorManager
import com.wuxianggujun.tinaide.ui.IUIManager
import com.wuxianggujun.tinaide.ui.UIManager
import com.wuxianggujun.tinaide.output.IOutputManager
import com.wuxianggujun.tinaide.output.OutputManager
class MainActivity : BaseActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: Toolbar
    private lateinit var uiManager: IUIManager
    private lateinit var outputManager: IOutputManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)  // BaseActivity 已处理主题和沉浸式状态栏

        if (!ServiceLocator.isRegistered(IConfigManager::class.java)) {
            ServiceLocator.register<IConfigManager>(ConfigManager(this))
        }
        if (!ServiceLocator.isRegistered(IFileManager::class.java)) {
            ServiceLocator.registerSingleton<IFileManager> { FileManager(applicationContext) }
        }

        setContentView(R.layout.activity_main)

        initializeServices()

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(android.R.drawable.ic_menu_sort_by_size)

        // 直接在 Toolbar 上填充菜单，无需等待系统回调
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item ->
            onOptionsItemSelected(item)
        }

        drawerLayout = findViewById(R.id.drawer_layout)
        setupFileTreeHeader()

        uiManager.restoreLayoutState()
        refreshFileTree()
    }
    private fun initializeServices() {
        if (!ServiceLocator.isRegistered(IConfigManager::class.java)) {
            val configManager = ConfigManager(this)
            ServiceLocator.register<IConfigManager>(configManager)
        }
        uiManager = UIManager(this)
        ServiceLocator.register<IUIManager>(uiManager)

        if (!ServiceLocator.isRegistered(IFileManager::class.java)) {
            ServiceLocator.registerSingleton<IFileManager> { FileManager(applicationContext) }
        }

        val editorManager = EditorManager(this, supportFragmentManager)
        ServiceLocator.register<IEditorManager>(editorManager)
        
        // 注册输出管理器
        if (!ServiceLocator.isRegistered(IOutputManager::class.java)) {
            outputManager = OutputManager(applicationContext)
            ServiceLocator.register<IOutputManager>(outputManager)
        } else {
            outputManager = ServiceLocator.get<IOutputManager>()
        }
    }

    private fun showOpenProjectDialog() {
        val dialog = com.wuxianggujun.tinaide.ui.dialog.ProjectDialog(
            com.wuxianggujun.tinaide.ui.dialog.ProjectDialog.Mode.OPEN_PROJECT
        ) { _ ->
            refreshFileTree()
        }
        dialog.show(supportFragmentManager, "OpenProject")
    }

    private fun refreshFileTree() {
        val fileTreeFragment = supportFragmentManager.findFragmentById(R.id.file_tree_container)
            as? com.wuxianggujun.tinaide.ui.fragment.FileTreeFragment
        fileTreeFragment?.refresh()
        updateProjectHeaderName()
    }
    private fun setupFileTreeHeader() {
        findViewById<ImageButton>(R.id.btn_add_file)?.setOnClickListener {
            showAddFileDialog()
        }
        findViewById<ImageButton>(R.id.btn_refresh_file_tree)?.setOnClickListener {
            refreshFileTree()
        }
        findViewById<ImageButton>(R.id.btn_view_mode)?.setOnClickListener {
            toastInfo("查看功能开发中")
        }
        updateProjectHeaderName()
    }

    private fun updateProjectHeaderName() {
        val nameView = findViewById<TextView>(R.id.tv_project_name)
        if (nameView == null) {
            android.util.Log.e("MainActivity", "tv_project_name not found!")
            return
        }
        val fm = ServiceLocator.get<IFileManager>()
        val project = fm.getCurrentProject()
        val projectName = project?.name ?: "未打开项目"
        nameView.text = projectName
        nameView.visibility = View.VISIBLE
        android.util.Log.d("MainActivity", "Project name updated to: $projectName")
    }
    private fun showAddFileDialog() {
        val fm = ServiceLocator.get<IFileManager>()
        val project = fm.getCurrentProject()
        if (project == null) {
            toastError("请先打开项目")
            return
        }
        
        // 使用 Material Design 输入对话框
        MaterialDialogBuilder.showInput(
            context = this,
            title = "添加文件",
            hint = "文件名，例如 main.cpp",
            validator = { input ->
                when {
                    input.isEmpty() -> "文件名不能为空"
                    !input.matches(Regex("[a-zA-Z0-9_.-]+")) -> "文件名包含非法字符"
                    else -> null // 验证通过
                }
            },
            onConfirm = { name ->
                val root = File(project.rootPath)
                FileUtils.createFile(root, name)
                    .onSuccess { file ->
                        toastSuccess("已创建 ${file.name}")
                        refreshFileTree()
                    }
                    .onFailure { error ->
                        handleErrorWithToast(error, "创建失败")
                    }
            }
        )
    }
    override fun onDestroy() {
        super.onDestroy()
        ServiceLocator.clear()
    }

    private fun onCompileProject() {
        // 清空之前的输出
        outputManager.clearOutput()
        
        // 显示输出窗口
        outputManager.showOutput()
        
        // 在后台线程执行编译，避免阻塞 UI
        Thread {
            // 加载本地库和 sysroot
            com.wuxianggujun.tinaide.core.nativebridge.NativeLoader.loadIfNeeded()
            val sysrootDir = try {
                com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller.ensureInstalled(this)
            } catch (t: Throwable) { null }

            val fm = com.wuxianggujun.tinaide.core.ServiceLocator.get<com.wuxianggujun.tinaide.file.IFileManager>()
            val project = fm.getCurrentProject()
            if (project == null || sysrootDir == null) {
                runOnUiThread {
                    toastError("未找到项目或sysroot安装失败")
                }
                return@Thread
            }

            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            val target = when {
                abi.contains("arm64", ignoreCase = true) -> "aarch64-linux-android28"
                abi.contains("x86_64", ignoreCase = true) -> "x86_64-linux-android28"
                else -> "aarch64-linux-android28"
            }

            val root = java.io.File(project.rootPath)
            val sources = root.walkTopDown()
                .filter { it.isFile && (it.extension.equals("c", true) || it.extension.equals("cc", true) || it.extension.equals("cpp", true) || it.extension.equals("cxx", true)) }
                .toList()

            if (sources.isEmpty()) {
                runOnUiThread { toastWarning("未找到 C/C++ 源文件") }
                return@Thread
            }

            val buildRoot = java.io.File(filesDir, "build/${project.name}").apply { mkdirs() }
            val buildDir = java.io.File(buildRoot, "obj").apply { mkdirs() }
            val logFile = java.io.File(buildRoot, "build.log").apply { parentFile?.mkdirs(); if (!exists()) createNewFile() }

            fun log(line: String) {
                try {
                    android.util.Log.i("Compile", line)
                    logFile.appendText(line + "\n")
                    outputManager.appendOutput(line + "\n")
                } catch (_: Throwable) {}
            }

            log("=== 编译开始 ===")
            log("目标: $target")
            log("sysroot: ${sysrootDir.absolutePath}")
            log("工程: ${project.name} @ ${project.rootPath}")
            log("源文件数: ${sources.size}")

            // 组装 include 目录：sysroot + 项目常见 include 位置
            val includeDirs = mutableListOf<String>()
            includeDirs += java.io.File(sysrootDir, "usr/include").absolutePath
            includeDirs += java.io.File(sysrootDir, "usr/include/c++/v1").absolutePath
            listOf("include", "includes", "src").forEach { sub ->
                val d = java.io.File(root, sub)
                if (d.exists()) includeDirs += d.absolutePath
            }

            // Compute entry symbol: projectName + _main (e.g., myproj_main)
            val entrySymbol = "${'$'}{project.name}_main"

            val flags = mutableListOf<String>()
            flags += listOf("-Wall", "-Wextra")
            // 启用 C++ 异常（防止 dynamic_cast 引用失败等直接触发 terminate）
            flags += listOf("-fexceptions", "-fcxx-exceptions")
            // Rename user's main to the project-scoped entry to avoid symbol conflicts
            flags += listOf("-Dmain=${'$'}entrySymbol")

            var ok = 0
            var syntaxOk = 0
            val failed = mutableListOf<String>()
            val compiledObjs = mutableListOf<String>()

            for (src in sources) {
                // Force C++ mode for uniform symbol/mangling behavior
                val isCxx = true
                val rel = src.absolutePath.removePrefix(root.absolutePath).trimStart(java.io.File.separatorChar)
                val objName = rel.replace(java.io.File.separatorChar, '_') + ".o"
                val objFile = java.io.File(buildDir, objName)

                log("[${if (isCxx) "C++" else "C"}] 编译 ${src.name} -> ${objFile.name}")
                var err = try {
                    com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler.emitObj(
                        sysrootDir.absolutePath,
                        src.absolutePath,
                        objFile.absolutePath,
                        target,
                        isCxx,
                        flags.toTypedArray(),
                        includeDirs.toTypedArray()
                    )
                } catch (t: Throwable) {
                    "JNI error: ${t.message}"
                }

                if (err.isEmpty()) {
                    ok++
                    compiledObjs += objFile.absolutePath
                    log("成功: ${src.name}")
                } else {
                    // fallback: syntax-only
                    val syn = try {
                        com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler.syntaxCheck(
                            sysrootDir.absolutePath,
                            src.absolutePath,
                            target,
                            isCxx
                        )
                    } catch (t: Throwable) { "syntax JNI error: ${t.message}" }

                    if (syn.isEmpty()) {
                        syntaxOk++
                        val reason = err.ifEmpty { "(no diagnostics)" }
                        log("语法通过(未生成.o): ${src.name}; 原因: ${reason}")
                    } else {
                        val fmsg = "${src.name}: $err | $syn"
                        failed += fmsg
                        log("失败: $fmsg")
                    }
                }
            }

            // 统一链接和运行（单文件或多文件）
            if (compiledObjs.isNotEmpty()) {
                log("=== 链接阶段 ===")
                val soFile = java.io.File(buildRoot, "lib${project.name}.so")
                val linkErr = try {
                    com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler.linkSoMany(
                        sysrootDir.absolutePath,
                        compiledObjs.toTypedArray(),
                        soFile.absolutePath,
                        target,
                        true,  // C++ 模式
                        emptyArray(),
                        emptyArray()
                    )
                } catch (t: Throwable) { "link JNI error: ${t.message}" }
                
                if (linkErr.isEmpty()) {
                    log("链接成功: ${soFile.name}")
                    try {
                        log("=== 运行阶段 ===")
                        // 直接调用 main，native 侧会自动尝试 C++ mangled 名称
                        val out = com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler.runSharedIsolated(
                            soFile.absolutePath,
                            entrySymbol,
                            15000
                        )
                        log(out)
                    } catch (t: Throwable) {
                        log("运行失败: ${t.message}")
                    }
                } else {
                    log("链接失败: $linkErr")
                }
            }

            val msg = buildString {
                appendLine("目标: $target")
                appendLine("sysroot: ${sysrootDir.absolutePath}")
                appendLine("输出: ${buildDir.absolutePath}")
                appendLine("生成 .o 成功: $ok, 语法通过(回退): $syntaxOk, 失败: ${failed.size}")
                if (failed.isNotEmpty()) {
                    appendLine()
                    appendLine("失败样例: ")
                    appendLine(failed.take(5).joinToString("\n"))
                }
            }

            log("=== 编译结束 ===")
            log(msg)
            log("\n日志文件: ${logFile.absolutePath}")
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // 菜单已经通过 toolbar.inflateMenu 设置
        // 这里也需要填充菜单，避免系统回调时清空已有菜单
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                drawerLayout.openDrawer(findViewById(R.id.nav_view))
                true
            }
            R.id.action_run -> { onCompileProject(); true }
            R.id.action_build -> { onCompileProject(); true }
            R.id.action_settings -> {
                // 打开设置界面
                val intent = Intent(this, com.wuxianggujun.tinaide.settings.SettingsActivity::class.java)
                startActivity(intent)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}
