# Material Design 风格指南

## 📦 已完成的 Material Design 改造

### 1. Material Dialog（对话框）

#### ✅ 已创建的工具类
- **`MaterialDialogBuilder`** - 统一的对话框构建器

#### 📝 使用示例

**信息对话框**
```kotlin
MaterialDialogBuilder.showInfo(
    context = this,
    title = "提示",
    message = "操作成功",
    onPositive = {
        // 点击确定后的回调
    }
)
```

**确认对话框**
```kotlin
MaterialDialogBuilder.showConfirm(
    context = this,
    title = "确认删除",
    message = "确定要删除这个文件吗？",
    onPositive = {
        // 确认删除
    },
    onNegative = {
        // 取消操作
    }
)
```

**输入对话框（带验证）**
```kotlin
MaterialDialogBuilder.showInput(
    context = this,
    title = "添加文件",
    hint = "文件名，例如 main.cpp",
    validator = { input ->
        when {
            input.isEmpty() -> "文件名不能为空"
            !input.matches(Regex("[a-zA-Z0-9_.-]+")) -> "文件名包含非法字符"
            else -> null // 验证通过
        }
    },
    onConfirm = { fileName ->
        // 创建文件
    }
)
```

**单选列表对话框**
```kotlin
val items = arrayOf("选项1", "选项2", "选项3")
MaterialDialogBuilder.showSingleChoice(
    context = this,
    title = "请选择",
    items = items,
    selectedIndex = 0,
    onSelected = { index, item ->
        // 处理选择
    }
)
```

**多选列表对话框**
```kotlin
val items = arrayOf("选项1", "选项2", "选项3")
val checkedItems = booleanArrayOf(false, true, false)

MaterialDialogBuilder.showMultiChoice(
    context = this,
    title = "请选择（可多选）",
    items = items,
    checkedItems = checkedItems,
    onConfirm = { selectedIndices ->
        // 处理选择结果
    }
)
```

**警告对话框**
```kotlin
MaterialDialogBuilder.showWarning(
    context = this,
    title = "警告",
    message = "此操作可能导致数据丢失",
    onPositive = {
        // 用户知晓警告
    }
)
```

**错误对话框**
```kotlin
MaterialDialogBuilder.showError(
    context = this,
    title = "编译失败",
    message = "main.cpp:10:5 expected ';'",
    onPositive = {
        // 关闭对话框
    }
)
```

**进度对话框**
```kotlin
val dialog = MaterialDialogBuilder.showProgress(
    context = this,
    title = "正在编译",
    message = "请稍候...",
    cancelable = false
)

// 操作完成后关闭
dialog.dismiss()
```

**自定义视图对话框**
```kotlin
val customView = layoutInflater.inflate(R.layout.custom_dialog, null)
MaterialDialogBuilder.showCustomView(
    context = this,
    title = "自定义对话框",
    view = customView,
    positiveText = "确定",
    negativeText = "取消",
    onPositive = {
        // 确定操作
    }
)
```

### 2. Material Icons（图标）

#### ✅ 已创建的图标
- `ic_file.xml` - 文件图标
- `ic_folder.xml` - 文件夹图标
- `ic_settings.xml` - 设置图标
- `ic_code.xml` - 代码图标
- `ic_build.xml` - 编译图标

#### 📝 在 XML 中使用
```xml
<ImageView
    android:layout_width="24dp"
    android:layout_height="24dp"
    android:src="@drawable/ic_settings"
    android:tint="?attr/colorPrimary" />
```

#### 📝 在代码中使用
```kotlin
imageView.setImageResource(R.drawable.ic_settings)
```

### 3. Material Dialog 主题

#### ✅ 已定义的主题
- `ThemeOverlay.App.MaterialAlertDialog` - 对话框主题
- `MaterialAlertDialog.App.Title.Text` - 标题文字样式
- `MaterialAlertDialog.App.Body.Text` - 内容文字样式
- `Widget.App.Button` - 按钮样式

所有对话框自动应用深色主题，与应用整体风格一致。

### 4. Material Components

#### ✅ TextInputLayout
用于所有输入框，提供：
- Material Design 风格的轮廓框
- 浮动标签
- 错误提示
- 字符计数

```xml
<com.google.android.material.textfield.TextInputLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:boxBackgroundMode="outline"
    app:counterEnabled="true"
    app:counterMaxLength="100"
    app:errorEnabled="true">

    <com.google.android.material.textfield.TextInputEditText
        android:layout_width="match_parent"
        android:layout_height="wrap_content" />

</com.google.android.material.textfield.TextInputLayout>
```

#### ✅ CircularProgressIndicator
用于进度指示，替代旧的 ProgressBar：

```xml
<com.google.android.material.progressindicator.CircularProgressIndicator
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:indeterminate="true" />
```

## 🎨 Material Design 原则

### 统一性
- ✅ 所有对话框使用 `MaterialDialogBuilder`
- ✅ 所有图标使用 Material Icons
- ✅ 统一的颜色主题

### 响应式
- ✅ 输入验证实时反馈
- ✅ 按钮状态自动管理
- ✅ 错误提示友好

### 美观性
- ✅ 圆角设计
- ✅ 阴影效果
- ✅ 动画过渡

## 📋 待改造列表

以下对话框还需要替换为 Material Design 风格：

1. **FileContextMenuDialog** - 文件上下文菜单
2. **ProjectDialog** - 项目对话框
3. **EditorContainerFragment** - 编辑器跳转对话框
4. **InputDialogHelper** - 输入对话框辅助类
5. **FindReplaceDialog** - 查找替换对话框

## 🚀 如何添加更多 Material Icons

### 方法1：从 Android Studio 导入
1. 右键 `res/drawable` 文件夹
2. 选择 `New` → `Vector Asset`
3. 选择 `Clip Art`，搜索图标
4. 点击 `Next` → `Finish`

### 方法2：从 Material Design 网站
访问 https://fonts.google.com/icons
1. 搜索并下载 SVG 图标
2. 在 Android Studio 中转换为 Vector Drawable

### 方法3：手动创建
参考已有的 `ic_*.xml` 文件格式创建。

## 💡 最佳实践

1. **始终使用 MaterialDialogBuilder**
   - 不要直接使用 `AlertDialog.Builder`
   - 不要直接使用 `MaterialAlertDialogBuilder`

2. **输入验证**
   - 使用 `validator` 参数进行实时验证
   - 提供友好的错误提示

3. **颜色一致性**
   - 使用主题定义的颜色
   - 不要硬编码颜色值

4. **图标大小**
   - 统一使用 24dp × 24dp
   - 使用 `android:tint` 设置颜色

5. **对话框取消**
   - 危险操作设置 `cancelable = false`
   - 普通操作允许点击外部关闭

## 🔧 故障排除

### 对话框样式不生效
检查主题是否正确继承：
```xml
<style name="ThemeOverlay.App.MaterialAlertDialog" 
       parent="ThemeOverlay.Material3.MaterialAlertDialog">
```

### 图标显示不正确
检查 `android:tint` 属性：
```xml
android:tint="?attr/colorOnSurface"
```

### 输入框验证失败
确保 validator 返回 `null` 表示验证通过：
```kotlin
validator = { input ->
    if (input.isEmpty()) "不能为空" else null
}
```
