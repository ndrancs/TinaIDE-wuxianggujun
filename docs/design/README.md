# 设计文档索引

> 更新日期：2026-04-22

本目录存放 TinaIDE 仍在维护的设计与规范文档。

## 入口

- **主文档中心**：`docs/README.md`

## 当前约定

- 本轮已删除 `TinaEditor-Audit-2026-04-18-R2.md`、`TinaEditor-Audit-2026-04-18-R3.md`、`TinaEditor-Audit-2026-04-18-R4-LSP.md` 这类阶段性审计草稿。
- 已进一步删除未纳入索引的一组旧设计稿，包括 TinaEditor 阶段性审计/高亮优化稿、APK 打包审计稿、AI/支付方案稿以及构建重写草稿。
- 下面列出的文档才应被视为当前仍可维护的设计入口。
- 当前目录除 `README.md` 外，已只保留索引中列出的现行设计文档。

## 当前维护中的设计文档

### 编辑器与语言服务

- [补全性能分析与改进方案](Completion-Performance-Analysis.md)
- [编辑器补全系统架构设计](Editor-Completion-System-Design.md)
- [编辑器主题自定义功能设计](Editor-Theme-Customization-Design.md)
- [LSP Snippet 占位符处理](LSP-Snippet-Placeholder-Handling.md)
- [高亮链路审查报告](TinaEditor-Highlight-Pipeline-Review.md)
- [统一布局快照](unified-layout-snapshot.md)
- [智能换行实现说明](smart-wrap-implementation.md)

### 运行时与系统

- [PRoot 功能分析](PRoot-Feature-Analysis.md)
- [PRoot 运行时重构说明](PRoot-Runtime-Refactor.md)

### UI 规范

- [UI 组件样式指南](UI-Components-Style-Guide.md)
- [TinaIDE 设计系统](TinaIDE-Design-System.md)

## 相关文档

- [插件开发者指南](../plugins/README.md)
- [插件路线图](../plugins/Plugin-Roadmap.md)
- [Toolchain 构建与同步指南](../toolchain-build-guide.md)
