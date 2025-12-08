# TinaIDE 文档中心

欢迎来到 TinaIDE 文档中心！这里包含了项目的核心技术文档和使用指南。

## 📚 文档导航

### 快速开始
- [快速开始指南](快速开始.md) - 环境准备和首次运行
- [项目 README](../README.md) - 项目概述

### 核心架构
- [架构概览](架构概览.md) - 整体架构设计
- [Clang/LLVM 集成路线图](CLANG_INTEGRATION_ROADMAP.md) - 编译器集成方案
- [Native 链接策略](Native-Linking-Strategies.md) - 原生库链接方案
- [Native 编译运行方案](Native-Compile-Runtime.md) - 编译运行实现

### LSP 语言服务
- [LSP 集成指南](LSP-Integration.md) - LSP 使用方法
- [LSP 架构文档](LSP-Architecture-Major-Refactor.md) - 架构设计
- [LSP 调试指南](LSP-Debug-Guide.md) - 调试方法
- [LSP 架构简化提案](LSP-Architecture-Simplification-Proposal.md) - 简化方案
- [LSP 补全 Bug 分析](LSP-Completion-Bug-Analysis.md) - 问题分析
- [Clangd 补全问题分析](Clangd-Completion-Issue-Analysis.md) - 优化方案

### 开发指南
- [开发指南](开发指南.md) - 开发规范和工作流
- [Material Design 指南](Material-Design-Guide.md) - UI 设计规范
- [底部面板指南](Bottom-Panel-Guide.md) - 底部面板使用

### 未来规划
- [插件系统架构](Plugin-System-Architecture.md) - 插件系统设计
- [Tree-Sitter 重写计划](Tree-Sitter-Rewrite-Plan.md) - 语法高亮重写

## 🎯 项目特性

### 核心功能
- ✅ **嵌入式 Clang/LLVM**: 库模式集成，无需外部工具
- ✅ **单文件编译**: 快速编译 C/C++ 单文件项目
- ✅ **LSP 支持**: clangd 提供智能补全和诊断
- ✅ **Sora Editor**: 强大的代码编辑器
- ✅ **Tree-sitter**: 语法高亮支持

### 支持的架构
- `arm64-v8a` (主要支持)
- `x86_64` (模拟器支持)

## 🚀 快速开始

### 1. 构建工具链
```powershell
pwsh ./docker/llvm-build/build-local.ps1 -Abi arm64-v8a -ApiLevel 24
```

### 2. 同步到项目
```powershell
pwsh ./tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24
```

### 3. 构建 APK
```bash
./gradlew assembleDebug
```

## 📖 技术栈

### 前端
- **Kotlin**: 主要开发语言
- **Material Design 3**: UI 设计规范
- **Sora Editor**: 代码编辑器核心

### 后端
- **C++**: JNI 原生代码
- **Clang/LLVM 17**: 编译器工具链
- **Tree-sitter**: 语法解析

### 构建工具
- **Gradle**: Android 构建系统
- **Docker**: 工具链构建环境
- **PowerShell**: 自动化脚本

## 🔧 开发原则

- **KISS** (Keep It Simple, Stupid): 保持简单
- **YAGNI** (You Aren't Gonna Need It): 只实现需要的功能
- **DRY** (Don't Repeat Yourself): 避免重复
- **SOLID**: 面向对象设计原则

---

**最后更新**: 2025-12-08  
**维护者**: TinaIDE 开发团队
