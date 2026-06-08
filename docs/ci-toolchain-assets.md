# CI Toolchain Assets

> 更新时间：2026-06-08

## 背景

TinaIDE 的 APK 构建需要 `app/src/<abi>/assets/tina-toolchain/current.properties`
声明的 native toolchain 归档包。

这些归档包体积较大，仍保留在 Git LFS 跟踪范围内。但 GitHub Actions
不能依赖 `actions/checkout` 拉取 LFS 对象，否则 Git LFS 带宽耗尽时，
CI 会在 checkout 阶段失败，无法进入 Gradle 构建。

## 当前规则

- `dev-build.yml` 只保留 `workflow_dispatch`，不再随 `dev` push 自动运行。
- `pr-build.yml` 只保留 `workflow_dispatch`，不再随 PR 自动运行 APK 构建。
- `dev-build.yml`、`pr-build.yml` 和 `release.yml` 的 APK 构建 checkout
  都使用 `lfs: false`。
- 构建前统一运行：

```bash
bash tools/ci/restore-tina-toolchain-assets.sh <abi-flavor>
```

脚本会：

1. 读取 `app/src/<abi>/assets/tina-toolchain/current.properties`。
2. 找到当前 ABI 需要的 `full` 或 `base` 归档包。
3. 从 GitHub Release `toolchain-v0.2.4` 下载对应归档包。
4. 使用仓库内的 `.sha256` 文件校验下载结果。
5. 覆盖 checkout 后留下的 Git LFS 指针文件。

## 当前 Release 资产

Release tag：

```text
repo: wuxianggujun/TinaIDE
tag: toolchain-v0.2.4
```

包含资产：

```text
tinaide-toolchain-aarch64-v0.2.4-patched.tar.xz
tinaide-toolchain-aarch64-v0.2.4-patched.sha256
tinaide-toolchain-x86_64-v0.2.4-patched.tar.xz
tinaide-toolchain-x86_64-v0.2.4-patched.sha256
```

## 更新流程

当 toolchain 版本变化时：

1. 重新生成并同步 `app/src/<abi>/assets/tina-toolchain/current.properties`。
2. 上传新版本 tar.xz 和 sha256 到新的 toolchain Release tag。
3. 更新 workflow 中的 `TINA_TOOLCHAIN_RELEASE_TAG`。
4. 本地验证：

```bash
bash tools/ci/restore-tina-toolchain-assets.sh arm64 x86_64
./gradlew :app:verifyTinaToolchainAssets --console=plain
```

## 不要做

- 不要把 APK 构建 workflow 改回 `lfs: true`。
- 不要把 LFS 指针文件打进 APK。
- 不要把 toolchain 旧归档放到 `app/src/**/assets/tina-toolchain/archive/`。
