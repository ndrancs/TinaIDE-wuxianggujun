Param(
  [ValidateSet('arm64-v8a','x86_64')][string]$Abi = 'x86_64',
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

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $scriptRoot '..\..')

if (-not $OutputPath -or [string]::IsNullOrWhiteSpace($OutputPath)) {
  $OutputPath = Join-Path $root 'docker/llvm-build/build-output'
}
$outBase = (Resolve-Path $OutputPath).Path
$outDirHost = Join-Path $outBase $Abi
New-Item -ItemType Directory -Force -Path (Join-Path $outDirHost 'tools/bin') | Out-Null

# Ensure dev container is running
$running = (& docker ps --format '{{.Names}}' | Select-String -SimpleMatch $ContainerName) -ne $null
if (-not $running) {
  Write-Err "Dev container '$ContainerName' not running. Please run docker/llvm-build/build-local.ps1 once to create it."
  exit 2
}

function Exec-In-Dev { param([string]$cmd) & docker exec $ContainerName bash -lc $cmd }

$cleanFlag = if ($Clean) { "1" } else { "0" }
$assign = "ABI='$Abi'; API_LEVEL='$ApiLevel'; XMAKE_REPO='$XmakeRepoUrl'; XMAKE_REF='$XmakeRef'; CLEAN_BUILD='$cleanFlag';"
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
  echo "[i] Clean build requested, removing BUILD_DIR..."
  rm -rf "${BUILD_DIR}"
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

make -j"$(nproc)"
make DESTDIR="${INSTALL_ROOT}" install

# Re-link CLI objects into shared lib (libxmake_runner.so)
RUNNER_DIR="/work/build/tools/xmake-runner"
mkdir -p "${RUNNER_DIR}"
cat > "${RUNNER_DIR}/xmake_runner.cpp" <<'EOF'
#include <jni.h>
#include <string>
#include <vector>

extern "C" int main(int, char**);

// JNI 函数：Java_com_wuxianggujun_tinaide_core_nativebridge_XmakeRunner_xmake_1run
// 对应 Kotlin: external fun xmake_run(argc: Int, argv: Array<String>): Int
extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_XmakeRunner_xmake_1run(
    JNIEnv* env, jobject /* this */, jint argc, jobjectArray argv) {
    
    // 转换 Java String[] 到 char**
    std::vector<std::string> args;
    std::vector<char*> argv_ptrs;
    
    for (int i = 0; i < argc; i++) {
        jstring jstr = (jstring)env->GetObjectArrayElement(argv, i);
        const char* str = env->GetStringUTFChars(jstr, nullptr);
        args.push_back(str);
        env->ReleaseStringUTFChars(jstr, str);
    }
    
    for (auto& arg : args) {
        argv_ptrs.push_back(const_cast<char*>(arg.c_str()));
    }
    argv_ptrs.push_back(nullptr);
    
    // 调用 xmake main
    return main(argc, argv_ptrs.data());
}

// 保留原有的 C 函数接口（兼容）
extern "C" __attribute__((visibility("default"))) int xmake_run(int argc, char** argv) {
    return main(argc, argv);
}
EOF
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

if [ -f "${INSTALL_ROOT}${PREFIX}/bin/xmake" ]; then
  cp -af "${INSTALL_ROOT}${PREFIX}/bin/xmake" "${HOST_BIN}/xmake"
  chmod 0755 "${HOST_BIN}/xmake"
  "${STRIP}" -S "${HOST_BIN}/xmake" || true
else
  echo "[!] xmake binary missing under ${INSTALL_ROOT}${PREFIX}/bin"
  exit 2
fi

if [ -f "${INSTALL_ROOT}${PREFIX}/bin/xrepo" ]; then
  cp -af "${INSTALL_ROOT}${PREFIX}/bin/xrepo" "${HOST_BIN}/xrepo"
  chmod 0755 "${HOST_BIN}/xrepo"
fi

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

Write-Info "Building xmake for ABI=$Abi (API=$ApiLevel) in container: $ContainerName"
$cmd = @"
$assign
$session
"@
$cmd = $cmd -replace "`r`n", "`n"
Exec-In-Dev $cmd
Write-Info "xmake build completed. Binaries at: $(Join-Path $outDirHost 'tools/bin/xmake')"
Write-Info "Done."
