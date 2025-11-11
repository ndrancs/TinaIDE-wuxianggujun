# LLVM 构建系统重构计划

## 🎯 重构目标

1. **目录结构清晰化**：构建产物与项目依赖分离
2. **命名规范化**：从 `embedded-ndk` 改为 `llvm-build`，反映实际内容
3. **职责明确化**：构建和集成步骤分离

## 📊 当前问题

### 问题 1：目录混乱

```
❌ 当前（错误）
external/
├── embedded-ndk-libs/        # Docker 构建产物，不应该在这里
│   ├── arm64-v8a/
│   └── x86_64/
└── sora-editor/              # 真正的第三方依赖

✅ 改进后（正确）
docker/
└── llvm-build/
    └── build-output/         # 构建产物临时目录
        ├── arm64-v8a/
        └── x86_64/

external/
└── sora-editor/              # 只保留真正的第三方依赖
```

### 问题 2：命名过时

- `embedded-ndk` → 这是 NDK 移植时代的名字
- 现在使用 LLVM/Clang，应该改为 `llvm-build` 或 `embedded-llvm`

### 问题 3：构建和集成耦合

```
❌ 当前流程
Docker 构建 → 直接输出到 external/ → App 引用

✅ 改进流程
Docker 构建 → build-output/ → 同步脚本 → app/src/main/
```

## 🔄 重构步骤

### Phase 1: 重命名目录和文件

#### 1.1 目录重命名

```powershell
# 1. 重命名 Docker 构建目录
Rename-Item docker/embedded-ndk docker/llvm-build

# 2. 删除 external 下的构建产物（不需要了）
Remove-Item external/embedded-ndk-libs -Recurse -Force
```

#### 1.2 文件重命名

```powershell
# tools/ 目录
Rename-Item tools/sync-embedded-ndk.ps1 tools/sync-llvm-build.ps1
# tools/sync-llvm-headers.ps1 名字已经正确，保持不变
```

#### 1.3 脚本内部路径更新

需要更新的脚本：
- `docker/llvm-build/build-local.ps1`
- `docker/llvm-build/sync-to-app.ps1`
- `docker/llvm-build/clean-local.ps1`
- `docker/llvm-build/fetch-clang-headers.ps1`
- `tools/sync-llvm-build.ps1`
- `tools/sync-llvm-headers.ps1`

### Phase 2: 修改构建输出路径

#### 2.1 修改 `build-local.ps1`

```powershell
# 旧路径
$OutBaseLibs = Join-Path $root 'external/embedded-ndk-libs'

# 新路径
$OutBaseLibs = Join-Path $root 'docker/llvm-build/build-output'
```

#### 2.2 修改输出目录结构

```
docker/llvm-build/build-output/
├── arm64-v8a/
│   ├── libs/
│   │   └── arm64-v8a/
│   │       ├── libLLVM-17.so
│   │       ├── libclang-cpp.so
│   │       └── libc++_shared.so
│   ├── sysroot/
│   │   ├── usr/include/
│   │   └── usr/lib/
│   ├── include/              # Clang C++ 头文件（开发期使用）
│   ├── MANIFEST
│   └── SHA256SUMS
└── x86_64/
    └── ... (同上)
```

### Phase 3: 更新同步脚本

#### 3.1 修改 `tools/sync-llvm-build.ps1`

```powershell
Param(
  [ValidateSet('arm64-v8a','x86_64')]
  [string]$Abi = 'arm64-v8a',
  [int]$ApiLevel = 24,
  [string]$BuildOutputRoot = 'docker/llvm-build/build-output',  # 新路径
  [string]$AppJniLibs = 'app/src/main/jniLibs',
  [string]$AppAssetsSysroot = 'app/src/main/assets/sysroot'
)

# 从构建产物目录读取
$srcBase = Join-Path $root "$BuildOutputRoot/$Abi"
```

#### 3.2 同步逻辑保持不变

```powershell
# 复制 .so 文件
Copy-Item "$srcBase/libs/$Abi/*.so" -Destination "$AppJniLibs/$Abi/"

# 镜像 sysroot
robocopy "$srcBase/sysroot" "$AppAssetsSysroot" /MIR /NFL /NDL /NJH /NJS
```

### Phase 4: 更新 `.gitignore`

```gitignore
# 旧规则（删除）
# external/embedded-ndk/
# external/embedded-ndk-libs/

# 新规则（添加）
# LLVM 构建产物（临时文件，不提交）
docker/llvm-build/build-output/
docker/llvm-build/dev-work/

# App 集成的库文件（由同步脚本生成，不提交）
app/src/main/jniLibs/arm64-v8a/libLLVM*.so
app/src/main/jniLibs/arm64-v8a/libclang*.so
app/src/main/jniLibs/arm64-v8a/liblld*.so
app/src/main/jniLibs/arm64-v8a/libc++_shared.so
app/src/main/jniLibs/x86_64/libLLVM*.so
app/src/main/jniLibs/x86_64/libclang*.so
app/src/main/jniLibs/x86_64/liblld*.so
app/src/main/jniLibs/x86_64/libc++_shared.so

# Sysroot（由同步脚本生成，不提交）
app/src/main/assets/sysroot/
```

### Phase 5: 更新文档

需要更新的文档：
- `docs/CLANG_INTEGRATION_ROADMAP.md` - 主路线图
- `docs/EMBEDDED_CLANG_STATUS.md` - 状态报告
- `docs/EMBEDDED_NDK_SHARED_LIBS.md` - 可以重命名为 `LLVM_SHARED_LIBS.md`
- `docs/EMBEDDED_NDK_TOOLS.md` - 可以重命名为 `LLVM_BUILD_TOOLS.md`
- `docs/EMBEDDED_NDK_TOOLS_DOCKER.md` - 可以重命名为 `LLVM_BUILD_DOCKER.md`

## 📝 详细重构清单

### Step 1: 备份当前状态 ✅

```powershell
# 创建备份
git checkout -b refactor/llvm-build-structure
git commit -am "backup: 重构前的状态"
```

### Step 2: 重命名目录 ✅

```powershell
# 1. 重命名 docker 目录
git mv docker/embedded-ndk docker/llvm-build

# 2. 删除 external 下的构建产物
Remove-Item external/embedded-ndk-libs -Recurse -Force -ErrorAction SilentlyContinue
```

### Step 3: 重命名脚本 ✅

```powershell
git mv tools/sync-embedded-ndk.ps1 tools/sync-llvm-build.ps1
```

### Step 4: 更新脚本内容 🔄

#### 4.1 `docker/llvm-build/build-local.ps1`

**修改点**：
1. 输出路径从 `external/embedded-ndk-libs` 改为 `docker/llvm-build/build-output`
2. 移除 exec 模式相关代码（暂不需要）
3. 更新注释和变量名

```powershell
# 关键修改
$OutBasePath = Join-Path $root 'docker/llvm-build/build-output'
```

#### 4.2 `docker/llvm-build/sync-to-app.ps1`

**修改点**：
1. 源路径从 `external/embedded-ndk-libs` 改为 `docker/llvm-build/build-output`
2. 更新文档字符串

#### 4.3 `docker/llvm-build/clean-local.ps1`

**修改点**：
1. 清理路径改为 `docker/llvm-build/build-output`
2. 移除 exec 模式相关代码
3. 添加清理 app/jniLibs 和 app/assets/sysroot 的选项

#### 4.4 `tools/sync-llvm-build.ps1`

**修改点**：
1. 参数 `$EmbeddedRoot` 改为 `$BuildOutputRoot`
2. 默认路径 `external/embedded-ndk-libs` 改为 `docker/llvm-build/build-output`
3. 更新注释

#### 4.5 `tools/sync-llvm-headers.ps1`

**修改点**：
1. Docker 工作目录路径从 `docker/embedded-ndk/dev-work` 改为 `docker/llvm-build/dev-work`

### Step 5: 更新 `.gitignore` ✅

```gitignore
# LLVM 构建系统（临时文件，不提交）
docker/llvm-build/build-output/
docker/llvm-build/dev-work/

# App 集成的 LLVM 库（由同步脚本生成，不提交）
app/src/main/jniLibs/**/libLLVM*.so
app/src/main/jniLibs/**/libclang*.so
app/src/main/jniLibs/**/liblld*.so
app/src/main/jniLibs/**/libc++_shared.so

# Sysroot（由同步脚本生成，不提交）
app/src/main/assets/sysroot/
```

### Step 6: 更新文档 📚

#### 6.1 重命名文档文件

```powershell
git mv docs/EMBEDDED_NDK_SHARED_LIBS.md docs/LLVM_SHARED_LIBS.md
git mv docs/EMBEDDED_NDK_TOOLS.md docs/LLVM_BUILD_TOOLS.md
git mv docs/EMBEDDED_NDK_TOOLS_DOCKER.md docs/LLVM_BUILD_DOCKER.md
```

#### 6.2 更新文档内容

全局替换：
- `embedded-ndk` → `llvm-build`
- `EMBEDDED_NDK` → `LLVM_BUILD`
- `Embedded NDK` → `LLVM Build`

特别更新 `CLANG_INTEGRATION_ROADMAP.md`：
- 目录结构图
- 快速开始命令
- 所有脚本路径引用

### Step 7: 更新 Dockerfile 和构建脚本 🐳

#### 7.1 `docker/llvm-build/Dockerfile.dev`

**修改点**：
- 容器镜像名从 `embedded-ndk-dev` 改为 `llvm-build-dev`
- 注释更新

#### 7.2 `docker/llvm-build/build-local.ps1`

**修改点**：
- `$ContainerName` 默认值从 `tina-ndk-dev` 改为 `tina-llvm-build`
- 镜像标签从 `embedded-ndk-dev` 改为 `llvm-build-dev`

## 🧪 验证步骤

### 1. 清理旧产物

```powershell
# 清理构建产物
./docker/llvm-build/clean-local.ps1 -RemoveJniLibs -RemoveAssets -Yes

# 清理 Docker 镜像
docker rmi embedded-ndk-dev:r26d -f
docker rmi llvm-build-dev:r26d -f
```

### 2. 重新构建

```powershell
# 构建 arm64-v8a
./docker/llvm-build/build-local.ps1 -Mode libs -Abi arm64-v8a -ApiLevel 24
```

**期望结果**：
- 产物位于 `docker/llvm-build/build-output/arm64-v8a/`
- 包含 `libs/`、`sysroot/`、`MANIFEST`、`SHA256SUMS`

### 3. 同步到项目

```powershell
# 同步到 app
pwsh tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24
```

**期望结果**：
- `app/src/main/jniLibs/arm64-v8a/` 包含 `.so` 文件
- `app/src/main/assets/sysroot/` 包含完整 sysroot

### 4. 构建 APK

```bash
./gradlew :app:assembleDebug
```

**期望结果**：
- 编译成功
- APK 包含 LLVM 库和 sysroot

### 5. 运行验证

```bash
./gradlew :app:installDebug
```

在应用中点击"编译"，期望看到：
- clang-cpp loaded: true
- clang/LLVM version: 17.0.6
- sysroot: /data/data/.../files/sysroot

## 📋 完整重构脚本

创建一个自动化重构脚本 `tools/refactor-to-llvm-build.ps1`：

```powershell
#!/usr/bin/env pwsh
# 自动化重构脚本

Param([switch]$DryRun)

$ErrorActionPreference = 'Stop'

function Info($msg) { Write-Host "[i] $msg" -ForegroundColor Cyan }
function Warn($msg) { Write-Host "[!] $msg" -ForegroundColor Yellow }
function Err($msg)  { Write-Host "[x] $msg" -ForegroundColor Red }

$root = Resolve-Path (Join-Path $PSScriptRoot '..')

Info "开始 LLVM 构建系统重构..."

# Step 1: 检查 git 状态
$status = git status --porcelain
if ($status) {
    Err "工作目录不干净，请先提交或暂存更改"
    exit 1
}

# Step 2: 创建备份分支
if (-not $DryRun) {
    Info "创建备份分支..."
    git checkout -b refactor/llvm-build-structure
}

# Step 3: 重命名目录
Info "重命名目录..."
if (-not $DryRun) {
    git mv docker/embedded-ndk docker/llvm-build
}

# Step 4: 重命名脚本
Info "重命名脚本..."
if (-not $DryRun) {
    git mv tools/sync-embedded-ndk.ps1 tools/sync-llvm-build.ps1
}

# Step 5: 重命名文档
Info "重命名文档..."
if (-not $DryRun) {
    git mv docs/EMBEDDED_NDK_SHARED_LIBS.md docs/LLVM_SHARED_LIBS.md
    git mv docs/EMBEDDED_NDK_TOOLS.md docs/LLVM_BUILD_TOOLS.md
    git mv docs/EMBEDDED_NDK_TOOLS_DOCKER.md docs/LLVM_BUILD_DOCKER.md
}

# Step 6: 更新文件内容（需要手动完成）
Warn "接下来需要手动更新以下文件："
Warn "  1. docker/llvm-build/build-local.ps1"
Warn "  2. docker/llvm-build/sync-to-app.ps1"
Warn "  3. docker/llvm-build/clean-local.ps1"
Warn "  4. docker/llvm-build/fetch-clang-headers.ps1"
Warn "  5. tools/sync-llvm-build.ps1"
Warn "  6. tools/sync-llvm-headers.ps1"
Warn "  7. .gitignore"
Warn "  8. docs/*.md"

Info "重构完成！"
Info "请按照 docs/REFACTOR_LLVM_BUILD.md 完成剩余步骤"
```

## 🎯 重构后的优势

1. **职责清晰**：
   - Docker 只负责构建 → `docker/llvm-build/build-output/`
   - 同步脚本负责集成 → `app/src/main/`
   - external 只放真正的第三方库

2. **命名准确**：
   - `llvm-build` 准确反映实际内容
   - 避免 NDK 的误导性

3. **易于管理**：
   - 构建产物在临时目录，随时可删除重建
   - App 目录由同步脚本生成，保持一致性
   - `.gitignore` 更清晰

4. **灵活性**：
   - 可以同时保留多个 ABI 的构建产物
   - 可以选择性同步到 App
   - 便于切换不同版本的 LLVM

## ⚠️ 注意事项

1. **破坏性变更**：此重构会改变目录结构，需要团队成员同步
2. **CI/CD 更新**：如果有 CI 配置，需要同步更新路径
3. **文档同步**：所有文档都需要更新路径引用
4. **逐步迁移**：建议在新分支上完成重构，测试无误后再合并

## 📅 时间线

- **准备阶段**（1 小时）：备份、创建分支
- **重命名阶段**（1 小时）：目录和文件重命名
- **代码更新**（2-3 小时）：更新所有脚本和文档
- **测试验证**（1-2 小时）：完整构建和运行测试
- **总计**：约 5-7 小时

---

**准备好开始重构了吗？我可以帮你逐步执行这些步骤！**
