cmake_minimum_required(VERSION 3.22)
project(native_compiler)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

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
    # LSP 模块
    lsp/clangd_server.cpp
    # LSP Native Client 模块（新增）
    lsp/native_client/transport/shared_memory_helper.cpp
    lsp/native_client/transport/shared_memory_transport.cpp
    lsp/native_client/transport/control_channel.cpp
    # 共享内存测试 JNI
    shared_memory_test_jni.cpp
    # 混合传输测试 JNI
    hybrid_transport_test_jni.cpp
)
# 允许共享库中存在由其他共享库在运行时解析的未定义符号
# 修复在链接 lld 静态库时对 libLLVM-17.so 中符号的解析约束（禁用 -z defs 效果）
target_link_options(native_compiler PRIVATE -Wl,--allow-shlib-undefined -Wl,--unresolved-symbols=ignore-in-shared-libs -Wl,-z,undefs)

# 项目根目录（CMakeLists.txt 位于 app/src/main/cpp）
# 从 app/src/main/cpp 回到仓库根需要四级目录
get_filename_component(PROJ_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)

# 统一的 LLVM/Clang 头文件位置（已规范化）
set(COMMON_HEADERS ${PROJ_ROOT}/external/llvm-build-libs/common-headers)
include_directories(
    ${COMMON_HEADERS}
    ${COMMON_HEADERS}/llvm
    ${COMMON_HEADERS}/llvm/llvm
    ${COMMON_HEADERS}/llvm/llvm/Config
    ${COMMON_HEADERS}/clang
    ${COMMON_HEADERS}/clang-c
)
# If unified LLVM headers look available, mark as present (实际是否启用取决于预编译库是否可用)
# 注意：LLVM源文件被复制到了 llvm/llvm/ 子目录（因为源码中include/llvm/本身就带llvm前缀）
set(HAVE_LLVM_HEADERS OFF)
if (EXISTS "${COMMON_HEADERS}/llvm/llvm/Support/Casting.h")
    set(HAVE_LLVM_HEADERS ON)
    message(STATUS "LLVM headers detected. In-process LLD default: OFF")
else()
    message(WARNING "LLVM headers not found at ${COMMON_HEADERS}/llvm/llvm/Support/")
endif()

# 构建期仅用于链接（不会打包进 APK）：导入预编译的 clang/LLVM so。
# 注意：这些路径位于工程目录，不在 jniLibs 下，AGP 只打包 jniLibs/<abi>，
# 因此不会被包含进最终 APK。
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
  message(WARNING "Missing prebuilt clang/LLVM in ${PREBUILT_LIBS_DIR}. Building without in-process LLVM/Clang support.\n"
                  "To enable, run: ./docker/llvm-build/build-local.ps1 -Abi ${ANDROID_ABI} -ApiLevel 26 && ./tools/sync-llvm-build.ps1 -Abi ${ANDROID_ABI}")
endif()

find_library(log-lib log)

# 根据是否存在预编译 LLVM/Clang 决定启用 in-process 能力与链接项
if(HAVE_LLVM_HEADERS AND HAVE_LLVM_LIBS)
  add_definitions(-DLLVM_HEADERS_AVAILABLE=1)
else()
  add_definitions(-DLLVM_HEADERS_AVAILABLE=0)
endif()
# 如存在预编译 LLD 静态库，则开启进程内 LLD 并链接（仅构建期，仍不会打包进 APK）。
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

# 在完成 LLD（静态）配置后再追加 LLVM/Clang（共享）与日志库，确保链接顺序：
# lldELF/lldCommon（静态）→ LLVM-17/clang-cpp（共享）→ log
if(HAVE_LLVM_HEADERS AND HAVE_LLVM_LIBS)
  target_link_libraries(native_compiler PRIVATE LLVM-17 clang-cpp c++_shared ${log-lib})
else()
  target_link_libraries(native_compiler PRIVATE c++_shared ${log-lib})
endif()
