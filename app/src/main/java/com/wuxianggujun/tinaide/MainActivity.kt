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

import com.wuxianggujun.tinaide.lsp.model.Diagnostic
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
import com.wuxianggujun.tinaide.core.lsp.CompileCommandsGenerator
import com.wuxianggujun.tinaide.core.lsp.CompileCommandsGenerator.BuildVariant
import com.wuxianggujun.tinaide.core.lsp.CppProjectScanner
import com.wuxianggujun.tinaide.core.nativebridge.SysrootInstaller
import com.wuxianggujun.tinaide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePaddingRelative
import androidx.core.view.GravityCompat
class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    companion object {
        // 使用固定的作用域名称，确保 Activity 重建时服务可以正确注册和清理
        private const val SERVICE_SCOPE = "MainActivity"
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var uiManager: IUIManager
    private lateinit var outputManager: IOutputManager
    private lateinit var compilerViewModel: CompilerViewModel
    private lateinit var bottomPanelManager: com.wuxianggujun.tinaide.ui.BottomPanelManager

    private var navHeaderBinding: IncludeFileTreeHeaderBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)  // BaseActivity 已处理主题和沉浸式状态栏

        // 绑定侧边栏头部
        navHeaderBinding = binding.fileTreeHeader
        applyDrawerHeaderInsets()

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
                            bottomPanelManager.setBuildSucceeded(false)
                        }
                        is CompileState.Success -> {
                            hideLoading()
                            toastSuccess("编译完成")
                            bottomPanelManager.setBuildSucceeded(true)
                        }
                        is CompileState.Error -> {
                            hideLoading()
                            toastError(state.message)
                            bottomPanelManager.setBuildSucceeded(false)
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
        setupBottomPanel()

        uiManager.restoreLayoutState()
        refreshFileTree()
    }

    private fun applyDrawerHeaderInsets() {
        val header = navHeaderBinding?.drawerHeaderContainer ?: return
        val baseStart = header.paddingStart
        val baseTop = header.paddingTop
        val baseEnd = header.paddingEnd
        val baseBottom = header.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePaddingRelative(
                start = baseStart,
                top = baseTop + topInset,
                end = baseEnd,
                bottom = baseBottom
            )
            insets
        }
    }
    private fun initializeServices() {
        if (!ServiceLocator.isRegistered(IConfigManager::class.java)) {
            val configManager = ConfigManager(applicationContext)
            ServiceLocator.register<IConfigManager>(configManager)
        }
        
        // UIManager 绑定当前 Activity 生命周期
        uiManager = UIManager(this)
        ServiceLocator.registerScoped(SERVICE_SCOPE, IUIManager::class.java, uiManager)

        if (!ServiceLocator.isRegistered(IFileManager::class.java)) {
            ServiceLocator.registerSingleton<IFileManager> { FileManager(applicationContext) }
        }

        // EditorManager 为应用级单例，使用 ApplicationContext
        if (!ServiceLocator.isRegistered(IEditorManager::class.java)) {
            ServiceLocator.registerSingleton<IEditorManager> { 
                EditorManager(applicationContext) 
            }
        }
        
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
    private fun setupBottomPanel() {
        bottomPanelManager = com.wuxianggujun.tinaide.ui.BottomPanelManager(
            activity = this,
            container = binding.bottomPanelContainer,
            onCompile = { onCompileProject() },
            onStop = { /* TODO: 实现停止功能 */ },
            onOpenOutput = { 
                // 打开编译输出界面
                outputManager.showOutput()
            },
            onDiagnosticClick = { diagnostic ->
                // TODO: 跳转到诊断对应的代码位置
                toastInfo("诊断: ${diagnostic.message} (${diagnostic.uri}:${diagnostic.range.start.line + 1})")
            },
            onSymbolClick = { symbol ->
                // 插入符号到当前编辑器光标位置
                insertSymbolToEditor(symbol)
            }
        )
    }
    
    /**
     * 插入符号到当前编辑器光标位置
     */
    private fun insertSymbolToEditor(symbol: String) {
        val editorContainerFragment = supportFragmentManager
            .findFragmentById(R.id.editor_container) as? com.wuxianggujun.tinaide.ui.fragment.EditorContainerFragment
        editorContainerFragment?.insertTextAtCursor(symbol)
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
        
        // 获取文件树Fragment
        val fileTreeFragment = supportFragmentManager.findFragmentById(R.id.file_tree_container)
            as? com.wuxianggujun.tinaide.ui.fragment.FileTreeFragment
        
        // 获取当前选中的目录，如果没有选中则使用项目根目录
        val targetDir = fileTreeFragment?.getSelectedDirectory() ?: File(project.rootPath)
        
        // 使用 DialogFragment
        val dialog = com.wuxianggujun.tinaide.ui.dialog.InputDialog.newInstance(
            title = "添加文件到 ${targetDir.name}",
            hint = "文件名，例如 main.cpp",
            validator = { input ->
                when {
                    input.isEmpty() -> "文件名不能为空"
                    !input.matches(Regex("[a-zA-Z0-9_.-]+")) -> "文件名包含非法字符"
                    File(targetDir, input).exists() -> "文件已存在"
                    else -> null // 验证通过
                }
            },
            onConfirm = { name ->
                FileUtils.createFile(targetDir, name)
                    .onSuccess { file ->
                        toastSuccess("已创建 ${file.name}")
                        refreshFileTree()
                    }
                    .onFailure { error ->
                        handleErrorWithToast(error, "创建失败")
                    }
            }
        )
        dialog.show(supportFragmentManager, "add_file_dialog")
    }
    override fun onDestroy() {
        if (::bottomPanelManager.isInitialized) {
            bottomPanelManager.destroy()
        }
        super.onDestroy()
        // 只清理 Activity 级服务（UIManager），EditorManager 为应用级单例不清理
        ServiceLocator.clearScope(SERVICE_SCOPE)
    }

    private fun onCompileProject() {
        when (outputManager.getOutputMode()) {
            IOutputManager.OutputMode.BOTTOM_PANEL -> {
                bottomPanelManager.clearBuildLog()
                bottomPanelManager.switchToBuildLog()
                bottomPanelManager.expand()
            }
            IOutputManager.OutputMode.ACTIVITY -> {
                outputManager.showOutput()
            }
        }
        compilerViewModel.compile()
    }

    private fun onGenerateCompileCommands(): Boolean {
        val fileManager = ServiceLocator.get<IFileManager>()
        val project = fileManager.getCurrentProject()
        if (project == null) {
            toastError("请先打开项目")
            return true
        }

        lifecycleScope.launch {
            showLoading("正在生成 compile_commands.json...", cancelable = false)
            try {
                val generatedPath = withContext(Dispatchers.IO) {
                    val context = applicationContext
                    val sysrootDir = SysrootInstaller.ensureInstalled(context)
                    val scan = CppProjectScanner.scanProject(project.rootPath)
                    if (scan.sourceFiles.isEmpty()) {
                        throw IllegalStateException("项目中没有可用的 C/C++ 源文件")
                    }
                    val variant = if (BuildConfig.DEBUG) BuildVariant.Debug else BuildVariant.Release
                    CompileCommandsGenerator.generate(
                        projectPath = project.rootPath,
                        sysrootDir = sysrootDir,
                        sourceFiles = scan.sourceFiles,
                        includeDirs = scan.includeDirs,
                        isCxx = scan.hasCppSources,
                        variant = variant
                    ).absolutePath
                }
                toastSuccess("已生成 compile_commands.json\n$generatedPath")
            } catch (e: Exception) {
                Logger.e("Failed to generate compile_commands.json", e, "MainActivity")
                toastError("生成失败：${e.message}")
            } finally {
                hideLoading()
            }
        }
        return true
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
                drawerLayout.openDrawer(GravityCompat.START)
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
            R.id.action_generate_compile_commands -> onGenerateCompileCommands()
            else -> super.onOptionsItemSelected(item)
        }
    }
}
