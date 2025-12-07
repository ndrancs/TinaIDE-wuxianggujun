cmake_minimum_required(VERSION 3.22)
project(native_compiler)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 项目根目录（CMakeLists.txt 位于 app/src/main/cpp）
get_filename_component(PROJ_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)

# ============================================================================
# 源文件列表
# ============================================================================

add_library(native_compiler SHARED
    native_compiler.cpp
    # Utils 模块
    utils/jni_utils.cpp
    utils/file_utils.cpp
    # Compiler 模块
    compiler/clang_compiler.cpp
    # Linker 模块
    linker/lld_linker.cpp
    # Runner 模块
    runner/shared_runner.cpp
    # LSP 模块（简化架构）
    lsp/clangd_server.cpp
    lsp/simple/simple_lsp_client.cpp
    lsp/simple/simple_lsp_jni.cpp
    # Tree-sitter CMake parser
    treesitter/cmake/parser.c
    treesitter/cmake/scanner.c
    treesitter/cmake/cmake_jni.cpp
)

# Tree-sitter 公共头文件路径（所有语言共享）
target_include_directories(native_compiler PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/treesitter/include
)

# 允许共享库中存在由其他共享库在运行时解析的未定义符号
target_link_options(native_compiler PRIVATE 
    -Wl,--allow-shlib-undefined 
    -Wl,--unresolved-symbols=ignore-in-shared-libs 
    -Wl,-z,undefs
)

# ============================================================================
# 头文件路径
# ============================================================================

# 统一的 LLVM/Clang 头文件位置
set(COMMON_HEADERS ${PROJ_ROOT}/external/llvm-build-libs/common-headers)
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${COMMON_HEADERS}
    ${COMMON_HEADERS}/llvm
    ${COMMON_HEADERS}/llvm/llvm
    ${COMMON_HEADERS}/llvm/llvm/Config
    ${COMMON_HEADERS}/clang
    ${COMMON_HEADERS}/clang-c
)

# 检查 LLVM 头文件是否可用
set(HAVE_LLVM_HEADERS OFF)
if (EXISTS "${COMMON_HEADERS}/llvm/llvm/Support/Casting.h")
    set(HAVE_LLVM_HEADERS ON)
    message(STATUS "LLVM headers detected")
else()
    message(WARNING "LLVM headers not found at ${COMMON_HEADERS}/llvm/llvm/Support/")
endif()

# ============================================================================
# 预编译库
# ============================================================================

set(PREBUILT_LIBS_DIR ${PROJ_ROOT}/docker/llvm-build/build-output/${ANDROID_ABI}/libs/${ANDROID_ABI})
set(HAVE_LLVM_LIBS OFF)

if(EXISTS ${PREBUILT_LIBS_DIR}/libclang-cpp.so AND EXISTS ${PREBUILT_LIBS_DIR}/libLLVM-17.so)
    set(HAVE_LLVM_LIBS ON)
    add_library(clang-cpp SHARED IMPORTED GLOBAL)
    set_target_properties(clang-cpp PROPERTIES IMPORTED_NO_SONAME TRUE IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libclang-cpp.so)
    add_library(LLVM-17 SHARED IMPORTED GLOBAL)
    set_target_properties(LLVM-17 PROPERTIES IMPORTED_NO_SONAME TRUE IMPORTED_LOCATION ${PREBUILT_LIBS_DIR}/libLLVM-17.so)
    message(STATUS "Found prebuilt LLVM runtime: ${PREBUILT_LIBS_DIR}")
else()
    message(WARNING "Missing prebuilt clang/LLVM in ${PREBUILT_LIBS_DIR}")
endif()

find_library(log-lib log)

# ============================================================================
# 编译定义
# ============================================================================

if(HAVE_LLVM_HEADERS AND HAVE_LLVM_LIBS)
    add_definitions(-DLLVM_HEADERS_AVAILABLE=1)
else()
    add_definitions(-DLLVM_HEADERS_AVAILABLE=0)
endif()

# LLD 静态库（可选）
set(LLD_COMMON_A ${PREBUILT_LIBS_DIR}/liblldCommon.a)
set(LLD_ELF_A    ${PREBUILT_LIBS_DIR}/liblldELF.a)
if(EXISTS ${LLD_COMMON_A} AND EXISTS ${LLD_ELF_A})
    add_library(lldCommon STATIC IMPORTED GLOBAL)
    set_target_properties(lldCommon PROPERTIES IMPORTED_LOCATION ${LLD_COMMON_A})
    add_library(lldELF STATIC IMPORTED GLOBAL)
    set_target_properties(lldELF PROPERTIES IMPORTED_LOCATION ${LLD_ELF_A})
    target_link_libraries(native_compiler PRIVATE lldELF lldCommon)
    add_definitions(-DLLD_LINK_ENABLED=1)
    message(STATUS "Prebuilt LLD detected. In-process LLD: ON")
else()
    add_definitions(-DLLD_LINK_ENABLED=0)
    message(STATUS "Prebuilt LLD not found. In-process LLD: OFF")
endif()

# ============================================================================
# 链接库
# ============================================================================

if(HAVE_LLVM_HEADERS AND HAVE_LLVM_LIBS)
    target_link_libraries(native_compiler PRIVATE LLVM-17 clang-cpp c++_shared ${log-lib})
else()
    target_link_libraries(native_compiler PRIVATE c++_shared ${log-lib})
endif()
