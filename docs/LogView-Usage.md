# LogView 使用文档

## 概述

`LogView` 是基于 sora-editor 的自定义日志显示组件，专门用于显示编译输出和日志信息。

## 特性

✅ **日志等级颜色高亮**
- `ERROR` / `FAIL` - 红色 (#F44336)
- `WARN` - 橙色 (#FF9800)
- `INFO` - 绿色 (#4CAF50)
- `DEBUG` - 蓝色 (#2196F3)
- `VERBOSE` - 灰色 (#9E9E9E)
- `SUCCESS` - 亮绿色 (#00E676)

✅ **只读模式** - 可复制但不可编辑

✅ **无行号** - 日志不需要显示行号

✅ **高性能** - 基于 sora-editor，性能优秀

✅ **自动滚动** - 新日志追加时自动滚动到底部

## 在 XML 中使用

```xml
<com.wuxianggujun.tinaide.output.LogView
    android:id="@+id/log_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

## 在 Kotlin 中使用

### 基础用法

```kotlin
val logView = findViewById<LogView>(R.id.log_view)

// 追加普通日志
logView.appendLog("这是一条普通日志\n")

// 追加带等级的日志
logView.appendLog(LogLevel.ERROR, "编译失败")
logView.appendLog(LogLevel.WARN, "警告：未使用的变量")
logView.appendLog(LogLevel.INFO, "编译成功")
logView.appendLog(LogLevel.DEBUG, "调试信息")

// 清空日志
logView.clearLog()

// 获取日志内容
val content = logView.getLogContent()
```

### 自动识别日志等级

日志文本中包含关键字会自动高亮：

```kotlin
logView.appendLog("[ERROR] 文件未找到\n")  // ERROR 会显示为红色
logView.appendLog("[WARN] 内存不足\n")    // WARN 会显示为橙色
logView.appendLog("[INFO] 编译完成\n")    // INFO 会显示为绿色
```

## 日志等级

```kotlin
enum class LogLevel {
    ERROR,      // 错误（红色）
    WARN,       // 警告（橙色）
    INFO,       // 信息（绿色）
    DEBUG,      // 调试（蓝色）
    VERBOSE,    // 详细（灰色）
    SUCCESS,    // 成功（亮绿色）
    FAIL        // 失败（亮红色）
}
```

## 自定义配置

如果需要自定义 LogView，可以继承并重写：

```kotlin
class CustomLogView(context: Context, attrs: AttributeSet?) : LogView(context, attrs) {
    init {
        // 自定义配置
        textSizePx = 40f  // 修改文字大小
        // 修改颜色方案等
    }
}
```

## 与 OutputManager 集成

LogView 已经集成到输出系统中：

```kotlin
// 获取输出管理器
val outputManager = ServiceLocator.get<IOutputManager>()

// 追加输出（会自动更新所有监听的 LogView）
outputManager.appendOutput("[INFO] 编译开始\n")

// 显示输出窗口（会打开 OutputActivity，其中包含 LogView）
outputManager.showOutput()
```

## 编译输出示例

```
=== 编译开始 ===
目标: aarch64-linux-android24
sysroot: /data/user/0/com.wuxianggujun.tinaide/files/sysroot
工程: TestProject @ /storage/emulated/0/TinaIDE/Projects/TestProject
源文件数: 3

[INFO] 编译 main.cpp -> main.cpp.o
SUCCESS: main.cpp
[INFO] 编译 utils.cpp -> utils.cpp.o
SUCCESS: utils.cpp
[WARN] 未使用的变量 'unused_var'
[INFO] 编译 test.cpp -> test.cpp.o
ERROR: test.cpp:10:5 expected ';'

=== 编译结束 ===
生成 .o 成功: 2, 语法通过(回退): 0, 失败: 1
```

在上面的输出中：
- `[INFO]` 会显示为绿色
- `SUCCESS` 会显示为亮绿色
- `[WARN]` 会显示为橙色
- `ERROR` 会显示为红色

## 性能优化

LogView 基于 sora-editor，已经做了以下优化：

1. **延迟渲染** - 只渲染可见区域
2. **高效滚动** - 使用硬件加速
3. **智能语法分析** - 只分析修改的行
4. **异步追加** - 不阻塞 UI 线程

## 注意事项

1. LogView 默认是只读的，如需编辑请调用 `logView.isEditable = true`
2. 日志等级关键字需要大写（ERROR、WARN、INFO 等）才能被识别
3. 建议每次追加日志时添加换行符 `\n`，否则会连续显示在同一行
