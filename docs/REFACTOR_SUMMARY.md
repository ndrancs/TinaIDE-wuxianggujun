# LLVM 构建系统重构总结

## ✅ 重构完成

重构日期：2025-11-11

### 🎯 重构目标

1. **目录结构清晰化**：构建产物与项目依赖分离
2. **命名规范化**：从 `embedded-ndk` 改为 `llvm-build`
3. **职责明确化**：构建和集成步骤分离
4. **简化模式**：移除不需要的 `exec` 模式，只保留 `libs` 模式

## 📊 变更内容

### 1. 目录重命名

```bash
# 已完成
docker/llvm-build/          → docker/llvm-build/
tools/sync-llvm-build.ps1   → tools/sync-llvm-build.ps1
```

### 2. 文档重命名

```bash
# 已完成
docs/LLVM_BUILD_SHARED_LIBS.md      → docs/LLVM_SHARED_LIBS.md
docs/LLVM_BUILD_TOOLS.md            → docs/LLVM_BUILD_TOOLS.md
docs/LLVM_BUILD_TOOLS_DOCKER.md     → docs/LLVM_BUILD_DOCKER.md
docs/EMBEDDED_CLANG_STATUS.md         → docs/LLVM_CLANG_STATUS.md
```

### 3. 核心路径变更

#### 构建输出路径

```
旧路径：docker/llvm-build/build-output/<abi>/
新路径：docker/llvm-build/build-output/<abi>/
```

#### Docker 工作目录

```
旧路径：docker/llvm-build/dev-work/
新路径：docker/llvm-build/dev-work/
```

### 4. 脚本更新

#### `build-local.ps1`
- ✅ 移除 `-Mode` 参数（只保留默认的 libs 模式）
- ✅ 移除 `exec` 模式所有代码
- ✅ 输出路径改为 `docker/llvm-build/build-output/`
- ✅ 容器名从 `tina-llvm-build` 改为 `tina-llvm-build`
- ✅ 镜像名从 `embedded-ndk-dev` 改为 `llvm-build-dev`
- ✅ Docker 挂载从 `/hostout-libs` 改为 `/hostout`

#### `sync-llvm-build.ps1`
- ✅ 参数 `$EmbeddedRoot` 改为 `$BuildOutputRoot`
- ✅ 默认路径改为 `docker/llvm-build/build-output`
- ✅ 更新所有路径引用

#### `sync-llvm-headers.ps1`
- ✅ `$DockerRoot` 默认改为 `docker/llvm-build/dev-work`
- ✅ `$DestRoot` 默认改为 `docker/llvm-build/build-output/common-headers`

#### `clean-local.ps1`
- ✅ 移除 `-Mode` 参数
- ✅ 移除 `Clean-ExternalLibs` 和 `Clean-ExternalExec` 函数
- ✅ 新增 `Clean-BuildOutput` 函数，清理 `docker/llvm-build/build-output`
- ✅ 简化 `Clean-Assets`，只清理 sysroot
- ✅ 镜像名匹配改为 `llvm-build-dev:*`

#### `sync-to-app.ps1`
- ✅ 移除 `-Mode` 参数
- ✅ 移除 `exec` 模式所有代码
- ✅ 更新源路径为 `docker/llvm-build/build-output`
- ✅ 简化 `Detect-Abi` 函数

#### `fetch-clang-headers.ps1`
- ✅ 容器名改为 `tina-llvm-build`
- ✅ 路径改为 `docker/llvm-build/build-output`
- ✅ Docker 挂载路径从 `/hostout-libs` 改为 `/hostout`

#### `Dockerfile.dev`
- ✅ 更新注释说明

### 5. `.gitignore` 更新

```gitignore
# 旧规则（已删除）
# docker/llvm-build/dev-work/
# docker/llvm-build/build-output/
# docker/llvm-build/build-output/

# 新规则（已添加）
docker/llvm-build/dev-work/
docker/llvm-build/build-output/

# App 集成的 LLVM 库（由同步脚本生成，不提交）
app/src/main/jniLibs/**/libLLVM*.so
app/src/main/jniLibs/**/libclang*.so
app/src/main/jniLibs/**/liblld*.so
app/src/main/jniLibs/**/libc++_shared.so

# Sysroot（由同步脚本生成，不提交）
app/src/main/assets/sysroot/
```

## 🔄 新的工作流程

### 构建流程

```powershell
# 1. 构建 LLVM (输出到临时目录)
./docker/llvm-build/build-local.ps1 -Abi arm64-v8a -ApiLevel 24
# 产物位置：docker/llvm-build/build-output/arm64-v8a/

# 2. 同步到项目
./tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24
# 复制到：app/src/main/jniLibs/arm64-v8a/
#        app/src/main/assets/sysroot/

# 3. 构建 APK
./gradlew assembleDebug
```

### 清理流程

```powershell
# 清理构建产物和 App 集成文件
./docker/llvm-build/clean-local.ps1 -Abi arm64-v8a -RemoveJniLibs -RemoveAssets -Yes

# 清理所有产物和 Docker 镜像
./docker/llvm-build/clean-local.ps1 -Abi all -RemoveJniLibs -RemoveAssets -PruneImages -Yes
```

## 📁 新的目录结构

```
TinaIDE/
├── docker/
│   └── llvm-build/                    # ✨ 重命名
│       ├── Dockerfile.dev
│       ├── build-local.ps1            # ✨ 简化，移除 exec 模式
│       ├── sync-to-app.ps1            # ✨ 简化
│       ├── clean-local.ps1            # ✨ 简化
│       ├── fetch-clang-headers.ps1    # ✨ 更新路径
│       ├── dev-work/                  # Docker 工作目录（.gitignore）
│       └── build-output/              # ✨ 新增：构建产物（.gitignore）
│           ├── arm64-v8a/
│           │   ├── libs/
│           │   ├── sysroot/
│           │   ├── include/
│           │   ├── MANIFEST
│           │   └── SHA256SUMS
│           ├── x86_64/
│           └── common-headers/        # Clang C++ 头文件
│
├── tools/
│   ├── sync-llvm-build.ps1            # ✨ 重命名并更新
│   └── sync-llvm-headers.ps1          # ✨ 更新路径
│
├── external/
│   └── sora-editor/                   # ✅ 只保留真正的第三方依赖
│
├── app/src/main/
│   ├── jniLibs/<abi>/                 # 由 sync 脚本复制
│   └── assets/sysroot/                # 由 sync 脚本复制
│
└── docs/
    ├── CLANG_INTEGRATION_ROADMAP.md   # ✨ 新增
    ├── REFACTOR_LLVM_BUILD.md         # ✨ 新增（详细计划）
    ├── REFACTOR_SUMMARY.md            # ✨ 新增（本文档）
    ├── LLVM_SHARED_LIBS.md            # ✨ 重命名
    ├── LLVM_BUILD_TOOLS.md            # ✨ 重命名
    ├── LLVM_BUILD_DOCKER.md           # ✨ 重命名
    └── LLVM_CLANG_STATUS.md           # ✨ 重命名
```

## ✨ 改进成果

### 1. 职责清晰

**构建阶段**：
- Docker 构建 → 输出到 `docker/llvm-build/build-output/`
- 产物保存在临时目录，随时可删除重建

**集成阶段**：
- 同步脚本 → 复制到 `app/src/main/`
- App 目录由脚本生成，保持一致性

### 2. 命名准确

- `llvm-build` 准确反映实际内容（LLVM/Clang）
- 避免 `ndk` 的误导性（不是 NDK 移植，而是 LLVM 集成）

### 3. 简化维护

- 移除不需要的 `exec` 模式
- 所有脚本只处理一种场景，逻辑更清晰
- 减少分支判断，降低错误风险

### 4. 易于理解

- 构建产物在 `docker/llvm-build/build-output/` 一目了然
- `external/` 只放真正的第三方库
- 职责分离，新人更容易理解项目结构

## 🧪 验证步骤

### 1. 清理旧环境

```powershell
# 删除旧的构建产物
Remove-Item docker/llvm-build/build-output -Recurse -Force -ErrorAction SilentlyContinue

# 清理 Docker 镜像
docker rmi llvm-build-dev:r26d -f
```

### 2. 构建测试

```powershell
# 构建 arm64-v8a
./docker/llvm-build/build-local.ps1 -Abi arm64-v8a -ApiLevel 24
```

**期望结果**：
- 产物位于 `docker/llvm-build/build-output/arm64-v8a/`
- 包含 `libs/`、`sysroot/`、`MANIFEST`、`SHA256SUMS`

### 3. 同步测试

```powershell
# 同步到 App
./tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24
```

**期望结果**：
- `app/src/main/jniLibs/arm64-v8a/` 包含 `.so` 文件
- `app/src/main/assets/sysroot/` 包含完整 sysroot

### 4. APK 构建测试

```bash
./gradlew :app:assembleDebug
```

**期望结果**：
- 编译成功
- APK 包含 LLVM 库和 sysroot

### 5. 运行测试

```bash
./gradlew :app:installDebug
```

在应用中点击"编译"，期望看到：
- clang-cpp loaded: true
- clang/LLVM version: 17.0.6
- sysroot: /data/data/.../files/sysroot

## 📝 Git 提交记录

```
6fa8440 refactor: 更新同步脚本路径为 llvm-build
0635a38 refactor: 重命名 embedded-ndk 为 llvm-build，更新构建脚本
2d986f8 chore: 重构前的备份 - 删除旧文档，添加重构计划
```

## 🎯 后续工作

### 待完成的文档更新

需要更新以下文档中的路径引用（全局替换）：

1. **LLVM_SHARED_LIBS.md**
   - `embedded-ndk` → `llvm-build`
   - `docker/llvm-build/build-output` → `docker/llvm-build/build-output`

2. **LLVM_BUILD_TOOLS.md**
   - 同上

3. **LLVM_BUILD_DOCKER.md**
   - 同上

4. **LLVM_CLANG_STATUS.md**
   - 同上

5. **CLANG_INTEGRATION_ROADMAP.md**
   - 更新所有示例命令和路径

### 建议的下一步

1. **创建测试脚本** - 自动化验证构建流程
2. **添加 CI 配置** - 自动测试构建和同步
3. **优化构建时间** - 利用 ccache 和增量构建
4. **文档完善** - 添加常见问题排查指南

## 📚 参考资源

- [CLANG_INTEGRATION_ROADMAP.md](./CLANG_INTEGRATION_ROADMAP.md) - 集成路线图
- [REFACTOR_LLVM_BUILD.md](./REFACTOR_LLVM_BUILD.md) - 详细重构计划
- [LLVM_BUILD_DOCKER.md](./LLVM_BUILD_DOCKER.md) - Docker 构建指南

---

**重构完成日期**：2025-11-11  
**执行人**：TinaIDE 开发团队  
**版本**：v1.0.0
