#!/usr/bin/env bash
set -euo pipefail

# Build & package TinaIDE Android-native toolchain (arm64-v8a by default).
# Runs inside docker/toolchain-builder container.

usage() {
  cat <<'EOF'
Usage:
  ARCH=aarch64 API_LEVEL=28 LLVM_VERSION=22.1.0-rc3 TOOLCHAIN_VERSION=0.2.0 \
  bash scripts/build-and-package-android-toolchain.sh

Common env flags:
  BUILD_TOOLS=0|1
  PACKAGE_FULL=0|1
  PACKAGE_BASE=0|1
  PACKAGE_TOOLS=0|1
  STRIP_BINARIES=0|1
  ENABLE_STATIC_PIE=0|1
  USE_CCACHE=0|1
  LLVM_PARALLEL_LINK_JOBS=6
  APPLY_LLVM_ANDROID_PATH_FIX_PATCH=0|1   # only fix clang InstalledDir under linker64
  APPLY_LLVM_ANDROID_EXEC_WRAP_PATCH=0|1  # wrap LLVM internal exec via linker64
  APPLY_CMAKE_ANDROID_EXEC_PATCH=0|1
  PACKAGE_VARIANT=patched
  ANDROID_TOOLS_ROOT=/tmp/tina-toolchain/android-tools
  NINJA_TARGETS="clang lld"
  SKIP_STAGE_AND_PACKAGE=0|1
  SYSROOT_API_LEVELS=21,23,28
  FORCE_RECONFIGURE=0|1
EOF
}

if [[ $# -gt 0 ]]; then
  case "$1" in
    -h|--help) usage; exit 0 ;;
    *) echo "[ERROR] unsupported argument: $1" >&2; usage; exit 2 ;;
  esac
fi

ARCH="${ARCH:-aarch64}"                 # aarch64 | x86_64
API_LEVEL="${API_LEVEL:-28}"
LLVM_VERSION="${LLVM_VERSION:-22.1.0-rc3}"
TOOLCHAIN_VERSION="${TOOLCHAIN_VERSION:-0.2.0}"
LLVM_TARGETS="${LLVM_TARGETS:-X86;AArch64}" # build only x86_64 + arm64 backends by default

BUILD_TOOLS="${BUILD_TOOLS:-1}"         # 1=build cmake/ninja/make, 0=skip
PRUNE_SYSROOT_HEADERS="${PRUNE_SYSROOT_HEADERS:-0}"
PRUNE_CLANG_RESOURCE="${PRUNE_CLANG_RESOURCE:-0}"
SYSROOT_API_LEVELS="${SYSROOT_API_LEVELS:-}" # empty=auto-detect all available, else comma-separated list (e.g. 21,24,28)
RUNTIME_API_LEVELS="${RUNTIME_API_LEVELS:-24}" # extra API levels for clang runtime compatibility dirs
ENABLE_STATIC_PIE="${ENABLE_STATIC_PIE:-0}"  # 1=experimental static-pie (may fail with NDK r27 libunwind)
LLVM_FETCH_PREFER_CODELOAD="${LLVM_FETCH_PREFER_CODELOAD:-0}" # 1=skip github release-assets and use codeload snapshot directly
STRIP_BINARIES="${STRIP_BINARIES:-1}"   # 1=strip toolchain binaries, 0=keep unstripped
PRESERVE_LEGACY_RELEASE="${PRESERVE_LEGACY_RELEASE:-1}" # 1=move old release artifacts out of active release dir
USE_CCACHE="${USE_CCACHE:-1}"           # 1=enable ccache launcher when available
LLVM_PARALLEL_LINK_JOBS="${LLVM_PARALLEL_LINK_JOBS:-6}"
APPLY_LLVM_ANDROID_PATH_FIX_PATCH="${APPLY_LLVM_ANDROID_PATH_FIX_PATCH:-1}" # 1=fix clang InstalledDir when launched through linker/linker64
APPLY_LLVM_ANDROID_EXEC_WRAP_PATCH="${APPLY_LLVM_ANDROID_EXEC_WRAP_PATCH:-1}" # 1=wrap LLVM internal exec() through linker/linker64
APPLY_CMAKE_ANDROID_EXEC_PATCH="${APPLY_CMAKE_ANDROID_EXEC_PATCH:-1}" # 1=patch CMake subprocess exec() sources
PACKAGE_VARIANT="${PACKAGE_VARIANT:-}"   # optional package suffix, e.g. patched
NINJA_TARGETS="${NINJA_TARGETS:-}"       # empty=use default targets list; otherwise build only specified targets (space/comma separated)
SKIP_STAGE_AND_PACKAGE="${SKIP_STAGE_AND_PACKAGE:-0}" # 1=stop after ninja build (for incremental rebuild/debug)

# Toolchain binaries are intended to be standalone Android ELF executables.
# They should NOT depend on libLLVM.so/libclang-cpp.so at runtime.
FORCE_RECONFIGURE="${FORCE_RECONFIGURE:-0}"   # 1=wipe target build dir and reconfigure

PACKAGE_FULL="${PACKAGE_FULL:-1}"       # 1=also emit monolithic package
PACKAGE_BASE="${PACKAGE_BASE:-1}"       # 1=emit base layer (no cmake/ninja/make)
PACKAGE_TOOLS="${PACKAGE_TOOLS:-1}"     # 1=emit tools layer (cmake/ninja/make + share/cmake-*)

WORKSPACE="${WORKSPACE:-/workspace}"
ROOT="${ROOT:-${WORKSPACE}/build/tina-toolchain}"
SRC_ROOT="${ROOT}/src"
BUILD_ROOT="${ROOT}/build"
CCACHE_DIR="${CCACHE_DIR:-${ROOT}/.ccache}"

HOST_BUILD="${BUILD_ROOT}/host"
HOST_INSTALL="${ROOT}/host-toolchain"
HOST_TOOLCHAIN="${HOST_INSTALL}"

LLVM_PATCH_PROFILE="pathfix${APPLY_LLVM_ANDROID_PATH_FIX_PATCH}-execwrap${APPLY_LLVM_ANDROID_EXEC_WRAP_PATCH}"
BUILD_VARIANT_SUFFIX="${PACKAGE_VARIANT:-${LLVM_PATCH_PROFILE}}"
TARGET_BUILD="${BUILD_ROOT}/android-${ARCH}-api${API_LEVEL}-${BUILD_VARIANT_SUFFIX}"

RELEASE_ROOT="${ROOT}/release"
STAGING_ROOT="${ROOT}/staging"
ANDROID_TOOLS_ROOT="${ANDROID_TOOLS_ROOT:-${ROOT}/android-tools}"

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-}"

die() { echo "[ERROR] $*" >&2; exit 1; }
log() { echo "[INFO] $*"; }
require_cmd() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }

require_cmd cmake
require_cmd ninja
require_cmd tar
require_cmd xz
[[ "${SKIP_STAGE_AND_PACKAGE}" == "1" ]] || require_cmd zstd
require_cmd sha256sum
require_cmd file
require_cmd readelf
require_cmd curl
require_cmd awk
require_cmd patch

if [[ -n "${PACKAGE_VARIANT}" && ! "${PACKAGE_VARIANT}" =~ ^[A-Za-z0-9._-]+$ ]]; then
  die "PACKAGE_VARIANT contains unsupported characters: ${PACKAGE_VARIANT}"
fi

[[ -n "${ANDROID_NDK_HOME}" ]] || die "ANDROID_NDK_HOME is not set"
[[ -d "${ANDROID_NDK_HOME}" ]] || die "ANDROID_NDK_HOME not found: ${ANDROID_NDK_HOME}"

case "${ARCH}" in
  aarch64) ANDROID_ABI="arm64-v8a"; TARGET_TRIPLE="aarch64-linux-android" ;;
  x86_64) ANDROID_ABI="x86_64"; TARGET_TRIPLE="x86_64-linux-android" ;;
  *) die "unsupported ARCH: ${ARCH} (supported: aarch64, x86_64)" ;;
esac

mkdir -p "${SRC_ROOT}" "${BUILD_ROOT}" "${RELEASE_ROOT}" "${STAGING_ROOT}"

CMAKE_CCACHE_ARGS=()
if [[ "${USE_CCACHE}" != "0" ]]; then
  if command -v ccache >/dev/null 2>&1; then
    mkdir -p "${CCACHE_DIR}"
    export CCACHE_DIR
    CMAKE_CCACHE_ARGS+=(
      -DCMAKE_C_COMPILER_LAUNCHER=ccache
      -DCMAKE_CXX_COMPILER_LAUNCHER=ccache
    )
  else
    log "WARNING: USE_CCACHE=1 but ccache not found, fallback to non-ccache build"
  fi
fi

llvm_src="${SRC_ROOT}/llvm-project-${LLVM_VERSION}-${LLVM_PATCH_PROFILE}"
llvm_tar="${SRC_ROOT}/llvm-project-${LLVM_VERSION}.src.tar.xz"
llvm_url="https://github.com/llvm/llvm-project/releases/download/llvmorg-${LLVM_VERSION}/llvm-project-${LLVM_VERSION}.src.tar.xz"
llvm_fallback_tar="${SRC_ROOT}/llvm-project-${LLVM_VERSION}.src-fallback.tar.gz"
llvm_fallback_url="https://codeload.github.com/llvm/llvm-project/tar.gz/refs/tags/llvmorg-${LLVM_VERSION}"

log "Workspace: ${WORKSPACE}"
log "ROOT: ${ROOT}"
log "LLVM_VERSION: ${LLVM_VERSION}"
log "ARCH: ${ARCH} (ABI=${ANDROID_ABI})"
log "API_LEVEL: ${API_LEVEL}"
log "LLVM_TARGETS: ${LLVM_TARGETS}"
log "BUILD_TOOLS=${BUILD_TOOLS} PACKAGE_FULL=${PACKAGE_FULL} PACKAGE_BASE=${PACKAGE_BASE} PACKAGE_TOOLS=${PACKAGE_TOOLS}"
log "ENABLE_STATIC_PIE=${ENABLE_STATIC_PIE}"
log "USE_CCACHE=${USE_CCACHE} LLVM_PARALLEL_LINK_JOBS=${LLVM_PARALLEL_LINK_JOBS}"
log "APPLY_LLVM_ANDROID_PATH_FIX_PATCH=${APPLY_LLVM_ANDROID_PATH_FIX_PATCH}"
log "APPLY_LLVM_ANDROID_EXEC_WRAP_PATCH=${APPLY_LLVM_ANDROID_EXEC_WRAP_PATCH}"
log "APPLY_CMAKE_ANDROID_EXEC_PATCH=${APPLY_CMAKE_ANDROID_EXEC_PATCH}"
log "PACKAGE_VARIANT=${PACKAGE_VARIANT:-<default>}"
log "LLVM_PATCH_PROFILE=${LLVM_PATCH_PROFILE}"
log "TARGET_BUILD=${TARGET_BUILD}"
log "ANDROID_TOOLS_ROOT=${ANDROID_TOOLS_ROOT}"
if [[ -n "${NINJA_TARGETS}" ]]; then
  log "NINJA_TARGETS=${NINJA_TARGETS}"
else
  log "NINJA_TARGETS=<default>"
fi
log "SKIP_STAGE_AND_PACKAGE=${SKIP_STAGE_AND_PACKAGE}"
if [[ ${#CMAKE_CCACHE_ARGS[@]} -gt 0 ]]; then
  log "CCACHE_DIR=${CCACHE_DIR}"
fi
log "PRUNE_SYSROOT_HEADERS=${PRUNE_SYSROOT_HEADERS} PRUNE_CLANG_RESOURCE=${PRUNE_CLANG_RESOURCE} STRIP_BINARIES=${STRIP_BINARIES}"
if [[ "${ENABLE_STATIC_PIE}" == "1" ]]; then
  log "WARNING: ENABLE_STATIC_PIE=1 is experimental on NDK r27 and may fail at link time (e.g. unresolved dl_iterate_phdr)"
fi
if [[ -n "${SYSROOT_API_LEVELS}" ]]; then
  log "SYSROOT_API_LEVELS=${SYSROOT_API_LEVELS}"
fi

log "== Fetch LLVM source =="
if [[ ! -d "${llvm_src}" ]]; then
  rm -rf "${llvm_src}" "${SRC_ROOT}/llvm-project-${LLVM_VERSION}.src" "${SRC_ROOT}/llvm-project-llvmorg-${LLVM_VERSION}"

  if [[ ! -f "${llvm_tar}" ]]; then
    if [[ "${LLVM_FETCH_PREFER_CODELOAD}" == "1" ]]; then
      log "Skip release asset download (LLVM_FETCH_PREFER_CODELOAD=1)."
    else
      log "Downloading (release asset): ${llvm_url}"
      if ! curl -L --retry 6 --retry-all-errors --retry-delay 2 \
        --connect-timeout 20 --max-time 900 --speed-time 60 --speed-limit 1024 --continue-at - \
        -o "${llvm_tar}" "${llvm_url}"; then
        log "Release asset download failed, fallback to codeload snapshot."
        rm -f "${llvm_tar}"
      fi
    fi
  fi

  if [[ -f "${llvm_tar}" ]] && tar -tJf "${llvm_tar}" >/dev/null 2>&1; then
    log "Extracting: ${llvm_tar}"
    tar -xJf "${llvm_tar}" -C "${SRC_ROOT}"
    mv "${SRC_ROOT}/llvm-project-${LLVM_VERSION}.src" "${llvm_src}"
  else
    [[ -f "${llvm_tar}" ]] && rm -f "${llvm_tar}" || true
    log "Downloading (fallback codeload): ${llvm_fallback_url}"
    curl -L --retry 6 --retry-all-errors --retry-delay 2 \
      --connect-timeout 20 --max-time 900 --speed-time 60 --speed-limit 1024 \
      -o "${llvm_fallback_tar}" "${llvm_fallback_url}"
    log "Extracting fallback snapshot: ${llvm_fallback_tar}"
    tar -xzf "${llvm_fallback_tar}" -C "${SRC_ROOT}"
    mv "${SRC_ROOT}/llvm-project-llvmorg-${LLVM_VERSION}" "${llvm_src}"
  fi
fi

apply_llvm_patch_if_needed() {
  local enabled="$1"
  local label="$2"
  local patch_file="$3"

  if [[ "${enabled}" == "0" ]]; then
    log "Skip ${label} (disabled)."
    return
  fi

  [[ -f "${llvm_src}/llvm/lib/Support/Unix/Path.inc" ]] || \
    die "LLVM source layout invalid: missing ${llvm_src}/llvm/lib/Support/Unix/Path.inc (try removing ${llvm_src} and rebuild)"
  [[ -f "${patch_file}" ]] || die "LLVM patch file not found: ${patch_file}"

  log "Applying ${label}: ${patch_file}"

  # 先判断“能否完整正向应用”。不要直接调用 patch --forward，
  # 否则在部分 hunk 失败时可能污染缓存源码。
  if command -v git >/dev/null 2>&1 && (cd "${llvm_src}" && git apply --check "${patch_file}") >/tmp/tinaide_llvm_patch.log 2>&1; then
    (cd "${llvm_src}" && git apply "${patch_file}")
    log "${label} applied by git apply."
    return
  fi

  # 再判断是否已经完整应用。
  if command -v git >/dev/null 2>&1 && (cd "${llvm_src}" && git apply --reverse --check "${patch_file}") >/tmp/tinaide_llvm_patch.log 2>&1; then
    log "${label} already applied, skipping."
    return
  fi

  if patch -d "${llvm_src}" -p1 --forward --dry-run -i "${patch_file}" >/tmp/tinaide_llvm_patch.log 2>&1; then
    patch -d "${llvm_src}" -p1 --forward --batch -i "${patch_file}" >/tmp/tinaide_llvm_patch.log 2>&1
    log "${label} applied."
    return
  fi

  if patch -d "${llvm_src}" -p1 --reverse --dry-run -i "${patch_file}" >/tmp/tinaide_llvm_patch.log 2>&1; then
    log "${label} already applied, skipping."
    return
  fi

  cat /tmp/tinaide_llvm_patch.log >&2 || true
  die "Failed to apply ${label}."
}

apply_llvm_android_patches_if_needed() {
  apply_llvm_patch_if_needed \
    "${APPLY_LLVM_ANDROID_PATH_FIX_PATCH}" \
    "LLVM Android linker pathfix patch" \
    "${WORKSPACE}/tools/toolchain-patches/llvm-android-linker-pathfix.patch"

  apply_llvm_patch_if_needed \
    "${APPLY_LLVM_ANDROID_EXEC_WRAP_PATCH}" \
    "LLVM Android execwrap patch" \
    "${WORKSPACE}/tools/toolchain-patches/llvm-android-linker-execwrap.patch"
}

apply_llvm_android_patches_if_needed

log "== Build LLVM source =="

get_llvm_major_from_tblgen() {
  local tblgen="$1"
  [[ -x "${tblgen}" ]] || return 1
  "${tblgen}" --version 2>/dev/null | awk '/LLVM version/ {split($3, v, "."); print v[1]; exit}'
}

log "== Build host toolchain (x86_64 Linux) =="
expected_llvm_major="${LLVM_VERSION%%.*}"
host_install_tblgen="${HOST_INSTALL}/bin/llvm-tblgen"
host_build_tblgen="${HOST_BUILD}/bin/llvm-tblgen"
stale_host_toolchain="0"

if [[ -x "${host_install_tblgen}" ]]; then
  host_install_major="$(get_llvm_major_from_tblgen "${host_install_tblgen}" || true)"
  if [[ -z "${host_install_major}" || "${host_install_major}" != "${expected_llvm_major}" ]]; then
    log "Host install llvm-tblgen major mismatch: ${host_install_major:-unknown} (want ${expected_llvm_major})"
    stale_host_toolchain="1"
  fi
fi

if [[ -x "${host_build_tblgen}" ]]; then
  host_build_major="$(get_llvm_major_from_tblgen "${host_build_tblgen}" || true)"
  if [[ -z "${host_build_major}" || "${host_build_major}" != "${expected_llvm_major}" ]]; then
    log "Host build llvm-tblgen major mismatch: ${host_build_major:-unknown} (want ${expected_llvm_major})"
    stale_host_toolchain="1"
  fi
fi

if [[ "${stale_host_toolchain}" == "1" ]]; then
  log "Rebuilding host toolchain due to llvm-tblgen version mismatch."
  rm -rf "${HOST_BUILD}" "${HOST_INSTALL}"
fi

if [[ -x "${HOST_INSTALL}/bin/llvm-tblgen" ]]; then
  HOST_TOOLCHAIN="${HOST_INSTALL}"
elif [[ -x "${HOST_BUILD}/bin/llvm-tblgen" ]]; then
  HOST_TOOLCHAIN="${HOST_BUILD}"
  log "Host install not found; reuse build dir as host toolchain: ${HOST_TOOLCHAIN}"
else
  mkdir -p "${HOST_BUILD}"
  if [[ ! -f "${HOST_BUILD}/build.ninja" ]]; then
    cmake -G Ninja "${llvm_src}/llvm" \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX="${HOST_INSTALL}" \
      -DLLVM_ENABLE_PROJECTS="clang;lld" \
      -DLLVM_TARGETS_TO_BUILD="X86;AArch64" \
      -DLLVM_ENABLE_ASSERTIONS=OFF \
      -DLLVM_ENABLE_BACKTRACES=OFF \
      -DLLVM_ENABLE_TERMINFO=OFF \
      -DLLVM_INCLUDE_TESTS=OFF \
      -DCLANG_INCLUDE_TESTS=OFF \
      -DLLVM_PARALLEL_LINK_JOBS="${LLVM_PARALLEL_LINK_JOBS}" \
      "${CMAKE_CCACHE_ARGS[@]}" \
      -S "${llvm_src}/llvm" -B "${HOST_BUILD}"
  fi
  # Cross-build only needs native tablegen tools. Building/installing the full
  # host LLVM tree is unnecessary and very slow for iterative toolchain variants.
  ninja -C "${HOST_BUILD}" -j"$(nproc)" llvm-tblgen clang-tblgen
  HOST_TOOLCHAIN="${HOST_BUILD}"
  log "Reuse host build dir as tablegen toolchain: ${HOST_TOOLCHAIN}"
fi

[[ -x "${HOST_TOOLCHAIN}/bin/llvm-tblgen" ]] || die "host llvm-tblgen missing: ${HOST_TOOLCHAIN}/bin/llvm-tblgen"
[[ -x "${HOST_TOOLCHAIN}/bin/clang-tblgen" ]] || die "host clang-tblgen missing: ${HOST_TOOLCHAIN}/bin/clang-tblgen"
actual_host_major="$(get_llvm_major_from_tblgen "${HOST_TOOLCHAIN}/bin/llvm-tblgen" || true)"
[[ "${actual_host_major}" == "${expected_llvm_major}" ]] || \
  die "host llvm-tblgen major mismatch after build: ${actual_host_major:-unknown} (want ${expected_llvm_major})"

log "== Build Android LLVM/Clang toolchain (native Android ELF) =="
mkdir -p "${TARGET_BUILD}"

ndk_cmake_toolchain="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake"
[[ -f "${ndk_cmake_toolchain}" ]] || die "NDK CMake toolchain not found: ${ndk_cmake_toolchain}"

cache_get() {
  local key="$1"
  local cache="${TARGET_BUILD}/CMakeCache.txt"
  [[ -f "${cache}" ]] || return 1
  awk -F= -v k="$key" '$0 ~ ("^"k":") {print $2; exit}' "${cache}"
}

should_reconfigure="0"
reconfigure_reason=()
if [[ "${FORCE_RECONFIGURE}" == "1" ]]; then
  should_reconfigure="1"
  reconfigure_reason+=("FORCE_RECONFIGURE=1")
fi

if [[ -f "${TARGET_BUILD}/CMakeCache.txt" ]]; then
  # Ensure toolchain binaries are standalone ELF executables.
  actual_stl="$(cache_get ANDROID_STL || true)"
  actual_build_dylib="$(cache_get LLVM_BUILD_LLVM_DYLIB || true)"
  actual_link_dylib="$(cache_get LLVM_LINK_LLVM_DYLIB || true)"
  actual_clang_link_dylib="$(cache_get CLANG_LINK_CLANG_DYLIB || true)"
  actual_exe_linker_flags="$(cache_get CMAKE_EXE_LINKER_FLAGS || true)"

  if [[ "${actual_stl}" != "c++_static" ]]; then
    should_reconfigure="1"
    reconfigure_reason+=("ANDROID_STL=${actual_stl:-<missing>} (want c++_static)")
  fi
  if [[ "${actual_build_dylib}" != "OFF" ]]; then
    should_reconfigure="1"
    reconfigure_reason+=("LLVM_BUILD_LLVM_DYLIB=${actual_build_dylib:-<missing>} (want OFF)")
  fi
  if [[ "${actual_link_dylib}" != "OFF" ]]; then
    should_reconfigure="1"
    reconfigure_reason+=("LLVM_LINK_LLVM_DYLIB=${actual_link_dylib:-<missing>} (want OFF)")
  fi
  if [[ "${actual_clang_link_dylib}" != "OFF" ]]; then
    should_reconfigure="1"
    reconfigure_reason+=("CLANG_LINK_CLANG_DYLIB=${actual_clang_link_dylib:-<missing>} (want OFF)")
  fi
  if [[ "${ENABLE_STATIC_PIE}" == "1" ]]; then
    if [[ "${actual_exe_linker_flags}" != *"-static-pie"* ]]; then
      should_reconfigure="1"
      reconfigure_reason+=("CMAKE_EXE_LINKER_FLAGS=${actual_exe_linker_flags:-<missing>} (want contains -static-pie)")
    fi
  else
    if [[ "${actual_exe_linker_flags}" == *"-static-pie"* ]]; then
      should_reconfigure="1"
      reconfigure_reason+=("CMAKE_EXE_LINKER_FLAGS=${actual_exe_linker_flags:-<missing>} (want no -static-pie)")
    fi
  fi
fi

if [[ "${should_reconfigure}" == "1" ]]; then
  log "Reconfigure target build dir: ${TARGET_BUILD}"
  if [[ ${#reconfigure_reason[@]} -gt 0 ]]; then
    log "Reasons:"
    for r in "${reconfigure_reason[@]}"; do
      log "  - ${r}"
    done
  fi
  rm -rf "${TARGET_BUILD}"
  mkdir -p "${TARGET_BUILD}"
fi

if [[ ! -f "${TARGET_BUILD}/build.ninja" ]]; then
  exe_linker_flags="-Wl,-z,max-page-size=16384"
  if [[ "${ENABLE_STATIC_PIE}" == "1" ]]; then
    exe_linker_flags="-static-pie ${exe_linker_flags}"
  fi
  cmake -G Ninja "${llvm_src}/llvm" \
    -DCMAKE_TOOLCHAIN_FILE="${ndk_cmake_toolchain}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DANDROID_ABI="${ANDROID_ABI}" \
    -DANDROID_PLATFORM="android-${API_LEVEL}" \
    -DANDROID_STL="c++_static" \
    -DCMAKE_EXE_LINKER_FLAGS="${exe_linker_flags}" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DLLVM_ENABLE_PROJECTS="clang;clang-tools-extra;lld;compiler-rt" \
    -DLLVM_TARGETS_TO_BUILD="${LLVM_TARGETS}" \
    -DLLVM_DEFAULT_TARGET_TRIPLE="${TARGET_TRIPLE}${API_LEVEL}" \
    -DLLVM_TABLEGEN="${HOST_TOOLCHAIN}/bin/llvm-tblgen" \
    -DCLANG_TABLEGEN="${HOST_TOOLCHAIN}/bin/clang-tblgen" \
    -DLLVM_BUILD_LLVM_DYLIB=OFF \
    -DLLVM_LINK_LLVM_DYLIB=OFF \
    -DCLANG_LINK_CLANG_DYLIB=OFF \
    -DLLVM_ENABLE_ASSERTIONS=OFF \
    -DLLVM_ENABLE_BACKTRACES=OFF \
    -DLLVM_ENABLE_TERMINFO=OFF \
    -DLLVM_INCLUDE_TESTS=OFF \
    -DCLANG_INCLUDE_TESTS=OFF \
    -DLLVM_PARALLEL_LINK_JOBS="${LLVM_PARALLEL_LINK_JOBS}" \
    "${CMAKE_CCACHE_ARGS[@]}" \
    -DCLANG_BUILD_TOOLS=ON \
    -DCLANG_TOOLS_EXTRA_BUILD_CLANGD=ON \
    -DCLANG_TOOLS_EXTRA_BUILD_CLANG_FORMAT=ON \
    -S "${llvm_src}/llvm" -B "${TARGET_BUILD}"
fi

default_targets=(
  clang clangd clang-format lld
  llvm-ar llvm-nm llvm-objdump llvm-objcopy llvm-strip llvm-ranlib llvm-size llvm-strings
)
targets=()
if [[ -n "${NINJA_TARGETS}" ]]; then
  # Allow both comma and space separators, e.g. "clang,lld" or "clang lld".
  normalized_targets="${NINJA_TARGETS//,/ }"
  read -r -a targets <<< "${normalized_targets}"
else
  targets=("${default_targets[@]}")
fi

[[ ${#targets[@]} -gt 0 ]] || die "no ninja targets resolved (check NINJA_TARGETS)"

# If packaging is enabled, ensure required binaries exist; auto-add missing targets.
if [[ "${SKIP_STAGE_AND_PACKAGE}" == "0" ]]; then
  pkg_required_targets=(clang clangd clang-format lld llvm-ar llvm-nm llvm-objdump llvm-objcopy llvm-strip llvm-ranlib llvm-size llvm-strings)
  missing_for_package=()

  # clang may be a symlink to clang-<major>; require at least one clang entry.
  if [[ ! -e "${TARGET_BUILD}/bin/clang" ]] && ! compgen -G "${TARGET_BUILD}/bin/clang-*" >/dev/null; then
    missing_for_package+=(clang)
  fi

  for b in "${pkg_required_targets[@]}"; do
    [[ "${b}" == "clang" ]] && continue
    [[ -e "${TARGET_BUILD}/bin/${b}" ]] || missing_for_package+=("${b}")
  done

  if [[ ${#missing_for_package[@]} -gt 0 ]]; then
    log "Package requires missing binaries; auto-adding ninja targets: ${missing_for_package[*]}"
    targets+=("${missing_for_package[@]}")
  fi
fi

# Deduplicate targets while preserving order.
declare -A _seen_targets=()
uniq_targets=()
for t in "${targets[@]}"; do
  [[ -n "${t}" ]] || continue
  if [[ -z "${_seen_targets[$t]+x}" ]]; then
    _seen_targets[$t]=1
    uniq_targets+=("${t}")
  fi
done
targets=("${uniq_targets[@]}")
unset _seen_targets uniq_targets

log "ninja targets: ${targets[*]}"
ninja -C "${TARGET_BUILD}" -j"$(nproc)" "${targets[@]}"

if [[ "${SKIP_STAGE_AND_PACKAGE}" == "1" ]]; then
  log "Skip staging and packaging (SKIP_STAGE_AND_PACKAGE=1). Build artifacts remain in: ${TARGET_BUILD}"
  exit 0
fi

log "== Stage toolchain layout =="
rm -rf "${STAGING_ROOT}/toolchain"
mkdir -p "${STAGING_ROOT}/toolchain/bin" "${STAGING_ROOT}/toolchain/lib"

copy_bin() {
  local name="$1"
  if [[ -e "${TARGET_BUILD}/bin/${name}" ]]; then
    cp -a "${TARGET_BUILD}/bin/${name}" "${STAGING_ROOT}/toolchain/bin/"
  else
    die "missing built binary: ${name}"
  fi
}

# clang is usually a symlink to clang-<major>. Keep both (symlink + real file).
clang_link="${TARGET_BUILD}/bin/clang"
if [[ -L "${clang_link}" ]]; then
  clang_real="$(readlink "${clang_link}")"
  copy_bin "${clang_real}"
  ln -sf "${clang_real}" "${STAGING_ROOT}/toolchain/bin/clang"
  ln -sf "${clang_real}" "${STAGING_ROOT}/toolchain/bin/clang++"
else
  copy_bin clang
  ln -sf clang "${STAGING_ROOT}/toolchain/bin/clang++"
fi

copy_bin clangd
copy_bin clang-format
copy_bin lld
ln -sf lld "${STAGING_ROOT}/toolchain/bin/ld.lld"

for b in llvm-ar llvm-nm llvm-objdump llvm-objcopy llvm-strip llvm-ranlib llvm-size llvm-strings; do
  copy_bin "${b}"
done

(
  cd "${STAGING_ROOT}/toolchain/bin"
  ln -sf llvm-ar ar
  ln -sf llvm-nm nm
  ln -sf llvm-objdump objdump
  ln -sf llvm-objcopy objcopy
  ln -sf llvm-strip strip
  ln -sf llvm-ranlib ranlib
)

if [[ "${STRIP_BINARIES}" != "0" ]]; then
  log "== Strip Android ELF binaries =="
  ndk_strip="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  find "${STAGING_ROOT}/toolchain/bin" -maxdepth 1 -type f -executable -print0 \
    | xargs -0 -n 1 "${ndk_strip}" --strip-all 2>/dev/null || true
else
  log "== Keep unstripped Android ELF binaries (STRIP_BINARIES=0) =="
fi

log "== Verify clangd runtime deps (no LLVM dylibs) =="
if readelf -d "${STAGING_ROOT}/toolchain/bin/clangd" | grep -F "Shared library: [libLLVM.so]" >/dev/null; then
  die "clangd still depends on libLLVM.so (expected standalone ELF)"
fi
if readelf -d "${STAGING_ROOT}/toolchain/bin/clangd" | grep -F "Shared library: [libclang-cpp.so]" >/dev/null; then
  die "clangd still depends on libclang-cpp.so (expected standalone ELF)"
fi

log "== Stage clang resource =="
[[ -d "${TARGET_BUILD}/lib/clang" ]] || die "missing clang resource dir: ${TARGET_BUILD}/lib/clang"
cp -a "${TARGET_BUILD}/lib/clang" "${STAGING_ROOT}/toolchain/lib/"

log "== Ensure compiler-rt runtimes (builtins/sanitizers/libunwind) =="
clang_base="${STAGING_ROOT}/toolchain/lib/clang"
clang_ver="$(ls -1 "${clang_base}" | sort -V | tail -n 1 || true)"
[[ -n "${clang_ver}" ]] || die "cannot determine clang resource version under: ${clang_base}"
rt_dir="${clang_base}/${clang_ver}/lib/linux"
mkdir -p "${rt_dir}"

case "${ARCH}" in
  aarch64)
    rt_arch="aarch64"
    sysroot_arch="aarch64-linux-android"
    ;;
  x86_64)
    rt_arch="x86_64"
    sysroot_arch="x86_64-linux-android"
    ;;
  *) die "unsupported ARCH for runtimes: ${ARCH}" ;;
esac

if [[ ! -f "${rt_dir}/libclang_rt.builtins-${rt_arch}-android.a" ]]; then
  ndk_clang_base="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/lib/clang"
  ndk_clang_ver="$(ls -1 "${ndk_clang_base}" | sort -V | tail -n 1 || true)"
  ndk_rt_dir="${ndk_clang_base}/${ndk_clang_ver}/lib/linux"
  log "Clang runtimes missing; copy from NDK clang resource: ${ndk_clang_base}/${ndk_clang_ver}"
  shopt -s nullglob
  cp -a "${ndk_rt_dir}"/libclang_rt.*-"${rt_arch}"-android* "${rt_dir}/"
  shopt -u nullglob
  mkdir -p "${rt_dir}/${rt_arch}"
  cp -a "${ndk_rt_dir}/${rt_arch}/libunwind.a" "${rt_dir}/${rt_arch}/" 2>/dev/null || true
  cp -a "${ndk_rt_dir}/${rt_arch}/libatomic.a" "${rt_dir}/${rt_arch}/" 2>/dev/null || true
fi

[[ -f "${rt_dir}/libclang_rt.builtins-${rt_arch}-android.a" ]] || \
  die "missing runtime: ${rt_dir}/libclang_rt.builtins-${rt_arch}-android.a"
[[ -f "${rt_dir}/${rt_arch}/libunwind.a" ]] || \
  die "missing runtime: ${rt_dir}/${rt_arch}/libunwind.a"

log "== Add android sysroot (Bionic + libc++) =="
sysroot_out="${STAGING_ROOT}/toolchain/android-sysroot"
rm -rf "${sysroot_out}"
mkdir -p "${ROOT}/_tmp_sysroot_out"
tmp_out="${ROOT}/_tmp_sysroot_out"
rm -rf "${tmp_out}"
mkdir -p "${tmp_out}"

export TARGET_ARCH="${ARCH}"
if [[ -n "${SYSROOT_API_LEVELS}" ]]; then
  export API_LEVELS="${SYSROOT_API_LEVELS}"
else
  unset API_LEVELS 2>/dev/null || true
fi
export OUTPUT_DIR="${tmp_out}"
export PRUNE_SYSROOT_HEADERS="${PRUNE_SYSROOT_HEADERS}"
sed -i 's/\n$//' "${WORKSPACE}/docker/proot-build/build-sysroot.sh" 2>/dev/null || true
bash "${WORKSPACE}/docker/proot-build/build-sysroot.sh"
sysroot_tar="$(ls -1 "${tmp_out}"/android-sysroot-*.tar.xz | head -n 1 || true)"
[[ -n "${sysroot_tar}" ]] || die "sysroot tar not produced in: ${tmp_out}"
tar -xJf "${sysroot_tar}" -C "${STAGING_ROOT}/toolchain"

# Some Android clang driver invocations may resolve builtins path as:
#   <resource>/lib/<triple><api>/libclang_rt.builtins.a
# Create compatibility directories for all detected sysroot API levels.
runtime_api_levels=()
if [[ -d "${STAGING_ROOT}/toolchain/android-sysroot/usr/lib/${sysroot_arch}" ]]; then
  while IFS= read -r api; do
    [[ -n "${api}" ]] || continue
    runtime_api_levels+=("${api}")
  done < <(find "${STAGING_ROOT}/toolchain/android-sysroot/usr/lib/${sysroot_arch}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -n)
fi
runtime_api_levels+=("${API_LEVEL}")
IFS=', ' read -r -a extra_runtime_api_levels <<< "${RUNTIME_API_LEVELS}"
for api in "${extra_runtime_api_levels[@]}"; do
  [[ -n "${api}" ]] || continue
  runtime_api_levels+=("${api}")
done
mapfile -t runtime_api_levels < <(printf '%s\n' "${runtime_api_levels[@]}" | awk '/^[0-9]+$/ {print $1}' | sort -n -u)
[[ ${#runtime_api_levels[@]} -gt 0 ]] || runtime_api_levels=("24")

for api in "${runtime_api_levels[@]}"; do
  [[ "${api}" =~ ^[0-9]+$ ]] || continue
  legacy_rt_dir="${clang_base}/${clang_ver}/lib/${rt_arch}-unknown-linux-android${api}"
  mkdir -p "${legacy_rt_dir}"
  cp -a "${rt_dir}/libclang_rt.builtins-${rt_arch}-android.a" "${legacy_rt_dir}/libclang_rt.builtins.a"
  cp -a "${rt_dir}/${rt_arch}/libunwind.a" "${legacy_rt_dir}/libunwind.a"
  if [[ -f "${rt_dir}/${rt_arch}/libatomic.a" ]]; then
    cp -a "${rt_dir}/${rt_arch}/libatomic.a" "${legacy_rt_dir}/libatomic.a"
  fi
done

log "== Prune clang resource (optional) =="
if [[ "${PRUNE_CLANG_RESOURCE}" != "0" ]]; then
  rt_dir="${clang_base}/${clang_ver}/lib/linux"
  if [[ -d "${rt_dir}" ]]; then
    shopt -s nullglob
    case "${ARCH}" in
      aarch64)
        for f in "${rt_dir}"/libclang_rt.*-android*; do
          base="$(basename "${f}")"
          [[ "${base}" == *aarch64-android* ]] || rm -f "${f}"
        done
        rm -rf "${rt_dir}/arm" "${rt_dir}/i386" "${rt_dir}/riscv64" "${rt_dir}/x86_64" 2>/dev/null || true
        ;;
      x86_64)
        for f in "${rt_dir}"/libclang_rt.*-android*; do
          base="$(basename "${f}")"
          [[ "${base}" == *x86_64-android* ]] || rm -f "${f}"
        done
        rm -rf "${rt_dir}/arm" "${rt_dir}/i386" "${rt_dir}/riscv64" "${rt_dir}/aarch64" 2>/dev/null || true
        ;;
    esac
    shopt -u nullglob
  fi
fi

log "== Build extra build tools (cmake/ninja/make) =="
if [[ "${BUILD_TOOLS}" != "0" ]]; then
  ndk_host_toolchain="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64"
  sed -i 's/\n$//' "${WORKSPACE}/scripts/build-android-tools.sh" 2>/dev/null || true
  export STRIP_TOOLS_BINARIES="${STRIP_BINARIES}"
  export ANDROID_TOOLS_ROOT
  export APPLY_CMAKE_ANDROID_EXEC_PATCH
  bash "${WORKSPACE}/scripts/build-android-tools.sh" \
    --arch "${ARCH}" \
    --api "${API_LEVEL}" \
    --host-toolchain "${ndk_host_toolchain}" \
    --install-dir "${STAGING_ROOT}/toolchain"

  [[ -x "${STAGING_ROOT}/toolchain/bin/cmake" ]] || die "cmake not installed into staging toolchain"
  cmake_share_dir="$(ls -d "${STAGING_ROOT}/toolchain/share/cmake-"* 2>/dev/null | head -n 1 || true)"
  [[ -n "${cmake_share_dir}" ]] || die "cmake share/cmake-* not found in staging toolchain (cmake will not work)"
fi

log "== Write VERSION =="
cat > "${STAGING_ROOT}/toolchain/VERSION" <<EOF
TinaIDE Tina Toolchain
Toolchain Version: ${TOOLCHAIN_VERSION}
Package Variant: ${PACKAGE_VARIANT:-default}
LLVM Version: ${LLVM_VERSION}
Target: ${TARGET_TRIPLE}${API_LEVEL}
Build Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

log "== Package (layers + optional full) =="
package_variant_suffix=""
if [[ -n "${PACKAGE_VARIANT}" ]]; then
  package_variant_suffix="-${PACKAGE_VARIANT}"
fi
pkg_root="tinaide-toolchain-${ARCH}-v${TOOLCHAIN_VERSION}${package_variant_suffix}"
pkg_base="tinaide-toolchain-base-${ARCH}-v${TOOLCHAIN_VERSION}${package_variant_suffix}"
pkg_tools="tinaide-toolchain-tools-${ARCH}-v${TOOLCHAIN_VERSION}${package_variant_suffix}"
legacy_release_dir="${ROOT}/legacy-release"

(
  cd "${RELEASE_ROOT}"
  if [[ "${PRESERVE_LEGACY_RELEASE}" != "0" ]]; then
    mkdir -p "${legacy_release_dir}"
    shopt -s nullglob
    for f in tinaide-toolchain-*.tar.zst tinaide-toolchain-*.tar.xz tinaide-toolchain-*.sha256; do
      case "${f}" in
        "${pkg_root}.tar.zst"|"${pkg_root}.tar.xz"|"${pkg_root}.sha256"|\
        "${pkg_base}.tar.zst"|"${pkg_base}.tar.xz"|\
        "${pkg_tools}.tar.zst"|"${pkg_tools}.tar.xz")
          continue
          ;;
      esac
      mv -f "${f}" "${legacy_release_dir}/"
    done
    shopt -u nullglob
  fi

  checksum_file="${pkg_root}.sha256"
  rm -f "${checksum_file}"

  if [[ "${PACKAGE_FULL}" != "0" ]]; then
    rm -rf "${RELEASE_ROOT:?}/${pkg_root}"
    cp -a "${STAGING_ROOT}/toolchain" "${RELEASE_ROOT}/${pkg_root}"
    # sysroot 由 android-sysroot 独立包管理，不打入 toolchain 包
    rm -rf "${RELEASE_ROOT}/${pkg_root}/android-sysroot"
    tar -cf - "${pkg_root}" | zstd -19 -T0 -o "${pkg_root}.tar.zst"
    tar -cf - "${pkg_root}" | xz -9e -T0 > "${pkg_root}.tar.xz"
    sha256sum "${pkg_root}.tar.zst" "${pkg_root}.tar.xz" >> "${checksum_file}"
  fi

  if [[ "${PACKAGE_BASE}" != "0" ]]; then
    # sysroot 由 android-sysroot 独立包管理，base 层仅包含 clang/lld 等工具 + clang resource
    tar -C "${STAGING_ROOT}/toolchain" \
      --exclude='./bin/cmake' \
      --exclude='./bin/ninja' \
      --exclude='./bin/make' \
      --exclude='./bin/pkgconf' \
      --exclude='./bin/pkg-config' \
      --transform "s,^[.]/,${pkg_root}/," \
      -cf - ./VERSION ./bin ./lib \
      | zstd -19 -T0 -o "${pkg_base}.tar.zst"
    tar -C "${STAGING_ROOT}/toolchain" \
      --exclude='./bin/cmake' \
      --exclude='./bin/ninja' \
      --exclude='./bin/make' \
      --exclude='./bin/pkgconf' \
      --exclude='./bin/pkg-config' \
      --transform "s,^[.]/,${pkg_root}/," \
      -cf - ./VERSION ./bin ./lib \
      | xz -9e -T0 > "${pkg_base}.tar.xz"
    sha256sum "${pkg_base}.tar.zst" "${pkg_base}.tar.xz" >> "${checksum_file}"
  fi

  if [[ "${PACKAGE_TOOLS}" != "0" ]]; then
    [[ "${BUILD_TOOLS}" != "0" ]] || die "PACKAGE_TOOLS=1 requires BUILD_TOOLS=1"
    [[ -d "${STAGING_ROOT}/toolchain/share" ]] || die "tools layer missing share/ (expected after BUILD_TOOLS=1)"
    [[ -e "${STAGING_ROOT}/toolchain/bin/pkgconf" ]] || die "pkgconf not installed into staging toolchain"
    [[ -e "${STAGING_ROOT}/toolchain/bin/pkg-config" ]] || die "pkg-config not installed into staging toolchain"
    tar -C "${STAGING_ROOT}/toolchain" \
      --transform "s,^[.]/,${pkg_root}/," \
      -cf - ./VERSION ./bin/cmake ./bin/ninja ./bin/make ./bin/pkgconf ./bin/pkg-config ./share \
      | zstd -19 -T0 -o "${pkg_tools}.tar.zst"
    tar -C "${STAGING_ROOT}/toolchain" \
      --transform "s,^[.]/,${pkg_root}/," \
      -cf - ./VERSION ./bin/cmake ./bin/ninja ./bin/make ./bin/pkgconf ./bin/pkg-config ./share \
      | xz -9e -T0 > "${pkg_tools}.tar.xz"
    sha256sum "${pkg_tools}.tar.zst" "${pkg_tools}.tar.xz" >> "${checksum_file}"
  fi
)

log "Done:"
if [[ "${PACKAGE_FULL}" != "0" ]]; then
  log "  ${RELEASE_ROOT}/${pkg_root}.tar.zst"
  log "  ${RELEASE_ROOT}/${pkg_root}.tar.xz"
fi
if [[ "${PACKAGE_BASE}" != "0" ]]; then
  log "  ${RELEASE_ROOT}/${pkg_base}.tar.zst"
  log "  ${RELEASE_ROOT}/${pkg_base}.tar.xz"
fi
if [[ "${PACKAGE_TOOLS}" != "0" ]]; then
  log "  ${RELEASE_ROOT}/${pkg_tools}.tar.zst"
  log "  ${RELEASE_ROOT}/${pkg_tools}.tar.xz"
fi
log "  ${RELEASE_ROOT}/${pkg_root}.sha256"
