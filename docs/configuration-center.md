# Cosmic IDE 配置中心实现解析

> 项目绝对路径：`c:\\Users\\wuxianggujun\\CodeSpace\\AndroidStudioProjects\\Cosmic-IDE`
>
> 本文聚焦 Cosmic IDE 的“配置中心”（Settings）实现，包括：
> - 配置数据是如何存储与初始化的
> - 配置界面是如何搭建的（布局结构 + 分组）
> - 各类配置项与业务逻辑之间的关系

---

## 总体结构概览

与“配置”强相关的核心文件主要分布在：

- 配置存储层（SharedPreferences 封装）
  - `common/src/main/java/org/cosmicide/common/Prefs.kt`
  - `app/src/main/kotlin/org/cosmicide/startup/PreferencesInitializer.kt`
- 配置 UI（设置界面）
  - `app/src/main/kotlin/org/cosmicide/fragment/SettingsFragment.kt`
  - `app/src/main/kotlin/org/cosmicide/fragment/settings/*.kt`
  - `app/src/main/kotlin/org/cosmicide/util/PreferenceKeys.kt`
  - `app/src/main/res/layout/fragment_settings.xml`
  - `app/src/main/res/values/arrays.xml`
- 配置的主要使用方（部分示例）
  - `app/src/main/kotlin/org/cosmicide/App.kt`
  - `app/src/main/kotlin/org/cosmicide/editor/IdeEditor.kt`
  - `app/src/main/kotlin/org/cosmicide/fragment/EditorFragment.kt`
  - `util/src/main/java/org/cosmicide/rewrite/plugin/api/PluginLoader.kt`

整体思路可以概括为：

1. 用 `Prefs` 统一访问 SharedPreferences 中的配置，并提供类型安全的属性接口；
2. 用 `modernpreferences` 第三方库构建 RecyclerView 驱动的设置界面；
3. 用一组 `SettingsProvider` 子类将设置项按“外观 / 编辑器 / 编译器 / 格式化 / 插件 / Git / Gemini / 关于”等模块化组织；
4. 在应用启动和编辑器逻辑里，通过 `Prefs.xxx` 实时读取配置，影响主题、编辑器行为、编译参数等。
---

## 一、配置存储层：Prefs + Initializer

### 1. Prefs：统一的配置访问入口

文件：`common/src/main/java/org/cosmicide/common/Prefs.kt`

`Prefs` 是一个 Kotlin `object`，本质是对 `SharedPreferences` 的一次封装：

- 初始化：
  - `fun init(context: Context)` 使用 `PreferenceManager.getDefaultSharedPreferences(context)` 获取默认偏好存储；
  - 由 `PreferencesInitializer` 在应用启动时自动调用（见后文）。
- 状态：
  - `val isInitialized: Boolean` 通过 `Prefs::prefs.isInitialized` 检查是否已初始化。
- 常用配置属性示例：
  - 外观与主题：
    - `val appTheme: String` → key: `"app_theme"`，默认 `"auto"`；
  - 编辑器行为：
    - `val useLigatures: Boolean` → key: `"font_ligatures"`；
    - `val wordWrap: Boolean` → key: `"word_wrap"`；
    - `val scrollbarEnabled: Boolean` → key: `"scrollbar"`；
    - `val hardwareAcceleration: Boolean` → key: `"hardware_acceleration"`；
    - `val nonPrintableCharacters: Boolean` → key: `"non_printable_characters"`；
    - `val lineNumbers: Boolean` → key: `"line_numbers"`；
    - `val useSpaces: Boolean` → key: `"use_spaces"`；
    - `val tabSize: Int` → key: `"tab_size"`；
    - `val bracketPairAutocomplete: Boolean` → key: `"bracket_pair_autocomplete"`；
    - `val quickDelete: Boolean` → key: `"quick_delete"`；
    - `val stickyScroll: Boolean` → key: `"sticky_scroll"`；
    - `val disableSymbolsView: Boolean` → key: `"disable_symbols_view"`；
  - 编译相关：
    - `val useFastJarFs: Boolean` → key: `"use_fastjarfs"`；
    - `val javacFlags: String` → key: `"javac_flags"`；
    - `val compilerJavaVersion: Int` → key: `"java_version"`（字符串+`Integer.parseInt`）；
    - `val kotlinVersion: String` → key: `"kotlin_version"`；
  - 格式化相关：
    - `val ktfmtStyle: String` → key: `"ktfmt_style"`；
    - `val googleJavaFormatOptions: Set<String>?` → key: `"google_java_formatter_options"`；
    - `val googleJavaFormatStyle: String` → key: `"google_java_formatter_style"`；
  - Git：
    - `val gitUsername: String` → key: `"git_username"`；
    - `val gitEmail: String` → key: `"git_email"`；
    - `val gitApiKey: String` → key: `"git_api_key"`；
  - Gemini（AI）相关：
    - `val geminiApiKey: String` → key: `"gemini_api_key"`；
    - `val geminiModel: String` → key: `"gemini_model"`；
    - `val temperature: Float` → key: `"temperature"`，带 `toFloatOrNull + coerceIn(0f, 1f)`；
    - `val topP: Float` → key: `"top_p"`，同样 `toFloatOrNull + coerceIn(0f, 1f)`；
    - `val topK: Float` → key: `"top_k"`，从 `Int` 读取后 `coerceIn(1, 100)` 再转为 `Float`；
    - `val maxTokens: Int` → key: `"max_tokens"`，`coerceIn(60, 2048)`；
  - 其他：
    - `val analyticsEnabled: Boolean` → key: `"analytics_preference"`；
    - `val experimentsEnabled: Boolean` → key: `"experiments_enabled"`；
    - `val editorFont: String` → key: `"editor_font"`；
    - `val repositories: String` → key: `"repos"`，带默认仓库列表；
    - `val pluginRepository: String` → key: `"plugin_repository"`。

可以看到，`Prefs` 的职责非常单一：

- 对外提供 **类型安全、带默认值、带边界检查** 的配置访问接口；
- 对内隐藏 SharedPreferences 的 key 字符串与解析细节。

### 2. PreferencesInitializer：启动时初始化 Prefs

文件：`app/src/main/kotlin/org/cosmicide/startup/PreferencesInitializer.kt`

```kotlin
class PreferencesInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        FileUtil.init(context.getExternalFilesDir(null)!!)
        Prefs.init(context.applicationContext)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
```

- 使用 AndroidX Startup 的 `Initializer` 机制：
  - 随应用启动自动执行，不需要手动在 `Application.onCreate` 中调用；
  - 先初始化 `FileUtil`（数据目录），再初始化 `Prefs`。
- 依赖关系通过 `MainInitializer` 串起来：
  - `MainInitializer` 的 `dependencies()` 返回：`DebugInitializer`, `PreferencesInitializer`；
  - 确保调试环境与偏好系统在应用主逻辑启动前就就绪。

这一层实现体现了：

- **SRP**：`Prefs` 只管读配置，不管何时初始化；初始化逻辑交给 `PreferencesInitializer`；
- **KISS**：对业务代码来说，只需要关心“用之前 Prefs 已经可以用了”，不需要操心初始化顺序。
---

## 二、配置 UI 框架：SettingsFragment + modernpreferences

### 1. settings 布局：RecyclerView 驱动的设置页

布局文件：`app/src/main/res/layout/fragment_settings.xml`

结构非常简洁：

- 顶部：`AppBarLayout + CollapsingToolbarLayout + MaterialToolbar`
  - Toolbar 标题 `@string/action_settings`，左侧是返回箭头图标；
- 内容区：一个 `RecyclerView`（id: `preferences_view`）
  - 使用 `LinearLayoutManager`；
  - 作为 `modernpreferences` 的 `PreferencesAdapter` 的宿主，负责展示整个设置树。

可以理解为：

- UI 布局只负责“放一个 Toolbar + 一个 RecyclerView”；
- 真正的“设置项 / 分组 / 子页面”全部由 Kotlin 代码通过 modernpreferences 的 DSL 构建。

### 2. SettingsFragment：总入口 + 顶层分组

文件：`app/src/main/kotlin/org/cosmicide/fragment/SettingsFragment.kt`

`SettingsFragment` 继承自 `BaseBindingFragment<FragmentSettingsBinding>`，负责：

- 初始化各类 `SettingsProvider`：
  - `AppearanceSettings`（外观）
  - `EditorSettings`（编辑器）
  - `CompilerSettings`（编译器）
  - `FormatterSettings`（格式化）
  - `PluginSettingsProvider`（插件）
  - `GitSettings`（Git）
  - `GeminiSettings`（AI）
  - `AboutSettings`（关于）
- 使用 modernpreferences 的 `screen { ... }` DSL 构建顶层 `PreferenceScreen`：

```kotlin
val screen = screen(requireContext()) {
    subScreen {
        collapseIcon = true
        title = "Appearance"
        summary = "Customize the appearance as you see fit"
        appearanceSettings.provideSettings(this)
    }
    subScreen {
        collapseIcon = true
        title = "Code editor"
        summary = "Customize editor settings"
        editorSettings.provideSettings(this)
    }
    // ... Compiler / Formatter / Plugins / Git / Gemini / About
}
```

- 每个 `subScreen` 都对应一个二级设置页面；
- 每个具体页面的内容由对应的 `SettingsProvider.provideSettings(this)` 填充。

然后：

- 用 `PreferencesAdapter(screen)` 适配器绑定到 `binding.preferencesView`；
- 处理 Toolbar 返回和系统返回键：
  - 若当前在某个子页面，则调用 `preferencesAdapter.goBack()` 回到上一级；
  - 若已经在顶层页面，则执行 `parentFragmentManager.popBackStack()` 退出设置。
  - 特殊逻辑：如果当前在 Gemini 页面并返回，会调用 `ChatProvider.regenerateModel()` 以重新加载模型配置。

### 3. SettingsProvider 接口：统一各设置页面的构建方式

文件：`app/src/main/kotlin/org/cosmicide/fragment/settings/SettingsProvider.kt`

```kotlin
interface SettingsProvider {
    fun provideSettings(builder: PreferenceScreen.Builder)
}
```

所有设置页类（AppearanceSettings / EditorSettings / CompilerSettings 等）都实现这个接口：

- 这样 `SettingsFragment` 只需要关心“有哪些 SettingsProvider”，不关心每个页面里面具体放了什么控件；
- 便于扩展：新增一个设置模块，写一个类实现 `SettingsProvider` 即可。

### 4. PreferenceKeys：配置 key 的集中定义

文件：`app/src/main/kotlin/org/cosmicide/util/PreferenceKeys.kt`

这里集中定义了所有在 UI 层使用的偏好 key：

- 外观：
  - `APP_THEME = "app_theme"`
- 编译器：
  - `COMPILER_USE_FJFS = "use_fast_jar_file_system"`
  - `COMPILER_USE_K2 = "use_k2"`
  - `COMPILER_USE_SSVM = "use_ssvm"`
  - `COMPILER_JAVA_VERSIONS = "java_versions"`
  - `COMPILER_JAVAC_FLAGS = "javac_flags"`
  - `COMPILER_KOTLIN_VERSION = "kotlin_version"`
- 编辑器：
  - `EDITOR_FONT_SIZE = "font_size"`
  - `EDITOR_TAB_SIZE = "tab_size"`
  - `EDITOR_USE_SPACES = "use_spaces"`
  - `EDITOR_LIGATURES_ENABLE = "ligatures_enable"`
  - `EDITOR_WORDWRAP_ENABLE = "wordwrap_enable"`
  - `EDITOR_SCROLLBAR_SHOW = "scrollbar_show"`
  - `EDITOR_HW_ENABLE = "hardware_acceleration_enable"`
  - `EDITOR_NON_PRINTABLE_SYMBOLS_SHOW = "non_printable_symbols_show"`
  - `EDITOR_LINE_NUMBERS_SHOW = "line_numbers_show"`
  - `EDITOR_DOUBLE_CLICK_CLOSE = "double_click_close"`
  - `EDITOR_EXP_JAVA_COMPLETION = "experimental_java_completion"`
  - `KOTLIN_REALTIME_ERRORS = "kotlin_realtime_errors"`
  - `EDITOR_FONT = "editor_font"`
  - `BRACKET_PAIR_AUTOCOMPLETE = "bracket_pair_autocomplete"`
  - `QUICK_DELETE = "quick_delete"`
  - `STICKY_SCROLL = "sticky_scroll"`
  - `DISABLE_SYMBOLS_VIEW = "disable_symbols_view"`
- 格式化：
  - `FORMATTER_KTFMT_STYLE = "ktfmt_style"`
  - `FORMATTER_GJF_STYLE = "google_java_formatter_style"`
  - `FORMATTER_GJF_OPTIONS = "google_java_formatter_options"`
- Git：
  - `GIT_USERNAME`, `GIT_EMAIL`, `GIT_API_KEY`
- 插件：
  - `AVAILABLE_PLUGINS`, `INSTALLED_PLUGINS`, `PLUGIN_REPOSITORY`, `PLUGIN_SETTINGS`
- Gemini：
  - `GEMINI_API_KEY`, `GEMINI_MODEL`, `TEMPERATURE`, `TOP_P`, `TOP_K`, `CANDIDATE_COUNT`, `MAX_TOKENS`

这些 key 与 `Prefs` 中读取的 key 基本保持一致（个别字段有历史命名差异），共同构成了“配置中心”的命名规范。
---

## 三、各设置分组的具体内容

下面简要梳理每个 SettingsProvider 对应的配置项和效果，方便你对照 UI 理解。

### 1. 外观设置：AppearanceSettings

文件：`app/src/main/kotlin/org/cosmicide/fragment/settings/AppearanceSettings.kt`

- 使用 `singleChoice(PreferenceKeys.APP_THEME, themeItems)`：
  - 选项来源：`R.array.app_theme_entries` / `R.array.app_theme_entry_values`：
    - entries：自动 / 亮色 / 暗色；
    - entry_values：`"auto"`, `"light"`, `"dark"`；
  - 变更时调用：

```kotlin
defaultOnSelectionChange { newValue ->
    val theme = getTheme(newValue)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(activity, UiModeManager::class.java)?.setApplicationNightMode(theme)
    } else {
        AppCompatDelegate.setDefaultNightMode(...)
    }
}
```

- 与 `App` 中逻辑呼应：
  - `App.onCreate()` 会读取 `Prefs.appTheme`，通过 `getTheme(Prefs.appTheme)` 设置应用夜间模式；
  - `App.applyThemeBasedOnConfiguration()` 会在配置变化（如系统夜间模式切换）时调整代码编辑器主题（TextMate 主题）。

总结：外观设置通过 `APP_THEME` 这一 key 将 UI 选择与 `Prefs.appTheme` 打通，再由 `App` 统一决定夜间模式和编辑器主题。

### 2. 编辑器设置：EditorSettings

文件：`app/src/main/kotlin/org/cosmicide/fragment/settings/EditorSettings.kt`

主要是对编辑器行为的各种控制：

- 字号与缩进：
  - `seekBar(PreferenceKeys.EDITOR_FONT_SIZE)` → 对应 `Prefs.editorFontSize`；
  - `seekBar(PreferenceKeys.EDITOR_TAB_SIZE)` → 对应 `Prefs.tabSize`；
- 代码辅助：
  - `switch(PreferenceKeys.EDITOR_EXP_JAVA_COMPLETION)` → 是否启用实验性的 Java 补全；
  - `switch(PreferenceKeys.KOTLIN_REALTIME_ERRORS)` → Kotlin 实时错误检查（会有性能影响）；
- 字体与布局：
  - `editText(PreferenceKeys.EDITOR_FONT)` → 对应 `Prefs.editorFont`，指定编辑器字体路径；
  - `switch(PreferenceKeys.STICKY_SCROLL)` → 对应 `Prefs.stickyScroll`，控制“吸顶滚动”；
  - `switch(PreferenceKeys.EDITOR_USE_SPACES)` → 对应 `Prefs.useSpaces`；
  - `switch(PreferenceKeys.EDITOR_LIGATURES_ENABLE)` → 对应 `Prefs.useLigatures`；
  - `switch(PreferenceKeys.EDITOR_WORDWRAP_ENABLE)` → 对应 `Prefs.wordWrap`；
  - `switch(PreferenceKeys.BRACKET_PAIR_AUTOCOMPLETE)` → 对应 `Prefs.bracketPairAutocomplete`；
  - `switch(PreferenceKeys.EDITOR_SCROLLBAR_SHOW)` → 对应 `Prefs.scrollbarEnabled`；
  - `switch(PreferenceKeys.QUICK_DELETE)` → 对应 `Prefs.quickDelete`；
  - `switch(PreferenceKeys.EDITOR_HW_ENABLE)` → 对应 `Prefs.hardwareAcceleration`；
  - `switch(PreferenceKeys.EDITOR_NON_PRINTABLE_SYMBOLS_SHOW)` → 对应 `Prefs.nonPrintableCharacters`；
  - `switch(PreferenceKeys.EDITOR_LINE_NUMBERS_SHOW)` → 对应 `Prefs.lineNumbers`；
  - `switch(PreferenceKeys.EDITOR_DOUBLE_CLICK_CLOSE)` → 对应 `Prefs.doubleClickClose`；
  - `switch(PreferenceKeys.DISABLE_SYMBOLS_VIEW)` → 对应 `Prefs.disableSymbolsView`。

这些配置如何被编辑器使用？

- 在 `IdeEditor` 中（`app/src/main/kotlin/org/cosmicide/editor/IdeEditor.kt`）：

```kotlin
isLigatureEnabled = Prefs.useLigatures
isWordwrap = Prefs.wordWrap
setScrollBarEnabled(Prefs.scrollbarEnabled)
isHardwareAcceleratedDrawAllowed = Prefs.hardwareAcceleration
isLineNumberEnabled = Prefs.lineNumbers
props.deleteEmptyLineFast = Prefs.quickDelete
props.stickyScroll = Prefs.stickyScroll
setTextSize(Prefs.editorFontSize)
tabWidth = Prefs.tabSize
nonPrintablePaintingFlags = if (Prefs.nonPrintableCharacters) flags else 0
```

- 在 `EditorFragment` 中（`doubleClickClose`）：

```kotlin
if (Prefs.doubleClickClose && fileViewModel.currentPosition.value == tab.position) {
    fileViewModel.removeFile(tab.position)
}
```

可以清楚看到：**EditorSettings 负责“把用户的选择写入 prefs”，IdeEditor / EditorFragment 等消费这些配置决定编辑行为**。
### 3. 编译器设置：CompilerSettings

文件：`app/src/main/kotlin/org/cosmicide/fragment/settings/CompilerSettings.kt`

- `switch(PreferenceKeys.COMPILER_USE_FJFS)`：是否启用 Fast Jar File System；
- `switch(PreferenceKeys.COMPILER_USE_K2)`：是否启用 Kotlin K2 编译器；
- `singleChoice(PreferenceKeys.COMPILER_JAVA_VERSIONS, ...)`：选择 Java 版本（字符串数组 `R.array.java_version_entries`）；
- `singleChoice(PreferenceKeys.COMPILER_KOTLIN_VERSION, ...)`：选择 Kotlin 语言版本（使用 `LanguageVersion` 枚举）；
- `editText(PreferenceKeys.COMPILER_JAVAC_FLAGS)`：额外的 `javac` 命令行参数；
- 另外还有一个 `categoryHeader("libs")`：
  - 内部的 `editText("repos")` 对应 `Prefs.repositories` 中所使用的仓库列表字符串。

这些配置会在编译相关的模块里被读取，用于控制编译行为（例如是否使用 K2、JDK 版本、附加参数等）。

### 4. 格式化设置：FormatterSettings

文件：`app/src/main/kotlin/org/cosmicide/fragment/settings/FormatterSettings.kt`

- `singleChoice(PreferenceKeys.FORMATTER_KTFMT_STYLE, ktfmtStyleItems)`：
  - Kotlin 代码风格（`dropbox` / `google` / `kotlinlang`）；
  - 对应 `Prefs.ktfmtStyle`；
- `multiChoice(PreferenceKeys.FORMATTER_GJF_OPTIONS, gjfOptionItems)`：
  - Google Java Format 的选项（例如 `--skip-javadoc-formatting`）；
  - 对应 `Prefs.googleJavaFormatOptions`；
- `singleChoice(PreferenceKeys.FORMATTER_GJF_STYLE, gjfStyleItems)`：
  - GJF 风格（`aosp` / `google`）；
  - 对应 `Prefs.googleJavaFormatStyle`。

在 `EditorFragment` / `GoogleJavaFormat` / `KtfmtFormatter` 中，这些配置会被用来决定格式化时采用的风格与选项。

### 5. Git 设置：GitSettings

文件：`app/src/main/kotlin/org/cosmicide/fragment/settings/GitSettings.kt`

- 三个简单的文本配置：
  - `PreferenceKeys.GIT_USERNAME` → 对应 `Prefs.gitUsername`；
  - `PreferenceKeys.GIT_EMAIL` → 对应 `Prefs.gitEmail`；
  - `PreferenceKeys.GIT_API_KEY` → 对应 `Prefs.gitApiKey`；

它们用于配置 Git 集成（如推送到 GitHub 时的身份信息和 Token）。

### 6. Gemini 设置：GeminiSettings

文件：`app/src/main/kotlin/org/cosmicide/fragment/settings/GeminiSettings.kt`

- API Key：
  - `editText(PreferenceKeys.GEMINI_API_KEY)` → 对应 `Prefs.geminiApiKey`；
- 模型名称：
  - `editText(PreferenceKeys.GEMINI_MODEL)`，默认 `"gemini-2.0-flash"` → 对应 `Prefs.geminiModel`；
- 采样参数：
  - `singleChoice(PreferenceKeys.TEMPERATURE, ...)` → 对应 `Prefs.temperature`；
  - `singleChoice(PreferenceKeys.TOP_P, ...)` → 对应 `Prefs.topP`；
  - `seekBar(PreferenceKeys.TOP_K)` → 对应 `Prefs.topK`；
  - `seekBar(PreferenceKeys.MAX_TOKENS)` → 对应 `Prefs.maxTokens`；

当你在设置页面修改这些参数后，返回时 `SettingsFragment` 会在 Gemini 子页面返回时调用 `ChatProvider.regenerateModel()` 来基于最新配置重新创建模型实例。

### 7. 插件设置：PluginSettingsProvider

文件：`app/src/main/kotlin/org/cosmicide/fragment/settings/PluginSettingsProvider.kt`

- 插件列表导航：
  - `pref(PreferenceKeys.AVAILABLE_PLUGINS)`：跳转到 `PluginListFragment`；
  - `pref(PreferenceKeys.INSTALLED_PLUGINS)`：跳转到 `PluginsFragment`；
- 插件仓库地址：
  - `editText(PreferenceKeys.PLUGIN_REPOSITORY)` 默认值来自 `Prefs.pluginRepository`；
- 插件自己的设置项：

```kotlin
categoryHeader(PreferenceKeys.PLUGIN_SETTINGS) {
    title = "Plugin settings"
}
PluginLoader.prefsMethods.forEach {
    it.invoke(this)
}
```

- `PluginLoader`（`util/src/main/java/org/cosmicide/rewrite/plugin/api/PluginLoader.kt`）在加载插件时，如果插件类中存在静态方法 `registerPreferences(PreferenceScreen.Builder)`，就会把它封装成 `PreferenceScreen.Builder.() -> Unit` 加入 `prefsMethods`。
- 因此，插件可以像主应用一样，通过 modernpreferences DSL 自己往“Plugin settings” 分类下插入设置项，实现 **插件级配置**。

### 8. 关于页面：AboutSettings

文件：`app/src/main/kotlin/org/cosmicide/fragment/settings/AboutSettings.kt`

这个页面内容比较丰富，主要包括：

- 应用介绍与开源信息；
- 捐赠入口（PayPal / Patreon）；
- 点击“版本号”多次触发“开发者模式”（写入 `experiments_enabled`，由 `Prefs.experimentsEnabled` 读取）；
- 复制版本信息到剪贴板；
- 跳转源码仓库；
- 存储权限设置跳转；
- Shizuku + rish 执行特权命令；
- 清理缓存并重新进入资源安装流程；
- `switch("analytics_preference")` 控制匿名使用统计（对应 `Prefs.analyticsEnabled` 和 `Analytics.setAnalyticsCollectionEnabled`）。

这部分更多是“杂项设置 + 调试工具 + 支持信息”的集合，也可以当作你设计自己 About 页时的参考。
---

## 四、配置与业务逻辑的连接点示例

### 1. App 级别：主题与分析

文件：`app/src/main/kotlin/org/cosmicide/App.kt`

- 在 `onCreate()` 中：

```kotlin
val theme = getTheme(Prefs.appTheme)
val uiModeManager = getSystemService(UiModeManager::class.java)
// 根据 app_theme 设置夜间模式
...
Analytics.setAnalyticsCollectionEnabled(Prefs.analyticsEnabled)
```

- 在配置变化时（如系统主题变更）：

```kotlin
override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)

    if (Prefs.isInitialized) {
        applyThemeBasedOnConfiguration()
    }
}

fun applyThemeBasedOnConfiguration() {
    val themeName = when (getTheme(Prefs.appTheme)) {
        AppCompatDelegate.MODE_NIGHT_YES -> "darcula"
        AppCompatDelegate.MODE_NIGHT_NO -> "QuietLight"
        else -> { ... }
    }
    ThemeRegistry.getInstance().setTheme(themeName)
}
```

可以看到：

- `Prefs.appTheme` 决定 AppCompat/UiModeManager 的夜间模式；
- 同时也影响 TextMate 编辑器主题（darcula / QuietLight）。

### 2. 编辑器级别：IdeEditor + EditorFragment

- `IdeEditor` 在初始化时大量读取 `Prefs`：
  - 控制字体大小、Tab 宽度、是否自动换行、是否显示行号、是否开启硬件加速、是否显示不可见字符、是否启用粘性滚动等；
- `EditorFragment` 用 `Prefs.doubleClickClose` 控制“标签双击关闭”的 UX 行为。

通过这两个类可以直观看到：

- **Setting → SharedPreferences → Prefs → UI/逻辑** 的典型链路；
- 这样的链路对你自己项目也很有参考价值。

### 3. 插件级别：PluginLoader + PluginSettingsProvider

- 插件在加载时可以注册自己的 `registerPreferences(builder)` 静态方法；
- `PluginSettingsProvider` 会把这些方法插入到“Plugin settings” 分类下；
- 插件在内部同样可以使用 SharedPreferences（或其他方式）读回这些设置，实现完全解耦的插件设置中心。

---

## 五、可以直接借鉴的设计思路小结

结合这一整套配置中心实现，你可以在自己项目中借鉴：

1. **集中配置访问层（类似 Prefs）**
   - 用一个单例或专门类封装 SharedPreferences；
   - 所有配置项都以属性形式暴露，并在内部处理默认值、范围、安全解析。

2. **使用 Startup/Initializer 做全局初始化**
   - 比在 `Application.onCreate` 里手动初始化更解耦、更方便测试和复用。

3. **用一个 Fragment + RecyclerView + DSL 构建设置 UI**
   - 布局只放 Toolbar + RecyclerView；
   - 使用类似 modernpreferences 的 DSL，将各类设置项拆到不同的 Provider 类里。

4. **为每一类设置做一个 SettingsProvider**
   - 外观 / 编辑器 / 编译器 / 格式化 / 插件 / Git / AI / 关于，分而治之；
   - 新增设置只要增加一个 Provider 或在现有 Provider 中扩展即可。

5. **插件配置扩展点**
   - 通过类似 `PluginLoader.prefsMethods` 这种机制，让插件可以向设置中心注册自己的 PreferenceScreen 片段，实现插件级配置中心。

如果你之后想继续深入某一块（比如专门深入“编辑器配置如何影响代码高亮和补全”），可以告诉我具体模块或文件路径，我可以在这份文档基础上继续扩展新的章节。

> [2025-11 更新]：已在 TinaIDE 中实现 `core.config.Prefs` 作为配置访问门面，并在 `SettingsFragment` 中将部分编辑器/主题设置同步写入 Prefs，对应的运行时行为（如 `EditorFragment` 中的字体大小、行号显示、自动换行等）已经改为从 Prefs 读取。这一步完成了「Settings → SharedPreferences/ConfigManager → Prefs → 实际组件」的基础链路，后续可以在此基础上进一步重构设置 UI 布局。