package com.wuxianggujun.tinaide

import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Intent
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: Toolbar
    private lateinit var uiManager: IUIManager
    private var lastBuildSummary: String? = null
    private var tvOutput: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 强制使用深色主题，确保主题一致性
        setTheme(R.style.Theme_TinaIDE)
        super.onCreate(savedInstanceState)

        if (!ServiceLocator.isRegistered(IConfigManager::class.java)) {
            ServiceLocator.register<IConfigManager>(ConfigManager(this))
        }
        if (!ServiceLocator.isRegistered(IFileManager::class.java)) {
            ServiceLocator.registerSingleton<IFileManager> { FileManager(applicationContext) }
        }

        // 避免标题栏侵入系统状态栏，关闭 edge-to-edge 布局
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        // Output panel wiring
        tvOutput = findViewById(R.id.tv_output)
        findViewById<ImageButton>(R.id.btn_output_info)?.setOnClickListener {
            val summary = lastBuildSummary ?: "暂无输出"
            AlertDialog.Builder(this)
                .setTitle("编译结果")
                .setMessage(summary)
                .setPositiveButton("确定", null)
                .show()
        }

        initializeServices()

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(android.R.drawable.ic_menu_sort_by_size)

        drawerLayout = findViewById(R.id.drawer_layout)
        setupFileTreeHeader()

        // 已启用 decorFitsSystemWindows=true，无需手动应用 WindowInsets

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
            Toast.makeText(this, "查看功能开发中", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "请先打开项目", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this)
        input.hint = "文件名，例如 main.cpp"
        AlertDialog.Builder(this)
            .setTitle("添加文件")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "文件名不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                try {
                    val root = File(project.rootPath)
                    fm.createFile(root, name)
                    Toast.makeText(this, "已创建 $name", Toast.LENGTH_SHORT).show()
                    refreshFileTree()
                } catch (e: Exception) {
                    Toast.makeText(this, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    override fun onDestroy() {
        super.onDestroy()
        ServiceLocator.clear()
    }

    private fun onCompileProject() {
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
                    Toast.makeText(this, "未找到项目或sysroot安装失败", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            val target = when {
                abi.contains("arm64", ignoreCase = true) -> "aarch64-linux-android24"
                abi.contains("x86_64", ignoreCase = true) -> "x86_64-linux-android24"
                else -> "aarch64-linux-android24"
            }

            val root = java.io.File(project.rootPath)
            val sources = root.walkTopDown()
                .filter { it.isFile && (it.extension.equals("c", true) || it.extension.equals("cc", true) || it.extension.equals("cpp", true) || it.extension.equals("cxx", true)) }
                .toList()

            if (sources.isEmpty()) {
                runOnUiThread { Toast.makeText(this, "未找到 C/C++ 源文件", Toast.LENGTH_SHORT).show() }
                return@Thread
            }

            val buildRoot = java.io.File(filesDir, "build/${project.name}").apply { mkdirs() }
            val buildDir = java.io.File(buildRoot, "obj").apply { mkdirs() }
            val logFile = java.io.File(buildRoot, "build.log").apply { parentFile?.mkdirs(); if (!exists()) createNewFile() }

            fun log(line: String) {
                try {
                    android.util.Log.i("Compile", line)
                    logFile.appendText(line + "\n")
                    runOnUiThread {
                        tvOutput?.append(line + "\n")
                        val sv = findViewById<android.widget.ScrollView>(R.id.output_scroll)
                        sv?.post { sv.fullScroll(android.view.View.FOCUS_DOWN) }
                    }
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

            val flags = mutableListOf<String>()
            flags += listOf("-Wall", "-Wextra")

            var ok = 0
            var syntaxOk = 0
            val failed = mutableListOf<String>()

            for (src in sources) {
                val isCxx = !src.extension.equals("c", true)
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
                    log("成功: ${src.name}")
                    // 尝试链接为可执行文件并运行，输出到面板
                    val exe = java.io.File(buildRoot, src.nameWithoutExtension + ".exe")
                    val linkErr = try {
                        com.wuxianggujun.tinaide.core.nativebridge.NativeCompiler.linkExe(
                            sysrootDir.absolutePath,
                            objFile.absolutePath,
                            exe.absolutePath,
                            target,
                            isCxx
                        )
                    } catch (t: Throwable) { "link JNI error: ${t.message}" }
                    if (linkErr.isEmpty()) {
                        exe.setExecutable(true)
                        try {
                            log("[运行] ${exe.name}")
                            val pb = java.lang.ProcessBuilder(exe.absolutePath)
                                .redirectErrorStream(true)
                            val p = pb.start()
                            val out = p.inputStream.bufferedReader().use { it.readText() }
                            val code = p.waitFor()
                            log("[退出码] $code")
                            if (out.isNotEmpty()) log(out.trimEnd())
                        } catch (t: Throwable) {
                            log("运行失败: ${t.message}")
                        }
                    } else {
                        log("链接失败: $linkErr")
                    }
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

            // 保存结果，供“输出工具栏”的感叹号按钮查看
            lastBuildSummary = msg + "\n\n日志: ${logFile.absolutePath}"
        }.start()
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
            R.id.action_open_project -> { showOpenProjectDialog(); true }
            R.id.action_run -> { onCompileProject(); true }
            R.id.action_build -> { onCompileProject(); true }

            else -> super.onOptionsItemSelected(item)
        }
    }
}
