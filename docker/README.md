# Docker 构建文档索引

> 更新日期：2026-06-08

本目录只服务维护者重建运行资产、PRoot、rsync 和第三方 native 包。普通 App 构建不需要 Docker；默认编译 / LSP 链路仍是 `native tina-toolchain + Android sysroot`。

## 入口

- [PRoot Builder](proot-build/README.md)：从 Termux 源码编译 PRoot、loader 和 talloc。
- [Rsync Docker Builder](rsync-build/README.md)：构建支持 16KB 页面对齐的 Android rsync 二进制。
- [TinaIDE Package Builder](tinaide-pkg/README.md)：编译 zlib、OpenSSL、curl、libgit2、SDL3 等 native 依赖包。
- `toolchain-builder/`：维护 tina-toolchain 相关 Docker 构建脚本；以目录内脚本和当前构建配置为准。

## 状态说明

- Docker 文档属于维护者工具文档，不是用户运行时文档。
- 涉及当前构建事实时，优先校对 `app/build.gradle.kts`、`build-logic/convention/**`、`docs/toolchain-build-guide.md` 和 `docs/documentation-status.md`。
- 不要把这里的 PRoot / Docker 流程理解为 App 默认 C/C++ 编译宿主。
