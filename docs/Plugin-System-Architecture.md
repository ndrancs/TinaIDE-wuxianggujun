# TinaIDE 插件化系统架构设计

## 📋 目录

1. [概述](#概述)
2. [设计目标](#设计目标)
3. [架构设计](#架构设计)
4. [技术方案](#技术方案)
5. [实现细节](#实现细节)
6. [插件开发指南](#插件开发指南)
7. [安全性考虑](#安全性考虑)

---

## 概述

TinaIDE 插件化系统允许动态加载语言编译器插件，无需将所有编译器打包进主 APK。用户可以按需下载和安装所需的语言支持插件。

### 核心特性

- ✅ **动态加载** - 运行时加载插件 JAR/DEX
- ✅ **按需安装** - 用户需要什么语言就安装什么
- ✅ **独立更新** - 插件可单独更新，无需更新整个 APP
- ✅ **体积优化** - 主 APK 保持小体积（~20MB）
- ✅ **无权限问题** - 使用 DexClassLoader，在私有目录加载

---

## 设计目标

### 1. 主应用轻量化

```
TinaIDE 主 APK (20MB)
├── 核心功能
│   ├── 编辑器（Sora Editor）
│   ├── 文件管理
│   ├── 项目管理
│   └── UI 框架
└── 内置插件
    └── C/C++ 支持（libclang.so）
```

### 2. 插件按需下载

```
用户需要 Java?
    → 插件市场下载 java-plugin.jar (2MB)
    → 自动安装并加载
    → 立即可用

用户需要 Python?
    → 下载 python-plugin.jar (15MB)
    → 加载 Chaquopy
    → 支持 Python 开发
```

### 3. 扩展性

- 第三方开发者可以开发插件
- 支持插件市场/商店
- 插件版本管理
- 插件依赖管理

---

## 架构设计

### 整体架构

```
┌─────────────────────────────────────────────────────┐
│                  TinaIDE Main App                    │
├─────────────────────────────────────────────────────┤
│                   Plugin Manager                     │
│  ┌───────────────────────────────────────────────┐  │
│  │          Plugin Loader (DexClassLoader)       │  │
│  └───────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────┤
│                 ILanguagePlugin API                  │
├─────────────────────────────────────────────────────┤
│    Builtin Plugins     │     Dynamic Plugins         │
├────────────────────────┼─────────────────────────────┤
│  C/C++ (libclang.so)   │  Java (ECJ)                │
│  内置在 APK            │  Python (Chaquopy)         │
│                        │  JavaScript (Rhino)        │
│                        │  Go (TinyGo)               │
│                        │  ... (第三方插件)          │
└────────────────────────┴─────────────────────────────┘
```

### 插件生命周期

```
1. 发现插件
   └─ 插件市场/本地导入
   
2. 下载插件
   └─ 下载 JAR 到 cache
   
3. 安装插件
   └─ 验证签名/兼容性
   └─ 复制到 plugins/ 目录
   
4. 加载插件
   └─ DexClassLoader 加载
   └─ 实例化 ILanguagePlugin
   
5. 使用插件
   └─ compile() / run()
   
6. 卸载插件
   └─ dispose()
   └─ 删除文件
```

---

## 技术方案

### 1. DexClassLoader 动态加载

Android 官方支持的动态加载方案。

#### 原理

```kotlin
// 主 APK 的 ClassLoader
val parentClassLoader = context.classLoader

// 创建插件 ClassLoader
val pluginClassLoader = DexClassLoader(
    pluginJarPath,              // 插件 JAR 路径
    optimizedDirectory,          // DEX 优化目录
    librarySearchPath,           // Native 库路径（可选）
    parentClassLoader            // 父 ClassLoader
)

// 加载插件类
val pluginClass = pluginClassLoader.loadClass("com.example.JavaPlugin")
val plugin = pluginClass.newInstance() as ILanguagePlugin
```

#### 优势

- ✅ Android 官方支持
- ✅ 无需 Root 权限
- ✅ 沙盒隔离
- ✅ 支持热更新

#### 文件结构

```
/data/data/com.wuxianggujun.tinaide/
├── files/
│   └── plugins/                 # 已安装插件
│       ├── java.jar
│       ├── python.jar
│       └── javascript.jar
├── cache/
│   ├── plugin_dex/              # DEX 优化缓存
│   └── downloads/               # 临时下载
└── code_cache/                  # ART 缓存
```

---

### 2. 插件接口定义

所有插件必须实现 `ILanguagePlugin` 接口。

#### 核心接口

```kotlin
interface ILanguagePlugin {
    // 元数据
    val id: String                          // 插件唯一 ID
    val name: String                        // 显示名称
    val version: String                     // 版本号
    val supportedExtensions: List<String>   // 支持的文件扩展名
    
    // 生命周期
    fun initialize(context: Context)        // 初始化
    fun dispose()                           // 清理
    
    // 编译
    fun canCompile(): Boolean               // 是否支持编译
    fun compile(file: File, options: CompileOptions): CompileResult
    
    // 运行
    fun run(executable: File, args: List<String>): RunResult
}
```

#### 数据类

```kotlin
data class CompileOptions(
    val outputPath: String,
    val optimizationLevel: Int = 0,
    val debugSymbols: Boolean = false,
    val additionalFlags: List<String> = emptyList()
)

data class CompileResult(
    val success: Boolean,
    val output: String,
    val errors: List<CompileError> = emptyList()
)

data class CompileError(
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
    val severity: ErrorSeverity
)
```

---

### 3. 插件打包格式

#### JAR 结构

```
java-plugin-1.0.0.jar
├── META-INF/
│   └── MANIFEST.MF
├── plugin.json                    # 插件元数据
├── com/
│   └── tinaide/
│       └── plugin/
│           └── java/
│               ├── JavaLanguagePlugin.class
│               └── EcjCompiler.class
└── libs/                          # 依赖库（可选）
    └── ecj-3.35.0.jar
```

#### plugin.json 格式

```json
{
  "id": "java",
  "name": "Java Language Support",
  "version": "1.0.0",
  "author": "TinaIDE Team",
  "description": "Java 编译和运行支持 (ECJ 编译器)",
  "icon": "https://cdn.tinaide.com/icons/java.png",
  "mainClass": "com.tinaide.plugin.java.JavaLanguagePlugin",
  "minAppVersion": "1.0.0",
  "maxAppVersion": "2.0.0",
  "size": 2048000,
  "md5": "abc123...",
  "downloadUrl": "https://plugins.tinaide.com/java-plugin-1.0.0.jar",
  "dependencies": [],
  "permissions": [],
  "resources": {
    "android.jar": "libs/android.jar"
  }
}
```

---

### 4. 插件加载流程

```kotlin
// 1. 下载插件
suspend fun downloadPlugin(url: String): File {
    val tempFile = File(context.cacheDir, "temp.jar")
    // HTTP 下载到 tempFile
    return tempFile
}

// 2. 验证插件
fun validatePlugin(file: File): PluginInfo {
    // 解析 plugin.json
    val metadata = extractMetadata(file)
    
    // 验证版本兼容性
    if (!isVersionCompatible(metadata.minAppVersion)) {
        throw IncompatibleVersionException()
    }
    
    // 验证签名（可选）
    if (!verifySignature(file)) {
        throw SecurityException("Invalid signature")
    }
    
    return metadata
}

// 3. 安装插件
fun installPlugin(tempFile: File, metadata: PluginInfo) {
    val targetFile = File(pluginDir, "${metadata.id}.jar")
    tempFile.copyTo(targetFile, overwrite = true)
}

// 4. 加载插件
fun loadPlugin(pluginId: String): ILanguagePlugin {
    val pluginFile = File(pluginDir, "$pluginId.jar")
    
    // 创建 ClassLoader
    val classLoader = DexClassLoader(
        pluginFile.absolutePath,
        optimizedDir.absolutePath,
        null,
        context.classLoader
    )
    
    // 加载主类
    val metadata = extractMetadata(pluginFile)
    val clazz = classLoader.loadClass(metadata.mainClass)
    
    // 实例化
    val plugin = clazz.newInstance() as ILanguagePlugin
    plugin.initialize(context)
    
    return plugin
}
```

---

## 实现细节

### 1. PluginManager 实现

```kotlin
class PluginManager private constructor() {
    
    private val plugins = ConcurrentHashMap<String, ILanguagePlugin>()
    private val pluginLoader by lazy { PluginLoader(context!!) }
    
    companion object {
        @Volatile
        private var instance: PluginManager? = null
        
        fun getInstance(): PluginManager {
            return instance ?: synchronized(this) {
                instance ?: PluginManager().also { instance = it }
            }
        }
    }
    
    fun initialize(context: Context) {
        // 加载内置插件
        registerBuiltinPlugins(context)
        
        // 自动加载已安装插件
        loadInstalledPlugins()
    }
    
    private fun loadInstalledPlugins() {
        pluginLoader.getInstalledPlugins().forEach { info ->
            try {
                val plugin = pluginLoader.loadPlugin(info.id).getOrThrow()
                registerPlugin(plugin)
            } catch (e: Exception) {
                Logger.e("Failed to load plugin: ${info.id}", e)
            }
        }
    }
    
    fun registerPlugin(plugin: ILanguagePlugin) {
        plugins[plugin.id] = plugin
        Logger.i("Registered plugin: ${plugin.name}")
    }
    
    fun getPluginForFile(file: File): ILanguagePlugin? {
        val ext = file.extension.lowercase()
        return plugins.values.find { ext in it.supportedExtensions }
    }
    
    suspend fun downloadAndInstall(
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Result<ILanguagePlugin> {
        return withContext(Dispatchers.IO) {
            runCatching {
                // 下载
                val tempFile = downloadFile(downloadUrl, onProgress)
                
                // 安装
                val metadata = pluginLoader.installPlugin(tempFile).getOrThrow()
                
                // 加载
                val plugin = pluginLoader.loadPlugin(metadata.id).getOrThrow()
                
                // 注册
                registerPlugin(plugin)
                
                tempFile.delete()
                plugin
            }
        }
    }
}
```

---

### 2. CompileService 统一编译入口

```kotlin
class CompileService {
    
    private val pluginManager = PluginManager.getInstance()
    
    suspend fun compileFile(
        file: File,
        outputPath: String,
        onProgress: (String) -> Unit
    ): CompileResult = withContext(Dispatchers.IO) {
        
        // 根据文件类型找插件
        val plugin = pluginManager.getPluginForFile(file)
            ?: return@withContext CompileResult(
                success = false,
                output = "不支持的文件类型: ${file.extension}"
            )
        
        onProgress("使用 ${plugin.name} 编译...")
        
        // 调用插件编译
        plugin.compile(
            file,
            CompileOptions(outputPath = outputPath)
        )
    }
    
    suspend fun compileProject(
        projectRoot: File,
        buildDir: File,
        onProgress: (String) -> Unit
    ): ProjectCompileResult {
        
        // 扫描源文件
        val sourceFiles = projectRoot.walkTopDown()
            .filter { it.isFile }
            .toList()
        
        // 按语言分组
        val filesByPlugin = sourceFiles.groupBy { file ->
            pluginManager.getPluginForFile(file)
        }
        
        // 编译每种语言
        filesByPlugin.forEach { (plugin, files) ->
            if (plugin == null) return@forEach
            
            onProgress("编译 ${files.size} 个 ${plugin.name} 文件...")
            
            files.forEach { file ->
                val result = plugin.compile(file, CompileOptions(
                    outputPath = File(buildDir, file.nameWithoutExtension + ".o").absolutePath
                ))
                
                if (result.success) {
                    onProgress("✅ ${file.name}")
                } else {
                    onProgress("❌ ${file.name}")
                }
            }
        }
        
        return ProjectCompileResult(success = true)
    }
}
```

---

## 插件开发指南

### 1. 创建插件项目

```bash
# 创建 Android Library 模块
tinaide-plugins/
├── java-plugin/
│   ├── build.gradle.kts
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       └── resources/
│   │           └── plugin.json
└── settings.gradle.kts
```

### 2. build.gradle.kts 配置

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.tinaide.plugin.java"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    // 只编译时依赖主应用
    compileOnly(project(":app"))
    
    // 插件需要的库
    implementation("org.eclipse.jdt:ecj:3.35.0")
}

// 打包插件 JAR
tasks.register<Jar>("buildPlugin") {
    archiveBaseName.set("java-plugin")
    archiveVersion.set("1.0.0")
    
    // 包含编译后的类
    from("build/intermediates/classes/release")
    
    // 包含依赖库
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    
    // 包含资源
    from("src/main/resources")
    
    // 排除不需要的文件
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
```

### 3. 实现插件

```kotlin
package com.tinaide.plugin.java

import android.content.Context
import com.wuxianggujun.tinaide.plugin.*
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import java.io.File

class JavaLanguagePlugin : ILanguagePlugin {
    
    override val id = "java"
    override val name = "Java"
    override val version = "1.0.0"
    override val supportedExtensions = listOf("java")
    
    override fun initialize(context: Context) {
        // 初始化逻辑
    }
    
    override fun dispose() {
        // 清理资源
    }
    
    override fun canCompile() = true
    
    override fun compile(file: File, options: CompileOptions): CompileResult {
        // 使用 ECJ 编译
        val args = arrayOf(
            "-source", "11",
            "-target", "11",
            "-d", options.outputPath,
            file.absolutePath
        )
        
        val success = BatchCompiler.compile(args, ...)
        
        return CompileResult(success, "...")
    }
    
    override fun run(executable: File, args: List<String>): RunResult {
        // 运行逻辑
        return RunResult(success = false, message = "Not implemented")
    }
}
```

### 4. 打包发布

```bash
# 构建插件
./gradlew :java-plugin:buildPlugin

# 生成文件
build/libs/java-plugin-1.0.0.jar

# 上传到插件市场
```

---

## 安全性考虑

### 1. 插件验证

```kotlin
// 验证签名
fun verifyPluginSignature(pluginFile: File): Boolean {
    // TODO: 使用数字签名验证插件完整性
    return true
}

// 验证兼容性
fun checkCompatibility(metadata: PluginInfo): Boolean {
    val appVersion = BuildConfig.VERSION_NAME
    return appVersion >= metadata.minAppVersion &&
           appVersion <= metadata.maxAppVersion
}
```

### 2. 权限控制

```kotlin
// 插件运行在沙盒中
// 只能访问授权的目录和资源
class PluginSandbox {
    fun canAccessFile(file: File): Boolean {
        // 检查文件是否在允许范围内
        return file.startsWith(allowedDir)
    }
}
```

### 3. 资源隔离

- 每个插件独立的 ClassLoader
- 插件之间互不干扰
- 插件崩溃不影响主应用

---

## 性能优化

### 1. 懒加载

```kotlin
// 插件只在首次使用时加载
val plugin by lazy {
    pluginLoader.loadPlugin("java")
}
```

### 2. 缓存优化

```kotlin
// DEX 优化结果缓存
val optimizedDir = File(context.codeCache, "plugin_dex")

// 避免重复加载
private val loadedPlugins = mutableMapOf<String, ILanguagePlugin>()
```

### 3. 异步加载

```kotlin
// 后台加载插件列表
suspend fun preloadPlugins() = withContext(Dispatchers.IO) {
    getInstalledPlugins().forEach { loadPlugin(it.id) }
}
```

---

## 未来扩展

### 1. 插件市场

- 官方插件仓库
- 第三方插件支持
- 评分和评论系统
- 自动更新

### 2. 插件通信

- 插件间通信 API
- 共享资源机制
- 事件总线

### 3. 高级特性

- 代码补全插件
- 调试器插件
- Git 插件
- 主题插件

---

## 参考资料

- [Android DexClassLoader](https://developer.android.com/reference/dalvik/system/DexClassLoader)
- [Eclipse ECJ](https://www.eclipse.org/jdt/core/)
- [Android Plugin Architecture Best Practices](https://developer.android.com/guide/practices)
