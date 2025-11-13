package com.wuxianggujun.tinaide.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.wuxianggujun.tinaide.base.BaseActivity
import com.wuxianggujun.tinaide.extensions.*
import com.wuxianggujun.tinaide.utils.Logger
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.ConfigManager
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.core.register
import com.wuxianggujun.tinaide.core.registerSingleton
import com.wuxianggujun.tinaide.file.FileManager
import com.wuxianggujun.tinaide.file.IFileManager
import com.wuxianggujun.tinaide.ui.adapter.ProjectListAdapter
import com.wuxianggujun.tinaide.ui.dialog.ProjectDialog
import java.io.File
import android.provider.DocumentsContract

/**
 * 项目管理页：菜单栏 + 项目列表
 */
class ProjectManagerActivity : BaseActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ProjectListAdapter
    private var isNavigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)  // BaseActivity 已处理主题和状态栏
        setContentView(R.layout.activity_project_manager)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        initializeServices()

        recycler = findViewById(R.id.recycler_projects)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ProjectListAdapter { dir ->
            openProject(dir)
        }
        recycler.adapter = adapter

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
            R.id.action_new_project -> {
                showProjectDialog(ProjectDialog.Mode.NEW_PROJECT)
                true
            }
            R.id.action_choose_dir -> {
                chooseRootDirectory()
                true
            }
            R.id.action_refresh -> {
                reloadProjects()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun initializeServices() {
        // 注册 ConfigManager（若未注册）
        if (!ServiceLocator.isRegistered(IConfigManager::class.java)) {
            ServiceLocator.register<IConfigManager>(ConfigManager(this))
        }
        // 注册 FileManager（单例，供后续页面使用）
        if (!ServiceLocator.isRegistered(IFileManager::class.java)) {
            ServiceLocator.registerSingleton<IFileManager> { FileManager(applicationContext) }
        }
    }

    private fun getProjectsRootDir(): File {
        val cfg = ServiceLocator.get<IConfigManager>()
        val saved = cfg.get(KEY_PROJECTS_ROOT, "")
        if (saved.isNotBlank()) return File(saved)
        return File(Environment.getExternalStorageDirectory(), "TinaIDE/Projects")
    }

    private fun reloadProjects() {
        val root = getProjectsRootDir()
        if (!root.exists()) root.mkdirs()
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        adapter.submitList(dirs)
        val emptyView = findViewById<android.widget.TextView>(R.id.tv_empty)
        if (dirs.isEmpty()) {
            emptyView?.visibility = android.view.View.VISIBLE
            recycler.visibility = android.view.View.GONE
        } else {
            emptyView?.visibility = android.view.View.GONE
            recycler.visibility = android.view.View.VISIBLE
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



    companion object {
        private const val REQ_CHOOSE_DIR = 1002
        private const val KEY_PROJECTS_ROOT = "project.root_dir"
    }

    private fun chooseRootDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, REQ_CHOOSE_DIR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CHOOSE_DIR -> {
                if (data != null) {
                    val treeUri: Uri? = data.data
                    if (treeUri != null) {
                        contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        val path = resolveTreeUriToPath(treeUri)
                        if (path != null) {
                            ServiceLocator.get<IConfigManager>().set(KEY_PROJECTS_ROOT, path)
                            reloadProjects()
                        } else {
                            toastError("无法解析选择的目录，请选择内部存储目录")
                        }
                    }
                }
            }
        }
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



