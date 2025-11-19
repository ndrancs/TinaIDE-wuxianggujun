# CMake 项目支持

## 概述

TinaIDE 现在支持编译 CMake 项目！系统会自动检测项目根目录下的 `CMakeLists.txt` 文件，并使用 sysroot 中预编译的 CMake 和 Ninja 工具进行构建。

## 功能特性

### 1. 自动检测构建系统
- 如果项目根目录存在 `CMakeLists.txt`，自动使用 CMake 构建
- 否则回退到原有的单文件编译模式

### 2. CMake 项目编译流程
1. **检测工具**: 验证 sysroot 中的 cmake 和 ninja 可执行文件
2. **生成工具链文件**: 自动创建 Android 交叉编译工具链配置
3. **CMake 配置**: 运行 `cmake -S <source> -B <build> -G Ninja`
4. **CMake 构建**: 运行 `cmake --build <build>`
5. **输出查找**: 自动查找生成的 .so、.a 或可执行文件

### 3. 工具链配置
自动生成的工具链文件包含：
- Sysroot 路径配置
- Clang/Clang++ 编译器路径
- 目标架构 (aarch64-linux-android 或 x86_64-linux-android)
- 编译器标志 (-fPIC, -fexceptions, -fcxx-exceptions)
- 搜索路径配置

## 使用方法

### 创建 CMake 项目
1. 在项目对话框中选择 "C++(CMake)" 类型
2. 系统会自动生成包含 CMakeLists.txt 的项目模板

### 编译 CMake 项目
1. 打开包含 CMakeLists.txt 的项目
2. 点击"编译"按钮
3. 系统自动检测并使用 CMake 构建
4. 查看输出面板获取详细的构建日志

### 项目结构示例
```
my_project/
├── CMakeLists.txt          # CMake 配置文件
├── src/
│   └── main.cpp           # 源代码
├── include/               # 头文件目录
└── build/                 # 构建输出（自动生成）
```

### CMakeLists.txt 示例
```cmake
cmake_minimum_required(VERSION 3.10)
project(MyProject)

# 添加可执行文件
add_executable(MyProject src/main.cpp)

# 或者添加库
# add_library(MyLib SHARED src/mylib.cpp)
```

## 技术实现

### 核心类
- **CMakeProjectCompiler**: CMake 项目编译器
  - 负责 CMake 配置和构建流程
  - 生成工具链文件
  - 执行命令并捕获输出
  
- **CompileProjectUseCase**: 编译用例协调器
  - 检测项目类型（CMake vs 单文件）
  - 调用相应的编译器
  - 统一的进度和日志接口

### 构建流程
```
CompileProjectUseCase.execute()
    ↓
检测 CMakeLists.txt?
    ↓ 是
compileCMakeProject()
    ↓
CMakeProjectCompiler.compile()
    ↓
1. 检查 cmake/ninja
2. 创建工具链文件
3. CMake 配置阶段
4. CMake 构建阶段
5. 查找输出文件
```

## 环境要求

### Sysroot 内容
确保 sysroot.zip 包含：
- `usr/bin/cmake` - CMake 可执行文件
- `usr/bin/ninja` - Ninja 构建工具
- `usr/bin/clang` - C 编译器
- `usr/bin/clang++` - C++ 编译器
- `usr/include/` - 系统头文件
- `usr/lib/` - 系统库文件

### 构建 Sysroot
使用项目提供的 Docker 脚本：
```powershell
# 构建包含 CMake 和 Ninja 的 sysroot
pwsh ./docker/llvm-build/build-local.ps1 -Abi arm64-v8a -ApiLevel 24

# 同步到 App
pwsh ./tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24
```

## 限制和注意事项

1. **超时限制**: 单次构建最多 60 秒
2. **并行构建**: 默认使用 4 个并行任务
3. **构建类型**: 默认为 Debug 模式
4. **目标架构**: 根据设备 ABI 自动选择
5. **环境变量**: 自动设置 PATH 和 LD_LIBRARY_PATH

## 调试和日志

### 查看详细日志
所有构建输出都会显示在输出面板中，包括：
- CMake 配置输出
- Ninja 构建输出
- 错误和警告信息
- 生成的文件列表

### 构建文件位置
```
<app_files>/build/<project_name>/
├── cmake-build/           # CMake 构建目录
│   ├── CMakeCache.txt
│   ├── build.ninja
│   └── <output_files>
└── android-toolchain.cmake  # 工具链文件
```

## 未来改进

- [ ] 支持自定义 CMake 参数
- [ ] 支持 Release 构建模式
- [ ] 支持多目标构建
- [ ] 集成 CTest 测试框架
- [ ] 支持 CMake 预设 (CMakePresets.json)
- [ ] 增量构建优化
- [ ] 构建缓存管理

## 相关文档

- [LLVM 构建工具](LLVM_BUILD_TOOLS.md)
- [Clang 集成路线图](CLANG_INTEGRATION_ROADMAP.md)
- [项目模板](../app/src/main/assets/templates/)
