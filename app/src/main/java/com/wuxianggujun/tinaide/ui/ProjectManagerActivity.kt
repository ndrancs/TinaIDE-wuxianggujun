package com.wuxianggujun.tinaide.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.appbar.MaterialToolbar
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.wuxianggujun.tinaide.base.BaseActivity
import com.wuxianggujun.tinaide.databinding.ProjectManagerFragmentBinding
import com.wuxianggujun.tinaide.extensions.*
import com.wuxianggujun.tinaide.utils.Logger
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.core.register
import com.wuxianggujun.tinaide.core.registerSingleton
import com.wuxianggujun.tinaide.file.FileManager
import com.wuxianggujun.tinaide.file.IFileManager
import com.wuxianggujun.tinaide.ui.adapter.ProjectListAdapter
import com.wuxianggujun.tinaide.ui.dialog.ProjectDialog
import com.wuxianggujun.tinaide.project.ProjectPaths
import java.io.File
import android.provider.DocumentsContract

/**
 * 项目管理页：菜单栏 + 项目列表
 */
class ProjectManagerActivity : BaseActivity<ProjectManagerFragmentBinding>(ProjectManagerFragmentBinding::inflate) {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ProjectListAdapter
    private var isNavigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)  // BaseActivity 已处理主题和状态栏

        val toolbar = binding.toolbar
        setSupportActionBar(toolbar)

        initializeServices()

        recycler = binding.projectsRecycler
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ProjectListAdapter(
            onClick = { dir -> openProject(dir) },
            onLongClick = { dir -> showDeleteProjectDialog(dir) }
        )
        recycler.adapter = adapter

        // 设置下拉刷新：下拉即重新加载项目列表
        val swipeRefresh = binding.scrollingView
        swipeRefresh.setOnRefreshListener {
            reloadProjects()
            swipeRefresh.isRefreshing = false
        }

        // 设置 FAB 新建项目入口
        binding.createProjectFab.setOnClickListener {
            showProjectDialog(ProjectDialog.Mode.NEW_PROJECT)
        }
        // 请求权限后加载项目列表
        requestStoragePermissionsIfNeeded {
            reloadProjects()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从 MainActivity 返回时刷新列表，但跳转过程中不刷新
        if (!isNavigating) {
            reloadProjects()
        }
        isNavigating = false
    }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.project_manager_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                // 打开设置界面，与主编辑器保持一致
                val intent = Intent(this, com.wuxianggujun.tinaide.settings.SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun initializeServices() {
        // 注册 ConfigManager（若未注册），使用 applicationContext 作为应用级服务
        if (!ServiceLocator.isRegistered(IConfigManager::class.java)) {
            ServiceLocator.register<IConfigManager>(ConfigManager(applicationContext))
        }
        // 注册 FileManager（单例，供后续页面使用）
        if (!ServiceLocator.isRegistered(IFileManager::class.java)) {
            ServiceLocator.registerSingleton<IFileManager> { FileManager(applicationContext) }
        }
    }

    private fun getProjectsRootDir(): File {
        val cfg = ServiceLocator.get<IConfigManager>()
        val saved = cfg.get(ConfigKeys.ProjectRootDir)
        return if (saved.isNotBlank()) File(saved) else ProjectPaths.defaultInternalProjectsDir(this)
    }

    private fun reloadProjects() {
        val root = getProjectsRootDir()
        if (!root.exists()) root.mkdirs()
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        adapter.submitList(dirs)

        // 使用 Material Design 风格的空态视图，而不是旧的 tv_empty 文本
        val emptyProjects = binding.emptyProjects.root
        val loadingContainer = binding.emptyContainer.root

        // 主动隐藏 loading 容器，避免与空态文案叠加
        loadingContainer.visibility = View.GONE

        if (dirs.isEmpty()) {
            recycler.visibility = View.GONE
            emptyProjects.visibility = View.VISIBLE
        } else {
            recycler.visibility = View.VISIBLE
            emptyProjects.visibility = View.GONE
        }
    }

    private fun showDeleteProjectDialog(dir: File) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("删除项目")
            .setMessage("确定要删除项目 \"${dir.name}\" 吗？\n\n此操作将永久删除项目文件夹及其所有内容，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                deleteProject(dir)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteProject(dir: File) {
        try {
            if (dir.deleteRecursively()) {
                toastSuccess("项目已删除")
                reloadProjects()
            } else {
                toastError("删除失败")
            }
        } catch (e: Exception) {
            handleErrorWithToast(e, "删除失败")
        }
    }

    private fun openProject(dir: java.io.File) {
        // 防止重复点击导致多次跳转
        if (isNavigating) return
        
        try {
            isNavigating = true
            val fm = ServiceLocator.get<IFileManager>()
            fm.openProject(dir.absolutePath)
            val intent = android.content.Intent(this, com.wuxianggujun.tinaide.MainActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            isNavigating = false
            handleErrorWithToast(e, "打开失败")
        }
    }

    private fun showProjectDialog(mode: ProjectDialog.Mode) {
        val dialog = ProjectDialog(mode) { dir ->
            openProject(dir)
        }
        dialog.show(supportFragmentManager, "ProjectManagerDialog")
    }

    private fun requestStoragePermissionsIfNeeded(onAfterGranted: () -> Unit) {
        val rootDir = getProjectsRootDir()
        if (rootDir.absolutePath.startsWith(filesDir.absolutePath)) {
            onAfterGranted()
            return
        }
        when {
            // Android 11+：未完全适配分区存储，使用 MANAGE_EXTERNAL_STORAGE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                XXPermissions.with(this)
                    .permission(Permission.MANAGE_EXTERNAL_STORAGE)
                    .request(object : OnPermissionCallback {
                        override fun onGranted(permissions: MutableList<String>, allGranted: Boolean) {
                            if (!allGranted) {
                                toastWarning(getString(R.string.permission_not_all_granted))
                            }
                            onAfterGranted()
                        }
                        override fun onDenied(permissions: MutableList<String>, doNotAskAgain: Boolean) {
                            if (doNotAskAgain) {
                                toastLong(getString(R.string.permission_denied_never_ask))
                                XXPermissions.startPermissionActivity(this@ProjectManagerActivity, permissions)
                            } else {
                                toastError(getString(R.string.permission_denied))
                            }
                        }
                    })
            }
            // Android 6.0 - 10：使用 XXPermissions + READ_MEDIA_*，由库在低版本自动兼容到外部存储权限
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                XXPermissions.with(this)
                    .permission(
                        Permission.READ_MEDIA_IMAGES,
                        Permission.READ_MEDIA_VIDEO,
                        Permission.READ_MEDIA_AUDIO
                    )
                    .request(object : OnPermissionCallback {
                        override fun onGranted(permissions: MutableList<String>, allGranted: Boolean) {
                            if (!allGranted) {
                                toastWarning(getString(R.string.permission_not_all_granted))
                            }
                            onAfterGranted()
                        }
                        override fun onDenied(permissions: MutableList<String>, doNotAskAgain: Boolean) {
                            if (doNotAskAgain) {
                                toastLong(getString(R.string.permission_denied_never_ask))
                                XXPermissions.startPermissionActivity(this@ProjectManagerActivity, permissions)
                            } else {
                                toastError(getString(R.string.permission_denied))
                            }
                        }
                    })
            }
            else -> {
                // 低于 M 无需运行时权限
                onAfterGranted()
            }
        }
    }

    private val chooseRootDirLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            val treeUri: Uri? = data.data
            if (treeUri != null) {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val path = resolveTreeUriToPath(treeUri)
                if (path != null) {
                    ServiceLocator.get<IConfigManager>().set(ConfigKeys.ProjectRootDir, path)
                    reloadProjects()
                } else {
                    toastError("无法解析选择的目录，请选择内部存储目录")
                }
            }
        }

    private fun chooseRootDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        chooseRootDirLauncher.launch(intent)
    }


    private fun resolveTreeUriToPath(uri: Uri): String? {
        return try {
            if ("com.android.externalstorage.documents" == uri.authority) {
                val docId = DocumentsContract.getTreeDocumentId(uri) // e.g. primary:Documents/TinaIDE
                val parts = docId.split(":", limit = 2)
                val volume = parts.getOrNull(0) ?: return null
                val rel = parts.getOrNull(1) ?: ""
                return when {
                    volume.equals("primary", true) || volume.equals("home", true) ->
                        File(Environment.getExternalStorageDirectory(), rel).absolutePath
                    else -> "/storage/$volume/${rel}"
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
