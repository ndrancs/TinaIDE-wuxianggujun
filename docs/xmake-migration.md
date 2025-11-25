# C++ 项目类型支持

## 概述

TinaIDE 支持两种 C++ 项目类型，满足不同的开发需求：

1. **C++ 项目 (xmake)** - 完整的项目结构，使用 xmake 构建系统（通过 JNI 调用 libxmake_runner.so）
2. **C++ 单文件** - 简单的单文件项目，使用内置 clang 编译器（通过 JNI 调用）

## 编译原理

由于 Android 高版本的 SELinux 限制，无法直接执行命令行工具。TinaIDE 采用 JNI 方式调用编译工具：

- **xmake 项目**：通过 `libxmake_runner.so` 调用 xmake 构建
- **单文件项目**：通过 `libnative_compiler.so` 调用 clang 编译

## 项目类型

### 1. C++ 项目 (xmake)

使用 xmake 构建系统的完整项目，适合：
- 多文件项目
- 需要依赖管理
- 复杂的构建配置

**编译流程：**
1. 检测 `xmake.lua` 文件
2. 加载 `libxmake_runner.so`
3. 调用 `XmakeRunner.build()` 执行构建

### 2. C++ 单文件

简单的单文件项目，只包含一个 `main.cpp`，适合：
- 快速测试代码
- 学习 C++ 基础
- 简单的算法练习

**编译流程：**
1. 扫描项目中的 .cpp/.c 文件
2. 使用 `NativeCompiler.emitObj()` 编译为 .o
3. 使用 `NativeCompiler.linkSoMany()` 链接为 .so
4. 使用 `NativeCompiler.runSharedIsolated()` 运行

## 项目结构

### C++ 项目 (xmake)

```
项目名/
├── xmake.lua          # xmake 配置文件
├── src/
│   └── main.cpp       # 主程序源文件
├── include/           # 头文件目录
├── README.md          # 项目说明
└── .gitignore         # Git 忽略文件
```

### C++ 单文件

```
项目名/
├── main.cpp           # 主程序源文件
└── README.md          # 项目说明
```

## 配置示例

### xmake.lua 配置

```lua
-- xmake 项目配置
set_project("项目名")
set_version("1.0.0")

-- 设置 C++ 标准
set_languages("c++17")

-- 定义目标
target("项目名")
    set_kind("binary")
    add_files("src/*.cpp")
    add_includedirs("include")
```

### 单文件项目示例

```cpp
#include <iostream>

int main() {
    std::cout << "Hello, World!" << std::endl;
    return 0;
}
```

## 常用命令

### xmake 项目

```bash
# 构建项目
xmake

# 运行项目
xmake run

# 清理构建
xmake clean

# 配置 Android 平台
xmake f -p android -a arm64-v8a

# 重新配置
xmake f -c
```

### 单文件项目

```bash
# 直接编译
g++ main.cpp -o 项目名

# 或使用 clang++
clang++ main.cpp -o 项目名

# 运行
./项目名
```

## 使用方法

### 创建项目

1. 在 TinaIDE 中点击"新建项目"
2. 输入项目名称和路径
3. 选择项目类型：
   - **C++ 项目 (xmake)** - 完整项目结构
   - **C++ 单文件** - 简单单文件
4. 点击"创建"

### 选择建议

- **学习 C++ 基础？** 选择"C++ 单文件"
- **开发完整应用？** 选择"C++ 项目 (xmake)"
- **需要多个源文件？** 选择"C++ 项目 (xmake)"
- **快速测试代码？** 选择"C++ 单文件"

## 编译集成

TinaIDE 的编译功能会自动检测项目类型：
- 如果存在 `xmake.lua`，使用 xmake 构建
- 如果是单个 `.cpp` 文件，使用 g++/clang++ 直接编译

## 注意事项

### xmake 项目
- xmake 需要在设备上安装，可以通过 Termux 安装：`pkg install xmake`
- 首次构建可能需要下载工具链和依赖包
- 建议使用 xmake 2.7.0 或更高版本

### 单文件项目
- 需要安装 g++ 或 clang++：`pkg install clang`
- 适合简单的程序，复杂项目建议使用 xmake
- 可以随时转换为 xmake 项目
