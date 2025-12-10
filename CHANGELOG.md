# Changelog

本文档记录 TinaIDE 项目的版本更新历史，包括新功能、Bug 修复和改进。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [1.0.154] - 2025-12-10

### 新增
- **Clangd 诊断功能完整实现**
  - Native 层：实现 `publishDiagnostics` JSON 解析，提取诊断项的 range、severity、message、code、source 字段
  - JNI 层：实现 C++ `DiagnosticItem` 到 Java 对象的转换
  - Kotlin 层：`LspService` 添加诊断缓存和监听器分发机制
  - UI 层：`BottomPanelManager` 自动订阅诊断事件，实时显示错误和警告
  - 支持项目级别诊断，多文件同时显示
  - 点击诊断项可跳转到对应代码位置

### 改进
- `LspService.addDiagnosticsListener()` 注册时立即发送缓存的诊断数据
- 文件关闭时自动清除对应的诊断缓存

---

## [未发布]

### 计划中
- 诊断功能的属性测试覆盖
- 诊断更新防抖优化（如需要）

---

## 版本说明

- **新增 (Added)**: 新功能
- **改进 (Changed)**: 对现有功能的改进
- **弃用 (Deprecated)**: 即将移除的功能
- **移除 (Removed)**: 已移除的功能
- **修复 (Fixed)**: Bug 修复
- **安全 (Security)**: 安全相关的修复
