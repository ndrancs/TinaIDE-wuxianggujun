Param(
  [ValidateSet('arm64-v8a','x86_64')][string]$Abi = 'x86_64',
  [int]$ApiLevel = 28,
  [string]$ContainerName = 'tina-llvm-build',
  [string]$OutputPath,
  # Default to a recent stable CMake tag; override with -CMakeTag 'v3.31.x' when needed
  [string]$CMakeTag = 'v3.31.4',
  # New: build shared-object runners instead of relying on exec() (SELinux blocks exec in app sandbox)
  [bool]$BuildNinjaSo = $true,
  # Default to also produce a CMake runner .so (best‑effort); keeps cmake executable build intact
  [bool]$BuildCMakeSo = $true,
  # New: try to produce a clangd runner .so for Android (best‑effort, heavy deps)
  [bool]$BuildClangdSo = $true,
  # Optional llvm-project tag for clangd build (fallback to 17.0.6)
  [string]$LlvmTagForClangd = 'llvmorg-17.0.6'
)

$ErrorActionPreference = 'Stop'

function Write-Info($msg) { Write-Host "[i] $msg" -ForegroundColor Cyan }
function Write-Err($msg)  { Write-Host "[!] $msg" -ForegroundColor Red }

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $scriptRoot '..\..')

if (-not $OutputPath -or [string]::IsNullOrWhiteSpace($OutputPath)) {
  $OutputPath = Join-Path $root 'docker/llvm-build/build-output'
}
$outBase = (Resolve-Path $OutputPath).Path
$outDirHost = Join-Path $outBase $Abi
New-Item -ItemType Directory -Force -Path (Join-Path $outDirHost 'tools/bin') | Out-Null

# Ensure dev container is running (re-use the one used by build-local.ps1)
$running = (& docker ps --format '{{.Names}}' | Select-String -SimpleMatch $ContainerName) -ne $null
if (-not $running) {
  Write-Err "Dev container '$ContainerName' not running. Please run docker/llvm-build/build-local.ps1 once to create it."
  exit 2
}

function Exec-In-Dev { param([string]$cmd) & docker exec $ContainerName bash -lc $cmd }

$assign = "ABI='$Abi'; API_LEVEL='$ApiLevel'; BUILD_NINJA_SO='$BuildNinjaSo'; BUILD_CMAKE_SO='$BuildCMakeSo';"
$session = @'
set -eux
case "${ABI}" in
  arm64-v8a) TRIPLE=aarch64-linux-android;;
  x86_64)    TRIPLE=x86_64-linux-android;;
  *) echo "Unsupported ABI: ${ABI}"; exit 1;;
esac

# Build Ninja for Android using CMake toolchain
if [ ! -d /work/src/ninja/.git ]; then
  git clone --depth=1 https://github.com/ninja-build/ninja.git /work/src/ninja
fi

cmake -S /work/src/ninja -B /work/build/tools/ninja-${ABI}-api${API_LEVEL} -G Ninja \
  -DCMAKE_SYSTEM_NAME=Android -DCMAKE_ANDROID_NDK=${ANDROID_NDK_HOME} \
  -DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake \
  -DCMAKE_ANDROID_ARCH_ABI=${ABI} -DCMAKE_ANDROID_API=${API_LEVEL} \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_CXX_FLAGS="-fPIC"
ninja -C /work/build/tools/ninja-${ABI}-api${API_LEVEL} -j$(nproc) ninja

mkdir -p /hostout/${ABI}/tools/bin
cp -af /work/build/tools/ninja-${ABI}-api${API_LEVEL}/ninja /hostout/${ABI}/tools/bin/

# Strip to reduce size if available
if [ -x "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" ]; then
  ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip -S /hostout/${ABI}/tools/bin/ninja || true
fi
chmod 0755 /hostout/${ABI}/tools/bin/ninja

# Optionally build libninja_runner.so (in-process runner)
if [ "${BUILD_NINJA_SO}" = "True" ] || [ "${BUILD_NINJA_SO}" = "true" ] || [ "${BUILD_NINJA_SO}" = "1" ]; then
  case "${ABI}" in
    arm64-v8a) TRIPLE=aarch64-linux-android;;
    x86_64)    TRIPLE=x86_64-linux-android;;
  esac
  NDK_CLANGXX="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/${TRIPLE}${API_LEVEL}-clang++"
  mkdir -p /work/build/tools/ninja-runner
  cat > /work/build/tools/ninja-runner/ninja_runner.cpp <<'EOF'
#include <android/log.h>

extern "C" int main(int, char**);
extern "C" int ninja_run(int argc, char** argv) { return main(argc, argv); }

// Provide implementation for browse functionality
// On Android, we don't have Python, so we provide a minimal implementation
// that logs a message and returns an error
struct State;
extern "C" int RunBrowsePython(State* state, const char* ninja_command,
                               const char* input_file, int argc, char** argv) {
  __android_log_print(ANDROID_LOG_WARN, "NinjaRunner", 
    "Browse mode is not supported on Android (no Python runtime)");
  __android_log_print(ANDROID_LOG_INFO, "NinjaRunner",
    "To view build graph, use ninja -t graph or ninja -t targets");
  return 1;
}
EOF
  # Collect ALL object files from the ninja target (including browse.cc.o)
  # We provide RunBrowsePython implementation above, so no missing symbols
  ninja_objs_core=$(find /work/build/tools/ninja-${ABI}-api${API_LEVEL}/CMakeFiles/libninja.dir -name '*.o' | xargs echo)
  ninja_objs_re2c=$(find /work/build/tools/ninja-${ABI}-api${API_LEVEL}/CMakeFiles/libninja-re2c.dir -name '*.o' | xargs echo)
  ninja_main_obj=$(find /work/build/tools/ninja-${ABI}-api${API_LEVEL}/CMakeFiles/ninja.dir -name 'ninja.cc.o' | head -n1)
  if [ -n "${ninja_main_obj}" ]; then ninja_objs_main=${ninja_main_obj}; else ninja_objs_main=""; fi
  if [ -n "${ninja_objs_core}" ] || [ -n "${ninja_objs_re2c}" ]; then ninja_objs="${ninja_objs_core} ${ninja_objs_re2c} ${ninja_objs_main}"; else ninja_objs=""; fi
  echo "[i] Including all Ninja objects with RunBrowsePython stub implementation"
  if [ -n "${ninja_objs}" ] && [ -x "${NDK_CLANGXX}" ]; then
    ${NDK_CLANGXX} -shared -fPIC -Wl,-z,now -Wl,-z,relro \
      -o /hostout/${ABI}/tools/bin/libninja_runner.so \
      ${ninja_objs} /work/build/tools/ninja-runner/ninja_runner.cpp -llog -landroid || \
      echo "[w] libninja_runner.so link failed; continuing"
  else
    echo "[w] Could not locate ninja objects or NDK clang++ for runner build; skipping"
  fi
fi

# Build CMake (minimal) for Android
if [ ! -d /work/src/cmake/.git ]; then
  # Use the tag from host env (injected by outer script via env) or fallback
  : ${CMAKE_TAG:=v3.31.4}
  git clone --depth=1 --branch "${CMAKE_TAG}" https://github.com/Kitware/CMake.git /work/src/cmake || \
  git clone --depth=1 https://github.com/Kitware/CMake.git /work/src/cmake
fi
# Workaround for CMake's vendored libarchive on Android: it includes
# "android_lf.h" for large-file compatibility, but the contrib header
# may not be present in some tags/branches. Provide a minimal shim so
# the build can proceed.
if [ ! -f /work/src/cmake/Utilities/cmlibarchive/contrib/android/include/android_lf.h ]; then
  mkdir -p /work/src/cmake/Utilities/cmlibarchive/contrib/android/include
  cat > /work/src/cmake/Utilities/cmlibarchive/contrib/android/include/android_lf.h <<'EOF'
/*
 * Macros for file64 functions
 * Android does not support the macro _FILE_OFFSET_BITS=64
 * As of android-21 it does however support many file64 functions
 */
#ifndef ARCHIVE_ANDROID_LF_H_INCLUDED
#define ARCHIVE_ANDROID_LF_H_INCLUDED

#if __ANDROID_API__ > 20
# include <dirent.h>
# include <fcntl.h>
# include <unistd.h>
# include <sys/stat.h>
# include <sys/statvfs.h>
# include <sys/types.h>
# include <sys/vfs.h>

/* dirent.h */
# define readdir  readdir64
# define dirent   dirent64
/* fcntl.h */
# define openat   openat64
# define open     open64
# define mkstemp  mkstemp64
/* unistd.h */
# define lseek    lseek64
# define ftruncate ftruncate64
/* sys/stat.h */
# define fstatat  fstatat64
# define fstat    fstat64
# define lstat    lstat64
# define stat     stat64
/* sys/statvfs.h */
# define fstatvfs fstatvfs64
# define statvfs  statvfs64
/* sys/types.h */
# define off_t    off64_t
/* sys/vfs.h */
# define fstatfs  fstatfs64
# define statfs   statfs64
#endif

#endif /* ARCHIVE_ANDROID_LF_H_INCLUDED */
EOF
fi

# Ensure libuv treats Android like Linux for source selection.
# CMake's vendored cmlibuv only adds linux-specific sources when
# CMAKE_SYSTEM_NAME is exactly "Linux". Android needs the same set
# (epoll, linux-*), otherwise many uv__* symbols are missing at link.
if grep -q 'if(CMAKE_SYSTEM_NAME STREQUAL "Linux")' /work/src/cmake/Utilities/cmlibuv/CMakeLists.txt; then
  sed -i 's/if(CMAKE_SYSTEM_NAME STREQUAL "Linux")/if(CMAKE_SYSTEM_NAME STREQUAL "Linux" OR CMAKE_SYSTEM_NAME STREQUAL "Android")/' \
    /work/src/cmake/Utilities/cmlibuv/CMakeLists.txt
  # Avoid linking -lrt on Android (bionic has no librt; symbols live in libc)
  sed -i 's/list(APPEND uv_libraries dl rt)/list(APPEND uv_libraries dl $<$<STREQUAL:${CMAKE_SYSTEM_NAME},Linux>:rt>)/' \
    /work/src/cmake/Utilities/cmlibuv/CMakeLists.txt
fi


# Provide Android-specific compat for libuv: pthread_setaffinity_np is not
# available on bionic. Define a small inline wrapper mapping to
# sched_setaffinity for the calling thread. This keeps behavior close to
# upstream on Linux while allowing Android builds to proceed.
mkdir -p /work/src/cmake/Utilities/cmlibuv/include
cat > /work/src/cmake/Utilities/cmlibuv/include/uv_android_compat.h <<'EOF'
#ifndef UV_ANDROID_COMPAT_H_
#define UV_ANDROID_COMPAT_H_

#ifdef __ANDROID__
# include <sched.h>
# include <pthread.h>
# include <errno.h>
/* Map missing GNU extensions to sched_* for current thread */
static inline int pthread_setaffinity_np(pthread_t thread,
                                         size_t cpusetsize,
                                         const cpu_set_t* mask) {
  (void)thread; /* Not available on Android; set for calling thread. */
  return sched_setaffinity(0, cpusetsize, mask);
}
static inline int pthread_getaffinity_np(pthread_t thread,
                                         size_t cpusetsize,
                                         cpu_set_t* mask) {
  (void)thread; /* Not available on Android; get for calling thread. */
  return sched_getaffinity(0, cpusetsize, mask);
}
#endif /* __ANDROID__ */

#endif /* UV_ANDROID_COMPAT_H_ */
EOF



# If the build directory was previously configured for a different ABI,
# wipe it to avoid cached toolchain settings forcing a wrong target.
if [ -f /work/build/tools/cmake-${ABI}-api${API_LEVEL}/CMakeCache.txt ]; then
  cached_abi=$(sed -n 's/^CMAKE_ANDROID_ARCH_ABI:.*=//p' \
    /work/build/tools/cmake-${ABI}-api${API_LEVEL}/CMakeCache.txt | head -n1)
  # Derive expected processor string from ABI
  case "${ABI}" in
    arm64-v8a) expected_proc=aarch64;;
    x86_64)    expected_proc=x86_64;;
    *)         expected_proc=${ABI};;
  esac
  cached_proc=$(sed -n 's/^CMAKE_SYSTEM_PROCESSOR:.*=//p' \
    /work/build/tools/cmake-${ABI}-api${API_LEVEL}/CMakeCache.txt | head -n1)
  if [ "x${cached_abi}" != "x${ABI}" ] || [ "x${cached_proc}" != "x${expected_proc}" ]; then
    echo "[i] Clearing stale CMake build dir (ABI='${cached_abi}', PROC='${cached_proc}') expected (ABI='${ABI}', PROC='${expected_proc}')" >&2
    rm -rf /work/build/tools/cmake-${ABI}-api${API_LEVEL}
  fi
fi
cmake -S /work/src/cmake -B /work/build/tools/cmake-${ABI}-api${API_LEVEL} -G Ninja \
  -DCMAKE_SYSTEM_NAME=Android -DCMAKE_ANDROID_NDK=${ANDROID_NDK_HOME} \
  -DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake \
  -DCMAKE_ANDROID_ARCH_ABI=${ABI} -DCMAKE_ANDROID_API=${API_LEVEL} \
  -DANDROID_ABI=${ABI} -DANDROID_PLATFORM=android-${API_LEVEL} \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DBUILD_TESTING=OFF -DBUILD_CursesDialog=OFF \
  -DCMAKE_USE_OPENSSL=OFF -DCMAKE_USE_SYSTEM_CURL=OFF \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_C_FLAGS="-fPIC -D_GNU_SOURCE -include /work/src/cmake/Utilities/cmlibuv/include/uv_android_compat.h" \
  -DCMAKE_CXX_FLAGS="-fPIC -D_GNU_SOURCE -include /work/src/cmake/Utilities/cmlibuv/include/uv_android_compat.h"
ninja -C /work/build/tools/cmake-${ABI}-api${API_LEVEL} -j$(nproc) cmake || true
if [ -f /work/build/tools/cmake-${ABI}-api${API_LEVEL}/bin/cmake ]; then
  cp -af /work/build/tools/cmake-${ABI}-api${API_LEVEL}/bin/cmake /hostout/${ABI}/tools/bin/
  if [ -x "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" ]; then
    ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip -S /hostout/${ABI}/tools/bin/cmake || true
  fi
  chmod 0755 /hostout/${ABI}/tools/bin/cmake
else
  echo "[w] cmake binary not produced; consider relaxing options or building dependencies" >&2
fi

# Optionally attempt libcmake_runner.so (best-effort; can fail due to missing libs)
if [ "${BUILD_CMAKE_SO}" = "True" ] || [ "${BUILD_CMAKE_SO}" = "true" ] || [ "${BUILD_CMAKE_SO}" = "1" ]; then
  case "${ABI}" in
    arm64-v8a) TRIPLE=aarch64-linux-android;;
    x86_64)    TRIPLE=x86_64-linux-android;;
  esac
  NDK_CLANGXX="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/${TRIPLE}${API_LEVEL}-clang++"
  mkdir -p /work/build/tools/cmake-runner
  cat > /work/build/tools/cmake-runner/cmake_runner.cpp <<'EOF'
extern "C" int main(int, char**);
extern "C" int cmake_run(int argc, char** argv) { return main(argc, argv); }
EOF
  cmake_objs=$(find /work/build/tools/cmake-${ABI}-api${API_LEVEL} -path '*/Source/CMakeFiles/cmake.dir/*' -name '*.o' | xargs echo)
  if [ -n "${cmake_objs}" ] && [ -x "${NDK_CLANGXX}" ]; then
    ${NDK_CLANGXX} -shared -fPIC -Wl,-z,now -Wl,-z,relro \
      -o /hostout/${ABI}/tools/bin/libcmake_runner.so \
      ${cmake_objs} /work/build/tools/cmake-runner/cmake_runner.cpp -llog -landroid || \
      echo "[w] libcmake_runner.so link failed; continuing"
  else
    echo "[w] Could not locate cmake objects or NDK clang++ for runner build; skipping"
  fi
fi
'@

Write-Info "Building tools for ABI=$Abi (API=$ApiLevel) in container: $ContainerName"
# Build a single multi-line command (true newlines) for bash -lc
$cmd = @"
CMAKE_TAG='$CMakeTag'
$assign
$session
"@
Exec-In-Dev $cmd
Write-Info "Tools built. Output at: $(Join-Path $outDirHost 'tools/bin')"
Write-Info "Done."
