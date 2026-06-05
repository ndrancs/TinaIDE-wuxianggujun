# TinaIDE 插件 API 指南

> 文档更新：2026-04-23
> 目标：给插件开发者一份“当前真实可用”的 API 清单，避免继续踩字段存在但宿主没接完的坑。

---

## 1. 先看稳定边界

当前建议按两层理解 TinaIDE 插件 API：

- **稳定层**
  `config` 插件能力、`lsp` 插件能力、脚本插件的基础宿主 API
- **Beta 层**
  脚本插件的高级自动化能力，以及尚未完整接入的事件/扩展点

如果你要写给普通用户的教程，主线只推荐：

1. `config`
2. `lsp`
3. `script` 作为进阶/Beta

---

## 2. 插件类型与入口

### 2.1 `config`

适合：

- 主题
- 代码片段
- 菜单
- 文件图标
- 项目模板

### 2.2 `script`

适合：

- 编辑器自动化
- 项目事件响应
- 宿主命令调用

说明：

- 运行时为 Lua
- 需要权限声明
- 当前建议按 Beta 能力对外说明

### 2.3 `lsp`

适合：

- 语言服务器接入
- 工具链安装
- 语言补全与诊断

---

## 3. Script API 命名空间

脚本插件统一从 `tina.*` 访问宿主 API。

### 3.1 稳定可用

- `tina.ui`
- `tina.log`
- `tina.editor`
- `tina.diagnostics`
- `tina.workspace`
- `tina.fs`（历史兼容）
- `tina.config`
- `tina.storage`
- `tina.db`
- `tina.network`
- `tina.commands`
- `tina.events`

### 3.2 使用建议

第一次写脚本插件时，优先只用：

- `tina.ui`
- `tina.log`
- `tina.editor`
- `tina.events`
- `tina.diagnostics`
- `tina.workspace`

等第一版跑通，再逐步引入：

- `tina.commands`
- `tina.config`
- `tina.storage`
- `tina.db`
- `tina.network`

---

## 4. 具体 API

### 4.1 `tina.ui`

用途：用户可见消息。

已提供：

- `tina.ui.showMessage(message)`
- `tina.ui.showWarning(message)`
- `tina.ui.showError(message)`

权限：

- `ui.notification`

### 4.2 `tina.log`

用途：插件日志。

已提供：

- `tina.log.debug(message)`
- `tina.log.info(message)`
- `tina.log.warn(message)`
- `tina.log.error(message)`

额外说明：

- 全局 `print()` 也会转到插件日志

### 4.3 `tina.editor`

用途：读取或修改当前编辑器上下文。

已提供：

- `tina.editor.getActiveEditor()`
- `tina.editor.getText()`
- `tina.editor.setText(text)`
- `tina.editor.getSelection()`
- `tina.editor.setSelection(startLine, startColumn, endLine, endColumn)`
- `tina.editor.insertText(text, line, column)`
- `tina.editor.replaceSelection(text)`
- `tina.editor.getLanguage()`
- `tina.editor.getCursorPosition()`
- `tina.editor.setCursorPosition(line, column)`
- `tina.editor.getFilePath()`
- `tina.editor.getFileName()`

权限映射：

- 读：`editor.read`
- 写：`editor.write`
- 选区：`editor.selection`

`tina.editor.getActiveEditor()` 返回当前活动编辑器快照。当前稳定字段：

- `tabId`
- `filePath`
- `fileName`
- `languageId`
- `isDirty`
- `cursor.line`
- `cursor.column`

说明：

- `insertText()` 与 `replaceSelection()` 现在会同步等待宿主编辑器结果，不再提前返回 `false`
- 选区内容仍然通过 `tina.editor.getSelection()` 单独读取，避免和 `editor.selection` 权限边界混淆

### 4.4 `tina.diagnostics`

用途：读取当前诊断面板里的错误、警告、提示。

已提供：

- `tina.diagnostics.get()`
- `tina.diagnostics.get(filePath)`

返回约定：

- 返回诊断快照表，不抛出宿主异常
- `diagnostics` 是诊断数组
- 计数字段包含 `totalCount`、`errorCount`、`warningCount`、`infoCount`、`hintCount`
- `filePath` 支持项目相对路径或项目内绝对路径

权限：

- `diagnostics.read`

### 4.5 `tina.workspace`

用途：访问当前项目根目录内的文件。

已提供：

- `tina.workspace.readFile(path)`
- `tina.workspace.writeFile(path, content)`
- `tina.workspace.findFiles(pattern, maxResults)`

返回约定：

- `readFile` 成功返回文本；失败返回 `nil, error`
- `writeFile` 成功返回 `true`；失败返回 `false, error`
- `findFiles` 返回相对路径数组，路径统一使用 `/`

约束：

- 只能在当前项目根目录下访问
- 不允许路径逃逸
- `findFiles` 支持 `*`、`?`、`**/`
- `findFiles` 会跳过 `.git`、`.gradle`、`.idea`、`.cxx`、`build`、`node_modules`

权限：

- 读：`workspace.read` 或 `file.read`
- 写：`workspace.write` 或 `file.write`

### 4.6 `tina.fs`

用途：历史兼容的工作区文件 API。

已提供：

- `tina.fs.readFile(path)`
- `tina.fs.writeFile(path, content)`
- `tina.fs.exists(path)`
- `tina.fs.isDirectory(path)`
- `tina.fs.listDir(path)`
- `tina.fs.mkdir(path)`

说明：

- 新插件优先使用 `tina.workspace.*`
- 老插件可以继续使用 `tina.fs.*`
- 权限仍映射到 `workspace.read` / `workspace.write` 对应的底层文件权限

### 4.7 `tina.config`

用途：读取和更新 manifest `configuration.properties` 中声明的插件配置。

已提供：

- `tina.config.get(key)`
- `tina.config.get(key, fallback)`
- `tina.config.set(key, value)`
- `tina.config.reset(key)`

说明：

- 不需要 `storage.local` 权限。
- 只能访问当前插件 manifest 声明过的配置 key。
- 未保存值时，`get()` 会先返回 manifest `default`，再使用调用方传入的 fallback。
- `set()` 会校验类型；`string` + `enum` 会拒绝未声明的枚举值。
- 支持类型为 `boolean`、`string`、`number`。
- 配置变化会触发 `config.changed`，该事件只发给配置所属插件，不会暴露给其他插件。

### 4.8 `tina.storage`

用途：插件级键值存储。

已提供：

- `tina.storage.get(key)`
- `tina.storage.set(key, value)`
- `tina.storage.remove(key)`

权限：

- `storage.local`

### 4.9 `tina.db`

用途：插件独立 SQLite 数据库。

已提供：

- `tina.db.execute(sql, params)`
- `tina.db.query(sql, params)`
- `tina.db.transaction(callback)`
- `tina.db.close()`
- `tina.db.tableExists(tableName)`

权限：

- `storage.database`

### 4.10 `tina.network`

用途：网络请求。

已提供：

- `tina.network.fetch(url, method, body, contentType)`
- `tina.network.get(url)`
- `tina.network.post(url, body, contentType)`

权限：

- `network.fetch`
- `network.unrestricted`

约束：

- 使用 `network.fetch` 时，目标主机必须命中白名单
- 若要完全放开，需要更高风险权限

### 4.11 `tina.commands`

用途：调用宿主现有命令，或注册当前插件自己的命令回调。

已提供：

- `tina.commands.execute(commandId)`
- `tina.commands.execute(commandId, relativePath)`
- `tina.commands.execute(commandId, relativePath, isDirectory)`
- `tina.commands.register(commandId, callbackName)`
- `tina.commands.register(commandId, callbackName, title)`
- `tina.commands.unregister(commandId)`

说明：

- 第二个参数会解析为当前项目根目录内的目标路径
- 如果命令不需要目标文件，直接只传 `commandId` 即可
- `register()` 只能注册当前插件自己的命令
- 宿主命令 ID 与插件命令 ID 不能冲突
- 跨插件重复命令 ID 会被拒绝

插件命令回调会收到一个 payload table，当前稳定字段：

- `commandId`
- `filePath`
- `fileName`
- `isDirectory`
- `isDirty`

当前适合优先使用的命令：

- `view.toggleFileTree`
- `view.toggleSymbols`
- `view.settings`
- `editor.format`
- `editor.save`
- `editor.saveAll`
- `project.refresh`
- `project.build`
- `project.run`

对文件目标依赖更强的命令：

- `file.copyPath`
- `file.copyName`
- `file.copyRelativePath`
- `file.delete`
- `file.rename`

权限：

- `command.execute`
- `commands.execute` 也会被归一化为同一权限

菜单绑定规则：

- `contributions.menus["filetree/context"]`
- `contributions.menus["editor/context"]`

上述 `command` 字段现在支持两类值：

- 宿主内置命令
- 当前插件已注册的插件命令

### 4.12 `tina.events`

用途：监听宿主事件。

已提供：

- `tina.events.on(eventId, callbackName)`
- `tina.events.off(eventId)`
- `tina.events.clear()`

当前宿主已接入的事件：

- `project.opened`
- `project.closed`
- `build.started`
- `build.finished`
- `editor.opened`
- `editor.closed`
- `editor.activeChanged`
- `editor.selectionChanged`
- `editor.dirtyChanged`
- `editor.saved`
- `file.created`
- `file.deleted`
- `file.renamed`
- `diagnostics.changed`
- `config.changed`

当前仅保留 ID、但不建议在教程里承诺已稳定触发的事件：

- `custom`

常见事件数据：

- `editor.opened` / `editor.closed` / `editor.saved`
  包含 `tabId`、`filePath`、`fileName`，打开/关闭事件还包含 `contentType`
- `editor.activeChanged`
  包含 `tabId`、`filePath`、`fileName`、`contentType`、`isDirty`
- `editor.selectionChanged`
  包含 `tabId`、`filePath`、`fileName`、`hasSelection`、`selection`
- `editor.dirtyChanged`
  包含 `tabId`、`filePath`、`fileName`、`isDirty`
- `file.created` / `file.deleted`
  包含 `filePath`、`fileName`、`isDirectory`
- `file.renamed`
  包含 `oldPath`、`oldName`、`newPath`、`newName`、`isDirectory`
- `diagnostics.changed`
  包含 `fileUri`、`fileName`、`totalCount`、`errorCount`、`warningCount`、`diagnostics`
- `config.changed`
  包含 `pluginId`、`key`、`value`、`previousValue`
- `project.opened` / `project.closed`
  包含 `rootPath`、`projectName`
- `build.started` / `build.finished`
  包含 `rootPath`

高频事件说明：

- `editor.selectionChanged` 已在宿主侧做 180ms 防抖
- `diagnostics.changed` 由 LSP / 内置语言服务诊断变化触发
- `editor.dirtyChanged` 只在脏状态真正变化时触发

---

## 5. 权限清单

### 5.1 低风险

- `editor.read`
- `editor.selection`
- `diagnostics.read`
- `ui.notification`

### 5.2 中低风险

- `editor.write`
- `clipboard.read`
- `clipboard.write`
- `command.execute`

### 5.3 中风险

- `file.read`
- `file.write`
- `workspace.read`（等价于 `file.read`）
- `workspace.write`（等价于 `file.write`）
- `network.fetch`
- `storage.local`
- `storage.database`

### 5.4 高风险

- `file.system`
- `shell.execute`
- `network.unrestricted`

---

## 6. 一个最小可运行示例

```lua
function on_project_opened(data)
  local root_path = data and data.rootPath or "unknown"
  tina.log.info("Project opened: " .. root_path)
  tina.ui.showMessage("Project opened: " .. root_path)
end

function on_editor_saved(data)
  local file_name = data and data.fileName or "unknown"
  tina.log.info("Editor saved: " .. file_name)
end

function on_file_created(data)
  local path = data and data.filePath or "unknown"
  tina.log.info("File created: " .. path)
end

function on_diagnostics_changed(data)
  local errors = data and data.errorCount or 0
  local warnings = data and data.warningCount or 0
  tina.log.info("Diagnostics changed: errors=" .. errors .. ", warnings=" .. warnings)
end

tina.events.on("project.opened", "on_project_opened")
tina.events.on("editor.saved", "on_editor_saved")
tina.events.on("file.created", "on_file_created")
tina.events.on("diagnostics.changed", "on_diagnostics_changed")
tina.commands.execute("view.toggleFileTree")
tina.ui.showMessage("Plugin loaded")
```

配套权限：

```json
{
  "permissions": [
    "ui.notification",
    "editor.read",
    "command.execute"
  ]
}
```

---

## 7. 当前不建议在公开教程里承诺的点

以下内容在源码中有字段或雏形，但不建议对普通用户写成“稳了”：

- `hybrid` 插件作为主教程入口
- 所有脚本事件都已完整接入

---

## 8. 推荐公开表达方式

如果你要把这套 API 发给用户，我建议文案保持这个口径：

- `config` 和 `lsp` 是正式能力
- `script` 是进阶/Beta 能力
- 教程默认只使用“当前宿主已接好”的事件和 API

这样最稳，不会再把用户带进“文档说支持，实际没接完”的坑里。
