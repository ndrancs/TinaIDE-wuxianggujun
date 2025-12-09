package com.wuxianggujun.tinaide.ui.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.config.ConfigKeys
import com.wuxianggujun.tinaide.core.config.IConfigManager
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.extensions.*
import com.wuxianggujun.tinaide.file.IFileManager
import com.wuxianggujun.tinaide.project.ProjectTemplateInstaller
import com.wuxianggujun.tinaide.project.ProjectPaths
import java.io.File

/**
 * Material Design 3 风格的项目对话框
 */
class ProjectDialog(
    private val mode: Mode,
    private val onProjectSelected: (File) -> Unit
) : DialogFragment() {

    companion object {
        private const val INTERNAL_PATH_ALIAS = "projects"
    }

    enum class Mode {
        NEW_PROJECT,
        OPEN_PROJECT
    }

    private val fileManager: IFileManager by lazy {
        ServiceLocator.get<IFileManager>()
    }

    // 视图引用
    private var tilProjectPath: TextInputLayout? = null
    private var etProjectPath: TextInputEditText? = null

    // 基础路径（不包含项目名称）
    private var baseProjectPath: String = ""
    // 当前输入的项目名称
    private var currentProjectName: String = ""

    private val chooseDirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val path = resolveTreeUriToPath(uri)
        if (path != null) {
            baseProjectPath = path
            updateProjectPathDisplay()
            tilProjectPath?.error = null
            // 记住所选目录
            try {
                ServiceLocator.get<IConfigManager>().set(ConfigKeys.ProjectRootDir, path)
            } catch (_: Throwable) {}
        } else {
            requireContext().toastError(getString(R.string.error_project_path_empty))
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return when (mode) {
            Mode.NEW_PROJECT -> createNewProjectDialog()
            Mode.OPEN_PROJECT -> createOpenProjectDialog()
        }
    }


    /**
     * 创建 Material Design 3 风格的新建项目对话框
     */
    private fun createNewProjectDialog(): Dialog {
        val context = requireContext()
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_new_project, null)

        // 获取视图引用
        val tilProjectName = view.findViewById<TextInputLayout>(R.id.til_project_name)
        val etProjectName = view.findViewById<TextInputEditText>(R.id.et_project_name)
        tilProjectPath = view.findViewById(R.id.til_project_path)
        etProjectPath = view.findViewById(R.id.et_project_path)
        val tilProjectType = view.findViewById<TextInputLayout>(R.id.til_project_type)
        val dropdownProjectType = view.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_project_type)

        // 设置默认路径
        val cfg = ServiceLocator.get<IConfigManager>()
        val savedPath = cfg.get(ConfigKeys.ProjectRootDir)
        baseProjectPath = if (savedPath.isNotBlank()) {
            savedPath
        } else {
            ProjectPaths.defaultInternalProjectsPath(requireContext())
        }
        val defaultDir = File(baseProjectPath)
        if (!defaultDir.exists()) {
            defaultDir.mkdirs()
        }
        updateProjectPathDisplay()

        // 设置项目名称输入过滤器（只允许英文字母、数字、下划线、连字符）
        val projectNameFilter = InputFilter { source, start, end, _, _, _ ->
            for (i in start until end) {
                val c = source[i]
                if (!c.isLetterOrDigit() && c != '_' && c != '-') {
                    // 显示错误提示
                    tilProjectName.error = getString(R.string.error_project_name_invalid_chars)
                    return@InputFilter ""
                }
                // 不允许非ASCII字符（中文等）
                if (c.code > 127) {
                    tilProjectName.error = getString(R.string.error_project_name_invalid_chars)
                    return@InputFilter ""
                }
            }
            tilProjectName.error = null
            null // 接受输入
        }
        etProjectName.filters = arrayOf(projectNameFilter)

        // 监听项目名称输入，自动更新路径
        etProjectName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentProjectName = s?.toString()?.trim() ?: ""
                updateProjectPathDisplay()
            }
        })

        // 设置项目类型下拉菜单
        val projectTypes = resources.getStringArray(R.array.project_types)
        val adapter = ArrayAdapter(context, R.layout.item_dropdown_menu, projectTypes)
        dropdownProjectType.setAdapter(adapter)
        dropdownProjectType.setText(projectTypes[0], false)

        // 设置路径选择按钮点击事件
        tilProjectPath?.setEndIconOnClickListener {
            chooseDirLauncher.launch(null)
        }

        // 创建对话框
        val dialog = MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle(R.string.dialog_title_new_project)
            .setView(view)
            .setPositiveButton(R.string.btn_create, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        // 设置按钮点击事件（防止自动关闭）
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val projectName = etProjectName.text?.toString()?.trim() ?: ""
                // 直接使用 baseProjectPath 和 projectName 组合完整路径
                val fullProjectPath = File(baseProjectPath, projectName).absolutePath

                // 验证输入
                var hasError = false

                if (projectName.isEmpty()) {
                    tilProjectName.error = getString(R.string.error_project_name_empty)
                    hasError = true
                } else {
                    tilProjectName.error = null
                }

                if (baseProjectPath.isEmpty()) {
                    tilProjectPath?.error = getString(R.string.error_project_path_empty)
                    hasError = true
                } else {
                    tilProjectPath?.error = null
                }

                if (!hasError) {
                    createNewProject(projectName, fullProjectPath)
                    dialog.dismiss()
                }
            }
        }

        return dialog
    }

    /**
     * 创建打开项目对话框
     */
    private fun createOpenProjectDialog(): Dialog {
        val context = requireContext()

        val cfg = ServiceLocator.get<IConfigManager>()
        val savedPath = cfg.get(ConfigKeys.ProjectRootDir)
        val projectsDir = if (savedPath.isNotBlank()) File(savedPath) else {
            ProjectPaths.defaultInternalProjectsDir(requireContext())
        }

        val existingProjects = if (projectsDir.exists() && projectsDir.isDirectory) {
            projectsDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toTypedArray()
                ?: arrayOf(getString(R.string.no_projects))
        } else {
            arrayOf(getString(R.string.no_projects))
        }

        return MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle(R.string.dialog_title_open_project)
            .setItems(existingProjects) { _, which ->
                if (existingProjects[which] != getString(R.string.no_projects)) {
                    val projectDir = File(projectsDir, existingProjects[which])
                    openProject(projectDir)
                }
            }
            .setNeutralButton(R.string.btn_browse) { _, _ ->
                context.toastInfo("文件浏览器功能开发中")
            }
            .setNegativeButton(R.string.btn_cancel, null)
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

    private fun formatProjectPathForDisplay(path: String): String {
        val internalPath = ProjectPaths.defaultInternalProjectsPath(requireContext())
        return if (File(path).absolutePath == internalPath) {
            INTERNAL_PATH_ALIAS
        } else {
            path
        }
    }

    /**
     * 更新项目路径显示
     * 路径格式: 基础路径/项目名称（类似 CLion）
     */
    private fun updateProjectPathDisplay() {
        val displayBase = formatProjectPathForDisplay(baseProjectPath)
        val displayPath = if (currentProjectName.isNotEmpty()) {
            "$displayBase/$currentProjectName"
        } else {
            displayBase
        }
        etProjectPath?.setText(displayPath)
    }

    /**
     * 创建新项目
     * 注意：路径已经包含项目名称（baseProjectPath/projectName）
     */
    private fun createNewProject(projectName: String, projectPath: String) {
        try {
            // projectPath 已经是完整路径（包含项目名称）
            val projectDir = File(projectPath)

            if (projectDir.exists()) {
                requireContext().toastError(getString(R.string.error_project_exists))
                return
            }

            if (!projectDir.mkdirs()) {
                requireContext().toastError(getString(R.string.error_create_project_dir))
                return
            }

            val ok = ProjectTemplateInstaller.installCppSingleFile(projectDir, projectName)

            if (!ok) {
                requireContext().toastError(getString(R.string.error_template_failed))
                return
            }

            requireContext().toastSuccess(getString(R.string.success_project_created))
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

    override fun onDestroyView() {
        super.onDestroyView()
        tilProjectPath = null
        etProjectPath = null
        baseProjectPath = ""
        currentProjectName = ""
    }
}
