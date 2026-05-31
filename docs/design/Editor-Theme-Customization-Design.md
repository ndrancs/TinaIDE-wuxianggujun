# TinaIDE 编辑器主题自定义功能设计文档

**创建日期**: 2025-12-13
**作者**: Claude Code
**版本**: 1.0
**状态**: 部分能力已落地（主题插件可用），主题编辑器 UI 方案待继续实现

> 状态更新（2026-03-02）：文档中的 Sora 相关类名/结构仅用于历史参考；当前实现以 TinaEditor 主题体系为准。

---

## 1. 概述

### 1.1 功能目标

为 TinaIDE 编辑器提供完整的主题自定义能力，允许用户：

1. **自定义语法高亮颜色** - 修改关键字、注释、字符串、函数名等代码元素的颜色
2. **自定义编辑器界面颜色** - 修改背景、行号、光标、选中区域等 UI 元素的颜色
3. **导入/导出主题** - 支持 JSON 格式的主题文件导入导出
4. **预置主题扩展** - 除现有的 Dark/Light/Gray 外，新增更多预置主题

### 1.2 现有架构分析

#### 当前主题系统

TinaIDE 当前实现使用 `core:editor-view` 内的 `EditorColorScheme` 管理编辑器 UI 与语法高亮颜色；内置主题由伴生对象提供，插件主题由 `PluginEditorThemeRegistry` 读取启用插件贡献的 `ThemeConfig` 后注入：

```
EditorColorScheme
├── builtinDark()   - 深色主题
├── builtinLight()  - 浅色主题
└── builtinGray()   - 灰色主题

PluginEditorThemeRegistry
└── ThemeConfig.colors -> EditorColorScheme.fromThemeConfig(...)
```

**关键文件**：
- [EditorColorScheme.kt](../../core/editor-view/src/main/java/com/wuxianggujun/tinaide/core/editorview/EditorColorScheme.kt)
- [PluginEditorThemeRegistry.kt](../../feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/theme/PluginEditorThemeRegistry.kt)
- [PluginModels.kt](../../core/plugin/src/main/java/com/wuxianggujun/tinaide/plugin/PluginModels.kt)

#### 语法高亮系统

TinaIDE 使用 **Tree-sitter** 进行语法分析，通过 `.scm` 查询文件定义高亮规则：

```
tree-sitter-queries/
├── cpp/
│   ├── highlights.scm  - 语法高亮规则
│   ├── brackets.scm    - 括号匹配
│   ├── blocks.scm      - 代码块
│   └── locals.scm      - 局部变量
├── c/
│   └── ...
└── cmake/
    └── ...
```

**高亮规则映射流程**：
```
Tree-sitter 解析 → 捕获节点 (capture) → TsTheme 映射 → EditorColorScheme 颜色
```

---

## 2. 颜色体系详解

### 2.1 EditorColorScheme 颜色 ID 完整列表

旧实现预定义了 72 个颜色 ID（1-72），建议自定义颜色从 256 开始以避免冲突。

#### 编辑器界面颜色 (UI Colors)

| 颜色 ID | 常量名 | 用途 | 默认值 (Dark) |
|---------|--------|------|---------------|
| 4 | `WHOLE_BACKGROUND` | 编辑器主背景 | `#121212` |
| 3 | `LINE_NUMBER_BACKGROUND` | 行号区背景 | `#1E1E1E` |
| 9 | `CURRENT_LINE` | 当前行高亮 | `#20FFFFFF` |
| 2 | `LINE_NUMBER` | 行号文字 | `#6E6E6E` |
| 45 | `LINE_NUMBER_CURRENT` | 当前行号 | `#03DAC6` |
| 1 | `LINE_DIVIDER` | 行分隔线 | `#2D2D2D` |
| 5 | `TEXT_NORMAL` | 普通文本 | `#E0E0E0` |
| 7 | `SELECTION_INSERT` | 光标颜色 | `#03DAC6` |
| 8 | `SELECTION_HANDLE` | 选择手柄 | `#03DAC6` |
| 6 | `SELECTED_TEXT_BACKGROUND` | 选中文本背景 | `#4003DAC6` |
| 10 | `UNDERLINE` | 下划线 | `#03DAC6` |
| 14 | `BLOCK_LINE` | 代码块线 | `#3D3D3D` |
| 15 | `BLOCK_LINE_CURRENT` | 当前代码块线 | `#03DAC6` |
| 11 | `SCROLL_BAR_THUMB` | 滚动条 | `#4D4D4D` |
| 12 | `SCROLL_BAR_THUMB_PRESSED` | 滚动条按下 | `#03DAC6` |

#### 语法高亮颜色 (Syntax Colors)

| 颜色 ID | 常量名 | 用途 | 默认值 (Dark) | Tree-sitter 捕获 |
|---------|--------|------|---------------|-----------------|
| 21 | `KEYWORD` | 关键字 | `#569CD6` | `@keyword` |
| 22 | `COMMENT` | 注释 | `#6A9955` | `@comment` |
| 24 | `LITERAL` | 字符串/数字 | `#CE9178` | `@string`, `@number` |
| 23 | `OPERATOR` | 运算符 | `#D4D4D4` | `@operator` |
| 26 | `IDENTIFIER_NAME` | 类型/类名 | `#4EC9B0` | `@type`, `@class` |
| 25 | `IDENTIFIER_VAR` | 变量 | `#9CDCFE` | `@variable` |
| 27 | `FUNCTION_NAME` | 函数名 | `#DCDCAA` | `@function` |
| 28 | `ANNOTATION` | 注解 | `#4EC9B0` | - |
| 32 | `HTML_TAG` | HTML 标签 | `#569CD6` | - |
| 33 | `ATTRIBUTE_NAME` | 属性名 | `#9CDCFE` | - |
| 34 | `ATTRIBUTE_VALUE` | 属性值 | `#CE9178` | - |

#### 自动补全窗口颜色

| 颜色 ID | 常量名 | 用途 |
|---------|--------|------|
| 19 | `COMPLETION_WND_BACKGROUND` | 补全窗口背景 |
| 42 | `COMPLETION_WND_TEXT_PRIMARY` | 补全主文本 |
| 43 | `COMPLETION_WND_TEXT_SECONDARY` | 补全次要文本 |
| 67 | `COMPLETION_WND_TEXT_MATCHED` | 匹配高亮 |
| 44 | `COMPLETION_WND_ITEM_CURRENT` | 当前选中项 |

#### 诊断与问题颜色

| 颜色 ID | 常量名 | 用途 |
|---------|--------|------|
| 35 | `PROBLEM_ERROR` | 错误下划线 |
| 36 | `PROBLEM_WARNING` | 警告下划线 |
| 37 | `PROBLEM_TYPO` | 拼写问题 |

#### 彩虹括号颜色 (自定义 ID 256-261)

| 颜色 ID | 用途 | 默认值 (Dark) |
|---------|------|---------------|
| 256 | 第 1 层括号 | `#FF79C6` 粉红 |
| 257 | 第 2 层括号 | `#BD93F9` 紫色 |
| 258 | 第 3 层括号 | `#8BE9FD` 青色 |
| 259 | 第 4 层括号 | `#50FA7B` 绿色 |
| 260 | 第 5 层括号 | `#FFB86C` 橙色 |
| 261 | 第 6 层括号 | `#F1FA8C` 黄色 |

### 2.2 Tree-sitter 语法捕获 (Captures)

Tree-sitter 通过 `.scm` 文件定义语法捕获规则，捕获名称决定了代码元素的分类：

#### C/C++ 主要捕获

| 捕获名称 | 对应代码元素 | 映射的颜色 ID |
|----------|-------------|---------------|
| `@keyword` | if, for, while, class, struct... | `KEYWORD` |
| `@comment` | // 注释, /* 注释 */ | `COMMENT` |
| `@string` | "字符串" | `LITERAL` |
| `@number` | 123, 0xFF, 3.14 | `LITERAL` |
| `@constant` | 常量 | `LITERAL` |
| `@constant.builtin` | true, false, nullptr | `LITERAL` |
| `@type` | 类型标识符 | `IDENTIFIER_NAME` |
| `@type.builtin` | int, char, void... | `IDENTIFIER_NAME` |
| `@namespace` | 命名空间 | `IDENTIFIER_NAME` |
| `@class` | 类名 | `IDENTIFIER_NAME` |
| `@struct` | 结构体名 | `IDENTIFIER_NAME` |
| `@variable` | 变量 | `IDENTIFIER_VAR` |
| `@variable.builtin` | this | `IDENTIFIER_VAR` |
| `@variable.parameter` | 函数参数 | `IDENTIFIER_VAR` |
| `@field` | 字段 | `IDENTIFIER_VAR` |
| `@property` | 属性 | `IDENTIFIER_VAR` |
| `@function` | 函数名 | `FUNCTION_NAME` |
| `@function.method` | 方法名 | `FUNCTION_NAME` |
| `@constructor` | 构造函数 | `FUNCTION_NAME` |
| `@destructor` | 析构函数 | `FUNCTION_NAME` |
| `@operator` | + - * / = ... | `OPERATOR` |
| `@punctuation.bracket` | ( ) [ ] { } | 普通/彩虹括号 |
| `@punctuation.delimiter` | , ; | `OPERATOR` |
| `@keyword.directive` | #include, #define | `KEYWORD` |
| `@constant.macro` | 宏定义名 | `IDENTIFIER_VAR` |
| `@function.macro` | 宏函数名 | `FUNCTION_NAME` |

---

## 3. 主题数据结构设计

### 3.1 主题配置 JSON 格式

```json
{
  "name": "My Custom Theme",
  "version": "1.0",
  "author": "用户名",
  "description": "自定义主题描述",
  "isDark": true,
  "baseTheme": "dark",

  "editorColors": {
    "background": "#1E1E1E",
    "lineNumberBackground": "#252526",
    "lineNumber": "#858585",
    "lineNumberCurrent": "#C6C6C6",
    "currentLine": "#2D2D30",
    "textNormal": "#D4D4D4",
    "cursor": "#AEAFAD",
    "selectionBackground": "#264F78",
    "scrollbarThumb": "#4E4E4E",
    "blockLine": "#404040",
    "blockLineCurrent": "#646464"
  },

  "syntaxColors": {
    "keyword": "#569CD6",
    "comment": "#6A9955",
    "string": "#CE9178",
    "number": "#B5CEA8",
    "operator": "#D4D4D4",
    "type": "#4EC9B0",
    "variable": "#9CDCFE",
    "function": "#DCDCAA",
    "constant": "#4FC1FF",
    "namespace": "#4EC9B0",
    "class": "#4EC9B0",
    "parameter": "#9CDCFE",
    "property": "#9CDCFE",
    "macro": "#BD63C5",
    "preprocessor": "#C586C0"
  },

  "completionColors": {
    "background": "#252526",
    "textPrimary": "#D4D4D4",
    "textSecondary": "#808080",
    "textMatched": "#18A0FB",
    "itemCurrent": "#094771"
  },

  "diagnosticColors": {
    "error": "#F44747",
    "warning": "#CCA700",
    "info": "#3794FF",
    "hint": "#6A9955"
  },

  "rainbowBrackets": [
    "#FFD700",
    "#DA70D6",
    "#87CEEB",
    "#98FB98",
    "#FFA500",
    "#FF6B6B"
  ],

  "textStyles": {
    "keyword": { "bold": true },
    "comment": { "italic": true },
    "function": { "bold": false },
    "deprecatedStrikethrough": true
  }
}
```

### 3.2 Kotlin 数据类定义

```kotlin
/**
 * 编辑器自定义主题配置
 */
@Serializable
data class CustomThemeConfig(
    val name: String,
    val version: String = "1.0",
    val author: String = "",
    val description: String = "",
    val isDark: Boolean = true,
    val baseTheme: String = "dark", // "dark", "light", "gray"

    val editorColors: EditorColors = EditorColors(),
    val syntaxColors: SyntaxColors = SyntaxColors(),
    val completionColors: CompletionColors = CompletionColors(),
    val diagnosticColors: DiagnosticColors = DiagnosticColors(),
    val rainbowBrackets: List<String> = defaultRainbowBrackets,
    val textStyles: TextStyles = TextStyles()
) {
    companion object {
        val defaultRainbowBrackets = listOf(
            "#FF79C6", "#BD93F9", "#8BE9FD",
            "#50FA7B", "#FFB86C", "#F1FA8C"
        )
    }
}

@Serializable
data class EditorColors(
    val background: String = "#121212",
    val lineNumberBackground: String = "#1E1E1E",
    val lineNumber: String = "#6E6E6E",
    val lineNumberCurrent: String = "#03DAC6",
    val currentLine: String = "#20FFFFFF",
    val textNormal: String = "#E0E0E0",
    val cursor: String = "#03DAC6",
    val selectionBackground: String = "#4003DAC6",
    val scrollbarThumb: String = "#4D4D4D",
    val blockLine: String = "#3D3D3D",
    val blockLineCurrent: String = "#03DAC6"
)

@Serializable
data class SyntaxColors(
    val keyword: String = "#569CD6",
    val comment: String = "#6A9955",
    val string: String = "#CE9178",
    val number: String = "#CE9178",
    val operator: String = "#D4D4D4",
    val type: String = "#4EC9B0",
    val variable: String = "#9CDCFE",
    val function: String = "#DCDCAA",
    val constant: String = "#CE9178",
    val namespace: String = "#4EC9B0",
    val className: String = "#4EC9B0",
    val parameter: String = "#9CDCFE",
    val property: String = "#9CDCFE",
    val macro: String = "#9CDCFE",
    val preprocessor: String = "#569CD6"
)

@Serializable
data class CompletionColors(
    val background: String = "#252526",
    val textPrimary: String = "#E0E0E0",
    val textSecondary: String = "#9E9E9E",
    val textMatched: String = "#03DAC6",
    val itemCurrent: String = "#094771"
)

@Serializable
data class DiagnosticColors(
    val error: String = "#F44336",
    val warning: String = "#FFC107",
    val info: String = "#2196F3",
    val hint: String = "#6A9955"
)

@Serializable
data class TextStyles(
    val keywordBold: Boolean = true,
    val commentItalic: Boolean = true,
    val functionBold: Boolean = false,
    val deprecatedStrikethrough: Boolean = true
)
```

---

## 4. 功能实现设计

### 4.1 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        用户界面层 (UI Layer)                      │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────┐ │
│  │  主题选择器       │  │  颜色编辑器       │  │  主题预览器     │ │
│  │  ThemeSelector   │  │  ColorEditor     │  │  ThemePreview  │ │
│  └────────┬─────────┘  └────────┬─────────┘  └───────┬────────┘ │
│           │                     │                     │          │
├───────────┼─────────────────────┼─────────────────────┼──────────┤
│           ▼                     ▼                     ▼          │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              主题管理器 (ThemeManager)                    │   │
│  │  - loadTheme()      - saveTheme()     - exportTheme()    │   │
│  │  - importTheme()    - deleteTheme()   - applyTheme()     │   │
│  └────────────────────────────┬─────────────────────────────┘   │
│                               │                                  │
├───────────────────────────────┼──────────────────────────────────┤
│                               ▼                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              主题解析器 (ThemeParser)                     │   │
│  │  - parseJson()      - toColorScheme()  - validate()      │   │
│  └────────────────────────────┬─────────────────────────────┘   │
│                               │                                  │
├───────────────────────────────┼──────────────────────────────────┤
│                               ▼                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │         CustomEditorColorScheme (继承 EditorColorScheme)  │   │
│  │  - 基于 CustomThemeConfig 动态生成                        │   │
│  │  - 实时响应颜色更改                                        │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│                        存储层 (Storage Layer)                     │
├──────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────┐ │
│  │  内置主题 (Assets)│  │  用户主题 (Files)│  │  当前主题 (Prefs)│ │
│  │  /assets/themes/ │  │  /themes/*.json  │  │  DataStore     │ │
│  └──────────────────┘  └──────────────────┘  └────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### 4.2 核心类设计

#### ThemeManager - 主题管理器

```kotlin
/**
 * 编辑器主题管理器
 * 负责主题的加载、保存、应用和管理
 */
class ThemeManager private constructor(
    private val context: Context
) {
    companion object {
        private const val THEMES_DIR = "themes"
        private const val BUILTIN_THEMES_DIR = "builtin_themes"
        private const val CURRENT_THEME_KEY = "current_theme"

        @Volatile
        private var instance: ThemeManager? = null

        fun getInstance(context: Context): ThemeManager {
            return instance ?: synchronized(this) {
                instance ?: ThemeManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val themesDir = File(context.filesDir, THEMES_DIR)
    private val _currentTheme = MutableStateFlow<CustomThemeConfig?>(null)
    val currentTheme: StateFlow<CustomThemeConfig?> = _currentTheme.asStateFlow()

    private val _availableThemes = MutableStateFlow<List<ThemeInfo>>(emptyList())
    val availableThemes: StateFlow<List<ThemeInfo>> = _availableThemes.asStateFlow()

    init {
        ensureThemesDirExists()
        loadAvailableThemes()
    }

    /** 获取所有可用主题（内置 + 用户自定义） */
    fun loadAvailableThemes(): List<ThemeInfo>

    /** 加载指定主题 */
    suspend fun loadTheme(themeId: String): CustomThemeConfig?

    /** 保存用户自定义主题 */
    suspend fun saveTheme(theme: CustomThemeConfig): Result<String>

    /** 删除用户自定义主题 */
    suspend fun deleteTheme(themeId: String): Result<Unit>

    /** 导出主题为 JSON 文件 */
    suspend fun exportTheme(themeId: String, outputUri: Uri): Result<Unit>

    /** 从 JSON 文件导入主题 */
    suspend fun importTheme(inputUri: Uri): Result<CustomThemeConfig>

    /** 应用主题到编辑器 */
    fun applyTheme(theme: CustomThemeConfig): EditorColorScheme

    /** 创建新主题（基于现有主题） */
    fun createThemeFrom(baseTheme: String, newName: String): CustomThemeConfig
}

data class ThemeInfo(
    val id: String,
    val name: String,
    val author: String,
    val isDark: Boolean,
    val isBuiltin: Boolean,
    val previewColors: PreviewColors
)

data class PreviewColors(
    val background: Int,
    val keyword: Int,
    val string: Int,
    val comment: Int
)
```

#### CustomEditorColorScheme - 自定义颜色方案

```kotlin
/**
 * 基于 CustomThemeConfig 的动态颜色方案
 */
class CustomEditorColorScheme(
    private val config: CustomThemeConfig
) : EditorColorScheme(config.isDark) {

    override fun applyDefault() {
        super.applyDefault()
        applyCustomColors()
    }

    private fun applyCustomColors() {
        // 编辑器界面颜色
        with(config.editorColors) {
            setColor(WHOLE_BACKGROUND, parseColor(background))
            setColor(LINE_NUMBER_BACKGROUND, parseColor(lineNumberBackground))
            setColor(LINE_NUMBER, parseColor(lineNumber))
            setColor(LINE_NUMBER_CURRENT, parseColor(lineNumberCurrent))
            setColor(CURRENT_LINE, parseColor(currentLine))
            setColor(TEXT_NORMAL, parseColor(textNormal))
            setColor(SELECTION_INSERT, parseColor(cursor))
            setColor(SELECTION_HANDLE, parseColor(cursor))
            setColor(SELECTED_TEXT_BACKGROUND, parseColor(selectionBackground))
            setColor(SCROLL_BAR_THUMB, parseColor(scrollbarThumb))
            setColor(BLOCK_LINE, parseColor(blockLine))
            setColor(BLOCK_LINE_CURRENT, parseColor(blockLineCurrent))
        }

        // 语法高亮颜色
        with(config.syntaxColors) {
            setColor(KEYWORD, parseColor(keyword))
            setColor(COMMENT, parseColor(comment))
            setColor(LITERAL, parseColor(string))
            setColor(OPERATOR, parseColor(operator))
            setColor(IDENTIFIER_NAME, parseColor(type))
            setColor(IDENTIFIER_VAR, parseColor(variable))
            setColor(FUNCTION_NAME, parseColor(function))
            setColor(ANNOTATION, parseColor(type))
        }

        // 补全窗口颜色
        with(config.completionColors) {
            setColor(COMPLETION_WND_BACKGROUND, parseColor(background))
            setColor(COMPLETION_WND_TEXT_PRIMARY, parseColor(textPrimary))
            setColor(COMPLETION_WND_TEXT_SECONDARY, parseColor(textSecondary))
            setColor(COMPLETION_WND_TEXT_MATCHED, parseColor(textMatched))
            setColor(COMPLETION_WND_ITEM_CURRENT, parseColor(itemCurrent))
        }

        // 诊断颜色
        with(config.diagnosticColors) {
            setColor(PROBLEM_ERROR, parseColor(error))
            setColor(PROBLEM_WARNING, parseColor(warning))
            setColor(PROBLEM_TYPO, parseColor(hint))
        }

        // 彩虹括号
        config.rainbowBrackets.forEachIndexed { index, color ->
            if (index < 6) {
                setColor(256 + index, parseColor(color))
            }
        }
    }

    /** 动态更新单个颜色 */
    fun updateColor(colorKey: String, newColor: Int) {
        val colorId = colorKeyToId(colorKey)
        if (colorId != -1) {
            setColor(colorId, newColor)
        }
    }

    private fun parseColor(colorString: String): Int {
        return try {
            Color.parseColor(colorString)
        } catch (e: IllegalArgumentException) {
            Color.MAGENTA // 解析失败时使用醒目颜色提示
        }
    }

    private fun colorKeyToId(key: String): Int {
        return when (key) {
            "background" -> WHOLE_BACKGROUND
            "keyword" -> KEYWORD
            "comment" -> COMMENT
            "string" -> LITERAL
            // ... 其他映射
            else -> -1
        }
    }
}
```

### 4.3 UI 设计

#### 主题设置入口

在 **设置 → 外观 → 编辑器主题** 中添加自定义选项：

```
外观设置
├── 应用主题: 跟随系统 / 浅色 / 深色 / 灰色
├── 编辑器主题
│   ├── [选择主题]
│   │   ├── TinaIDE Dark (内置)
│   │   ├── TinaIDE Light (内置)
│   │   ├── TinaIDE Gray (内置)
│   │   ├── VS Code Dark+ (内置)
│   │   ├── Monokai (内置)
│   │   ├── 我的主题 1 (自定义)
│   │   └── + 创建新主题
│   ├── [自定义当前主题] → 打开主题编辑器
│   ├── [导入主题] → 文件选择器
│   └── [导出主题] → 保存为 JSON
```

#### 主题编辑器界面 (Compose UI)

```kotlin
@Composable
fun ThemeEditorScreen(
    theme: CustomThemeConfig,
    onThemeChanged: (CustomThemeConfig) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    var editingTheme by remember { mutableStateOf(theme) }
    var selectedCategory by remember { mutableStateOf(ColorCategory.EDITOR) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑主题: ${theme.name}") },
                actions = {
                    TextButton(onClick = onCancel) { Text("取消") }
                    TextButton(onClick = onSave) { Text("保存") }
                }
            )
        }
    ) { padding ->
        Row(modifier = Modifier.padding(padding)) {
            // 左侧：颜色分类选择
            CategorySelector(
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it }
            )

            // 中间：颜色列表
            ColorList(
                category = selectedCategory,
                theme = editingTheme,
                onColorClick = { colorKey ->
                    // 打开颜色选择器
                }
            )

            // 右侧：代码预览
            CodePreview(
                theme = editingTheme,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

enum class ColorCategory(val displayName: String) {
    EDITOR("编辑器"),
    SYNTAX("语法高亮"),
    COMPLETION("自动补全"),
    DIAGNOSTIC("诊断"),
    BRACKETS("彩虹括号")
}
```

#### 颜色选择器

```kotlin
@Composable
fun ColorPickerDialog(
    initialColor: Int,
    colorName: String,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var currentColor by remember { mutableStateOf(initialColor) }
    var hexInput by remember { mutableStateOf(colorToHex(initialColor)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择颜色: $colorName") },
        text = {
            Column {
                // 颜色预览
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(Color(currentColor))
                )

                Spacer(modifier = Modifier.height(16.dp))

                // RGB 滑块
                ColorSlider(label = "R", value = Color(currentColor).red) { ... }
                ColorSlider(label = "G", value = Color(currentColor).green) { ... }
                ColorSlider(label = "B", value = Color(currentColor).blue) { ... }
                ColorSlider(label = "A", value = Color(currentColor).alpha) { ... }

                Spacer(modifier = Modifier.height(16.dp))

                // HEX 输入
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hex ->
                        hexInput = hex
                        parseHexColor(hex)?.let { currentColor = it }
                    },
                    label = { Text("HEX") },
                    placeholder = { Text("#RRGGBB") }
                )

                // 预设颜色
                Text("预设颜色", style = MaterialTheme.typography.labelMedium)
                FlowRow {
                    presetColors.forEach { preset ->
                        ColorPresetChip(
                            color = preset,
                            selected = currentColor == preset,
                            onClick = { currentColor = preset }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(currentColor) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
```

#### 代码预览组件

```kotlin
@Composable
fun CodePreview(
    theme: CustomThemeConfig,
    modifier: Modifier = Modifier
) {
    val previewCode = """
        #include <iostream>

        // 计算阶乘
        int factorial(int n) {
            if (n <= 1) return 1;
            return n * factorial(n - 1);
        }

        class Calculator {
        public:
            double add(double a, double b) {
                return a + b;
            }
        };

        int main() {
            std::cout << "Hello, TinaIDE!" << std::endl;
            int result = factorial(5);
            return 0;
        }
    """.trimIndent()

    // 使用简化的语法高亮渲染预览
    SyntaxHighlightedText(
        code = previewCode,
        theme = theme,
        modifier = modifier
            .background(Color(theme.editorColors.background.toColorInt()))
            .padding(16.dp)
    )
}
```

---

## 5. 内置主题扩展

### 5.1 新增预置主题

除现有的 Dark/Light/Gray 外，新增以下流行主题：

| 主题名称 | 风格 | 灵感来源 |
|----------|------|----------|
| VS Code Dark+ | 深色 | Visual Studio Code 默认深色 |
| Monokai | 深色 | Sublime Text 经典主题 |
| Dracula | 深色 | 流行的跨编辑器主题 |
| One Dark | 深色 | Atom 编辑器默认主题 |
| Solarized Dark | 深色 | Solarized 配色方案 |
| Solarized Light | 浅色 | Solarized 配色方案 |
| GitHub Light | 浅色 | GitHub 代码显示风格 |
| Nord | 深色 | 北欧风格配色 |

### 5.2 主题配置文件示例

`assets/builtin_themes/monokai.json`:

```json
{
  "name": "Monokai",
  "version": "1.0",
  "author": "TinaIDE",
  "description": "经典 Monokai 配色方案",
  "isDark": true,
  "baseTheme": "dark",

  "editorColors": {
    "background": "#272822",
    "lineNumberBackground": "#272822",
    "lineNumber": "#90908A",
    "lineNumberCurrent": "#F8F8F2",
    "currentLine": "#3E3D32",
    "textNormal": "#F8F8F2",
    "cursor": "#F8F8F0",
    "selectionBackground": "#49483E",
    "scrollbarThumb": "#49483E",
    "blockLine": "#3E3D32",
    "blockLineCurrent": "#75715E"
  },

  "syntaxColors": {
    "keyword": "#F92672",
    "comment": "#75715E",
    "string": "#E6DB74",
    "number": "#AE81FF",
    "operator": "#F8F8F2",
    "type": "#66D9EF",
    "variable": "#F8F8F2",
    "function": "#A6E22E",
    "constant": "#AE81FF",
    "namespace": "#66D9EF",
    "className": "#A6E22E",
    "parameter": "#FD971F",
    "property": "#F8F8F2",
    "macro": "#AE81FF",
    "preprocessor": "#F92672"
  },

  "rainbowBrackets": [
    "#F92672",
    "#A6E22E",
    "#66D9EF",
    "#FD971F",
    "#AE81FF",
    "#E6DB74"
  ]
}
```

---

## 6. 存储与持久化

### 6.1 文件结构

```
/data/data/com.wuxianggujun.tinaide/
├── files/
│   └── themes/
│       ├── user_theme_1.json
│       ├── user_theme_2.json
│       └── ...
└── shared_prefs/
    └── theme_preferences.xml  (当前选中主题 ID)

/assets/
└── builtin_themes/
    ├── tina_dark.json
    ├── tina_light.json
    ├── tina_gray.json
    ├── monokai.json
    ├── dracula.json
    └── ...
```

### 6.2 DataStore 配置

```kotlin
val Context.themeDataStore by preferencesDataStore(name = "theme_settings")

object ThemePreferences {
    val CURRENT_THEME_ID = stringPreferencesKey("current_theme_id")
    val CUSTOM_THEME_IDS = stringSetPreferencesKey("custom_theme_ids")
    val FOLLOW_APP_THEME = booleanPreferencesKey("follow_app_theme")
}
```

---

## 7. 实现路线图

### Phase 1: 基础架构 (预计 3-4 天)

- [ ] 定义 `CustomThemeConfig` 数据类
- [ ] 实现 `ThemeManager` 核心逻辑
- [ ] 实现 `CustomEditorColorScheme`
- [ ] 主题 JSON 解析和验证

### Phase 2: 内置主题 (预计 2 天)

- [ ] 创建 Monokai 主题配置
- [ ] 创建 Dracula 主题配置
- [ ] 创建 VS Code Dark+ 主题配置
- [ ] 创建 One Dark 主题配置
- [ ] 主题加载和切换逻辑

### Phase 3: 用户界面 (预计 4-5 天)

- [ ] 主题选择器 UI (Compose)
- [ ] 颜色选择器对话框
- [ ] 主题编辑器界面
- [ ] 代码预览组件
- [ ] 设置页面集成

### Phase 4: 导入导出 (预计 2 天)

- [ ] 主题导出为 JSON
- [ ] 从 JSON 导入主题
- [ ] 主题文件验证
- [ ] 错误处理和用户提示

### Phase 5: 测试与优化 (预计 2 天)

- [ ] 主题切换性能优化
- [ ] 颜色对比度检查
- [ ] 边界情况测试
- [ ] 用户体验优化

---

## 8. 注意事项

### 8.1 性能考虑

1. **主题切换延迟** - 主题切换时需要重新渲染编辑器，应在后台线程解析 JSON
2. **内存占用** - 避免同时加载所有主题配置，使用懒加载
3. **颜色解析缓存** - 缓存解析后的颜色值，避免重复解析

### 8.2 兼容性考虑

1. **版本升级** - 主题格式应支持版本迁移
2. **缺失颜色** - 新版本新增颜色时，旧主题应有默认值回退
3. **无效颜色** - 颜色解析失败时应使用安全的默认颜色

### 8.3 可访问性考虑

1. **对比度检查** - 提供对比度检查工具，确保文本可读性
2. **色盲友好** - 预置主题应考虑色盲用户
3. **高对比度模式** - 可选的高对比度主题

---

## 9. 参考资料

- [Sora Editor - EditorColorScheme](https://github.com/Rosemoe/sora-editor)（历史参考）
- [Tree-sitter 语法高亮](https://tree-sitter.github.io/tree-sitter/syntax-highlighting)
- [VS Code 主题开发](https://code.visualstudio.com/api/extension-guides/color-theme)
- [Material Design 3 色彩系统](https://m3.material.io/styles/color/overview)
