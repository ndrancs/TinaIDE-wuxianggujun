package com.wuxianggujun.tinaide.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import android.Manifest
import android.content.pm.PackageManager
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
class ProjectManagerActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ProjectListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 防止标题栏侵入系统状态栏
        WindowCompat.setDecorFitsSystemWindows(window, true)
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

        requestStoragePermissionsIfNeeded { reloadProjects() }
        // 首次也尝试加载（若未授权，可能为空，授权后会再刷新）
        reloadProjects()
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
    }

    private fun openProject(projectDir: File) {
        try {
            ServiceLocator.get<IFileManager>().openProject(projectDir.absolutePath)
            startActivity(Intent(this, com.wuxianggujun.tinaide.MainActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
            // Android 11+：申请“所有文件访问”以便访问自定义项目目录
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                XXPermissions.with(this)
                    .permission(Permission.MANAGE_EXTERNAL_STORAGE)
                    .request(object : OnPermissionCallback {
                        override fun onGranted(permissions: MutableList<String>, allGranted: Boolean) {
                            if (!allGranted) {
                                Toast.makeText(this@ProjectManagerActivity, R.string.permission_not_all_granted, Toast.LENGTH_SHORT).show()
                            }
                            onAfterGranted()
                        }
                        override fun onDenied(permissions: MutableList<String>, doNotAskAgain: Boolean) {
                            if (doNotAskAgain) {
                                Toast.makeText(this@ProjectManagerActivity, R.string.permission_denied_never_ask, Toast.LENGTH_LONG).show()
                                XXPermissions.startPermissionActivity(this@ProjectManagerActivity, permissions)
                            } else {
                                Toast.makeText(this@ProjectManagerActivity, R.string.permission_denied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
            }
            // Android 6.0 - 10：使用平台 API 请求读写权限，避免 XXPermissions 在 target >= 33 时抛错
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val legacyPerms = arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                requestPermissions(legacyPerms, REQ_STORAGE_PERMS)
                // onRequestPermissionsResult 中回调 onAfterGranted()
            }
            else -> {
                // 低于 M 无需运行时权限
                onAfterGranted()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE_PERMS) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                reloadProjects()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQ_STORAGE_PERMS = 1001
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
        if (requestCode == REQ_CHOOSE_DIR && data != null) {
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
                    Toast.makeText(this, "无法解析选择的目录，请选择内部存储目录", Toast.LENGTH_SHORT).show()
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
