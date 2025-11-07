package com.wuxianggujun.tinaide

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ServiceLocator.isRegistered(IConfigManager::class.java)) {
            ServiceLocator.register<IConfigManager>(ConfigManager(this))
        }
        if (!ServiceLocator.isRegistered(IFileManager::class.java)) {
            ServiceLocator.registerSingleton<IFileManager> { FileManager(applicationContext) }
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        initializeServices()

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(android.R.drawable.ic_menu_sort_by_size)

        drawerLayout = findViewById(R.id.drawer_layout)
        setupFileTreeHeader()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

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
            R.id.action_run -> { Toast.makeText(this, "运行功能开发中", Toast.LENGTH_SHORT).show(); true }
            R.id.action_build -> { Toast.makeText(this, "编译功能开发中", Toast.LENGTH_SHORT).show(); true }
            R.id.action_open_terminal -> {
                // 使用 AIDE-Termux 的 TermuxActivity
                startActivity(Intent(this, com.termux.app.TermuxActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
