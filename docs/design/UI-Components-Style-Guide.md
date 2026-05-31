# TinaIDE UI 组件样式指南

## 概述

本文档定义了 TinaIDE 应用中 UI 组件的统一样式规范，确保整个应用的视觉一致性。

## 圆角规范

| 用途 | 圆角大小 | 常量名 |
|------|----------|--------|
| 徽章、标签 | 4dp | `TinaShapes.ExtraSmallCorner` |
| 小型卡片、小按钮 | 8dp | `TinaShapes.SmallCorner` |
| 按钮、输入框 | 12dp | `TinaShapes.ButtonCorner` / `TinaShapes.TextFieldCorner` |
| 卡片 | 16dp | `TinaShapes.CardCorner` |
| 对话框、底部弹窗 | 24dp | `TinaShapes.DialogCorner` |

## 按钮类型

### 1. 主要按钮 (Primary Button)
- **用途**: 页面中最重要的操作，如"确认"、"下一步"、"保存"
- **样式**: 填充样式，使用 `MaterialTheme.colorScheme.primary` 背景
- **组件**: `TinaPrimaryButton` / `TinaPrimaryButtonLarge`

```kotlin
TinaPrimaryButton(
    text = "确认",
    onClick = { /* ... */ }
)

// 大号按钮（用于页面底部）
TinaPrimaryButtonLarge(
    text = "下一步",
    onClick = { /* ... */ }
)
```

### 2. 次要按钮 (Secondary Button)
- **用途**: 次要操作，如"编辑"、"查看详情"
- **样式**: 色调样式 (Tonal)
- **组件**: `TinaSecondaryButton`

```kotlin
TinaSecondaryButton(
    text = "编辑",
    onClick = { /* ... */ }
)
```

### 3. 轮廓按钮 (Outlined Button)
- **用途**: 中等强调的操作，如"取消"、"返回"
- **样式**: 轮廓样式
- **组件**: `TinaOutlinedButton`

```kotlin
TinaOutlinedButton(
    text = "取消",
    onClick = { /* ... */ }
)
```

### 4. 文本按钮 (Text Button)
- **用途**: 低优先级操作，如"跳过"、"稍后"
- **样式**: 纯文本
- **组件**: `TinaTextButton`

```kotlin
TinaTextButton(
    text = "跳过",
    onClick = { /* ... */ }
)
```

### 5. 危险按钮 (Danger Button)
- **用途**: 删除、清除等危险操作
- **样式**: 使用 `MaterialTheme.colorScheme.error` 颜色
- **组件**: `TinaDangerButton` / `TinaDangerOutlinedButton`

```kotlin
TinaDangerButton(
    text = "删除",
    onClick = { /* ... */ }
)
```

## 输入框

### 统一输入框
- **样式**: OutlinedTextField，圆角 12dp
- **组件**: `TinaTextField`

```kotlin
TinaTextField(
    value = text,
    onValueChange = { text = it },
    label = "用户名",
    placeholder = "请输入用户名",
    isError = hasError,
    errorText = "用户名不能为空"
)
```

## 卡片

### 1. 普通卡片
- **圆角**: 16dp
- **组件**: `TinaCard`

```kotlin
TinaCard(
    onClick = { /* 可选 */ }
) {
    // 内容
}
```

## 对话框

### 统一对话框
- **圆角**: 24dp
- **组件**: `TinaAlertDialog` / `TinaConfirmDialog`

```kotlin
TinaConfirmDialog(
    title = "确认删除",
    message = "确定要删除这个项目吗？",
    confirmText = "删除",
    dismissText = "取消",
    onConfirm = { /* ... */ },
    onDismiss = { /* ... */ },
    isDanger = true
)
```

## 徽章

### 推荐徽章
```kotlin
TinaRecommendedBadge()
```

## 迁移指南

### 从旧样式迁移

1. **按钮圆角统一**
   - 将所有 `RoundedCornerShape(16.dp)` 或 `RoundedCornerShape(24.dp)` 或 `RoundedCornerShape(28.dp)` 改为 `RoundedCornerShape(TinaShapes.ButtonCorner)`
   - 或直接使用 `TinaPrimaryButton` 等组件

2. **卡片圆角统一**
   - 将所有卡片的 `RoundedCornerShape(12.dp)` 或 `RoundedCornerShape(16.dp)` 改为 `RoundedCornerShape(TinaShapes.CardCorner)`
   - 或直接使用 `TinaCard` 组件

3. **对话框圆角统一**
   - 将所有对话框的 `shape = RoundedCornerShape(24.dp)` 改为 `shape = RoundedCornerShape(TinaShapes.DialogCorner)`
   - 或直接使用 `TinaAlertDialog` 组件

## 文件位置

- 统一组件库: `core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/`
- 主题定义: `core/designsystem/src/main/java/com/wuxianggujun/tinaide/ui/theme/TinaIDETheme.kt`
- XML 主题: `app/src/main/res/values/themes.xml`

## 颜色使用规范

### ❌ 避免硬编码颜色
```kotlin
// 错误示例
Color(0xFF4A90D9)  // 硬编码蓝色
Color.White        // 在深色模式下可能不适用
Color.Black        // 在浅色模式下可能不适用
```

### ✅ 使用语义化颜色
```kotlin
// 正确示例
MaterialTheme.colorScheme.primary           // 主色
MaterialTheme.colorScheme.onPrimary         // 主色上的文字
MaterialTheme.colorScheme.surface           // 表面色
MaterialTheme.colorScheme.onSurface         // 表面上的文字
MaterialTheme.colorScheme.background        // 背景色
MaterialTheme.colorScheme.onBackground      // 背景上的文字
MaterialTheme.colorScheme.onSurfaceVariant  // 次要文字
MaterialTheme.colorScheme.outline           // 边框
MaterialTheme.colorScheme.outlineVariant    // 次要边框
MaterialTheme.colorScheme.error             // 错误色
```

### 常用颜色映射

| 用途 | 语义化颜色 |
|------|-----------|
| 主要按钮背景 | `MaterialTheme.colorScheme.primary` |
| 主要按钮文字 | `MaterialTheme.colorScheme.onPrimary` |
| 链接文字 | `MaterialTheme.colorScheme.primary` |
| 标题文字 | `MaterialTheme.colorScheme.onSurface` |
| 副标题/描述 | `MaterialTheme.colorScheme.onSurfaceVariant` |
| 输入框边框 | `MaterialTheme.colorScheme.outline` |
| 卡片背景 | `MaterialTheme.colorScheme.surface` |
| 页面背景 | `MaterialTheme.colorScheme.background` |
| 分隔线 | `MaterialTheme.colorScheme.outlineVariant` |
| 错误提示 | `MaterialTheme.colorScheme.error` |

## 注意事项

1. **新代码**: 优先使用 `core/designsystem` 中的统一组件和设计 Token
2. **旧代码**: 逐步迁移到统一组件，或至少使用 `TinaShapes` 常量
3. **对话框按钮**: 对话框内的按钮通常使用 `TextButton`，这是 Material 3 的标准做法
4. **颜色**: 始终使用 `MaterialTheme.colorScheme` 中的语义化颜色，避免硬编码颜色值
5. **深色模式**: 使用语义化颜色可自动适配深色模式，无需额外处理
