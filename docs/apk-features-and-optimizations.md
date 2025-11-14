# TinaIDE APK 功能与优化变更记录

> 本文用于记录当前 APK 的核心功能实现，以及近期在架构与代码层面做过的优化与重构，方便后续继续演进和对照。

---

## 一、当前 APK 主要功能概览

### 1. 项目管理（ProjectManagerActivity）

- **项目列表与根目录管理**
  - 通过 `ConfigManager` 读取/保存项目根目录（如 `TinaIDE/Projects`）。
  - 启动时根据配置加载项目根目录下的子目录作为“项目列表”。
- **下拉刷新项目列表**
  - 使用 `SwipeRefreshLayout` 下拉重新扫描项目目录。
- **创建与打开项目**
  - 通过 `ProjectDialog` 新建项目目录，或从存储中选择已有目录。
  - 调用 `FileManager` 打开项目，并跳转到 `MainActivity` 进入编辑界面。
- **权限处理**
  - 根据 Android 版本分别请求 `MANAGE_EXTERNAL_STORAGE` 或 `READ_MEDIA_*` 权限。
  - 整合 XXPermissions 库，统一处理“未全部授予 / 永久拒绝”等情况，并给出中文提示。

### 2. 文件树浏览（FileTreeFragment）

- **项目文件树展示**
  - 使用自定义 `TreeView<File>` + `TreeNode<File>` 展示当前项目的目录结构。
  - 支持递归构建目录树，按“目录优先 + 名称排序”排序文件列表。
- **懒加载子目录**
  - 为目录节点延迟加载子节点，避免一次性加载大项目导致卡顿。
- **点击/长按行为**
  - 单击文件：调用 `EditorContainerFragment` 打开文件。
  - 长按文件：弹出 `FileContextMenuDialog`，支持重命名、删除等操作，并在操作后刷新文件树。
- **空状态展示**
  - 当没有打开项目或未找到文件时，显示“暂无打开的项目”提示文案。

### 3. 多标签编辑器容器（EditorContainerFragment）

- **多标签文件编辑**
  - 使用 `ViewPager2 + TabLayout` 承载多个 `EditorFragment`。
  - 通过 `EditorManager` 管理 `EditorTab`，支持打开、关闭、切换文件。
- **标签栏交互**
  - 标签标题为文件名。
  - 支持长按标签弹出 `TabContextMenu`：关闭当前、关闭其他、关闭全部等操作。
- **编辑器工具栏**
  - 底部工具栏提供：撤销、重做、查找、跳转到行、保存当前文件等操作。
  - 通过当前 `EditorFragment` 对象获取 `CodeEditor` 执行对应方法。
- **空状态视图**
  - 使用 `ViewStub` 延迟加载“空编辑器”视图。
  - 当没有任何打开的标签时隐藏标签栏和工具栏，仅展示空状态；有标签时恢复正常视图。

### 4. 代码编辑（EditorFragment）

- **Sora Editor 集成**
  - 使用 `io.github.rosemoe.sora.widget.CodeEditor` 作为代码编辑组件。
  - 支持行号显示、自动缩进、块线、光标动画等基础特性。
- **文件加载与保存接口**
  - 从传入的 `file_path` 参数加载文件内容。
  - 提供 `getText()/setText()` 接口供外部获取/设置文本内容。
- **编辑操作封装**
  - `undo()/redo()`：封装编辑器的撤销/重做能力。
  - 预留 `isDirty()` 用于后续实现“未保存修改”检测。

### 5. 编译输出（OutputActivity）

- **独立输出窗口**
  - 使用 `CodeEditor` 作为输出区域，支持高性能滚动和文本选择。
- **只读/可编辑切换**
  - 默认只读，防止误改输出内容。
  - 通过菜单项切换是否可编辑，并动态更新菜单标题。
- **输出管理**
  - 通过 `OutputManager` 统一管理编译日志：
    - Activity 启动时加载已有输出；
    - 监听新的输出追加到编辑器尾部；
    - 支持清空输出内容。

### 6. 设置中心（SettingsActivity + SettingsFragment）

- **基于 AndroidX Preference 的设置界面**
  - 使用 `PreferenceFragmentCompat` 加载 `R.xml.preferences`。
- **编辑器相关设置**
  - 字体大小（SeekBarPreference）
  - 行号开关（SwitchPreferenceCompat）
  - Tab 宽度、编辑器主题（ListPreference）
- **编译器相关设置**
  - 优化级别、编译线程数等。
- **项目与外观设置**
  - 默认项目路径（目前为占位实现）。
  - 应用主题、沉浸式状态栏开关等。
- **关于信息**
  - 显示当前版本号和构建号。
  - 提供 GitHub 链接与“开源许可”入口（部分功能为 TODO）。

### 7. 主界面与服务管理（MainActivity + ServiceLocator）

- **主界面布局**
  - 顶部 AppBar + `DrawerLayout` 侧边导航。
  - 主内容区域放置 `EditorContainerFragment`，用于代码编辑。
- **服务定位器（ServiceLocator）集成**
  - 在 MainActivity 中为当前 Activity 注册作用域服务：
    - `UIManager`：负责 UI 状态和布局恢复；
    - `EditorManager`：管理编辑标签与 EditorFragment。
  - 同时注册/获取全局服务：
    - `ConfigManager`、`FileManager`、`OutputManager` 等。

---

## 二、近期架构与代码优化记录

### 1. 引入统一的 ViewBinding Fragment 基类

**文件：** `app/src/main/java/com/wuxianggujun/tinaide/base/BaseBindingFragment.kt`

- 新增 `BaseBindingFragment<T : ViewBinding>`，继承自 `BaseFragment`。
- 通过构造函数传入 `inflateBinding: (LayoutInflater, ViewGroup?, Boolean) -> T`，统一管理：
  - `onCreateView` 中的 Binding 初始化与销毁；
  - `binding` 非空访问；
- 提供可扩展的 UI 能力：
  - `enableSharedAxisTransitions`：可选启用 MaterialSharedAxis X 轴转场；
  - `enableDefaultBackHandler`：可选统一处理返回键（优先回退 Fragment 栈，否则回到桌面）。

**受影响的 Fragment：**

- `FileTreeFragment` → 改为 `BaseBindingFragment<FragmentFileTreeBinding>`，使用 `binding.fileTreeRecycler` / `binding.emptyView`。
- `EditorFragment` → 改为 `BaseBindingFragment<FragmentEditorBinding>`，使用 `binding.codeEditor`。
- `EditorContainerFragment` → 改为 `BaseBindingFragment<FragmentEditorContainerBinding>`，TabLayout / ViewPager2 / 工具栏以及 `ViewStub` 都通过 Binding 访问。

> 效果：
> - 去掉每个 Fragment 内部重复的 `inflate + findViewById` 模板代码（KISS / DRY）。
> - Fragment 只关心业务逻辑，视图生命周期由基类统一处理。

### 2. 启用并统一使用 ViewBinding（Activity 层）

**Gradle 配置：** `app/build.gradle.kts`

- 在 `android { buildFeatures { ... } }` 中开启：
  ```kotlin
  buildFeatures {
      buildConfig = true
      viewBinding = true
  }
  ```

**BaseActivity 改造：** `BaseActivity` → `BaseActivity<VB : ViewBinding>`

- 增加泛型参数 `VB`，并在构造函数中接收 `inflateBinding: (LayoutInflater) -> VB`。
- 在 `onCreate` 中：
  - 统一执行 `binding = inflateBinding(layoutInflater)`；
  - 调用 `setContentView(binding.root)`；
  - 保留原有沉浸式状态栏、协程、加载对话框与错误处理逻辑。
- 重写 `setContentView(view: View?)`，在任何情况下都调用 `setupFitsSystemWindows()`，保证状态栏适配统一。
- 在 `onDestroy` 中将 `_binding` 置空，避免内存泄露。

**受影响的 Activity：**

- `MainActivity` → `BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate)`
  - 当前仍保留部分 `findViewById` 调用，后续可以逐步迁移到 `binding.xxx`。
- `OutputActivity` → `BaseActivity<ActivityOutputBinding>(ActivityOutputBinding::inflate)`
  - 使用 `binding.toolbar` / `binding.outputEditor`，完全去掉手动 `findViewById`。
- `SettingsActivity` → `BaseActivity<ActivitySettingsBinding>(ActivitySettingsBinding::inflate)`
  - 使用 `binding.toolbar` 和 `binding.settingsContainer.id` 替代硬编码 ID。
- `ProjectManagerActivity` → `BaseActivity<ProjectManagerFragmentBinding>(ProjectManagerFragmentBinding::inflate)`
  - 使用 `binding.toolbar`、`binding.projectsRecycler`、`binding.scrollingView`、`binding.createProjectFab`，以及 `binding.emptyContainer.root` / `binding.emptyProjects.root` 管理视图状态。

> 效果：
> - 所有 Activity 统一通过 ViewBinding 访问布局，减少模板与强制类型转换。
> - 保持原有功能不变的同时，为后续 UI 重构提供一致的基座。

### 3. Fragment 基类与 Activity 的协同调整

**文件：** `BaseFragment.kt`

- 将原本对 `BaseActivity` 的类型转换改为兼容泛型版本：
  - `(activity as? BaseActivity)?.showLoading()` → `(activity as? BaseActivity<*>)?.showLoading()`。
  - `handleDefaultError` 中同理处理。
- 保持原有职责：
  - 提供 `launchSafely`、`withIO`、`withMain` 等协程工具方法；
  - 将 loading 与错误对话框委托给宿主 Activity；
  - 在 `onDestroyView` 中取消协程并隐藏加载框。

> 效果：
> - Fragment 与泛型 BaseActivity 之间仍然能够安全协作。
> - API 使用方式无感变化，只是适配了新的泛型签名。

### 4. 项目管理页（ProjectManagerActivity）增强

- **下拉刷新依赖补全**
  - 在 `app/build.gradle.kts` 中增加：
    ```kotlin
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    ```
  - 修复 `SwipeRefreshLayout` 未解析的问题，并在 `ProjectManagerActivity` 中正确引用。
- **空态与加载视图 Binding 化**
  - 通过 `ProjectManagerFragmentBinding` 的 `emptyContainer` / `emptyProjects` 子 Binding 管理加载与空态视图：
    - 启动时隐藏 loading 容器，避免文案与进度叠加；
    - 根据项目列表是否为空切换列表与空态。

> 效果：
> - 修复了构建期的依赖问题，避免 SwipeRefreshLayout 相关的编译错误。
> - 页面逻辑更清晰，视图状态切换集中在 `reloadProjects()` 中统一处理。

---

## 三、后续可考虑的改进方向（草案）

1. **进一步迁移 MainActivity 到完整 Binding 风格**
   - 逐步用 `binding.xxx` 替代零散的 `findViewById` 调用（Toolbar、DrawerLayout、NavView 等）。
2. **在合适的 Fragment 启用共享轴转场与统一返回行为**
   - 对某些导航场景（如设置页、项目管理页）可以基于 `BaseBindingFragment` 的开关启用 Material 动画和默认返回逻辑，统一动效体验。
3. **补充文档与注释**
   - 在 `docs/设计问题分析与改进方案.md` 中补一句：Activity/Fragment 的推荐继承方式与 ViewBinding 使用规范。
4. **逐步清理 Deprecated API**
   - 如 `startActivityForResult` 等，逐步迁移到 Activity Result API，减少未来升级风险。

> 本文会随着功能与重构的推进持续更新，用于对照「当时做了什么修改」以及「这些修改服务了哪些功能与目标」。
