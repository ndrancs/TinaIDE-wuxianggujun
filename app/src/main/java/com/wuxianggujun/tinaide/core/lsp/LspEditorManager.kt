package com.wuxianggujun.tinaide.core.lsp

import android.content.Context
import android.util.Log
import com.wuxianggujun.tinaide.core.nativebridge.SysrootLibraryLoader
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

    enum class BuildType(val dirName: String) {
        Debug("debug"),
        Release("release")
    }

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
    var buildType: BuildType = BuildType.Debug
    
    // clangd 二进制文件路径
    private var clangdPath: String? = null
    private var sysrootDir: File? = null
    
    // 是否已初始化
    private var initialized = false

    /**
     * 初始化 LSP 管理器
     *
     * 查找 libclangd.so 共享库，优先从 sysroot/usr/lib/<triple>/runtime/ 目录查找。
     * 由于 Android 限制可执行文件，clangd 必须编译为共享库并通过 JNI/dlopen 加载。
     *
     * @param sysrootDir sysroot 目录
     */
    fun initialize(sysrootDir: File) {
        if (initialized) {
            Log.d(TAG, "Already initialized")
            return
        }

        this.sysrootDir = sysrootDir

        // 使用 SysrootLibraryLoader 获取正确的 runtime 库目录
        val libraryLoader = SysrootLibraryLoader.getInstance(context)
        val runtimeLibDir = libraryLoader.runtimeLibDir
        
        // 查找 libclangd.so 共享库
        val clangdSoFile = File(runtimeLibDir, "libclangd.so")
        
        // 也检查旧的可执行文件路径作为 fallback（未来可能支持独立进程模式）
        val clangdBinFile = File(sysrootDir, "usr/bin/clangd")
        val clangdHostFile = File(sysrootDir, "usr/bin/clangd-host")
        
        clangdPath = when {
            // 优先使用共享库（当前唯一支持的方式）
            clangdSoFile.exists() -> {
                Log.i(TAG, "Found libclangd.so at: ${clangdSoFile.absolutePath}")
                clangdSoFile.absolutePath
            }
            // Fallback: 检查可执行文件（未来可能支持）
            clangdBinFile.exists() && clangdBinFile.name.endsWith(".so") -> {
                Log.i(TAG, "Found clangd .so at: ${clangdBinFile.absolutePath}")
                clangdBinFile.absolutePath
            }
            clangdHostFile.exists() && clangdHostFile.name.endsWith(".so") -> {
                Log.i(TAG, "Found clangd-host .so at: ${clangdHostFile.absolutePath}")
                clangdHostFile.absolutePath
            }
            else -> {
                Log.w(TAG, "libclangd.so not found in runtime directory: ${runtimeLibDir.absolutePath}")
                Log.w(TAG, "Please ensure libclangd.so is installed in sysroot/usr/lib/<triple>/runtime/")
                null
            }
        }

        if (clangdPath != null) {
            Log.i(TAG, "clangd path set to: $clangdPath")
            initialized = true
        } else {
            Log.e(TAG, "Failed to initialize LSP: libclangd.so not found")
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
            val compileDir = ensureBuildDir(projectPath, buildType)
            val serverArgs = buildServerArgs(compileDir)
            LspProject(projectPath).apply {
                init()
                // ���� clangd ����������
                addServerDefinition(ClangdServerDefinition(clangdPath!!, serverArgs))
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

    private fun ensureBuildDir(projectPath: String, buildType: BuildType = this.buildType): File {
        val buildRoot = File(projectPath, "build")
        val dir = File(buildRoot, buildType.dirName)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCompileCommandsFile(
        projectPath: String,
        buildType: BuildType = this.buildType
    ): File {
        val dir = ensureBuildDir(projectPath, buildType)
        return File(dir, "compile_commands.json")
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
        target: String = "aarch64-linux-android28",
        buildType: BuildType = this.buildType
    ): File {
        val compileCommandsFile = getCompileCommandsFile(projectPath, buildType)
        val compileDir = compileCommandsFile.parentFile
            ?: throw IllegalStateException("compile_commands.json must reside inside a directory")
        if (!compileDir.exists()) {
            compileDir.mkdirs()
        }
        val objDir = File(compileDir, "obj")
        if (!objDir.exists()) {
            objDir.mkdirs()
        }
        val sysrootPath = sysrootDir?.absolutePath
        val tripleBase = deriveTripleBase(target)
        val apiLevel = deriveApiLevel(target)
        val resolvedIncludeDirs = (includeDirs + projectPath).distinct()

        val commands = sourceFiles.map { sourceFile ->
            val args = mutableListOf<String>().apply {
                add("clang" + if (isCxx) "++" else "")
                add("-target")
                add(target)
                sysrootPath?.let { sysroot ->
                    add("--sysroot=" + sysroot)
                }
                if (isCxx) {
                    add("-std=c++17")
                }
                add("-c")
                add(sourceFile)
                add("-DANDROID")
                add("-D__ANDROID__")
                sysrootPath?.let { sysroot ->
                    add("-D__ANDROID_API__=" + apiLevel)
                    add("-isystem")
                    add(sysroot + "/usr/include")
                    if (tripleBase.isNotEmpty()) {
                        add("-isystem")
                        add(sysroot + "/usr/include/" + tripleBase)
                    }
                    add("-I" + sysroot + "/usr/include/c++/v1")
                }
                resolvedIncludeDirs.forEach { dir ->
                    add("-I" + dir)
                }
                defines.forEach { define ->
                    add("-D" + define)
                }
                val objFile = File(objDir, File(sourceFile).nameWithoutExtension + ".o")
                add("-o")
                add(objFile.absolutePath)
            }
            mapOf(
                "directory" to projectPath,
                "file" to sourceFile,
                "arguments" to args
            )
        }

        val json = buildString {
            append("[\n")
            commands.forEachIndexed { index, cmd ->
                append("  {\n")
                append("    \"directory\": \"" + cmd["directory"] + "\",\n")
                append("    \"file\": \"" + cmd["file"] + "\",\n")
                append("    \"arguments\": [\n")
                @Suppress("UNCHECKED_CAST")
                val args = cmd["arguments"] as List<String>
                args.forEachIndexed { argIndex, arg ->
                    append("      \"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
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

    private fun deriveTripleBase(target: String): String {
        if (target.isEmpty()) return ""
        return target.trimEnd { it.isDigit() }
    }

    private fun deriveApiLevel(target: String): String {
        val digits = target.takeLastWhile { it.isDigit() }
        return digits.ifEmpty { "24" }
    }


    private fun buildServerArgs(compileDir: File): List<String> {
        val args = mutableListOf("--compile-commands-dir=${compileDir.absolutePath}")
        findClangResourceDir()?.let { resourceDir ->
            args += "--resource-dir=${resourceDir.absolutePath}"
            Log.i(TAG, "Using clang resource dir: ${resourceDir.absolutePath}")
        } ?: Log.w(TAG, "Clang resource dir not found under sysroot; using clangd default")
        return args
    }

    private fun findClangResourceDir(): File? {
        val baseDir = sysrootDir ?: return null
        val clangRoot = File(baseDir, "lib/clang")
        if (!clangRoot.exists()) {
            return null
        }
        val versionDirs = clangRoot.listFiles { file -> file.isDirectory } ?: return null
        return versionDirs.maxByOrNull { it.name }
    }


}

