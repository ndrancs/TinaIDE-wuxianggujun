# TinaIDE 测试文档

> 更新日期：2026-05-31

本目录只保留当前仍值得固定维护的测试入口说明。

### Popup 回归固定入口

编辑器 popup 的共享回归建议固定跑下面两组命令：

```bash
./gradlew :core:editor-view:testDebugUnitTest --tests "com.wuxianggujun.tinaide.core.editorview.EditorPopupComposeSmokeTest" --tests "com.wuxianggujun.tinaide.core.editorview.PopupOverlaySharedAnchorIntegrationTest" --tests "com.wuxianggujun.tinaide.core.editorview.EditorOverlaysIntegrationTest"
```

```bash
./gradlew :core:editor-view:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wuxianggujun.tinaide.core.editorview.EditorCompletionPopupInstrumentationTest,com.wuxianggujun.tinaide.core.editorview.EditorSharedPopupInstrumentationTest
```

其中：

- 第一组覆盖 popup 组件 smoke、共享 anchor/layout 回归、`EditorOverlays` 组合场景。
- 第二组覆盖设备侧补全框、签名提示、选择菜单 popup 的稳定 tag 与交互回归。

## 相关指南

- [LSP 调试指南](../guides/LSP-Debug-Guide.md) - LSP 调试方法
- [远程 LSP 使用指南](../guides/Remote-LSP-Guide.md) - 远程 LSP 功能使用
