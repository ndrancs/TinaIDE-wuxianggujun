Param(
  [ValidateSet('libs','exec')][string]$Mode = 'libs',
  [ValidateSet('arm64-v8a','x86_64')][string]$Abi = 'arm64-v8a',
  [int]$ApiLevel = 24,
  [string]$NdkVersion = 'r26d',
  [string]$LlvmTag = 'llvmorg-17.0.6',
  [string]$ContainerName = 'tina-ndk-dev',
  [string]$OutBaseLibs,
  [string]$OutBaseExec
)

$ErrorActionPreference = 'Stop'

function Write-Info($msg) { Write-Host "[i] $msg" -ForegroundColor Cyan }
function Write-Err($msg)  { Write-Host "[!] $msg" -ForegroundColor Red }

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $scriptRoot '..\..')

# Determine output base directories (allow override via parameters)
if (-not $OutBaseLibs -or [string]::IsNullOrWhiteSpace($OutBaseLibs)) {
  $OutBaseLibs = Join-Path $root 'external/embedded-ndk-libs'
}
if (-not $OutBaseExec -or [string]::IsNullOrWhiteSpace($OutBaseExec)) {
  $OutBaseExec = Join-Path $root 'external/embedded-ndk'
}

# Ensure they exist and resolve to absolute normalized paths (only for the mode being used)
if ($Mode -eq 'libs') {
  New-Item -ItemType Directory -Force -Path $OutBaseLibs | Out-Null
  $libsBase = (Resolve-Path $OutBaseLibs).Path
  $execBase = $OutBaseExec  # Just store the path, don't create directory
} else {
  New-Item -ItemType Directory -Force -Path $OutBaseExec | Out-Null
  $execBase = (Resolve-Path $OutBaseExec).Path
  $libsBase = $OutBaseLibs  # Just store the path, don't create directory
}

function Ensure-DevContainer {
  param([string]$containerName,[string]$ndkVersion)
  $devImage = "embedded-ndk-dev:$ndkVersion"
  Write-Info "Ensuring dev image: $devImage"
  & docker build -f (Join-Path $root 'docker/embedded-ndk/Dockerfile.dev') --build-arg NDK_VERSION=$ndkVersion -t $devImage $root
  $running = (& docker ps --format '{{.Names}}' | Select-String -SimpleMatch $containerName) -ne $null
  if (-not $running) {
    $exists = (& docker ps -a --format '{{.Names}}' | Select-String -SimpleMatch $containerName) -ne $null
    if ($exists) { & docker rm -f $containerName | Out-Null }
    $workHost = Join-Path $root 'docker/embedded-ndk/dev-work'
    New-Item -ItemType Directory -Force -Path $workHost | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $workHost 'src'), (Join-Path $workHost 'build'), (Join-Path $workHost 'out') | Out-Null
    Write-Info "Starting dev container: $containerName"
    & docker run -d --name $containerName -w /work `
      -v "$($workHost):/work" `
      -v "$($libsBase):/hostout-libs" `
      -v "$($execBase):/hostout-exec" `
      $devImage | Out-Null
  }
}

function Exec-In-Dev { param([string]$cmd) & docker exec $ContainerName bash -lc $cmd }

Ensure-DevContainer -containerName $ContainerName -ndkVersion $NdkVersion

if ($Mode -eq 'libs') {
  $outDirHost = Join-Path $OutBaseLibs "${Abi}"
  New-Item -ItemType Directory -Force -Path $outDirHost | Out-Null
  $assign = "ABI='$Abi'; API_LEVEL='$ApiLevel'; LLVM_TAG='$LlvmTag'; NDK_VERSION='$NdkVersion';"
  $sessionScript = @'
set -eux
case "${ABI}" in
  arm64-v8a) TRIPLE=aarch64-linux-android; LLVM_TARGET=AArch64;;
  x86_64)    TRIPLE=x86_64-linux-android; LLVM_TARGET=X86;;
  *) echo "Unsupported ABI: ${ABI}"; exit 1;;
esac
mkdir -p /work/src /work/build/host /work/build/android/${ABI}-api${API_LEVEL} /hostout-libs/${ABI}
if [ ! -d /work/src/llvm-project/.git ]; then
  git clone --depth=1 --branch ${LLVM_TAG} https://github.com/llvm/llvm-project.git /work/src/llvm-project
fi
if [ ! -x /work/build/host/bin/llvm-tblgen ]; then
  cmake -S /work/src/llvm-project/llvm -B /work/build/host -G Ninja \
    -DLLVM_ENABLE_PROJECTS="clang;lld" -DLLVM_TARGETS_TO_BUILD="AArch64;X86" -DCMAKE_BUILD_TYPE=Release
  ninja -C /work/build/host -j$(nproc) llvm-tblgen clang-tblgen
fi
cmake -S /work/src/llvm-project/llvm -B /work/build/android/${ABI}-api${API_LEVEL} -G Ninja \
  -DLLVM_ENABLE_PROJECTS="clang;lld" -DLLVM_TARGETS_TO_BUILD="${LLVM_TARGET}" -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_SYSTEM_NAME=Android -DCMAKE_ANDROID_NDK=${ANDROID_NDK_HOME} -DCMAKE_ANDROID_ARCH_ABI=${ABI} -DCMAKE_ANDROID_API=${API_LEVEL} \
  -DLLVM_TABLEGEN=/work/build/host/bin/llvm-tblgen -DCLANG_TABLEGEN=/work/build/host/bin/clang-tblgen \
  -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF -DLLVM_INCLUDE_BENCHMARKS=OFF \
  -DLLVM_ENABLE_TERMINFO=OFF -DLLVM_ENABLE_LIBEDIT=OFF -DLLVM_ENABLE_CURSES=OFF \
  -DLLVM_ENABLE_ZLIB=OFF -DLLVM_ENABLE_ZSTD=OFF -DLLVM_ENABLE_LIBXML2=OFF \
  -DCLANG_ENABLE_ARCMT=OFF -DCLANG_ENABLE_STATIC_ANALYZER=OFF \
  -DLLVM_BUILD_TOOLS=OFF -DLLVM_BUILD_LLVM_DYLIB=ON -DLLVM_LINK_LLVM_DYLIB=ON -DCLANG_LINK_CLANG_DYLIB=ON \
  -DLLVM_ENABLE_ASSERTIONS=OFF -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON
ninja -C /work/build/android/${ABI}-api${API_LEVEL} -j$(nproc) clang-cpp lld

# Clean destination to avoid duplicate/readonly collisions on host mounts
rm -rf /hostout-libs/${ABI}/libs/${ABI} /hostout-libs/${ABI}/sysroot /hostout-libs/${ABI}/include || true
mkdir -p /hostout-libs/${ABI}/libs/${ABI} /hostout-libs/${ABI}/sysroot/usr/include /hostout-libs/${ABI}/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}
mkdir -p /hostout-libs/${ABI}/include/clang-c /hostout-libs/${ABI}/include/clang /hostout-libs/${ABI}/include/llvm /hostout-libs/${ABI}/include/lld
cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/libclang-cpp.so* /hostout-libs/${ABI}/libs/${ABI}/ || true
cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/libLLVM*.so*    /hostout-libs/${ABI}/libs/${ABI}/ || true
if compgen -G "/work/build/android/${ABI}-api${API_LEVEL}/lib/liblld*.so*" > /dev/null; then
  cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/liblld*.so* /hostout-libs/${ABI}/libs/${ABI}/
fi
# strip .so to minimize size (use NDK's llvm-strip)
if [ -x "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" ]; then
  STRIP_BIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  for so in /hostout-libs/${ABI}/libs/${ABI}/*.so*; do
    [ -f "$so" ] && $STRIP_BIN -S "$so" || true
  done
fi
if [ -d "${ANDROID_NDK_HOME}/sources/cxx-stl/llvm-libc++/libs/${ABI}" ]; then
  cp -af ${ANDROID_NDK_HOME}/sources/cxx-stl/llvm-libc++/libs/${ABI}/libc++_shared.so /hostout-libs/${ABI}/libs/${ABI}/ || true
fi
cp -af ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/. /hostout-libs/${ABI}/sysroot/usr/include/
# Ensure libc++ headers are present under sysroot/usr/include/c++/v1 (NDK layout varies by version)
mkdir -p /hostout-libs/${ABI}/sysroot/usr/include/c++/v1 || true
# Try prebuilt include path (newer NDKs)
cp -af ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/include/c++/v1/. /hostout-libs/${ABI}/sysroot/usr/include/c++/v1/ 2>/dev/null || true
# If still missing, try legacy sources path (older NDKs)
if [ ! -f /hostout-libs/${ABI}/sysroot/usr/include/c++/v1/__ios/fpos.h ]; then
  if [ -d "${ANDROID_NDK_HOME}/sources/cxx-stl/llvm-libc++/include" ]; then
    cp -af ${ANDROID_NDK_HOME}/sources/cxx-stl/llvm-libc++/include/. /hostout-libs/${ABI}/sysroot/usr/include/c++/v1/
  fi
fi
# Final verification: fail early if libc++ core headers not found
if [ ! -f /hostout-libs/${ABI}/sysroot/usr/include/c++/v1/__ios/fpos.h ]; then
  echo "[ERROR] libc++ headers not found in NDK. Checked prebuilt include/c++/v1 and sources/cxx-stl/llvm-libc++/include" >&2
  exit 2
fi

# Copy Clang resource headers (builtin headers like stdarg.h) into sysroot/lib/clang/<ver>/include
verdir=$(ls -d /work/build/android/${ABI}-api${API_LEVEL}/lib/clang/* 2>/dev/null | head -n1 || true)
if [ -n "$verdir" ] && [ -d "$verdir/include" ]; then
  verbase=$(basename "$verdir")
  # Place resource headers under the real version dir
  mkdir -p /hostout-libs/${ABI}/sysroot/lib/clang/${verbase}
  cp -af "$verdir/include" /hostout-libs/${ABI}/sysroot/lib/clang/${verbase}/
  # Also provide a stable alias "17" for runtime -resource-dir compatibility
  major=${verbase%%.*}
  [ -z "$major" ] && major=17
  mkdir -p /hostout-libs/${ABI}/sysroot/lib/clang/${major}
  cp -af "$verdir/include" /hostout-libs/${ABI}/sysroot/lib/clang/${major}/
else
  echo "[WARN] Clang resource headers not found under build output; falling back to clang/lib/Headers" >&2
  # Fallback: copy from clang source tree
  if [ -d /work/src/llvm-project/clang/lib/Headers ]; then
    mkdir -p /hostout-libs/${ABI}/sysroot/lib/clang/17/include
    cp -af /work/src/llvm-project/clang/lib/Headers/. /hostout-libs/${ABI}/sysroot/lib/clang/17/include/
  else
    echo "[ERROR] No clang resource headers available (build and source both missing)" >&2
    exit 2
  fi
fi
cp -af ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}/. /hostout-libs/${ABI}/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}/
cp -af /work/src/llvm-project/clang/include/clang-c/. /hostout-libs/${ABI}/include/clang-c/ || true
cp -a /work/src/llvm-project/clang/include/. /hostout-libs/${ABI}/include/clang/ || true
cp -a /work/src/llvm-project/llvm/include/.  /hostout-libs/${ABI}/include/llvm/  || true
cp -a /work/src/llvm-project/lld/include/.   /hostout-libs/${ABI}/include/lld/   || true
cp -a /work/build/android/${ABI}-api${API_LEVEL}/include/.          /hostout-libs/${ABI}/include/       || true
printf "MODE=shared-libs\nNDK=%s\nLLVM_TAG=%s\nABI=%s\nAPI_LEVEL=%s\nTRIPLE=%s\n" "${NDK_VERSION}" "${LLVM_TAG}" "${ABI}" "${API_LEVEL}" "${TRIPLE}" > /hostout-libs/${ABI}/MANIFEST
(cd /hostout-libs/${ABI} && find . -type f -print0 | sort -z | xargs -0 sha256sum) > /hostout-libs/${ABI}/SHA256SUMS || true
(cd /hostout-libs/${ABI} && zip -qr embedded-ndk-libs-${ABI}-api${API_LEVEL}.zip .)
'@
  Exec-In-Dev "$assign`n$sessionScript"
  Write-Info "Artifacts ready at: $outDirHost"
} else {
  $outDirHost = Join-Path $OutBaseExec "${Abi}"
  New-Item -ItemType Directory -Force -Path $outDirHost | Out-Null
  $assign = "ABI='$Abi'; API_LEVEL='$ApiLevel'; LLVM_TAG='$LlvmTag'; NDK_VERSION='$NdkVersion';"
  $sessionScript = @'
set -eux
case "${ABI}" in
  arm64-v8a) TRIPLE=aarch64-linux-android; LLVM_TARGET=AArch64;;
  x86_64)    TRIPLE=x86_64-linux-android; LLVM_TARGET=X86;;
  *) echo "Unsupported ABI: ${ABI}"; exit 1;;
esac
mkdir -p /work/src /work/build/host /work/build/android/${ABI}-api${API_LEVEL} /hostout-exec/${ABI}
if [ ! -d /work/src/llvm-project/.git ]; then
  git clone --depth=1 --branch ${LLVM_TAG} https://github.com/llvm/llvm-project.git /work/src/llvm-project
fi
if [ ! -x /work/build/host/bin/llvm-tblgen ]; then
  cmake -S /work/src/llvm-project/llvm -B /work/build/host -G Ninja \
    -DLLVM_ENABLE_PROJECTS="clang;lld" -DLLVM_TARGETS_TO_BUILD="AArch64;X86" -DCMAKE_BUILD_TYPE=Release
  ninja -C /work/build/host -j$(nproc) llvm-tblgen clang-tblgen
fi
cmake -S /work/src/llvm-project/llvm -B /work/build/android/${ABI}-api${API_LEVEL} -G Ninja \
  -DLLVM_ENABLE_PROJECTS="clang;lld" -DLLVM_TARGETS_TO_BUILD="${LLVM_TARGET}" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_SYSTEM_NAME=Android -DCMAKE_ANDROID_NDK=${ANDROID_NDK_HOME} -DCMAKE_ANDROID_ARCH_ABI=${ABI} -DCMAKE_ANDROID_API=${API_LEVEL} \
  -DLLVM_TABLEGEN=/work/build/host/bin/llvm-tblgen -DCLANG_TABLEGEN=/work/build/host/bin/clang-tblgen \
  -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF -DLLVM_INCLUDE_BENCHMARKS=OFF \
  -DLLVM_ENABLE_TERMINFO=OFF -DLLVM_ENABLE_LIBEDIT=OFF -DLLVM_ENABLE_CURSES=OFF \
  -DLLVM_ENABLE_ZLIB=OFF -DLLVM_ENABLE_ZSTD=OFF -DLLVM_ENABLE_LIBXML2=OFF \
  -DCLANG_ENABLE_ARCMT=OFF -DCLANG_ENABLE_STATIC_ANALYZER=OFF \
  -DLLVM_BUILD_TOOLS=ON
ninja -C /work/build/android/${ABI}-api${API_LEVEL} -j$(nproc) clang lld llvm-ar llvm-ranlib llvm-strip

mkdir -p /hostout-exec/${ABI}/toolchains/${ABI}/bin /hostout-exec/${ABI}/sysroot/usr/include /hostout-exec/${ABI}/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}
cp /work/build/android/${ABI}-api${API_LEVEL}/bin/clang       /hostout-exec/${ABI}/toolchains/${ABI}/bin/
cp /work/build/android/${ABI}-api${API_LEVEL}/bin/clang       /hostout-exec/${ABI}/toolchains/${ABI}/bin/clang++
cp /work/build/android/${ABI}-api${API_LEVEL}/bin/ld.lld      /hostout-exec/${ABI}/toolchains/${ABI}/bin/
cp /work/build/android/${ABI}-api${API_LEVEL}/bin/llvm-ar     /hostout-exec/${ABI}/toolchains/${ABI}/bin/
cp /work/build/android/${ABI}-api${API_LEVEL}/bin/llvm-ranlib /hostout-exec/${ABI}/toolchains/${ABI}/bin/
cp /work/build/android/${ABI}-api${API_LEVEL}/bin/llvm-strip  /hostout-exec/${ABI}/toolchains/${ABI}/bin/
cp -a ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/. /hostout-exec/${ABI}/sysroot/usr/include/
cp -a ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}/. /hostout-exec/${ABI}/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}/
printf "NDK=%s\nLLVM_TAG=%s\nABI=%s\nAPI_LEVEL=%s\nTRIPLE=%s\n" "${NDK_VERSION}" "${LLVM_TAG}" "${ABI}" "${API_LEVEL}" "${TRIPLE}" > /hostout-exec/${ABI}/MANIFEST
(cd /hostout-exec/${ABI} && find . -type f -print0 | sort -z | xargs -0 sha256sum) > /hostout-exec/${ABI}/SHA256SUMS || true
(cd /hostout-exec/${ABI} && zip -qr embedded-ndk-${ABI}-api${API_LEVEL}.zip .)
'@
  Exec-In-Dev "$assign`n$sessionScript"
  Write-Info "Artifacts ready at: $outDirHost"
}

Write-Info "Done."
