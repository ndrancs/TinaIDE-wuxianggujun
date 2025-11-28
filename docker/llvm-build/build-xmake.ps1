Param(
  [string[]]$Abi = @('arm64-v8a','x86_64'),
  [int]$ApiLevel = 28,
  [string]$ContainerName = 'tina-llvm-build',
  [string]$OutputPath,
  [string]$XmakeRepoUrl = 'https://github.com/wuxianggujun/xmake.git',
  [string]$XmakeRef = 'master',
  # 是否清理构建目录（默认 false，支持增量编译）
  [switch]$Clean = $false
)

$ErrorActionPreference = 'Stop'

function Write-Info($msg) { Write-Host "[i] $msg" -ForegroundColor Cyan }
function Write-Err($msg)  { Write-Host "[!] $msg" -ForegroundColor Red }
function Sync-XmakeOverlay {
  param([string]$Source,[string]$Target)
  if (-not (Test-Path $Source -PathType Container)) {
    if (Test-Path $Target) {
      Remove-Item -Recurse -Force $Target | Out-Null
    }
    return $false
  }
  New-Item -ItemType Directory -Force -Path $Target | Out-Null
  $cmd = "robocopy `"$Source`" `"$Target`" /MIR /NFL /NDL /NJH /NJS /nc /ns /np"
  cmd /c $cmd | Out-Null
  $rc = $LASTEXITCODE
  if ($rc -gt 3) {
    Write-Err "Failed to synchronize external/xmake overlay (robocopy exit $rc)"
    exit $rc
  }
  return $true
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $scriptRoot '..\..')

$overlaySource = Join-Path $root 'external/xmake'
$overlayTarget = Join-Path $root 'docker/llvm-build/dev-work/overlays/xmake'
$overlaySynced = Sync-XmakeOverlay -Source $overlaySource -Target $overlayTarget
if ($overlaySynced) {
  Write-Info "Synced xmake overlay from $overlaySource"
}

$templateRunnerPath = Join-Path $root 'docker/llvm-build/templates/xmake_runner.cpp'
if (-not (Test-Path $templateRunnerPath)) {
  Write-Err "Missing xmake runner template: $templateRunnerPath"
  exit 2
}
$runnerCppSource = Get-Content -Path $templateRunnerPath -Raw -Encoding UTF8
$runnerCppSource = $runnerCppSource -replace "`r", ""
if (-not $runnerCppSource.EndsWith("`n")) {
  $runnerCppSource += "`n"
}

if (-not $OutputPath -or [string]::IsNullOrWhiteSpace($OutputPath)) {
  $OutputPath = Join-Path $root 'docker/llvm-build/build-output'
}
$outBase = (Resolve-Path $OutputPath).Path

# Ensure dev container is running
$running = (& docker ps --format '{{.Names}}' | Select-String -SimpleMatch $ContainerName) -ne $null
if (-not $running) {
  Write-Err "Dev container '$ContainerName' not running. Please run docker/llvm-build/build-local.ps1 once to create it."
  exit 2
}

function Exec-In-Dev { param([string]$cmd) & docker exec $ContainerName bash -lc $cmd }

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

$cleanFlag = if ($Clean) { "1" } else { "0" }
$session = @'
set -eux
case "${ABI}" in
  arm64-v8a) TRIPLE=aarch64-linux-android;;
  x86_64)    TRIPLE=x86_64-linux-android;;
  *) echo "Unsupported ABI: ${ABI}"; exit 1;;
esac

TOOLCHAIN_BIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "${TOOLCHAIN_BIN}" ]; then
  echo "[!] LLVM toolchain not found at ${TOOLCHAIN_BIN}"
  exit 2
fi
CC="${TOOLCHAIN_BIN}/${TRIPLE}${API_LEVEL}-clang"
CXX="${TOOLCHAIN_BIN}/${TRIPLE}${API_LEVEL}-clang++"
AR="${TOOLCHAIN_BIN}/llvm-ar"
RANLIB="${TOOLCHAIN_BIN}/llvm-ranlib"
STRIP="${TOOLCHAIN_BIN}/llvm-strip"
LD="${TOOLCHAIN_BIN}/ld.lld"

SRC_DIR="/work/src/xmake"
if [ ! -d "${SRC_DIR}/.git" ]; then
  rm -rf "${SRC_DIR}"
  git clone --depth=1 "${XMAKE_REPO}" "${SRC_DIR}"
fi
cd "${SRC_DIR}"
git fetch origin --tags --depth=1 || true
if [ -n "${XMAKE_REF}" ]; then
  git fetch origin "${XMAKE_REF}" --depth=1 || true
  git checkout --force "${XMAKE_REF}" || git checkout --force origin/"${XMAKE_REF}" || git checkout --force "$(git rev-parse --abbrev-ref origin/HEAD)"
else
  git checkout --force "$(git rev-parse --abbrev-ref origin/HEAD)"
fi
git submodule update --init --recursive

OVERLAY_DIR="/work/overlays/xmake"
if [ -d "${OVERLAY_DIR}" ] && [ "$(ls -A "${OVERLAY_DIR}")" ]; then
  echo "[i] Applying TinaIDE xmake overlay from ${OVERLAY_DIR}"
  cp -af "${OVERLAY_DIR}/." "${SRC_DIR}/"
fi

# ============================================================================
# TinaIDE: Patch configure script to add is_os() function
# xmake.sh (configure) only has is_plat(), but tbox's xmake.lua uses is_os()
# We need to add is_os() so that "if is_os('android')" works correctly
# ============================================================================
echo "[i] Patching configure script to add is_os() function..."
if ! grep -q "^is_os()" "${SRC_DIR}/configure"; then
  # Insert is_os() function after is_plat() function
  sed -i '/^is_plat() {/,/^}$/{ /^}$/a\
\
# determining target os (for Android, os == plat)\
# TinaIDE: xmake.sh originally does not have is_os(), but tbox xmake.lua uses it\
is_os() {\
    local os=""\
    for os in $@; do\
        if test_eq "${_target_plat}" "${os}"; then\
            return 0\
        fi\
    done\
    return 1\
}
}' "${SRC_DIR}/configure"
  echo "[i] is_os() function added to configure script"
else
  echo "[i] is_os() function already exists in configure script"
fi

# Ensure Android config header exists for tbox (fallback to linux config)
if [ ! -d core/src/tbox/inc/android ]; then
  mkdir -p core/src/tbox/inc/android
fi
if [ ! -f core/src/tbox/inc/android/tbox.config.h ]; then
  if [ -f core/src/tbox/inc/linux/tbox.config.h ]; then
    cp core/src/tbox/inc/linux/tbox.config.h core/src/tbox/inc/android/tbox.config.h
  else
    echo "[!] Missing linux tbox.config.h to bootstrap Android config"
    exit 2
  fi
fi

BUILD_DIR="/work/build/tools/xmake-${ABI}-api${API_LEVEL}"
INSTALL_ROOT="/work/build/tools/xmake-install-${ABI}-api${API_LEVEL}"
PREFIX="/usr"

# 根据 CLEAN_BUILD 参数决定是否清理构建目录
if [ "${CLEAN_BUILD}" = "1" ]; then
  echo "[i] Clean build requested, removing BUILD_DIR and Makefile..."
  rm -rf "${BUILD_DIR}"
  # 删除旧的 Makefile，强制 configure 重新生成
  rm -f "${SRC_DIR}/Makefile"
fi
rm -rf "${INSTALL_ROOT}"
mkdir -p "${BUILD_DIR}" "${INSTALL_ROOT}"

export CC CXX AR RANLIB STRIP LD
export CFLAGS="-fPIE -fPIC -DANDROID -D__ANDROID_API__=${API_LEVEL}"
export CXXFLAGS="-fPIE -fPIC -DANDROID -D__ANDROID_API__=${API_LEVEL}"
export LDFLAGS="-fPIE -pie"
export PKG_CONFIG_PATH=""
export CCACHE_DISABLE=1

./configure \
  --plat=android \
  --arch="${ABI}" \
  --mode=minsize \
  --toolchain=clang \
  --builddir="${BUILD_DIR}" \
  --prefix="${PREFIX}" \
  --host="${TRIPLE}"

sed -i "s|^cc=.*|cc=${CC}|g" "${SRC_DIR}/Makefile"
sed -i "s|^cxx=.*|cxx=${CXX}|g" "${SRC_DIR}/Makefile"
sed -i "s|^as=.*|as=${CC}|g" "${SRC_DIR}/Makefile"
sed -i "s|^ld=.*|ld=${CXX}|g" "${SRC_DIR}/Makefile"
sed -i "s|^ar=.*|ar=${AR}|g" "${SRC_DIR}/Makefile"
sed -i 's/-lpthread//g' "${SRC_DIR}/Makefile"

# 添加 -llog 链接标志（Android 日志库）
echo "[i] Adding -llog to linker flags..."
sed -i 's/-latomic/-latomic -llog/g' "${SRC_DIR}/Makefile"

# 检查 Makefile 是否包含 platform/android 文件
echo "[i] Checking if Makefile includes Android platform files..."
if grep -q "platform/android" "${SRC_DIR}/Makefile"; then
  echo "[i] Android platform files are included in Makefile"
else
  echo "[!] Warning: Android platform files NOT found in Makefile"
  echo "[!] The is_os() patch may not have worked correctly"
fi

# 构建所有目标
make -j"$(nproc)"
make DESTDIR="${INSTALL_ROOT}" install

# remove standalone CLI binaries (not needed inside TinaIDE sysroot)
rm -f "${INSTALL_ROOT}${PREFIX}/bin/xmake" "${INSTALL_ROOT}${PREFIX}/bin/xrepo" || true

# Re-link CLI objects into shared lib (libxmake_runner.so)
RUNNER_DIR="/work/build/tools/xmake-runner"
mkdir -p "${RUNNER_DIR}"
cat > "${RUNNER_DIR}/xmake_runner.cpp" <<'EOF_RUNNER'
@@RUNNER_CPP@@
EOF_RUNNER
CLI_OBJ_LIST="$(mktemp)"
find "/work/build/tools/xmake-${ABI}-api${API_LEVEL}/.objs/cli/android/${ABI}/minsize" -name '*.o' > "${CLI_OBJ_LIST}"
if [ ! -s "${CLI_OBJ_LIST}" ]; then
  echo "[!] No CLI object files found; cannot build libxmake_runner.so"
  exit 2
fi
LIB_DIR="/work/build/tools/xmake-${ABI}-api${API_LEVEL}/android/${ABI}/minsize"
OUT_SO="/hostout/${ABI}/tools/bin/libxmake_runner.so"
"${CXX}" -shared -fPIC -Wl,-z,now -Wl,-z,relro \
  -o "${OUT_SO}" \
  @"${CLI_OBJ_LIST}" \
  "${LIB_DIR}/libxmake.a" \
  "${LIB_DIR}/liblua_cjson.a" \
  "${LIB_DIR}/liblz4.a" \
  "${LIB_DIR}/libsv.a" \
  "${LIB_DIR}/libtbox.a" \
  "${LIB_DIR}/liblua.a" \
  "${RUNNER_DIR}/xmake_runner.cpp" \
  -lc++_shared -llog -landroid -latomic -ldl -lm
rm -f "${CLI_OBJ_LIST}"
if [ -f "${OUT_SO}" ]; then
  chmod 0755 "${OUT_SO}"
  "${STRIP}" -S "${OUT_SO}" || true
fi

HOST_BIN="/hostout/${ABI}/tools/bin"
SYSROOT_BASE="/hostout/${ABI}/sysroot/usr"
mkdir -p "${HOST_BIN}" "${SYSROOT_BASE}/bin" "${SYSROOT_BASE}/share" "${SYSROOT_BASE}/lib"

for dir in bin share lib; do
  if [ -d "${INSTALL_ROOT}${PREFIX}/${dir}" ]; then
    mkdir -p "${SYSROOT_BASE}/${dir}"
    cp -af "${INSTALL_ROOT}${PREFIX}/${dir}/." "${SYSROOT_BASE}/${dir}/"
  fi
done

if [ -f "${OUT_SO}" ]; then
  mkdir -p "${SYSROOT_BASE}/lib/${TRIPLE}/runtime"
  cp -af "${OUT_SO}" "${SYSROOT_BASE}/lib/${TRIPLE}/runtime/"
fi

echo "[i] xmake installed to sysroot: ${SYSROOT_BASE}/bin/xmake"
'@

$session = $session.Replace('@@RUNNER_CPP@@', $runnerCppSource)

foreach ($currentAbi in $abiList) {
  Write-Info "Building xmake for ABI=$currentAbi (API=$ApiLevel) in container: $ContainerName"
  $outDirHost = Join-Path $outBase $currentAbi
  New-Item -ItemType Directory -Force -Path (Join-Path $outDirHost 'tools/bin') | Out-Null
  $assign = "ABI='$currentAbi'; API_LEVEL='$ApiLevel'; XMAKE_REPO='$XmakeRepoUrl'; XMAKE_REF='$XmakeRef'; CLEAN_BUILD='$cleanFlag';"
  $cmd = @"
$assign
$session
"@
  $cmd = $cmd -replace "`r`n", "`n"
  Exec-In-Dev $cmd
  $rc = $LASTEXITCODE
  if ($rc -ne 0) {
    Write-Err "xmake build failed inside container (exit $rc) for ABI $currentAbi"
    exit $rc
  }
  Write-Info "xmake build completed for ABI=$currentAbi. Binaries at: $(Join-Path $outDirHost 'tools/bin/xmake')"
}

Write-Info "Done."
