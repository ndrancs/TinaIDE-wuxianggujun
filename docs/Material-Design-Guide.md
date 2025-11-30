# TinaIDE Material Design 3 规范指南

## 概述

TinaIDE 项目统一使用 **Material Design 3 (MD3)** 设计规范。**禁止** MD2 和 MD3 混用，以确保 UI 风格的一致性和现代感。

## 强制规范

### 1. 主题配置

应用主题必须继承自 Material 3：

```xml
<!-- ✅ 正确 -->
<style name="Base.Theme.TinaIDE" parent="Theme.Material3.Dark.NoActionBar">

<!-- ❌ 错误 - 不要使用 MD2 主题 -->
<style name="Base.Theme.TinaIDE" parent="Theme.MaterialComponents.Dark.NoActionBar">
```

### 2. 组件使用规范

| 功能 | ✅ MD3 组件 | ❌ 禁止使用 |
|------|------------|------------|
| 工具栏 | `MaterialToolbar` | `androidx.appcompat.widget.Toolbar` |
| 按钮 | `MaterialButton` | `Button`, `AppCompatButton` |
| 图标按钮 | `MaterialButton` (style IconButton) | `ImageButton` |
| 复选框 | `MaterialCheckBox` | `CheckBox`, `AppCompatCheckBox` |
| 单选按钮 | `MaterialRadioButton` | `RadioButton` |
| 开关 | `MaterialSwitch` | `Switch`, `SwitchCompat` |
| 进度条 | `CircularProgressIndicator` / `LinearProgressIndicator` | `ProgressBar` |
| 卡片 | `MaterialCardView` | `CardView` |
| 文本输入 | `TextInputLayout` + `TextInputEditText` | `EditText` |
| 下拉菜单 | `MaterialAutoCompleteTextView` | `Spinner` |
| 对话框 | `MaterialAlertDialogBuilder` | `AlertDialog.Builder` |
| 分割线 | `MaterialDivider` | `View` with divider background |
| 浮动按钮 | `FloatingActionButton` / `ExtendedFloatingActionButton` | - |
| 底部导航 | `BottomNavigationView` | - |
| 导航抽屉 | `NavigationView` | - |

### 3. 样式继承规范

```xml
<!-- ✅ 正确 - 使用 Material3 样式 -->
<style name="Widget.Tina.PopupMenu" parent="Widget.Material3.PopupMenu">
<style name="TextAppearance.Tina.Body" parent="TextAppearance.Material3.BodyLarge">
<style name="ThemeOverlay.Tina.Toolbar" parent="ThemeOverlay.Material3.Dark.ActionBar">

<!-- ❌ 错误 - 不要使用 AppCompat 样式 -->
<style name="Widget.Tina.PopupMenu" parent="Widget.AppCompat.PopupMenu">
<style name="TextAppearance.Tina.Body" parent="TextAppearance.AppCompat.Body1">
<style name="ThemeOverlay.Tina.Toolbar" parent="ThemeOverlay.AppCompat.Dark.ActionBar">
```

### 4. 布局文件示例

#### 工具栏
```xml
<!-- ✅ 正确 -->
<com.google.android.material.appbar.MaterialToolbar
    android:id="@+id/toolbar"
    android:layout_width="match_parent"
    android:layout_height="?attr/actionBarSize"
    app:title="标题"
    app:titleTextColor="?attr/colorOnPrimary" />

<!-- ❌ 错误 -->
<androidx.appcompat.widget.Toolbar
    android:id="@+id/toolbar"
    ... />
```

#### 图标按钮
```xml
<!-- ✅ 正确 -->
<com.google.android.material.button.MaterialButton
    style="@style/Widget.Material3.Button.IconButton"
    android:layout_width="40dp"
    android:layout_height="40dp"
    app:icon="@drawable/ic_add"
    app:iconTint="?attr/colorOnSurface" />

<!-- ❌ 错误 -->
<ImageButton
    android:layout_width="36dp"
    android:layout_height="36dp"
    android:src="@drawable/ic_add"
    android:background="?attr/selectableItemBackgroundBorderless" />
```

#### 进度指示器
```xml
<!-- ✅ 正确 -->
<com.google.android.material.progressindicator.CircularProgressIndicator
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:indeterminate="true" />

<!-- ❌ 错误 -->
<ProgressBar
    android:layout_width="wrap_content"
    android:layout_height="wrap_content" />
```

#### 复选框
```xml
<!-- ✅ 正确 -->
<com.google.android.material.checkbox.MaterialCheckBox
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="选项" />

<!-- ❌ 错误 -->
<CheckBox
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="选项" />
```

### 5. Kotlin/Java 代码规范

```kotlin
// ✅ 正确 - 使用 MaterialToolbar
import com.google.android.material.appbar.MaterialToolbar
private lateinit var toolbar: MaterialToolbar

// ❌ 错误 - 不要使用 AppCompat Toolbar
import androidx.appcompat.widget.Toolbar
private lateinit var toolbar: Toolbar

// ✅ 正确 - 使用 MaterialAlertDialogBuilder
MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_App_MaterialAlertDialog)
    .setTitle("标题")
    .setMessage("内容")
    .show()

// ❌ 错误 - 不要使用 AlertDialog.Builder
AlertDialog.Builder(context)
    .setTitle("标题")
    .show()
```

## 颜色系统

使用 Material 3 的颜色属性：

| 用途 | 属性 |
|------|------|
| 主色 | `?attr/colorPrimary` |
| 主色上的文字 | `?attr/colorOnPrimary` |
| 次要色 | `?attr/colorSecondary` |
| 背景色 | `?attr/colorSurface` |
| 背景上的文字 | `?attr/colorOnSurface` |
| 错误色 | `?attr/colorError` |

## 文字样式

使用 Material 3 的 TextAppearance：

- `TextAppearance.Material3.DisplayLarge/Medium/Small`
- `TextAppearance.Material3.HeadlineLarge/Medium/Small`
- `TextAppearance.Material3.TitleLarge/Medium/Small`
- `TextAppearance.Material3.BodyLarge/Medium/Small`
- `TextAppearance.Material3.LabelLarge/Medium/Small`

## 代码审查检查清单

在提交代码前，请确认：

- [ ] 没有使用 `androidx.appcompat.widget.Toolbar`
- [ ] 没有使用原生 `CheckBox`、`RadioButton`、`Switch`
- [ ] 没有使用原生 `ProgressBar`
- [ ] 没有使用原生 `ImageButton`（改用 `MaterialButton` IconButton 样式）
- [ ] 没有使用 `AlertDialog.Builder`
- [ ] 没有继承 `Widget.AppCompat.*` 或 `TextAppearance.AppCompat.*` 样式
- [ ] 没有使用 `ThemeOverlay.AppCompat.*` 主题覆盖

## 参考资源

- [Material Design 3 官方文档](https://m3.material.io/)
- [Material Components Android](https://github.com/material-components/material-components-android)
- [Material 3 迁移指南](https://developer.android.com/develop/ui/views/theming/look-and-feel)
