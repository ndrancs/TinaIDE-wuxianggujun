package com.wuxianggujun.tinaide.ui.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.os.Environment
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.wuxianggujun.tinaide.core.ServiceLocator
import com.wuxianggujun.tinaide.core.get
import com.wuxianggujun.tinaide.file.IFileManager
import java.io.File

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
            val defaultPath = File(
                Environment.getExternalStorageDirectory(),
                "TinaIDE/Projects"
            ).absolutePath
            setText(defaultPath)
        }
        container.addView(pathInput)
        
        return AlertDialog.Builder(context)
            .setTitle("新建项目")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                val projectName = nameInput.text.toString().trim()
                val projectPath = pathInput.text.toString().trim()
                
                if (projectName.isEmpty()) {
                    Toast.makeText(context, "项目名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (projectPath.isEmpty()) {
                    Toast.makeText(context, "项目路径不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                createNewProject(projectName, projectPath)
            }
            .setNegativeButton("取消", null)
            .create()
    }
    
    /**
     * 创建打开项目对话框
     */
    private fun createOpenProjectDialog(): Dialog {
        val context = requireContext()
        
        // 获取常用项目路径
        val projectsDir = File(
            Environment.getExternalStorageDirectory(),
            "TinaIDE/Projects"
        )
        
        val existingProjects = if (projectsDir.exists() && projectsDir.isDirectory) {
            projectsDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toTypedArray()
                ?: arrayOf("暂无项目")
        } else {
            arrayOf("暂无项目")
        }
        
        return AlertDialog.Builder(context)
            .setTitle("打开项目")
            .setItems(existingProjects) { _, which ->
                if (existingProjects[which] != "暂无项目") {
                    val projectDir = File(projectsDir, existingProjects[which])
                    openProject(projectDir)
                }
            }
            .setNeutralButton("浏览...") { _, _ ->
                // TODO: 实现文件浏览器
                Toast.makeText(context, "文件浏览器功能开发中", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
    }
    
    /**
     * 创建新项目
     */
    private fun createNewProject(projectName: String, projectPath: String) {
        try {
            val projectDir = File(projectPath, projectName)
            
            if (projectDir.exists()) {
                Toast.makeText(requireContext(), "项目已存在", Toast.LENGTH_SHORT).show()
                return
            }
            
            // 创建项目目录
            if (!projectDir.mkdirs()) {
                Toast.makeText(requireContext(), "创建项目目录失败", Toast.LENGTH_SHORT).show()
                return
            }
            
            // 创建基本目录结构
            File(projectDir, "src").mkdirs()
            File(projectDir, "include").mkdirs()
            File(projectDir, "build").mkdirs()
            
            // 创建示例文件
            val mainFile = File(projectDir, "src/main.cpp")
            mainFile.writeText("""
                #include <iostream>
                
                int main() {
                    std::cout << "Hello, TinaIDE!" << std::endl;
                    return 0;
                }
            """.trimIndent())
            
            // 创建 README
            val readmeFile = File(projectDir, "README.md")
            readmeFile.writeText("""
                # $projectName
                
                这是一个使用 TinaIDE 创建的 C++ 项目。
                
                ## 构建
                
                ```bash
                g++ src/main.cpp -o build/main
                ```
                
                ## 运行
                
                ```bash
                ./build/main
                ```
            """.trimIndent())
            
            Toast.makeText(requireContext(), "项目创建成功", Toast.LENGTH_SHORT).show()
            
            // 打开项目
            openProject(projectDir)
            
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "创建项目失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 打开项目
     */
    private fun openProject(projectDir: File) {
        try {
            fileManager.openProject(projectDir.absolutePath)
            onProjectSelected(projectDir)
            Toast.makeText(requireContext(), "项目已打开: ${projectDir.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "打开项目失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
