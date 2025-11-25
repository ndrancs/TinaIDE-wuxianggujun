package com.wuxianggujun.tinaide.ui.dialog

import android.app.Dialog
import com.wuxianggujun.tinaide.ui.dialog.MaterialDialogBuilder
import android.os.Bundle
import android.os.Environment
import android.widget.EditText
import android.widget.Button
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import com.wuxianggujun.tinaide.extensions.*
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.DocumentsContract
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.file.IFileManager
import java.io.File
import com.wuxianggujun.tinaide.project.ProjectTemplateInstaller

/**
 * 项目对话框 - 新建/打开项目
 */
class ProjectDialog(
    private val mode: Mode,
    private val onProjectSelected: (File) -> Unit
) : DialogFragment() {
    
    enum class Mode {
        NEW_PROJECT,    // 新建项目
        OPEN_PROJECT    // 打开项目
    }
    
    private val fileManager: IFileManager by lazy {
        ServiceLocator.get<IFileManager>()
    }

    private var pathInputRef: EditText? = null

    private val chooseDirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val path = resolveTreeUriToPath(uri)
        if (path != null) {
            pathInputRef?.setText(path)
            // 记住所选目录为默认根目录
            try {
                val cfg = ServiceLocator.get<IConfigManager>()
                cfg.set(ConfigKeys.ProjectRootDir, path)
            } catch (_: Throwable) {}
        } else {
            requireContext().toastError("无法解析选择的目录")
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return when (mode) {
            Mode.NEW_PROJECT -> createNewProjectDialog()
            Mode.OPEN_PROJECT -> createOpenProjectDialog()
        }
    }
    
    /**
     * 创建新建项目对话框
     */
    private fun createNewProjectDialog(): Dialog {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        
        // 项目名称输入
        val nameInput = EditText(context).apply {
            hint = "项目名称"
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        container.addView(nameInput)
        
        // 项目路径输入
        val pathInput = EditText(context).apply {
            hint = "项目路径"
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            
            // 默认路径
            val cfg = ServiceLocator.get<IConfigManager>()
            val saved = cfg.get("project.root_dir", "")
            val defaultPath = if (saved.isNotBlank()) saved else File(
                Environment.getExternalStorageDirectory(),
                "TinaIDE/Projects"
            ).absolutePath
            setText(defaultPath)
        }
        pathInputRef = pathInput
        container.addView(pathInput)

        // 选择目录按钮
        val chooseBtn = Button(context).apply {
            text = getString(com.wuxianggujun.tinaide.R.string.btn_choose_directory)
            setOnClickListener { chooseDirLauncher.launch(null) }
        }
        container.addView(chooseBtn)

        // 项目类型
        val typeLabel = TextView(context).apply { text = "项目类型" }
        container.addView(typeLabel)
        val typeSpinner = Spinner(context)
        typeSpinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(com.wuxianggujun.tinaide.R.array.project_types)
        )
        container.addView(typeSpinner)
        
        return MaterialDialogBuilder.create(context)
            .setTitle("新建项目")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                val projectName = nameInput.text.toString().trim()
                val projectPath = pathInput.text.toString().trim()
                
                if (projectName.isEmpty()) {
                    context.toastError("项目名称不能为空")
                    return@setPositiveButton
                }
                
                if (projectPath.isEmpty()) {
                    context.toastError("项目路径不能为空")
                    return@setPositiveButton
                }
                
                val selectedType = typeSpinner.selectedItemPosition // 0: C++(CMake)
                createNewProject(projectName, projectPath, selectedType)
            }
            .setNegativeButton("取消", null)
            .create()
    }
    
    /**
     * 创建打开项目对话框
     */
    private fun createOpenProjectDialog(): Dialog {
        val context = requireContext()
        
        // 获取常用项目路径（优先配置）
        val cfg = ServiceLocator.get<IConfigManager>()
        val saved = cfg.get("project.root_dir", "")
        val projectsDir = if (saved.isNotBlank()) File(saved) else File(
            Environment.getExternalStorageDirectory(),
            "TinaIDE/Projects"
        )
        
        val existingProjects = if (projectsDir.exists() && projectsDir.isDirectory) {
            projectsDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toTypedArray()
                ?: arrayOf("暂无项目")
        } else {
            arrayOf("暂无项目")
        }
        
        return MaterialDialogBuilder.create(context)
            .setTitle("打开项目")
            .setItems(existingProjects) { _, which ->
                if (existingProjects[which] != "暂无项目") {
                    val projectDir = File(projectsDir, existingProjects[which])
                    openProject(projectDir)
                }
            }
            .setNeutralButton("浏览...") { _, _ ->
                // TODO: 实现文件浏览器
                context.toastInfo("文件浏览器功能开发中")
            }
            .setNegativeButton("取消", null)
            .create()
    }

    private fun resolveTreeUriToPath(uri: Uri): String? {
        return try {
            if ("com.android.externalstorage.documents" == uri.authority) {
                val docId = DocumentsContract.getTreeDocumentId(uri)
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
        } catch (_: Throwable) {
            null
        }
    }
    
    /**
     * 创建新项目（根据所选类型生成模板）
     */
    private fun createNewProject(projectName: String, projectPath: String, selectedType: Int) {
        try {
            val projectDir = File(projectPath, projectName)
            
            if (projectDir.exists()) {
                requireContext().toastError("项目已存在")
                return
            }
            
            if (!projectDir.mkdirs()) {
                requireContext().toastError("创建项目目录失败")
                return
            }

            val ok = when (selectedType) {
                0 -> ProjectTemplateInstaller.installCppXmakeTemplate(requireContext(), projectDir, projectName)  // C++ 项目 (xmake)
                1 -> ProjectTemplateInstaller.installCppSingleFile(projectDir, projectName)  // C++ 单文件
                else -> false
            }
            if (!ok) {
                requireContext().toastError("模板生成失败")
                return
            }
            
            requireContext().toastSuccess("项目创建成功")
            
            // 打开项目
            openProject(projectDir)
            
        } catch (e: Exception) {
            requireContext().handleErrorWithToast(e, "创建项目失败")
        }
    }
    
    /**
     * 打开项目
     */
    private fun openProject(projectDir: File) {
        try {
            fileManager.openProject(projectDir.absolutePath)
            onProjectSelected(projectDir)
            requireContext().toastSuccess("项目已打开: ${projectDir.name}")
        } catch (e: Exception) {
            requireContext().handleErrorWithToast(e, "打开项目失败")
        }
    }
}
