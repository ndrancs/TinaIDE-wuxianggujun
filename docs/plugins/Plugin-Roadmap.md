# 插件系统路线图（Roadmap）

> 文档更新：2026-04-22
> 目标：以 **配置插件优先** 的方式逐步扩展 TinaIDE 插件能力（Play 合规、低风险、可维护）。

---

## 0. 现状（已完成）

当前已落地的能力：

- 插件安装/卸载/启用/禁用：`core/plugin/src/main/java/.../plugin/PluginManager.kt`
- 插件管理 UI（设置 → 插件）：`feature/settings/src/main/java/.../settings/sections/PluginsSettingsSection.kt`
- 主题、代码片段、文件树菜单、编辑器 Tab 长按菜单
- 项目模板与 APK 导出模板（`contributions.projectTemplates` / `contributions.apkExports`）
- LSP 插件安装链路
- 脚本 / hybrid 插件最小运行时、权限确认与日志
- assets 内置测试插件自动安装：`core/plugin/src/main/java/.../plugin/BundledPluginsInstaller.kt` + `app/src/main/assets/bundled_plugins/`

当前插件状态模型见：

- `docs/plugins/Plugin-State-Model.md`

---

## 1. 总体原则（强约束）

### 1.1 不重复包管理器

插件系统不做“依赖下载/安装/升级”的具体实现；仅支持 **依赖声明 + 宿主提示 + 跳转到现有安装流程**。

理由（DRY + 风险控制）：

- 镜像源、校验、权限、回滚、缓存、冲突策略等是包管理器/工具链安装的核心能力，插件系统重复实现成本高且难以维护。

### 1.2 菜单先绑定宿主命令（配置插件优先）

阶段 1.5 只做 **命令映射**：插件声明菜单项，绑定宿主内置命令；不执行插件代码。

---

## 2. 阶段 1.5：配置插件增强（推荐优先做）

目标：在不引入脚本引擎的前提下，让插件能“扩展 UI + 提供内容”，覆盖 80% 常见需求。

### 2.1 任务总表

| 功能 | 价值 | 难度 | 优先级 | 备注 |
|------|------|------|--------|------|
| 宿主命令注册表（Command Registry） | ⭐⭐⭐⭐⭐ | ⭐⭐ | P0 | ✅ 已完成（宿主内置命令集合：`HostCommands.kt`；插件命令运行时注册） |
| 文件树目录菜单扩展 | ⭐⭐⭐⭐ | ⭐⭐⭐ | P0 | ✅ 已完成（`menus["filetree/context"]` → 宿主内置命令 / 当前插件已注册命令） |
| 编辑器菜单/工具栏扩展 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | P1 | 🚧 editor/context（Tab 长按菜单）已完成；toolbar 待实现 |
| SnippetManager（代码片段） | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | P1 | ✅ 已完成（`contributions.snippets`） |
| Keybindings（快捷键绑定） | ⭐⭐⭐ | ⭐⭐⭐ | P2 | 与现有快捷键系统融合 |
| requires（依赖声明提示） | ⭐⭐⭐ | ⭐⭐ | P2 | 仅展示与跳转，不做安装 |
| 插件详情页（权限/依赖/贡献预览） | ⭐⭐⭐ | ⭐⭐⭐ | P2 | UX 完整性 |
| **插件设置页面** | ⭐⭐⭐ | ⭐⭐⭐ | P2 | 预留，阶段 2 前置；规范已定义，代码待实现 |

---

## 3. 实现方案与示例

### 3.1 宿主命令注册表（P0）

**目标**：把宿主已有动作抽象成 `commandId -> handler`，供插件菜单/快捷键绑定。

当前实现（已落地最小集）：

- 宿主内置命令集合定义在：`core/common/src/main/java/.../core/commands/HostCommands.kt`
- 插件运行时命令通过 `PluginCommandRegistry` 注册和分发。
- 菜单解析只显示宿主内置命令或当前插件已注册命令；未知 `commandId` 会被忽略并记录日志。

建议最小接口：

```kotlin
interface HostCommand {
    val id: String
    val title: String
    suspend fun run(context: CommandContext): Result<Unit>
}
```

建议命令分类（示例）：

- `file.new`, `file.newFolder`, `file.rename`, `file.delete`, `file.copyPath`
- `editor.save`, `editor.saveAll`, `editor.format`, `editor.search`
- `terminal.openHere`

落地要点：

- `PluginManifest.contributions.commands` 用于提供标题、分类等声明信息，不再等同于唯一可执行集合。
- 可执行命令来自两处：宿主内置命令，或当前插件运行时已注册的插件命令。
- 插件菜单项引用未知 `command` 时，直接忽略并在日志里提示。

### 3.2 文件树目录菜单扩展（P0）

**目标**：在文件树右键菜单中插入插件菜单项。

当前实现（已落地最小集）：

- 扩展点：`app/src/main/java/.../ui/compose/components/FileTreeContextMenu.kt`
- 解析器：`core/plugin/src/main/java/.../plugin/PluginMenuResolver.kt`
- manifest 键：`contributions.menus["filetree/context"]`

你现有上下文菜单实现：

- `app/src/main/java/.../ui/compose/components/FileTreeContextMenu.kt`
- `app/src/main/java/.../ui/compose/components/FileTreeModels.kt`（`FileContextAction`）

建议改造策略（KISS）：

1. 在宿主侧定义 “菜单扩展点”：
   - `FileTreeContextMenu` 渲染完内置项后，追加 `PluginMenuRegistry` 提供的 `DropdownMenuItem`
2. 插件菜单项支持两类命令：
   - 宿主内置命令，例如 `file.copyPath`
   - 当前插件通过 `tina.commands.register()` 注册的命令
3. 菜单仍由 `manifest.contributions.menus["filetree/context"]` 声明：
   - `command` 指向宿主内置命令或当前插件已注册命令
4. 命令执行时传入上下文（当前文件/目录路径、项目根目录等）

插件 manifest 示例（草案）：

```json
{
  "contributions": {
    "commands": [{ "id": "file.copyPath", "title": "复制路径" }],
    "menus": {
      "filetree/context": [{ "command": "file.copyPath", "group": "9_plugin" }]
    }
  }
}
```

示例插件：

- 直接按上文 manifest 草案声明 `filetree/context` 菜单项即可。

### 3.3 编辑器菜单/工具栏扩展（P1）

**目标**：编辑器 UI 上提供“插件按钮/菜单项”。

当前实现（已落地最小集）：

- `contributions.menus["editor/context"]` 已可用（当前落点：编辑器 Tab 长按上下文菜单）
- 扩展点：`app/src/main/java/.../ui/compose/components/TabContextMenu.kt`
- `when` 最小支持：`isDirty`

示例插件：

- 直接按上文 manifest 草案声明 `editor/context` 菜单项即可。

建议先做“编辑器右键菜单”再做“工具栏”，因为工具栏涉及布局与状态更复杂。

方案与文件树类似：

- 宿主提供扩展点：`EditorContextMenu` / `EditorToolbar`
- 插件只提供菜单描述，宿主负责渲染与条件判断（`when`）

### 3.4 SnippetManager（P1）

**目标**：加载 `contributions.snippets` 并注册到补全系统。

当前实现（已落地最小集）：

- 只加载 JSON 数据（不执行插件代码）
- 在补全列表中展示 snippet，并使用 Tina 片段控制器进行插入
- 示例可直接复用本文的 snippet JSON 结构；当前仓库不再随 APK 内置
  `sample.snippets.cpp`，插件 starter 中保留了 snippet 配置示例。

建议分两步：

1. 先做到“静态插入片段”：在补全列表中出现 snippet（不做占位符跳转）
2. 再增强占位符（`$1`、`${1:default}`）与 tab 跳转

插件 snippet 示例：

```json
{
  "language": "cpp",
  "snippets": [
    { "prefix": "fori", "name": "for (int i=0;...)", "body": ["for (int i = 0; i < ${1:n}; i++) {", "  $0", "}"] }
  ]
}
```

### 3.5 Keybindings（P2）

你现有快捷键系统：

- `core/config/src/main/java/.../core/config/KeyboardShortcuts.kt`

建议：

- 插件 keybindings 只能绑定宿主命令注册表（同菜单）
- `when` 条件先不做或只做最小集（如 `editorFocus == true`）

### 3.6 requires（依赖声明提示）（P2）

**目标**：插件声明需要哪些工具链组件/包，宿主展示并引导用户安装（不代替包管理器）。

建议 `manifest.json` 字段（草案）：

```json
{
  "requires": {
    "toolchain": { "recommended": ["clangd", "cmake"], "optional": ["lldb"] },
    "packages": { "proot": ["python3"] }
  }
}
```

UI 行为建议：

- 插件详情页显示"依赖清单"和当前满足情况
- 提供"前往安装"按钮，跳转到工具链/依赖安装页面

### 3.7 插件设置页面（P2，阶段 2 前置）

**目标**：让插件可以声明可配置项，宿主自动生成设置 UI。

**当前状态**：
- 规范已定义（见 `docs/plugins/README.md` 中的 manifest 草案）
- 代码侧待实现
- 脚本 / hybrid 插件已经落地，但仍缺统一的宿主配置 UI

**为什么当前不急**：
- 现有插件类型（主题、代码片段）不需要复杂配置
- 主题插件 → 选择即可
- 代码片段插件 → 直接使用
- 等脚本 / hybrid 插件的宿主 API 与权限模型进一步稳定后再统一补更合理

**manifest.json 示例**：

```json
{
  "configuration": {
    "title": "My Plugin 设置",
    "properties": {
      "myPlugin.enableFeatureX": {
        "type": "boolean",
        "default": true,
        "description": "启用功能 X"
      },
      "myPlugin.outputFormat": {
        "type": "string",
        "default": "json",
        "enum": ["json", "xml", "yaml"],
        "description": "输出格式"
      }
    }
  }
}
```

**实现要点**：

1. **数据模型扩展**
   - 在 `PluginManifest` 中添加 `configuration: PluginConfiguration?` 字段
   - 定义 `ConfigProperty` 数据类（type、default、description、enum 等）

2. **UI 自动生成**
   - 根据属性类型生成对应控件：
     - `boolean` → Switch
     - `number` → TextField（数字输入）或 Slider
     - `string` → TextField
     - `string` + `enum` → DropdownMenu
   - 在插件详情页添加"设置"按钮入口

3. **配置存储**
   - 使用 SharedPreferences
   - 键格式：`plugin.<pluginId>.<propertyKey>`
   - 示例：`plugin.myPlugin.enableFeatureX` → `true`

4. **配置读取 API**（脚本插件使用）
   ```javascript
   const enabled = tina.config.get("myPlugin.enableFeatureX", true);
   tina.config.onDidChange("myPlugin.outputFormat", (newValue) => { ... });
   ```

**参考实现**：VS Code 的 `contributes.configuration`

---

## 4. 阶段 2：脚本 / Hybrid 插件能力收敛

脚本 / hybrid 插件基础运行时已经落地。下一阶段重点不是再引入另一套引擎，
而是继续收敛权限、API、生命周期和渠道策略。

### 4.1 分级发布策略（建议）

- **Play 安全模式（推荐）**
  - 仅允许运行“随 APK 一起发布”的脚本插件（例如 `assets/bundled_plugins/*` 中的脚本）
  - 不提供“从文件安装脚本插件/从网络下载脚本并执行”的能力
  - 脚本只允许调用宿主显式暴露的白名单 API（默认禁用高风险能力）

- **开发/非 Play 渠道模式（可选）**
  - 允许从文件安装脚本插件（用户明确选择文件）
  - 仍然必须：权限系统 + 沙箱 + API 白名单 + 审计日志
  - 建议用 `BuildConfig` 开关控制（release 默认关闭）

### 4.2 必做清单（安全与可维护）

- 必须：权限系统 + 沙箱 + API 白名单 + 用户授权提示
- 推荐：先做“官方脚本插件 / 受控 hybrid 插件”验证稳定性，再逐步开放第三方
