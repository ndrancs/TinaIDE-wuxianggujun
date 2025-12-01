package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import android.util.Log
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * LSP 编辑器管理器
 * 管理 clangd 语言服务器和 LSP 编辑器实例
 */
class LspEditorManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LspEditorManager"
        
        @Volatile
        private var instance: LspEditorManager? = null

        fun getInstance(context: Context): LspEditorManager {
            return instance ?: synchronized(this) {
                instance ?: LspEditorManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 项目路径 -> LspProject 的映射
    private val projects = ConcurrentHashMap<String, LspProject>()
    
    // 文件路径 -> LspEditor 的映射
    private val editors = ConcurrentHashMap<String, LspEditor>()
    
    // clangd 二进制文件路径
    private var clangdPath: String? = null
    
    // 是否已初始化
    private var initialized = false

    /**
     * 初始化 LSP 管理器
     * @param sysrootDir sysroot 目录，clangd 应该在 sysroot/usr/bin/clangd
     */
    fun initialize(sysrootDir: File) {
        if (initialized) {
            Log.d(TAG, "Already initialized")
            return
        }

        // 查找 clangd 二进制文件
        val clangdFile = File(sysrootDir, "usr/bin/clangd")
        val clangdHostFile = File(sysrootDir, "usr/bin/clangd-host")
        
        clangdPath = when {
            clangdFile.exists() -> clangdFile.absolutePath
            clangdHostFile.exists() -> clangdHostFile.absolutePath
            else -> {
                Log.w(TAG, "clangd not found in sysroot: ${sysrootDir.absolutePath}")
                null
            }
        }

        if (clangdPath != null) {
            Log.i(TAG, "Found clangd at: $clangdPath")
            // 确保可执行权限
            File(clangdPath!!).setExecutable(true)
            initialized = true
        }
    }

    /**
     * 检查 LSP 是否可用
     */
    fun isAvailable(): Boolean {
        return initialized && clangdPath != null
    }

    /**
     * 获取或创建项目的 LspProject
     */
    fun getOrCreateProject(projectPath: String): LspProject? {
        if (!isAvailable()) {
            Log.w(TAG, "LSP not available")
            return null
        }

        return projects.getOrPut(projectPath) {
            Log.i(TAG, "Creating LspProject for: $projectPath")
            LspProject(projectPath).apply {
                init()
                // 添加 clangd 服务器定义
                addServerDefinition(ClangdServerDefinition(clangdPath!!))
            }
        }
    }

    /**
     * 为文件创建 LSP 编辑器
     */
    suspend fun createLspEditor(
        filePath: String,
        projectPath: String,
        codeEditor: CodeEditor
    ): LspEditor? = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            Log.w(TAG, "LSP not available")
            return@withContext null
        }

        // 检查文件是否为 C/C++ 文件
        if (!ClangdServerDefinition.isCppFile(filePath)) {
            Log.d(TAG, "Not a C/C++ file: $filePath")
            return@withContext null
        }

        val project = getOrCreateProject(projectPath) ?: return@withContext null

        try {
            // 创建或获取 LspEditor
            val lspEditor = project.getOrCreateEditor(filePath)
            
            // 绑定到 CodeEditor
            withContext(Dispatchers.Main) {
                lspEditor.editor = codeEditor
            }

            // 连接到语言服务器
            Log.i(TAG, "Connecting to clangd for: $filePath")
            lspEditor.connectWithTimeout()
            
            // 打开文档
            lspEditor.openDocument()
            
            // 保存到映射
            editors[filePath] = lspEditor
            
            Log.i(TAG, "LSP editor created and connected for: $filePath")
            lspEditor
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create LSP editor for: $filePath", e)
            null
        }
    }

    /**
     * 获取已存在的 LSP 编辑器
     */
    fun getEditor(filePath: String): LspEditor? {
        return editors[filePath]
    }

    /**
     * 关闭文件的 LSP 编辑器
     */
    fun closeEditor(filePath: String) {
        editors.remove(filePath)?.let { editor ->
            scope.launch {
                try {
                    editor.dispose()
                    Log.i(TAG, "LSP editor closed for: $filePath")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing LSP editor for: $filePath", e)
                }
            }
        }
    }

    /**
     * 关闭项目的所有 LSP 资源
     */
    fun closeProject(projectPath: String) {
        projects.remove(projectPath)?.let { project ->
            // 移除该项目下的所有编辑器
            val toRemove = editors.filter { (path, _) ->
                path.startsWith(projectPath)
            }
            toRemove.keys.forEach { editors.remove(it) }
            
            scope.launch {
                try {
                    project.dispose()
                    Log.i(TAG, "LSP project closed: $projectPath")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing LSP project: $projectPath", e)
                }
            }
        }
    }

    /**
     * 保存文档（通知 LSP 服务器）
     */
    suspend fun saveDocument(filePath: String) {
        editors[filePath]?.let { editor ->
            try {
                editor.saveDocument()
                Log.d(TAG, "Document saved notification sent for: $filePath")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving document: $filePath", e)
            }
        }
    }

    /**
     * 关闭所有 LSP 资源
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down LSP manager")
        
        editors.clear()
        
        projects.values.forEach { project ->
            try {
                project.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "Error disposing project", e)
            }
        }
        projects.clear()
        
        scope.cancel()
        initialized = false
        
        Log.i(TAG, "LSP manager shutdown complete")
    }

    /**
     * 生成 compile_commands.json
     * clangd 需要这个文件来理解项目的编译配置
     */
    fun generateCompileCommands(
        projectPath: String,
        sourceFiles: List<String>,
        includeDirs: List<String> = emptyList(),
        defines: List<String> = emptyList(),
        isCxx: Boolean = true,
        target: String = "aarch64-linux-android28"
    ): File {
        val compileCommandsFile = File(projectPath, "compile_commands.json")
        
        val commands = sourceFiles.map { sourceFile ->
            val args = mutableListOf<String>().apply {
                add("clang${if (isCxx) "++" else ""}")
                add("-c")
                add(sourceFile)
                
                // 目标平台
                add("-target")
                add(target)
                
                // C++ 标准
                if (isCxx) {
                    add("-std=c++17")
                }
                
                // 包含目录
                includeDirs.forEach { dir ->
                    add("-I$dir")
                }
                
                // 宏定义
                defines.forEach { define ->
                    add("-D$define")
                }
                
                // Android 相关定义
                add("-DANDROID")
                add("-D__ANDROID__")
            }
            
            mapOf(
                "directory" to projectPath,
                "file" to sourceFile,
                "arguments" to args
            )
        }
        
        // 写入 JSON
        val json = buildString {
            append("[\n")
            commands.forEachIndexed { index, cmd ->
                append("  {\n")
                append("    \"directory\": \"${cmd["directory"]}\",\n")
                append("    \"file\": \"${cmd["file"]}\",\n")
                append("    \"arguments\": [\n")
                @Suppress("UNCHECKED_CAST")
                val args = cmd["arguments"] as List<String>
                args.forEachIndexed { argIndex, arg ->
                    append("      \"${arg.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
                    if (argIndex < args.size - 1) append(",")
                    append("\n")
                }
                append("    ]\n")
                append("  }")
                if (index < commands.size - 1) append(",")
                append("\n")
            }
            append("]\n")
        }
        
        compileCommandsFile.writeText(json)
        Log.i(TAG, "Generated compile_commands.json at: ${compileCommandsFile.absolutePath}")
        
        return compileCommandsFile
    }
}