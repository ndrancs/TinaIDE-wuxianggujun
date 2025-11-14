# TinaIDE 设置重构说明与参考路径

## 参考项目与本项目路径

- 参考项目（Cosmic-IDE）绝对路径：
  - `C:\Users\wuxianggujun\CodeSpace\AndroidStudioProjects\Cosmic-IDE`
- 本项目（TinaIDE）绝对路径：
  - `C:\Users\wuxianggujun\CodeSpace\AndroidStudioProjects\TinaIDE`

> 约定：在 TinaIDE 中重构“设置界面 + 配置中心”时，统一参考 Cosmic-IDE 的实现方式和代码结构。

---

## 重构目标（针对 TinaIDE）

1. **配置中心统一化**
   - 在 TinaIDE 中新增或完善一个 `Prefs` 单例，用来统一封装 SharedPreferences 的读写。
   - 引入类似 `PreferenceKeys` 的常量类，集中管理所有设置项的 key。
   - 避免在各个界面直接写字符串 key 和 `getSharedPreferences()`。

2. **设置界面结构统一化**
   - 在 TinaIDE 中新增一个 `SettingsFragment`，整体结构参考 Cosmic-IDE：
     - 顶部 `AppBarLayout + CollapsingToolbarLayout + MaterialToolbar`。
     - 内容使用一个 `RecyclerView` 承载设置列表。
   - 引入一个 `SettingsProvider` 接口，将设置拆分为多个分组类，例如：
     - 外观（AppearanceSettings）
     - 编辑器（EditorSettings）
     - 编译器（CompilerSettings）
     - 格式化（FormatterSettings）
     - 插件、Git、关于等（按 TinaIDE 实际需求裁剪）。

3. **布局与图标风格对齐**
   - 设置界面布局（如 `fragment_settings.xml`）参考 Cosmic-IDE 的布局结构进行设计。
   - 需要的图标（返回箭头、外观图标、编辑器图标等）可以从 Cosmic-IDE 的 `res/drawable` 中挑选并复制到 TinaIDE 的资源目录中使用。

4. **行为一致性与可维护性**
   - 保证 TinaIDE 中所有设置项：
     - UI 所用的 key = `PreferenceKeys` 常量 = `Prefs` 中读取的 key。
   - 对重要配置（如主题、编辑器行为、编译参数等），在 TinaIDE 里建立清晰的数据流：
     - Setting UI → SharedPreferences（通过 key）→ `Prefs` → 实际使用这些配置的组件（Activity/Fragment/Editor 等）。

---

## 使用约定

- 以后在 TinaIDE 中修改“设置界面”或“配置相关代码”前：
  1. 先确认是否仍然要参考 Cosmic-IDE 的对应实现；
  2. 优先在 `Prefs` 和 `PreferenceKeys` 中增加/修改定义，然后再在具体设置 UI 中使用；
  3. 保持 TinaIDE 中 docs 文档（如本文件和后续关于设置的文档）与实际实现同步更新。

- 当需要查看参考实现时：
  - Cosmic-IDE 中与设置相关的典型文件：
    - `common/src/main/java/org/cosmicide/common/Prefs.kt`
    - `app/src/main/kotlin/org/cosmicide/util/PreferenceKeys.kt`
    - `app/src/main/kotlin/org/cosmicide/fragment/SettingsFragment.kt`
    - `app/src/main/kotlin/org/cosmicide/fragment/settings/*.kt`
    - `app/src/main/res/layout/fragment_settings.xml`
    - `app/src/main/res/values/arrays.xml`

> 以后在 TinaIDE 里做设置相关的改动时，可以先打开本文件快速确认参考路径和设计目标。