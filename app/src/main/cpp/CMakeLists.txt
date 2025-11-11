cmake_minimum_required(VERSION 3.22)
project(native_compiler)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(native_compiler SHARED native_compiler.cpp)

# 项目根目录（CMakeLists.txt 位于 app/src/main/cpp）
# 从 app/src/main/cpp 回到仓库根需要四级目录
get_filename_component(PROJ_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)

# 统一的 LLVM/Clang 头文件位置（已规范化）
set(COMMON_HEADERS ${PROJ_ROOT}/external/embedded-ndk-libs/common-headers)
include_directories(
    ${COMMON_HEADERS}
    ${COMMON_HEADERS}/llvm
    ${COMMON_HEADERS}/llvm/llvm
    ${COMMON_HEADERS}/llvm/llvm/Config
    ${COMMON_HEADERS}/clang
    ${COMMON_HEADERS}/clang-c
)

# If unified LLVM headers look available, expose a macro to enable full in-process compilation
# 注意：LLVM源文件被复制到了 llvm/llvm/ 子目录（因为源码中include/llvm/本身就带llvm前缀）
if (EXISTS "${COMMON_HEADERS}/llvm/llvm/Support/Casting.h")
    add_definitions(-DLLVM_HEADERS_AVAILABLE=1)
    # 启用进程内 LLD 链接
    add_definitions(-DLLD_LINK_ENABLED=1)
    message(STATUS "LLVM headers detected, enabling in-process compilation")
else()
    message(WARNING "LLVM headers not found at ${COMMON_HEADERS}/llvm/llvm/Support/")
endif()

# 导入已随 APK 打包的共享库（来自 jniLibs/<abi>）
set(JNI_LIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(clang-cpp SHARED IMPORTED)
set_target_properties(clang-cpp PROPERTIES
    IMPORTED_NO_SONAME TRUE
    IMPORTED_LOCATION   ${JNI_LIBS_DIR}/libclang-cpp.so
)

add_library(LLVM-17 SHARED IMPORTED)
set_target_properties(LLVM-17 PROPERTIES
    IMPORTED_NO_SONAME TRUE
    IMPORTED_LOCATION   ${JNI_LIBS_DIR}/libLLVM-17.so
)

# 导入 LLD 静态库以支持进程内链接（ELF）
add_library(lldCommon STATIC IMPORTED)
set_target_properties(lldCommon PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/liblldCommon.a)
add_library(lldELF STATIC IMPORTED)
set_target_properties(lldELF PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/liblldELF.a)

find_library(log-lib log)

# 仅验证版本获取，后续可扩展为真正编译/链接
# 依赖顺序：先 LLVM 再 clang-cpp
# 运行时 libc++_shared.so 由 jniLibs 提供

target_link_libraries(native_compiler
    PRIVATE
    clang-cpp
    LLVM-17
    lldELF
    lldCommon
    ${log-lib}
)
