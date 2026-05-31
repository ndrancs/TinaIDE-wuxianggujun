# 自研 Linux 发行版运行时

> 更新日期：2026-04-28

本文记录 TinaIDE 当前 Linux rootfs 管理入口。当前实现已经收敛到
:core:linux-distro + :core:proot，不再保留旧脚本模块、灰度开关或兼容分支。

## 当前结论

- :core:linux-distro 是发行版 manifest、下载、校验、解包和注册的事实源。
- SelfHostedLinuxDistroRuntime 是 core:proot 唯一安装运行时。
- RootfsDistroRuntime 是设置页中性门面，只读取自研 manifest。
- PRootBootstrap 默认安装 SelfHostedLinuxDistroRuntime.DEFAULT_DISTRO_ID。
- RootfsSourceType 只保留 LINUX_DISTRO，旧来源不再兼容迁移。

## 模块边界

:core:linux-distro
  - manifest.json：Android assets 中的官方 rootfs 清单
  - LinuxDistroManager：下载、校验、解包、安装状态机
  - LinuxDistroInstallLayout：cache / staging / installed-rootfs 布局

:core:proot
  - SelfHostedLinuxDistroRuntime：连接 manager 与 RootfsProfileStore
  - RootfsDistroRuntime：设置页发行版列表和安装门面
  - PRootBootstrap：工作区安装页和自动引导入口
  - PRootEnvironment：LinuxEnvironment 实现、rootfs 健康检查与清理入口

## 安装流程

1. 用户在设置页或工作区安装页显式请求 Linux 环境。
2. PRootBootstrap 创建 SelfHostedLinuxDistroRuntime。
3. 运行时从 :core:linux-distro assets 加载 manifest。
4. LinuxDistroManager 下载 rootfs、校验 SHA-256、解包到 linux-distro/installed-rootfs。
5. LinuxDistroRootfsBootstrapper 进入新 rootfs，补齐 bash/curl/tar/xz/file/ca-certificates 等基础命令。
6. LinuxDistroRootfsProfileMapper 写入 RootfsProfileStore，并设置为活动 profile。
7. 工作区安装页继续安装 PRoot guest toolchain、Android sysroot 和 native toolchain。

## 清理结果

- 已移除旧 Gradle include 和 core:proot 对旧模块的依赖。
- 已移除旧运行时类、host tool wrapper、旧 catalog 与对应单元测试。
- 已移除运行时模式配置项和开发者灰度开关。
- 已移除 vendored 脚本 assets，避免继续携带旧许可证风险。
- 依赖安装页组件名改为 linux-distro-runtime。

## 运行时自检

- PRootEnvironment.checkLinuxDistroHealth() 是当前活动 rootfs 的结构化自检入口。
- LinuxDistroRootfsHealthChecker 会检查 rootfs 可用性、包管理器命令、包管理器版本、必需基础命令、可选基础命令、uname -m 和 /etc/os-release。
- 可选 proot 缺失不会阻塞 isUsable；bash/curl/tar/xz/file/update-ca-certificates 等必需命令缺失会标记不可用。

## Manifest 维护

- 官方源数据先通过 tools/linux-distro/linux-distros.lock.json 锁定。
- 生成脚本：tools/linux-distro/generate-linux-distro-manifest.ps1。
- 产物：core/linux-distro/src/main/assets/linux-distro/manifest.json。
- 当前 manifest 已支持 Alpine Linux 3.23 与 Ubuntu 24.04 LTS。
- 新增发行版时必须记录 URL、架构、版本、大小和 SHA-256。
- 刷新官方元数据只允许走发行版官方源，不复制外部脚本项目字段结构。

## 验收标准

- 代码中不再引用旧脚本运行时类、旧 runtime mode 配置或旧 package name。
- settings.gradle.kts 不再 include 旧模块。
- core:proot 只依赖 :core:linux-distro 和现有运行时模块。
- RootfsProfileStore 只清理 linux-distro/installed-rootfs 管理目录。
- 文档只描述自研 Linux 发行版管理器作为当前实现入口。
