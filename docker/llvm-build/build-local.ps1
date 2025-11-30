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
  # Host-side developer tools
  [bool]$BuildClangdHost = $true,
  [bool]$BuildLlvmDebugToolsHost = $true
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
  param([string]$containerName,[string]$ndkVersion)
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
    & docker run -d --name $containerName -w /work `
      -v "$($workHost):/work" `
      -v "$($outputBase):/hostout" `
      $devImage | Out-Null
  }
}

function Exec-In-Dev { param([string]$cmd) & docker exec $ContainerName bash -lc $cmd }

Ensure-DevContainer -containerName $ContainerName -ndkVersion $NdkVersion

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
if [ ! -d /work/src/llvm-project/.git ]; then
  git clone --depth=1 --branch ${LLVM_TAG} https://github.com/llvm/llvm-project.git /work/src/llvm-project
fi
cd /work/src/llvm-project
# Sync existing clone to requested tag/branch when the source already exists (faster incremental builds)
git fetch origin --tags --depth=1 || true
git fetch origin ${LLVM_TAG} --depth=1 || true
git checkout --force ${LLVM_TAG} || git checkout --force origin/${LLVM_TAG} || git checkout --force "$(git rev-parse --abbrev-ref origin/HEAD)"
git submodule update --init --recursive || true
cd /
if [ ! -x /work/build/host/bin/llvm-tblgen ]; then
  cmake -S /work/src/llvm-project/llvm -B /work/build/host -G Ninja \
    -DLLVM_ENABLE_PROJECTS="clang;clang-tools-extra;lld" -DLLVM_TARGETS_TO_BUILD="AArch64;X86" -DCMAKE_BUILD_TYPE=RelWithDebInfo
  ninja -C /work/build/host -j$(nproc) llvm-tblgen clang-tblgen || true
fi
# Optionally build host developer tools (clangd and common llvm debug tools) and copy to hostout tools/bin
mkdir -p /hostout/${ABI}/tools/bin
if [ "${BUILD_CLANGD_HOST}" = "True" ] || [ "${BUILD_CLANGD_HOST}" = "true" ] || [ "${BUILD_CLANGD_HOST}" = "1" ]; then
  ninja -C /work/build/host -j$(nproc) clangd || true
  if [ -f /work/build/host/bin/clangd ]; then cp -af /work/build/host/bin/clangd /hostout/${ABI}/tools/bin/clangd-host; fi
fi
if [ "${BUILD_LLVM_DEBUG_TOOLS_HOST}" = "True" ] || [ "${BUILD_LLVM_DEBUG_TOOLS_HOST}" = "true" ] || [ "${BUILD_LLVM_DEBUG_TOOLS_HOST}" = "1" ]; then
  ninja -C /work/build/host -j$(nproc) llvm-symbolizer llvm-objdump llvm-dwarfdump || true
  for t in llvm-symbolizer llvm-objdump llvm-dwarfdump; do if [ -f /work/build/host/bin/${t} ]; then cp -af /work/build/host/bin/${t} /hostout/${ABI}/tools/bin/${t}-host; fi; done
fi
# Configure control via MODE: incremental | reconfigure | clean
if [ "${MODE}" = "clean" ]; then
  rm -rf /work/build/android/${ABI}-api${API_LEVEL} || true
fi
if [ "${MODE}" = "reconfigure" ] || [ "${MODE}" = "clean" ]; then
  rm -f /work/build/android/${ABI}-api${API_LEVEL}/CMakeCache.txt || true
  rm -rf /work/build/android/${ABI}-api${API_LEVEL}/CMakeFiles || true
fi
cmake -S /work/src/llvm-project/llvm -B /work/build/android/${ABI}-api${API_LEVEL} -G Ninja \
  -DLLVM_ENABLE_PROJECTS="clang;lld" -DLLVM_TARGETS_TO_BUILD="${LLVM_TARGET}" -DCMAKE_BUILD_TYPE=${ANDROID_BUILD_TYPE} \
  -DCMAKE_SYSTEM_NAME=Android -DCMAKE_ANDROID_NDK=${ANDROID_NDK_HOME} -DCMAKE_ANDROID_ARCH_ABI=${ABI} -DCMAKE_ANDROID_API=${API_LEVEL} \
  -DLLVM_TABLEGEN=/work/build/host/bin/llvm-tblgen -DCLANG_TABLEGEN=/work/build/host/bin/clang-tblgen \
  -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF -DLLVM_INCLUDE_BENCHMARKS=OFF \
  -DLLVM_ENABLE_TERMINFO=OFF -DLLVM_ENABLE_LIBEDIT=OFF -DLLVM_ENABLE_CURSES=OFF \
  -DLLVM_ENABLE_ZLIB=OFF -DLLVM_ENABLE_ZSTD=OFF -DLLVM_ENABLE_LIBXML2=OFF \
  -DCLANG_ENABLE_ARCMT=OFF -DCLANG_ENABLE_STATIC_ANALYZER=OFF \
  -DLLVM_BUILD_TOOLS=OFF -DLLVM_BUILD_LLVM_DYLIB=ON -DLLVM_LINK_LLVM_DYLIB=ON -DCLANG_LINK_CLANG_DYLIB=ON \
  -DLLVM_ENABLE_THREADS=OFF \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_C_FLAGS="-femulated-tls" -DCMAKE_CXX_FLAGS="-femulated-tls" \
  -DLLVM_ENABLE_ASSERTIONS=$([ "${ENABLE_ASSERTIONS}" = "True" ] || [ "${ENABLE_ASSERTIONS}" = "true" ] || [ "${ENABLE_ASSERTIONS}" = "1" ] && echo ON || echo OFF) \
  -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=OFF
ninja -C /work/build/android/${ABI}-api${API_LEVEL} -j$(nproc) clang-cpp lld lldELF lldCommon

# Clean destination to avoid duplicate/readonly collisions on host mounts
rm -rf /hostout/${ABI}/libs/${ABI} /hostout/${ABI}/sysroot /hostout/${ABI}/include || true
mkdir -p /hostout/${ABI}/libs/${ABI} /hostout/${ABI}/sysroot/usr/include /hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/${API_LEVEL}
mkdir -p /hostout/${ABI}/include/clang-c /hostout/${ABI}/include/clang /hostout/${ABI}/include/llvm /hostout/${ABI}/include/lld
mkdir -p /hostout/${ABI}/tools/bin || true
cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/libclang-cpp.so* /hostout/${ABI}/libs/${ABI}/ || true
cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/libLLVM*.so*    /hostout/${ABI}/libs/${ABI}/ || true
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
cp -af /work/build/android/${ABI}-api${API_LEVEL}/lib/libLLVM*.so*    /hostout/${ABI}/sysroot/usr/lib/${TRIPLE}/runtime/ || true
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
  $assign = "ABI='$currentAbi'; API_LEVEL='$ApiLevel'; LLVM_TAG='$LlvmTag'; NDK_VERSION='$NdkVersion'; MODE='$Mode'; ANDROID_BUILD_TYPE='$AndroidBuildType'; ENABLE_ASSERTIONS='$EnableAssertions'; BUILD_CLANGD_HOST='$BuildClangdHost'; BUILD_LLVM_DEBUG_TOOLS_HOST='$BuildLlvmDebugToolsHost';"
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
