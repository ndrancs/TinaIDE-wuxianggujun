# CMake 项目测试指南

## 快速测试步骤

### 1. 准备环境
确保你的 sysroot.zip 包含 CMake 和 Ninja：
```bash
# 检查 sysroot 内容
unzip -l app/src/main/assets/sysroot.zip | grep -E "cmake|ninja"
```

应该看到：
```
usr/bin/cmake
usr/bin/ninja
```

### 2. 创建测试项目

#### 方法 A: 通过 UI 创建
1. 打开 TinaIDE
2. 点击"新建项目"
3. 选择项目类型: "C++(CMake)"
4. 输入项目名称: "HelloCMake"
5. 选择项目路径
6. 点击"创建"

#### 方法 B: 手动创建
在设备上创建以下结构：
```
/sdcard/TinaIDE/Projects/HelloCMake/
├── CMakeLists.txt
└── src/
    └── main.cpp
```

**CMakeLists.txt**:
```cmake
cmake_minimum_required(VERSION 3.10)
project(HelloCMake)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(HelloCMake src/main.cpp)
```

**src/main.cpp**:
```cpp
#include <iostream>
#include <string>

int main() {
    std::cout << "Hello from CMake project!" << std::endl;
    std::cout << "C++ Standard: " << __cplusplus << std::endl;
    
    std::string message = "TinaIDE CMake support is working!";
    std::cout << message << std::endl;
    
    return 0;
}
```

### 3. 编译项目

1. 在 TinaIDE 中打开项目
2. 点击"编译"按钮
3. 观察输出面板

#### 预期输出
```
检测到 CMake 项目，使用 CMake 构建系统
=== CMake 项目编译 ===
项目根目录: /sdcard/TinaIDE/Projects/HelloCMake
构建目录: /data/data/com.wuxianggujun.tinaide/files/build/HelloCMake/cmake-build
Sysroot: /data/data/com.wuxianggujun.tinaide/files/sysroot
CMake: /data/data/com.wuxianggujun.tinaide/files/sysroot/usr/bin/cmake
Ninja: /data/data/com.wuxianggujun.tinaide/files/sysroot/usr/bin/ninja
目标架构: arm64-v8a (aarch64-linux-android)
工具链文件: .../android-toolchain.cmake

--- CMake 配置阶段 ---
-- The C compiler identification is Clang ...
-- The CXX compiler identification is Clang ...
-- Configuring done
-- Generating done
-- Build files have been written to: ...

--- CMake 构建阶段 ---
[1/2] Building CXX object CMakeFiles/HelloCMake.dir/src/main.cpp.o
[2/2] Linking CXX executable HelloCMake

=== 构建成功 ===
生成的文件:
  - .../HelloCMake
```

### 4. 验证构建产物

检查构建目录：
```
/data/data/com.wuxianggujun.tinaide/files/build/HelloCMake/cmake-build/
├── CMakeCache.txt
├── CMakeFiles/
├── build.ninja
├── cmake_install.cmake
└── HelloCMake          # 可执行文件
```

## 测试用例

### 测试 1: 简单可执行文件
已在上面的快速测试中覆盖。

### 测试 2: 共享库项目

**CMakeLists.txt**:
```cmake
cmake_minimum_required(VERSION 3.10)
project(MyLibrary)

add_library(mylib SHARED src/mylib.cpp)
```

**src/mylib.cpp**:
```cpp
extern "C" {
    int add(int a, int b) {
        return a + b;
    }
}
```

预期输出: `libmylib.so`

### 测试 3: 静态库项目

**CMakeLists.txt**:
```cmake
cmake_minimum_required(VERSION 3.10)
project(MyStaticLib)

add_library(mystatic STATIC src/utils.cpp)
```

**src/utils.cpp**:
```cpp
#include <string>

std::string getMessage() {
    return "Hello from static library";
}
```

预期输出: `libmystatic.a`

### 测试 4: 多文件项目

**CMakeLists.txt**:
```cmake
cmake_minimum_required(VERSION 3.10)
project(MultiFile)

add_executable(MultiFile 
    src/main.cpp
    src/utils.cpp
    src/math.cpp
)

target_include_directories(MultiFile PRIVATE include)
```

**项目结构**:
```
MultiFile/
├── CMakeLists.txt
├── include/
│   ├── utils.h
│   └── math.h
└── src/
    ├── main.cpp
    ├── utils.cpp
    └── math.cpp
```

### 测试 5: 使用 C++17 特性

**CMakeLists.txt**:
```cmake
cmake_minimum_required(VERSION 3.10)
project(Cpp17Test)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(Cpp17Test src/main.cpp)
```

**src/main.cpp**:
```cpp
#include <iostream>
#include <optional>
#include <string_view>
#include <filesystem>

int main() {
    // std::optional (C++17)
    std::optional<int> value = 42;
    if (value) {
        std::cout << "Value: " << *value << std::endl;
    }
    
    // std::string_view (C++17)
    std::string_view sv = "Hello, C++17!";
    std::cout << sv << std::endl;
    
    // Structured bindings (C++17)
    auto [x, y] = std::make_pair(10, 20);
    std::cout << "x=" << x << ", y=" << y << std::endl;
    
    return 0;
}
```

## 故障排查

### 问题 1: CMake 未找到
**症状**: `CMake 未找到: /data/data/.../sysroot/usr/bin/cmake`

**解决方案**:
1. 检查 sysroot.zip 是否包含 cmake
2. 重新构建 sysroot: `pwsh ./docker/llvm-build/build-local.ps1`
3. 重新同步: `pwsh ./tools/sync-llvm-build.ps1`
4. 重新安装 App

### 问题 2: 权限错误
**症状**: `Permission denied` 执行 cmake 或 ninja

**解决方案**:
- 代码已自动设置可执行权限
- 如果仍有问题，检查 SELinux 设置

### 问题 3: 编译超时
**症状**: `CMake 配置 超时` 或 `CMake 构建 超时`

**解决方案**:
- 当前超时设置为 60 秒
- 对于大型项目，可能需要增加超时时间
- 修改 `CMakeProjectCompiler.kt` 中的 `waitFor(60, TimeUnit.SECONDS)`

### 问题 4: 找不到头文件
**症状**: `fatal error: 'iostream' file not found`

**解决方案**:
1. 确保 sysroot 包含 C++ 标准库头文件
2. 检查 `usr/include/c++/v1/` 目录
3. 工具链文件应该自动配置搜索路径

### 问题 5: 链接错误
**症状**: `undefined reference to ...`

**解决方案**:
1. 检查是否缺少必要的库
2. 在 CMakeLists.txt 中添加 `target_link_libraries()`
3. 确保 sysroot 包含所需的 .so 或 .a 文件

## 性能基准

在 ARM64 设备上的典型编译时间：

| 项目类型 | 文件数 | 代码行数 | 配置时间 | 构建时间 | 总时间 |
|---------|--------|---------|---------|---------|--------|
| Hello World | 1 | 10 | ~2s | ~3s | ~5s |
| 小型库 | 3-5 | 100-200 | ~3s | ~5s | ~8s |
| 中型项目 | 10-20 | 500-1000 | ~5s | ~10s | ~15s |
| 大型项目 | 50+ | 2000+ | ~10s | ~30s | ~40s |

## 下一步

测试通过后，可以尝试：
1. 集成第三方库（如 Boost、OpenCV）
2. 使用 CMake 的 find_package()
3. 创建多模块项目
4. 添加单元测试（CTest）
5. 配置安装规则（install）

## 反馈

如果遇到问题或有改进建议，请：
1. 查看输出面板的详细日志
2. 检查 `/data/data/com.wuxianggujun.tinaide/files/build/<project>/build.log`
3. 提交 Issue 并附上日志信息
