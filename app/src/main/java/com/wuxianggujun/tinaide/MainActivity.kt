package com.wuxianggujun.tinaide

import android.os.Bundle
import android.content.Intent
import com.google.android.material.appbar.MaterialToolbar
import androidx.drawerlayout.widget.DrawerLayout
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wuxianggujun.tinaide.base.BaseActivity
import com.wuxianggujun.tinaide.databinding.ActivityMainBinding
import com.wuxianggujun.tinaide.databinding.IncludeFileTreeHeaderBinding
import com.wuxianggujun.tinaide.extensions.toast
import com.wuxianggujun.tinaide.extensions.toastSuccess
import com.wuxianggujun.tinaide.extensions.toastError
import com.wuxianggujun.tinaide.extensions.toastInfo
import com.wuxianggujun.tinaide.extensions.toastWarning
import com.wuxianggujun.tinaide.extensions.handleErrorWithToast
import com.wuxianggujun.tinaide.ui.CompilerViewModel
import com.wuxianggujun.tinaide.ui.CompileState
import com.wuxianggujun.tinaide.ui.dialog.MaterialDialogBuilder
import com.wuxianggujun.tinaide.utils.FileUtils
import com.wuxianggujun.tinaide.utils.Logger
import java.io.File
import kotlinx.coroutines.launch

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
class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    // 用于在 ServiceLocator 中隔离与本 Activity 绑定的服务
    private val serviceScope = "MainActivity_${hashCode()}"

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var uiManager: IUIManager
    private lateinit var outputManager: IOutputManager
    private lateinit var compilerViewModel: CompilerViewModel

    private var navHeaderBinding: IncludeFileTreeHeaderBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)  // BaseActivity 已处理主题和沉浸式状态栏

        // 绑定侧边栏头部
        try {
            val nav = binding.navView
            val headerView = if (nav.headerCount > 0) nav.getHeaderView(0) else null
            if (headerView != null) {
                navHeaderBinding = IncludeFileTreeHeaderBinding.bind(headerView)
            }
        } catch (_: Throwable) { }

        initializeServices()

        compilerViewModel = ViewModelProvider(
            this,
            CompilerViewModel.Factory(applicationContext)
        )[CompilerViewModel::class.java]

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                compilerViewModel.state.collect { state ->
                    when (state) {
                        is CompileState.Idle -> {
                            hideLoading()
                        }
                        is CompileState.Compiling -> {
                            showLoading("正在编译项目...", cancelable = false)
                        }
                        is CompileState.Success -> {
                            hideLoading()
                            toastSuccess("编译完成")
                        }
                        is CompileState.Error -> {
                            hideLoading()
                            toastError(state.message)
                        }
                    }
                }
            }
        }

        toolbar = binding.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(android.R.drawable.ic_menu_sort_by_size)

        // 直接在 Toolbar 上填充菜单，无需等待系统回调
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item ->
            onOptionsItemSelected(item)
        }

        drawerLayout = binding.drawerLayout
        setupFileTreeHeader()

        uiManager.restoreLayoutState()
        refreshFileTree()
    }
    private fun initializeServices() {
        if (!ServiceLocator.isRegistered(IConfigManager::class.java)) {
            // ConfigManager 使用 applicationContext，作为应用级服务
            val configManager = ConfigManager(applicationContext)
            ServiceLocator.register<IConfigManager>(configManager)
        }
        // UIManager 绑定当前 Activity 生命周期，注册为作用域服务
        uiManager = UIManager(this)
        ServiceLocator.registerScoped(serviceScope, IUIManager::class.java, uiManager)

        if (!ServiceLocator.isRegistered(IFileManager::class.java)) {
            ServiceLocator.registerSingleton<IFileManager> { FileManager(applicationContext) }
        }

        // EditorManager 同样与当前 Activity 绑定，注册为作用域服务
        val editorManager = EditorManager(this, supportFragmentManager)
        ServiceLocator.registerScoped(serviceScope, IEditorManager::class.java, editorManager)
        
        // 注册输出管理器（应用级）
        if (!ServiceLocator.isRegistered(IOutputManager::class.java)) {
            outputManager = OutputManager(applicationContext)
            ServiceLocator.register<IOutputManager>(outputManager)
        } else {
            outputManager = ServiceLocator.get<IOutputManager>()
        }
    }

    private fun refreshFileTree() {
        val fileTreeFragment = supportFragmentManager.findFragmentById(R.id.file_tree_container)
            as? com.wuxianggujun.tinaide.ui.fragment.FileTreeFragment
        fileTreeFragment?.refresh()
        updateProjectHeaderName()
    }
    private fun setupFileTreeHeader() {
        val header = navHeaderBinding ?: return
        header.btnAddFile.setOnClickListener {
            showAddFileDialog()
        }
        header.btnRefreshFileTree.setOnClickListener {
            refreshFileTree()
        }
        header.btnViewMode.setOnClickListener {
            toastInfo("查看功能开发中")
        }
        updateProjectHeaderName()
    }

    private fun updateProjectHeaderName() {
        val header = navHeaderBinding
        if (header == null) {
            android.util.Log.e("MainActivity", "Nav header binding is null!")
            return
        }
        val nameView = header.tvProjectName
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
        // 清理与本 Activity 作用域绑定的服务（如 UIManager、EditorManager）
        ServiceLocator.clearScope(serviceScope)
    }

    private fun onCompileProject() {
        // 清空之前的输出
        outputManager.clearOutput()
        // 显示输出窗口
        outputManager.showOutput()
        // 交给 ViewModel + UseCase 在后台线程编译
        compilerViewModel.compile()
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
                drawerLayout.openDrawer(binding.navView)
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

