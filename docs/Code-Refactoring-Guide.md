# 代码重构指南

## 📦 新增的工具类和基类

### 1. Toast 工具 ✅

#### ToastUtil
统一的 Toast 管理工具，自动取消之前的 Toast。

```kotlin
// 基础用法
ToastUtil.show(context, "消息")
ToastUtil.showLong(context, "长消息")

// 带图标的消息
ToastUtil.showSuccess(context, "操作成功")  // ✅ 操作成功
ToastUtil.showError(context, "操作失败")    // ❌ 操作失败
ToastUtil.showWarning(context, "警告")      // ⚠️ 警告
ToastUtil.showInfo(context, "提示")         // ℹ️ 提示
```

#### Context 扩展函数（推荐）
```kotlin
// 在 Activity 或 Fragment 中直接使用
toast("消息")
toastLong("长消息")
toastSuccess("成功")
toastError("失败")
toastWarning("警告")
toastInfo("提示")
```

#### 迁移示例
```kotlin
// 旧代码
Toast.makeText(this, "文件已保存", Toast.LENGTH_SHORT).show()

// 新代码
toastSuccess("文件已保存")
```

---

### 2. 错误处理 ✅

#### ErrorHandler
统一的错误处理工具，自动记录日志并显示友好的错误信息。

```kotlin
// 显示错误对话框
ErrorHandler.handle(context, exception, title = "操作失败")

// 显示错误 Toast
ErrorHandler.handleWithToast(context, exception, prefix = "加载失败")

// 只记录日志
ErrorHandler.log(exception)
```

#### Context 扩展函数（推荐）
```kotlin
// 在 Activity 或 Fragment 中
handleError(exception)  // 显示对话框
handleErrorWithToast(exception, "保存失败")  // 显示 Toast
```

#### 迁移示例
```kotlin
// 旧代码
try {
    file.delete()
} catch (e: Exception) {
    Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
}

// 新代码
try {
    file.delete()
} catch (e: Exception) {
    handleErrorWithToast(e, "删除失败")
}
```

---

### 3. 日志工具 ✅

#### Logger
统一的日志管理，Release 版本自动禁用调试日志。

```kotlin
Logger.d("调试信息")              // Debug（仅 Debug 版本）
Logger.i("信息")                  // Info
Logger.w("警告")                  // Warning
Logger.e("错误", exception)       // Error
Logger.v("详细信息")              // Verbose（仅 Debug 版本）
```

#### 迁移示例
```kotlin
// 旧代码
Log.d("MainActivity", "File opened")
Log.e("MainActivity", "Error", exception)

// 新代码
Logger.d("File opened", tag = "MainActivity")
Logger.e("Error", exception, tag = "MainActivity")
```

---

### 4. BaseActivity ✅

提供的功能：
- ✅ 自动设置沉浸式状态栏
- ✅ 加载对话框管理
- ✅ 协程作用域（自动取消）
- ✅ 错误处理
- ✅ 生命周期日志

#### 基础用法
```kotlin
class MainActivity : BaseActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // 沉浸式状态栏已自动设置
        // 不需要再手动调用 immersionBar
    }
}
```

#### 加载对话框
```kotlin
// 显示加载
showLoading("正在编译...")

// 隐藏加载
hideLoading()
```

#### 协程使用
```kotlin
// 安全执行协程（自动处理异常）
launchSafely {
    // 在 IO 线程执行
    val data = withIO {
        // 耗时操作
        loadDataFromFile()
    }
    
    // 自动切换到主线程
    updateUI(data)
}

// 自定义错误处理
launchSafely(
    onError = { error ->
        toastError("加载失败: ${error.message}")
    }
) {
    // 异步任务
}
```

#### 自定义状态栏
```kotlin
class CustomActivity : BaseActivity() {
    
    override fun setupImmersionBar() {
        // 自定义状态栏配置
        immersionBar {
            statusBarColorInt(getColor(R.color.custom_color))
            // ...
            init()
        }
    }
}
```

---

### 5. BaseFragment ✅

功能与 BaseActivity 类似，但委托给 Activity 管理。

```kotlin
class MyFragment : BaseFragment() {
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 使用协程
        launchSafely {
            val data = withIO {
                // 加载数据
            }
            updateUI(data)
        }
    }
}
```

---

### 6. FileUtils ✅

文件操作工具，使用 Result 类型处理错误。

#### 创建文件/目录
```kotlin
// 创建文件
FileUtils.createFile(parentDir, "main.cpp")
    .onSuccess { file ->
        toastSuccess("文件已创建")
    }
    .onFailure { error ->
        handleErrorWithToast(error, "创建失败")
    }

// 创建目录
FileUtils.createDirectory(parentDir, "src")
    .onSuccess { dir ->
        toastSuccess("目录已创建")
    }
    .onFailure { error ->
        handleErrorWithToast(error)
    }
```

#### 删除文件
```kotlin
FileUtils.delete(file)
    .onSuccess {
        toastSuccess("已删除")
    }
    .onFailure { error ->
        handleError(error, "删除失败")
    }
```

#### 重命名文件
```kotlin
FileUtils.rename(file, "new_name.cpp")
    .onSuccess { newFile ->
        toastSuccess("已重命名")
    }
    .onFailure { error ->
        handleErrorWithToast(error)
    }
```

#### 复制文件
```kotlin
FileUtils.copyFile(source, dest, overwrite = false)
    .onSuccess {
        toastSuccess("复制成功")
    }
    .onFailure { error ->
        handleError(error)
    }
```

#### 读写文件
```kotlin
// 读取
FileUtils.readText(file)
    .onSuccess { content ->
        editor.setText(content)
    }
    .onFailure { error ->
        handleError(error, "读取失败")
    }

// 写入
FileUtils.writeText(file, editor.text.toString())
    .onSuccess {
        toastSuccess("保存成功")
    }
    .onFailure { error ->
        handleError(error, "保存失败")
    }
```

#### 其他工具方法
```kotlin
// 获取文件大小
val size = FileUtils.getFormattedSize(file)  // "1.5 MB"

// 检查文件名是否合法
if (FileUtils.isValidFileName(name)) {
    // 合法
}

// 判断是否是代码文件
if (FileUtils.isCodeFile(file)) {
    // 是代码文件
}
```

---

## 🔄 迁移步骤

### 步骤 1：修改 MainActivity
```kotlin
// 修改继承
class MainActivity : BaseActivity() {  // 原来是 AppCompatActivity()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // 删除这两行（BaseActivity 已自动处理）
        // setTheme(R.style.Theme_TinaIDE)
        // immersionBar { ... }
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // 其他代码保持不变
    }
    
    // 使用新的扩展函数
    private fun showAddFileDialog() {
        // 旧代码
        // Toast.makeText(this, "请先打开项目", Toast.LENGTH_SHORT).show()
        
        // 新代码
        toastError("请先打开项目")
    }
}
```

### 步骤 2：修改 ProjectManagerActivity
```kotlin
class ProjectManagerActivity : BaseActivity() {
    
    // 删除重复的沉浸式状态栏代码
    // immersionBar { ... }
    
    // 使用协程
    private fun loadProjects() {
        launchSafely {
            showLoading("加载项目列表...")
            
            val projects = withIO {
                // 耗时操作
                loadProjectsFromDisk()
            }
            
            hideLoading()
            updateProjectList(projects)
        }
    }
}
```

### 步骤 3：修改文件操作代码
```kotlin
// 旧代码
try {
    val file = File(parent, name)
    if (file.exists()) {
        Toast.makeText(this, "文件已存在", Toast.LENGTH_SHORT).show()
        return
    }
    file.createNewFile()
    Toast.makeText(this, "创建成功", Toast.LENGTH_SHORT).show()
} catch (e: Exception) {
    Toast.makeText(this, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
}

// 新代码
FileUtils.createFile(parent, name)
    .onSuccess { file ->
        toastSuccess("文件已创建")
        refreshFileTree()
    }
    .onFailure { error ->
        handleErrorWithToast(error, "创建失败")
    }
```

---

## 📊 收益分析

| 改进项 | 减少代码量 | 提升 |
|--------|-----------|------|
| Toast 封装 | 50%+ | 代码简洁性 ⬆⬆ |
| BaseActivity | 80%+ | 维护性 ⬆⬆⬆ |
| ErrorHandler | 60%+ | 用户体验 ⬆⬆ |
| FileUtils | 40%+ | 安全性 ⬆⬆⬆ |
| Logger | 20%+ | 调试效率 ⬆⬆ |

---

## ✅ 检查清单

完成迁移后，检查以下项目：

- [ ] 所有 Activity 继承自 BaseActivity
- [ ] 所有 Fragment 继承自 BaseFragment
- [ ] 替换所有 `Toast.makeText` 为扩展函数
- [ ] 替换所有 try-catch 为 FileUtils + Result
- [ ] 替换所有 Log.* 为 Logger.*
- [ ] 删除重复的沉浸式状态栏代码
- [ ] 使用 launchSafely 替代 Thread

---

## 🚀 下一步优化

1. **引入 ViewModel** - 分离业务逻辑
2. **使用 LiveData/Flow** - 响应式数据
3. **依赖注入（Koin）** - 替代 ServiceLocator
4. **数据库（Room）** - 持久化存储
5. **单元测试** - 保证代码质量
