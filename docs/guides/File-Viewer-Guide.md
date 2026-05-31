# 文件预览指南

> 更新日期：2026-05-30

TinaIDE 的文件打开逻辑由编辑器容器统一分发：普通文本、源码、Markdown 与 JSON 默认进入代码编辑器；大文本、图片与二进制文件会进入只读查看器，避免把不适合编辑的内容直接加载到代码编辑器。

## 如何打开

1. 在左侧文件树中点击文件。
2. TinaIDE 会根据文件类型自动选择合适的预览方式：
   - 可编辑文本：进入编辑器
   - 可预览格式：进入对应预览器

如果你希望交给系统应用处理，可以在文件树的上下文菜单中选择“用其他应用打开/分享”。

## 支持的查看类型

### 大文本

- 用途：分段查看超大文本文件，减少一次性加载造成的卡顿。
- 常见扩展名：任意文本文件，按文件大小自动判定。
- 相关实现：`feature/viewer/src/main/java/.../ui/compose/viewer/LargeTextViewerScreen.kt`

### 图片

- 用途：预览项目内图片资源。
- 常见扩展名：`.png` / `.jpg` / `.jpeg` / `.webp` / `.gif`
- 相关实现：`feature/viewer/src/main/java/.../ui/compose/viewer/ImagePreviewScreen.kt`

### Hex（二进制）

- 用途：查看二进制文件的十六进制内容。
- 常见扩展名：任意（通常用于 `.so` / `.bin` 等）
- 相关实现：`feature/viewer/src/main/java/.../ui/compose/viewer/HexViewerScreen.kt`

## 注意事项

- Markdown 与 JSON 不再维护独立文件查看器页面；它们按可编辑文本进入代码编辑器。
- `MarkdownViewer.kt` 是复用型 Markdown 渲染组件，用于帮助页、公告、Hover 等只读内容，不是文件树入口。
- 二进制文件默认只适合“查看”，不建议直接编辑。
