Param(
  [string[]]$Abi = @('arm64-v8a','x86_64'),
  [int]$ApiLevel = 28,
  [string]$NdkVersion = 'r26d',
  [string]$LlvmTag = 'llvmorg-17.0.6',
  [string]$ContainerName = 'tina-llvm-build',
  [string]$OutputPath,
  [ValidateSet('incremental','reconfigure','clean')][string]$Mode = 'incremental',
  # Build-type/diagnostics toggles for Android runtime libraries
  [ValidateSet('MinSizeRel','RelWithDebInfo','Debug')][string]$AndroidBuildType = 'MinSizeRel',
  [bool]$EnableAssertions = $false,
  # 源码更新控制
  [bool]$UpdateSource = $false,  # 默认不更新源码，使用本地已有的
  [bool]$ForceClone = $false,    # 强制重新克隆（会删除现有源码）
  # 构建并行度控制（用于解决内存不足问题）
  [int]$BuildJobs = 4,           # 编译并行度，默认4（0=自动检测nproc）
  [int]$LinkJobs = 2,            # 链接并行度，默认2（链接clangd需要大量内存）
  # Docker 内存限制
  [string]$DockerMemory = ''     # Docker 内存限制，如 '8g', '16g'，空=不限制
)

$ErrorActionPreference = 'Stop'

function Write-Info($msg) { Write-Host "[i] $msg" -ForegroundColor Cyan }
function Write-Err($msg)  { Write-Host "[!] $msg" -ForegroundColor Red }

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $scriptRoot '..\..')

# Determine output directory (allow override via parameter)
if (-not $OutputPath -or [string]::IsNullOrWhiteSpace($OutputPath)) {
  $OutputPath = Join-Path $root 'docker/llvm-build/build-output'
}

# Ensure output directory exists
New-Item -ItemType Directory -Force -Path $OutputPath | Out-Null
$outputBase = (Resolve-Path $OutputPath).Path

function Ensure-DevContainer {
  param([string]$containerName,[string]$ndkVersion,[string]$memoryLimit)
  $devImage = "llvm-build-dev:$ndkVersion"
  Write-Info "Ensuring dev image: $devImage"
  & docker build -f (Join-Path $root 'docker/llvm-build/Dockerfile.dev') --build-arg NDK_VERSION=$ndkVersion -t $devImage $root
  $running = (& docker ps --format '{{.Names}}' | Select-String -SimpleMatch $containerName) -ne $null
  if (-not $running) {
    $exists = (& docker ps -a --format '{{.Names}}' | Select-String -SimpleMatch $containerName) -ne $null
    if ($exists) { & docker rm -f $containerName | Out-Null }
    $workHost = Join-Path $root 'docker/llvm-build/dev-work'
    New-Item -ItemType Directory -Force -Path $workHost | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $workHost 'src'), (Join-Path $workHost 'build'), (Join-Path $workHost 'out') | Out-Null
    Write-Info "Starting dev container: $containerName"
    # 构建 docker run 参数
    $dockerArgs = @('-d', '--name', $containerName, '-w', '/work')
    # 添加内存限制（如果指定）
    if ($memoryLimit -and $memoryLimit -ne '') {
      $dockerArgs += @('-m', $memoryLimit)
      # 设置 swap 限制为内存的 2 倍，避免 OOM 时直接被杀
      $dockerArgs += @('--memory-swap', ($memoryLimit -replace '(\d+)', { [int]$_.Value * 2 }))
      Write-Info "Docker memory limit: $memoryLimit"
    }
    $dockerArgs += @('-v', "$($workHost):/work", '-v', "$($outputBase):/hostout", $devImage)
    & docker run @dockerArgs | Out-Null
  }
}

function Exec-In-Dev { param([string]$cmd) & docker exec $ContainerName bash -lc $cmd }

Ensure-DevContainer -containerName $ContainerName -ndkVersion $NdkVersion -memoryLimit $DockerMemory

$validAbis = @('arm64-v8a','x86_64')
function Normalize-AbiList {
  param([string[]]$values)
  $result = @()
  foreach ($value in $values) {
    if (-not $value) { continue }
    $parts = $value.Split(',', [System.StringSplitOptions]::RemoveEmptyEntries)
    foreach ($part in $parts) {
      $trimmed = $part.Trim()
      if ($trimmed) { $result += $trimmed }
    }
  }
  return $result
}
$normalizedAbi = Normalize-AbiList -values $Abi
if (-not $normalizedAbi -or $normalizedAbi.Count -eq 0) {
  $normalizedAbi = $validAbis
}
foreach ($entry in $normalizedAbi) {
  if ($validAbis -notcontains $entry) {
    throw "Unsupported ABI '$entry'. Valid values: $($validAbis -join ', ')"
  }
}
$abiList = $normalizedAbi

$sessionScript = @'
set -eux
case "${ABI}" in
  arm64-v8a) TRIPLE=aarch64-linux-android; LLVM_TARGET=AArch64;;
  x86_64)    TRIPLE=x86_64-linux-android; LLVM_TARGET=X86;;
  *) echo "Unsupported ABI: ${ABI}"; exit 1;;
esac
mkdir -p /work/src /work/build/host /work/build/android/${ABI}-api${API_LEVEL} /hostout/${ABI}

# 计算并行度
if [ "${BUILD_JOBS}" = "0" ] || [ -z "${BUILD_JOBS}" ]; then
  COMPILE_JOBS=$(nproc)
else
  COMPILE_JOBS=${BUILD_JOBS}
fi
# 链接并行度（clangd 链接需要大量内存，默认限制为 1-2）
if [ -z "${LINK_JOBS}" ]; then
  LINK_JOBS=2
fi
echo "[i] Build parallelism: compile=${COMPILE_JOBS}, link=${LINK_JOBS}"

# 源码管理逻辑
if [ "${FORCE_CLONE}" = "True" ] || [ "${FORCE_CLONE}" = "true" ] || [ "${FORCE_CLONE}" = "1" ]; then
  echo "[i] Force clone enabled, removing existing source..."
  rm -rf /work/src/llvm-project || true
fi

if [ ! -d /work/src/llvm-project/.git ]; then
  echo "[i] LLVM source not found, cloning ${LLVM_TAG}..."
  git clone --depth=1 --branch ${LLVM_TAG} https://github.com/llvm/llvm-project.git /work/src/llvm-project
elif [ "${UPDATE_SOURCE}" = "True" ] || [ "${UPDATE_SOURCE}" = "true" ] || [ "${UPDATE_SOURCE}" = "1" ]; then
  echo "[i] Updating source to ${LLVM_TAG}..."
  cd /work/src/llvm-project
  git fetch origin --tags --depth=1 || true
  git fetch origin ${LLVM_TAG} --depth=1 || true
  git checkout --force ${LLVM_TAG} || git checkout --force origin/${LLVM_TAG} || git checkout --force "$(git rev-parse --abbrev-ref origin/HEAD)"
  git submodule update --init --recursive || true
  cd /
else
  echo "[i] Using existing local source (skip git operations)"
  echo "[i] Current source version:"
  cd /work/src/llvm-project && git describe --tags --always 2>/dev/null || echo "unknown"
  cd /
fi

# 配置 host 构建（仅用于生成 tablegen 工具，用于交叉编译）
if [ ! -x /work/build/host/bin/llvm-tblgen ]; then
  echo "[i] Building host tablegen tools for cross-compilation..."
  cmake -S /work/src/llvm-project/llvm -B /work/build/host -G Ninja \
    -DLLVM_ENABLE_PROJECTS="clang;clang-tools-extra;lld" \
    -DLLVM_TARGETS_TO_BUILD="AArch64;X86" \
    -DCMAKE_BUILD_TYPE=Release \
    -DLLVM_PARALLEL_LINK_JOBS=${LINK_JOBS}
  ninja -C /work/build/host -j${COMPILE_JOBS} llvm-tblgen clang-tblgen || true
fi
# Configure control via MODE: incremental | reconfigure | clean
if [ "${MODE}" = "clean" ]; then
  rm -rf /work/build/android/${ABI}-api${API_LEVEL} || true
fi
if [ "${MODE}" = "reconfigure" ] || [ "${MODE}" = "clean" ]; then
  rm -f /work/build/android/${ABI}-api${API_LEVEL}/CMakeCache.txt || true
  rm -rf /work/build/android/${ABI}-api${API_LEVEL}/CMakeFiles || true
fi
# clangd 需要线程支持，默认始终启用
ENABLE_THREADS=ON
echo "[i] Enabling threads for clangd build"

cmake -S /work/src/llvm-project/llvm -B /work/build/android/${ABI}-api${API_LEVEL} -G Ninja \
  -DLLVM_ENABLE_PROJECTS="clang;clang-tools-extra;lld" -DLLVM_TARGETS_TO_BUILD="${LLVM_TARGET}" -DCMAKE_BUILD_TYPE=${ANDROID_BUILD_TYPE} \
  -DCMAKE_SYSTEM_NAME=Android -DCMAKE_ANDROID_NDK=${ANDROID_NDK_HOME} -DCMAKE_ANDROID_ARCH_ABI=${ABI} -DCMAKE_ANDROID_API=${API_LEVEL} \
  -DLLVM_TABLEGEN=/work/build/host/bin/llvm-tblgen -DCLANG_TABLEGEN=/work/build/host/bin/clang-tblgen \
  -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF -DLLVM_INCLUDE_BENCHMARKS=OFF \
  -DLLVM_ENABLE_TERMINFO=OFF -DLLVM_ENABLE_LIBEDIT=OFF -DLLVM_ENABLE_CURSES=OFF \
  -DLLVM_ENABLE_ZLIB=OFF -DLLVM_ENABLE_ZSTD=OFF -DLLVM_ENABLE_LIBXML2=OFF \
  -DCLANG_ENABLE_ARCMT=OFF -DCLANG_ENABLE_STATIC_ANALYZER=OFF \
  -DLLVM_BUILD_TOOLS=OFF -DLLVM_BUILD_LLVM_DYLIB=ON -DLLVM_LINK_LLVM_DYLIB=ON -DCLANG_LINK_CLANG_DYLIB=ON \
  -DLLVM_ENABLE_THREADS=${ENABLE_THREADS} \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_C_FLAGS="-femulated-tls" -DCMAKE_CXX_FLAGS="-femulated-tls" \
  -DLLVM_ENABLE_ASSERTIONS=$([ "${ENABLE_ASSERTIONS}" = "True" ] || [ "${ENABLE_ASSERTIONS}" = "true" ] || [ "${ENABLE_ASSERTIONS}" = "1" ] && echo ON || echo OFF) \
  -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=OFF \
  -DLLVM_PARALLEL_LINK_JOBS=${LINK_JOBS}
ninja -C /work/build/android/${ABI}-api${API_LEVEL} -j${COMPILE_JOBS} clang-cpp libclang lld lldELF lldCommon

CLANGD_WRAPPER_PATH=""
# Build clangd for Android as a shared library (libclangd.so)
# This requires clang-tools-extra to be enabled in LLVM_ENABLE_PROJECTS
echo "[i] Building clangd for Android (this may take a while)..."
# Build clangd static libraries first
ninja -C /work/build/android/${ABI}-api${API_LEVEL} -j${COMPILE_JOBS} clangDaemon clangdSupport clangdMain clangDaemonTweaks || {
  echo "[w] clangd Android build failed, retrying with reduced parallelism..."
  ninja -C /work/build/android/${ABI}-api${API_LEVEL} -j1 clangDaemon clangdSupport clangdMain clangDaemonTweaks || true
}

# Create a wrapper shared library that exports clangd_main
# This allows us to dlopen and call clangd from Android
CLANGD_WRAPPER_DIR="/work/build/android/${ABI}-api${API_LEVEL}/clangd-wrapper"
mkdir -p "${CLANGD_WRAPPER_DIR}"

LLVM_LINK_FLAG="-lLLVM"
LLVM_RESOLVED=$(ls /work/build/android/${ABI}-api${API_LEVEL}/lib/libLLVM-*.so 2>/dev/null | head -n1 || true)
if [ -n "${LLVM_RESOLVED}" ]; then
  LLVM_BASENAME=$(basename "${LLVM_RESOLVED}")
  LLVM_LINK_FLAG="-l:${LLVM_BASENAME}"
  echo "[i] Using LLVM shared library ${LLVM_BASENAME} for clangd wrapper link"
else
  echo "[w] libLLVM-*.so not found, falling back to -lLLVM"
fi

cat > "${CLANGD_WRAPPER_DIR}/clangd_wrapper.cpp" << 'CLANGD_WRAPPER_EOF'
// clangd wrapper for Android - exports clangd_main as a shared library entry point
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>

// Forward declaration of clangd's main function
namespace clang {
namespace clangd {
int clangdMain(int argc, char *argv[]);
}
}

extern "C" {

// Entry point for dlopen/dlsym
// Returns: exit code from clangd
// Note: stdin/stdout should be redirected before calling this
__attribute__((visibility("default")))
int clangd_main(int argc, char** argv) {
    return clang::clangd::clangdMain(argc, argv);
}

// Simplified entry with default arguments
__attribute__((visibility("default")))
int clangd_run() {
    const char* default_args[] = {
        "clangd",
        "--background-index=false",
        "--clang-tidy=false",
        "--completion-style=detailed",
        "--pch-storage=memory",
        "--log=error",
        nullptr
    };
    int argc = 0;
    while (default_args[argc]) argc++;
    return clang::clangd::clangdMain(argc, const_cast<char**>(default_args));
}

} // extern "C"
CLANGD_WRAPPER_EOF

# Compile the wrapper
${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++ \
  --target=${TRIPLE}${API_LEVEL} \
  -std=c++17 \
  -fPIC \
  -shared \
  -femulated-tls \
  -I/work/src/llvm-project/clang-tools-extra/clangd \
  -I/work/src/llvm-project/clang-tools-extra/clangd/tool \
  -I/work/src/llvm-project/clang/include \
  -I/work/src/llvm-project/llvm/include \
  -I/work/build/android/${ABI}-api${API_LEVEL}/include \
  -I/work/build/android/${ABI}-api${API_LEVEL}/tools/clang/include \
  -o "${CLANGD_WRAPPER_DIR}/libclangd.so" \
  "${CLANGD_WRAPPER_DIR}/clangd_wrapper.cpp" \
  -L/work/build/android/${ABI}-api${API_LEVEL}/lib \
  -Wl,--whole-archive \
  -lclangdMain \
  -lclangDaemon \
  -lclangdRemoteIndex \
  -lclangDaemonTweaks \
  -lclangdSupport \
  -lclangTidy \
  -lclangTidyAbseilModule \
  -lclangTidyAlteraModule \
  -lclangTidyAndroidModule \
  -lclangTidyBoostModule \
  -lclangTidyBugproneModule \
  -lclangTidyCERTModule \
  -lclangTidyConcurrencyModule \
  -lclangTidyCppCoreGuidelinesModule \
  -lclangTidyDarwinModule \
  -lclangTidyFuchsiaModule \
  -lclangTidyGoogleModule \
  -lclangTidyHICPPModule \
  -lclangTidyLinuxKernelModule \
  -lclangTidyLLVMLibcModule \
  -lclangTidyLLVMModule \
  -lclangTidyMiscModule \
  -lclangTidyModernizeModule \
  -lclangTidyMPIModule \
  -lclangTidyObjCModule \
  -lclangTidyOpenMPModule \
  -lclangTidyPerformanceModule \
  -lclangTidyPortabilityModule \
  -lclangTidyReadabilityModule \
  -lclangTidyUtils \
  -lclangTidyZirconModule \
  -Wl,--no-whole-archive \
  -lclangIncludeCleaner \
  -lclangPseudo \
  -lclangAST \
  -lclangASTMatchers \
  -lclangBasic \
  -lclangDriver \
  -lclangFormat \
  -lclangFrontend \
  -lclangIndex \
  -lclangLex \
  -lclangSema \
  -lclangSerialization \
  -lclangTooling \
  -lclangToolingCore \
  -lclangToolingInclusions \
  -lclangToolingInclusionsStdlib \
  -lclangToolingSyntax \
  -lclang-cpp \
  ${LLVM_LINK_FLAG} \
  -lc++_shared \
  -landroid \
  -llog \
  || echo "[w] Failed to build libclangd.so wrapper"

CLANGD_WRAPPER_PATH="${CLANGD_WRAPPER_DIR}/libclangd.so"
if [ -f "${CLANGD_WRAPPER_PATH}" ]; then
  echo "[i] libclangd.so built successfully for Android"
else
  echo "[w] libclangd.so not found after build attempt"
  CLANGD_WRAPPER_PATH=""
fi

# Clean destination to avoid duplicate/readonly collisions on host mounts
rm -rf /hostout/${ABI}/libs/${ABI} /hostout/${ABI}/sysroot /hostout/${ABI}/include || true
mkdir -p /hostout/${ABI}/libs/${ABI} /hostout/${ABI}/sysroot/usr/include /hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}
mkdir -p /hostout/${ABI}/include/clang-c /hostout/${ABI}/include/clang /hostout/${ABI}/include/llvm /hostout/${ABI}/include/lld
cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/libclang-cpp.so* /hostout/${ABI}/libs/${ABI}/ || true
cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/libclang.so* /hostout/${ABI}/libs/${ABI}/ || true
cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/libLLVM*.so*    /hostout/${ABI}/libs/${ABI}/ || true
if [ -n "${CLANGD_WRAPPER_PATH}" ] && [ -f "${CLANGD_WRAPPER_PATH}" ]; then
  cp -af "${CLANGD_WRAPPER_PATH}" /hostout/${ABI}/libs/${ABI}/ || true
fi
# Export LLD static libraries for in-process linking (build-time only)
if [ -f /work/build/android/${ABI}-api${API_LEVEL}/lib/liblldCommon.a ]; then
  cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/liblldCommon.a /hostout/${ABI}/libs/${ABI}/ || true
fi
if [ -f /work/build/android/${ABI}-api${API_LEVEL}/lib/liblldELF.a ]; then
  cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/liblldELF.a /hostout/${ABI}/libs/${ABI}/ || true
fi
# (Dynamic-only mode) Do not export LLD static libraries to keep artifacts small; we will use external ld.lld for linking.
# Not exporting ld.lld executable (no external fallback needed)
# Also place runtime copies under sysroot for on-device loading (avoid jniLibs dependency)
mkdir -p /hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/runtime || true
cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/libclang-cpp.so* /hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/runtime/ || true
cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/libclang.so* /hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/runtime/ || true
cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/libLLVM*.so*    /hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/runtime/ || true
if [ -n "${CLANGD_WRAPPER_PATH}" ] && [ -f "${CLANGD_WRAPPER_PATH}" ]; then
  cp -af "${CLANGD_WRAPPER_PATH}" /hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/runtime/ || true
fi
if compgen -G "/work/build/android/${ABI}-api${API_LEVEL}/lib/liblld*.so*" > /dev/null; then
  cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/liblld*.so* /hostout/${ABI}/libs/${ABI}/
fi
# strip .so to minimize size (use NDK's llvm-strip)
if [ -x "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" ]; then
  STRIP_BIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  for so in /hostout/${ABI}/libs/${ABI}/*.so*; do
    [ -f "$so" ] && $STRIP_BIN -S "$so" || true
  done
fi
# Ensure libc++_shared.so is available for dynamic C++ runtime
# Prefer the prebuilt sysroot location (NDK r23+), fallback to legacy sources path
LIBCXX_SHARED_SRC=""
if [ -f "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${TRIPLE}/libc++_shared.so" ]; then
  LIBCXX_SHARED_SRC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${TRIPLE}/libc++_shared.so"
elif [ -f "${ANDROID_NDK_HOME}/sources/cxx-stl/llvm-libc++/libs/${ABI}/libc++_shared.so" ]; then
  LIBCXX_SHARED_SRC="${ANDROID_NDK_HOME}/sources/cxx-stl/llvm-libc++/libs/${ABI}/libc++_shared.so"
fi
if [ -n "$LIBCXX_SHARED_SRC" ]; then
  cp -af "$LIBCXX_SHARED_SRC" "/hostout/${ABI}/libs/${ABI}/" || true
  # Also place a copy into the sysroot API directory so LLD can find it via -L <...>/<TRIPLE>/<API_LEVEL>
  mkdir -p "/hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}" || true
  cp -af "$LIBCXX_SHARED_SRC" "/hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}/" || true
else
  echo "[w] libc++_shared.so not found in NDK (checked sysroot and sources paths)" >&2
fi

# Copy libunwind.a for C++ exception handling support
# libunwind provides _Unwind_Resume and other unwinding symbols required by libc++ exceptions
# NDK r26+ stores libunwind.a under lib/clang/<ver>/lib/linux/<arch>/
UNWIND_ARCH=""
case "${ABI}" in
  arm64-v8a) UNWIND_ARCH="aarch64";;
  x86_64)    UNWIND_ARCH="x86_64";;
esac
LIBUNWIND_SRC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/17/lib/linux/${UNWIND_ARCH}/libunwind.a"
if [ ! -f "${LIBUNWIND_SRC}" ]; then
  # Fallback: try to find it dynamically
  LIBUNWIND_SRC=$(find "${ANDROID_NDK_HOME}" -path "*/lib/linux/${UNWIND_ARCH}/libunwind.a" 2>/dev/null | head -n1)
fi
if [ -f "${LIBUNWIND_SRC}" ]; then
  cp -af "${LIBUNWIND_SRC}" "/hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}/" || true
  echo "[i] Copied libunwind.a from ${LIBUNWIND_SRC}"
else
  echo "[w] libunwind.a not found for ${UNWIND_ARCH}" >&2
fi

# Copy libc++abi.a for C++ ABI support (may be needed for some exception handling)
LIBCXXABI_SRC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/17/lib/linux/${UNWIND_ARCH}/libc++abi.a"
if [ ! -f "${LIBCXXABI_SRC}" ]; then
  LIBCXXABI_SRC=$(find "${ANDROID_NDK_HOME}" -path "*/lib/linux/${UNWIND_ARCH}/libc++abi.a" 2>/dev/null | head -n1)
fi
if [ -f "${LIBCXXABI_SRC}" ]; then
  cp -af "${LIBCXXABI_SRC}" "/hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}/" || true
  echo "[i] Copied libc++abi.a from ${LIBCXXABI_SRC}"
fi

cp -af ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/. /hostout/${ABI}/sysroot/usr/include/ || true
# Ensure libc++ headers are present under sysroot/usr/include/c++/v1 (NDK layout varies by version)
mkdir -p /hostout/${ABI}/sysroot/usr/include/c++/v1 || true
# Try prebuilt include path (newer NDKs)
cp -af ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/include/c++/v1/. /hostout/${ABI}/sysroot/usr/include/c++/v1/ 2>/dev/null || true
# If still missing, try legacy sources path (older NDKs)
if [ ! -f /hostout/${ABI}/sysroot/usr/include/c++/v1/__ios/fpos.h ]; then
  if [ -d "${ANDROID_NDK_HOME}/sources/cxx-stl/llvm-libc++/include" ]; then
    cp -af ${ANDROID_NDK_HOME}/sources/cxx-stl/llvm-libc++/include/. /hostout/${ABI}/sysroot/usr/include/c++/v1/
  fi
fi
# Final verification: fail early if libc++ core headers not found
if [ ! -f /hostout/${ABI}/sysroot/usr/include/c++/v1/__ios/fpos.h ]; then
  echo "[ERROR] libc++ headers not found in NDK. Checked prebuilt include/c++/v1 and sources/cxx-stl/llvm-libc++/include" >&2
  exit 2
fi

# Copy Clang resource headers (builtin headers like stdarg.h) into sysroot/lib/clang/<ver>/include
verdir=$(ls -d /work/build/android/${ABI}-api${API_LEVEL}/lib/clang/* 2>/dev/null | head -n1 || true)
if [ -n "$verdir" ] && [ -d "$verdir/include" ]; then
  verbase=$(basename "$verdir")
  # Place resource headers under the real version dir
  mkdir -p /hostout/${ABI}/sysroot/lib/clang/${verbase}
  cp -af "$verdir/include" /hostout/${ABI}/sysroot/lib/clang/${verbase}/
  # Also provide a stable alias "17" for runtime -resource-dir compatibility
  major=${verbase%%.*}
  [ -z "$major" ] && major=17
  mkdir -p /hostout/${ABI}/sysroot/lib/clang/${major}
  cp -af "$verdir/include" /hostout/${ABI}/sysroot/lib/clang/${major}/
else
  echo "[WARN] Clang resource headers not found under build output; falling back to clang/lib/Headers" >&2
  # Fallback: copy from clang source tree
  if [ -d /work/src/llvm-project/clang/lib/Headers ]; then
    mkdir -p /hostout/${ABI}/sysroot/lib/clang/17/include
    cp -af /work/src/llvm-project/clang/lib/Headers/. /hostout/${ABI}/sysroot/lib/clang/17/include/
  else
    echo "[ERROR] No clang resource headers available (build and source both missing)" >&2
    exit 2
  fi
fi
# Copy NDK stub/crt libs for requested API level; if empty/missing, try fallbacks (common in some NDK layouts)
src_stub_base="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${TRIPLE}"
dst_stub_dir="/hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}"
mkdir -p "${dst_stub_dir}"

echo "[i] Probing NDK stub base: ${src_stub_base}"
echo "[i] Listing available API dirs under stub base:"; ls -la "${src_stub_base}" || true
echo "[i] Copying stub libs from: ${src_stub_base}/${API_LEVEL} → ${dst_stub_dir}"
cp -af "${src_stub_base}/${API_LEVEL}/." "${dst_stub_dir}/" 2>/dev/null || true

need=(crtbegin_dynamic.o crtend_android.o libc.so libm.so liblog.so libandroid.so)
missing=()
for f in "${need[@]}"; do
  [ -f "${dst_stub_dir}/${f}" ] || missing+=("${f}")
done

if [ ${#missing[@]} -gt 0 ]; then
  echo "[w] Requested API ${API_LEVEL} missing: ${missing[*]} — probing fallback APIs" >&2
  # Prefer lowest baseline 21 first (most complete), then higher levels
  for fb in 21 26 29 33; do
    [ "${fb}" = "${API_LEVEL}" ] && continue
    echo "[i] Inspect candidate API ${fb}:"; ls -la "${src_stub_base}/${fb}" || true
    if [ -d "${src_stub_base}/${fb}" ] && [ -f "${src_stub_base}/${fb}/crtbegin_dynamic.o" ]; then
      echo "[i] Using fallback API ${fb} for stub/crt; copying into ${dst_stub_dir}" >&2
      cp -af "${src_stub_base}/${fb}/." "${dst_stub_dir}/"
      break
    fi
  done
fi

# If still missing, search dynamically for any x86_64 triple dir that has crtbegin_dynamic.o
missing=()
for f in "${need[@]}"; do
  [ -f "${dst_stub_dir}/${f}" ] || missing+=("${f}")
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "[w] Fallback by search: looking for crtbegin_dynamic.o under ${src_stub_base}" >&2
  fb_dir=$(find "${src_stub_base}" -maxdepth 3 -mindepth 1 -type f -name crtbegin_dynamic.o -printf '%h\n' | sort -u | head -n1 || true)
  if [ -n "${fb_dir}" ] && [ -d "${fb_dir}" ]; then
    echo "[i] Found candidate: ${fb_dir} → ${dst_stub_dir}" >&2
    cp -af "${fb_dir}/." "${dst_stub_dir}/"
  else
    echo "[w] No crtbegin_dynamic.o found under ${src_stub_base}" >&2
  fi
fi

# Re-check after all fallbacks
missing=()
for f in "${need[@]}"; do
  [ -f "${dst_stub_dir}/${f}" ] || missing+=("${f}")
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "[ERROR] Still missing after fallback: ${missing[*]} in ${dst_stub_dir}" >&2
  echo "        Please inspect inside container: ls -l ${src_stub_base}/{${API_LEVEL},21,26,29,33} ; find ${src_stub_base} -name crtbegin_dynamic.o" >&2
  exit 2
fi
echo "[i] Final stub dir content (top 20):"; ls -la "${dst_stub_dir}" | head -n 40 || true
cp -af /work/src/llvm-project/clang/include/clang-c/. /hostout/${ABI}/include/clang-c/ || true
cp -a /work/src/llvm-project/clang/include/. /hostout/${ABI}/include/clang/ || true
cp -a /work/src/llvm-project/llvm/include/.  /hostout/${ABI}/include/llvm/  || true
cp -a /work/src/llvm-project/lld/include/.   /hostout/${ABI}/include/lld/   || true
cp -a /work/build/android/${ABI}-api${API_LEVEL}/include/.          /hostout/${ABI}/include/       || true
printf "MODE=shared-libs\nNDK=%s\nLLVM_TAG=%s\nABI=%s\nAPI_LEVEL=%s\nTRIPLE=%s\n" "${NDK_VERSION}" "${LLVM_TAG}" "${ABI}" "${API_LEVEL}" "${TRIPLE}" > /hostout/${ABI}/MANIFEST
(cd /hostout/${ABI} && find . -type f -print0 | sort -z | xargs -0 sha256sum) > /hostout/${ABI}/SHA256SUMS || true
(cd /hostout/${ABI} && zip -qr llvm-build-${ABI}-api${API_LEVEL}.zip .)
'@

foreach ($currentAbi in $abiList) {
  Write-Info "Building LLVM artifacts for ABI: $currentAbi"
  $outDirHost = Join-Path $OutputPath "${currentAbi}"
  New-Item -ItemType Directory -Force -Path $outDirHost | Out-Null
  $assign = "ABI='$currentAbi'; API_LEVEL='$ApiLevel'; LLVM_TAG='$LlvmTag'; NDK_VERSION='$NdkVersion'; MODE='$Mode'; ANDROID_BUILD_TYPE='$AndroidBuildType'; ENABLE_ASSERTIONS='$EnableAssertions'; UPDATE_SOURCE='$UpdateSource'; FORCE_CLONE='$ForceClone'; BUILD_JOBS='$BuildJobs'; LINK_JOBS='$LinkJobs';"
  $payload = "$assign`n$sessionScript"
  $payload = $payload -replace "`r",""
  $containerTemplate = @'
cat <<'__TB_BUILD_LOCAL__' > /tmp/tina-build-local.sh
__TINA_BUILD_LOCAL_PAYLOAD__
__TB_BUILD_LOCAL__
bash /tmp/tina-build-local.sh
rc=$?
rm -f /tmp/tina-build-local.sh
exit $rc
'@
  $containerCmd = $containerTemplate -replace "__TINA_BUILD_LOCAL_PAYLOAD__", $payload
  $containerCmd = $containerCmd -replace "`r",""
  Exec-In-Dev $containerCmd
  $rc = $LASTEXITCODE
  if ($rc -ne 0) {
    Write-Err "Build failed inside container (exit $rc) for ABI $currentAbi"
    exit $rc
  }
  Write-Info "Build completed for $currentAbi"
  Write-Info "Artifacts ready at: $outDirHost"
}

Write-Info "Done."
