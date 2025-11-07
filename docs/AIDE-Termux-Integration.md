# AIDE-Termux 集成说明

## 概述

TinaIDE 现在集成了 AIDE-Termux，这是一个完整的 Termux 终端模拟器实现。

## 集成的模块

从 `external/AIDE-Termux` 引入了以下模块：

- `:aide-termux-app` - Termux 主应用模块
- `:aide-terminal-emulator` - 终端模拟器核心
- `:aide-terminal-view` - 终端视图组件
- `:aide-termux-shared` - 共享工具类

## 使用方式

### 打开终端

在 MainActivity 中点击"打开终端"菜单项，会启动 `com.termux.app.TermuxActivity`。

```kotlin
startActivity(Intent(this, com.termux.app.TermuxActivity::class.java))
```

### 权限要求

AndroidManifest.xml 中已添加必要权限：
- INTERNET
- ACCESS_NETWORK_STATE  
- WAKE_LOCK
- VIBRATE
- FOREGROUND_SERVICE
- FOREGROUND_SERVICE_SPECIAL_USE
- 存储权限

### 组件注册

- `TermuxActivity` - 终端界面
- `TermuxService` - 后台服务，管理终端会话

## 与原 ReTerminal 方案的区别

| 特性 | ReTerminal (已废弃) | AIDE-Termux (当前) |
|------|---------------------|-------------------|
| 实现方式 | 自己封装 proot + Alpine | 完整的 Termux 实现 |
| 包管理 | 无 | 支持 apt/pkg |
| 维护成本 | 高 | 低（使用成熟方案） |
| 功能完整性 | 基础 | 完整 |

## 目录结构

```
app/src/main/java/com/wuxianggujun/tinaide/terminal/
├── virtualkeys/           # 虚拟按键相关（保留）
└── RuntimeDownloader.kt   # 运行时下载器（保留）
```

自定义的终端实现已全部移除：
- ~~TerminalActivity.kt~~
- ~~TerminalBackEnd.kt~~
- ~~TerminalSessionManager.kt~~
- ~~TerminalService.kt~~

## 首次运行

AIDE-Termux 会在首次运行时自动初始化 Termux 环境，包括：
- 下载 bootstrap
- 设置 $PREFIX 目录
- 安装基础包

无需手动准备 proot、libtalloc.so.2、alpine.tar.gz 等资源。

## 参考

- AIDE-Termux 源码：https://github.com/AndroidIDE-CN/AIDE-Termux
- Termux 官方：https://termux.dev/
