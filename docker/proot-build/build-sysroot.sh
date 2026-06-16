#!/bin/bash
# TinaIDE NDK Sysroot 统一打包脚本
# 
# 功能：
# - 从 Android NDK 提取所有 API 级别的 sysroot
# - 统一打包为单个 tar.xz 文件，供 TinaIDE 使用
# - 支持 arm64-v8a 和 x86_64 架构
# - 头文件只打包一次，节省空间和压缩率

set -euo pipefail

# ============ 配置 ============

# 目标架构（从环境变量获取，默认 aarch64）
TARGET_ARCH="${TARGET_ARCH:-aarch64}"

# NDK 路径
NDK="${ANDROID_NDK_HOME:-}"

# 输出目录（允许外部覆盖，便于将产物写入 /workspace）
OUTPUT_DIR="${OUTPUT_DIR:-/output}"
mkdir -p "${OUTPUT_DIR}"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info() { echo -e "${CYAN}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

cleanup() {
    if [ -n "${TEMP_DIR:-}" ] && [ -d "${TEMP_DIR}" ]; then
        rm -rf "${TEMP_DIR}"
    fi
}
trap cleanup EXIT

# ============ 架构配置 ============

case "${TARGET_ARCH}" in
    aarch64|arm64)
        ANDROID_ABI="arm64-v8a"
        TOOLCHAIN_TRIPLE="aarch64-linux-android"
        SHORT_ARCH="arm64"
        ;;
    x86_64)
        ANDROID_ABI="x86_64"
        TOOLCHAIN_TRIPLE="x86_64-linux-android"
        SHORT_ARCH="x86_64"
        ;;
    *)
        log_error "Unsupported architecture: ${TARGET_ARCH}"
        echo "Supported: aarch64, x86_64"
        exit 1
        ;;
esac

if [ -z "${NDK}" ]; then
    log_error "ANDROID_NDK_HOME is not set"
    exit 1
fi

# 检查 NDK
if [ ! -d "${NDK}" ]; then
    log_error "NDK not found: ${NDK}"
    exit 1
fi

TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -d "${TOOLCHAIN}" ]; then
    log_error "Toolchain not found: ${TOOLCHAIN}"
    exit 1
fi
NDK_VERSION="${NDK_VERSION:-$(basename "${NDK}" | sed 's/^android-ndk-//')}"
LLVM_VERSION="$(
    {
        ls -1 "${TOOLCHAIN}/lib/clang" 2>/dev/null || true
        ls -1 "${TOOLCHAIN}/lib64/clang" 2>/dev/null || true
    } | sort -V | tail -n 1
)"

echo ""
echo "============================================"
log_info "TinaIDE NDK Sysroot 统一打包"
echo "============================================"
  echo "  目标架构:    ${TARGET_ARCH}"
echo "  Android ABI: ${ANDROID_ABI}"
echo "  NDK:         ${NDK}"
echo "  NDK Version: ${NDK_VERSION}"
echo "  LLVM:        ${LLVM_VERSION:-unknown}"
echo "  输出目录:    ${OUTPUT_DIR}"
echo "============================================"
echo ""

# ============ 自动检测可用的 API 级别 ============

SYSROOT_BASE="${TOOLCHAIN}/sysroot"

if [ ! -d "${SYSROOT_BASE}" ]; then
    log_error "Sysroot base not found: ${SYSROOT_BASE}"
    exit 1
fi

# 从环境变量获取 API 级别，或自动检测
if [ -n "${API_LEVELS:-}" ]; then
    IFS=',' read -ra API_LEVELS_ARRAY <<< "${API_LEVELS}"
    log_info "使用指定的 API 级别: ${API_LEVELS}"
else
    # 自动检测 NDK 中所有可用的 API 级别
    log_info "自动检测可用的 API 级别..."
    API_LEVELS_ARRAY=()
    for dir in "${SYSROOT_BASE}/usr/lib/${TOOLCHAIN_TRIPLE}"/*; do
        if [ -d "$dir" ]; then
            api=$(basename "$dir")
            # 检查是否为数字（API 级别）
            if [[ "$api" =~ ^[0-9]+$ ]]; then
                API_LEVELS_ARRAY+=("$api")
            fi
        fi
    done
    
    # 排序 API 级别
    IFS=$'\n' API_LEVELS_ARRAY=($(sort -n <<<"${API_LEVELS_ARRAY[*]}"))
    unset IFS
    
    if [ ${#API_LEVELS_ARRAY[@]} -eq 0 ]; then
        log_error "未检测到任何 API 级别"
        exit 1
    fi
    
    log_info "检测到 ${#API_LEVELS_ARRAY[@]} 个 API 级别: ${API_LEVELS_ARRAY[*]}"
fi

echo "  API 级别:    ${API_LEVELS_ARRAY[*]}"

# ============ 打包统一的 sysroot（包含所有 API 级别）============

if [ ! -d "${SYSROOT_BASE}" ]; then
    log_error "Sysroot base not found: ${SYSROOT_BASE}"
    exit 1
fi

echo ""
log_info "创建统一 sysroot（包含所有 API 级别）..."

# 创建临时目录
TEMP_DIR=$(mktemp -d)
SYSROOT_STAGING="${TEMP_DIR}/android-sysroot"

log_info "  创建 sysroot 副本..."
mkdir -p "${SYSROOT_STAGING}"

# 复制完整的 sysroot 结构
# NDK r21+ 的 sysroot 结构：
# - usr/include/          (通用头文件，所有 API 共享)
# - usr/include/<triple>/ (架构特定头文件)
# - usr/lib/<triple>/     (库文件，按 API 级别组织)

log_info "  复制头文件（所有 API 共享）..."
if [ -d "${SYSROOT_BASE}/usr/include" ]; then
    mkdir -p "${SYSROOT_STAGING}/usr"
    cp -a "${SYSROOT_BASE}/usr/include" "${SYSROOT_STAGING}/usr/"
fi

# 仅保留目标架构的 triple 头文件目录（减少体积）
# NDK 的 usr/include 下会包含多个架构目录，例如：
# - aarch64-linux-android
# - x86_64-linux-android
# - i686-linux-android
# - arm-linux-androideabi
INCLUDE_DIR="${SYSROOT_STAGING}/usr/include"
PRUNE_SYSROOT_HEADERS="${PRUNE_SYSROOT_HEADERS:-1}"
case "${PRUNE_SYSROOT_HEADERS}" in
    1|true|yes|on) PRUNE_SYSROOT_HEADERS=1 ;;
    0|false|no|off) PRUNE_SYSROOT_HEADERS=0 ;;
    *) PRUNE_SYSROOT_HEADERS=1 ;;
esac

if [ "${PRUNE_SYSROOT_HEADERS}" -eq 1 ] && [ -d "${INCLUDE_DIR}" ]; then
    log_info "  裁剪非目标架构头文件目录..."
    for d in "${INCLUDE_DIR}"/*; do
        [ -d "$d" ] || continue
        name="$(basename "$d")"

        # 目标 triple：保留
        if [ "${name}" = "${TOOLCHAIN_TRIPLE}" ]; then
            continue
        fi

        # 其它 triple：删除
        case "${name}" in
            *-linux-android|*-linux-android*|*-linux-androideabi|*-linux-androideabi*)
                log_info "    - remove ${name}"
                rm -rf "$d"
                ;;
        esac
    done
fi

log_info "  复制库文件（所有 API 级别）..."
mkdir -p "${SYSROOT_STAGING}/usr/lib/${TOOLCHAIN_TRIPLE}"

# 1. 复制架构根目录下的共享/静态库（所有 API 共享）
log_info "    - 复制根目录下的共享/静态库 (.so/.a)..."
if [ -d "${SYSROOT_BASE}/usr/lib/${TOOLCHAIN_TRIPLE}" ]; then
    # 同时保留软链接（NDK 的部分库可能是 symlink / linker script）
    find "${SYSROOT_BASE}/usr/lib/${TOOLCHAIN_TRIPLE}" \
        -maxdepth 1 \
        \( -type f -o -type l \) \
        \( -name "*.a" -o -name "*.so" \) \
        -exec cp -a {} "${SYSROOT_STAGING}/usr/lib/${TOOLCHAIN_TRIPLE}/" \;
fi

# 2. 复制各个 API 级别的库文件
log_info "    - 复制各 API 级别的库..."
# 记录成功打包的 API 级别
PACKAGED_APIS=()

for API_LEVEL in "${API_LEVELS_ARRAY[@]}"; do
    if [ -d "${SYSROOT_BASE}/usr/lib/${TOOLCHAIN_TRIPLE}/${API_LEVEL}" ]; then
        log_info "      * API ${API_LEVEL}"
        cp -a "${SYSROOT_BASE}/usr/lib/${TOOLCHAIN_TRIPLE}/${API_LEVEL}" \
              "${SYSROOT_STAGING}/usr/lib/${TOOLCHAIN_TRIPLE}/"
        PACKAGED_APIS+=("${API_LEVEL}")
    else
        log_warn "      * API ${API_LEVEL} 库文件不存在，跳过"
    fi
done

# 检查是否至少有一个 API 级别
if [ ${#PACKAGED_APIS[@]} -eq 0 ]; then
    log_error "没有找到任何可用的 API 级别库文件"
    exit 1
fi

# 简单校验：确保最终包里确实包含 .so/.a
LIB_DIR="${SYSROOT_STAGING}/usr/lib/${TOOLCHAIN_TRIPLE}"
LIB_SO_COUNT=$(find "${LIB_DIR}" \( -type f -o -type l \) -name "*.so" | wc -l | tr -d ' ')
LIB_A_COUNT=$(find "${LIB_DIR}" \( -type f -o -type l \) -name "*.a" | wc -l | tr -d ' ')
log_info "  库文件统计: .so=${LIB_SO_COUNT}, .a=${LIB_A_COUNT}"
if [ "${LIB_SO_COUNT}" -eq 0 ] && [ "${LIB_A_COUNT}" -eq 0 ]; then
    log_error "未打包到任何 .so/.a（请检查 NDK sysroot 是否完整，或 TOOLCHAIN_TRIPLE 是否匹配）"
    exit 1
fi

# 生成 toolchain.cmake（用同一个 sysroot 支持多 API：通过 -DTINA_ANDROID_API_LEVEL=xx 切换）
DEFAULT_API_LEVEL="28"
if ! printf '%s\n' "${PACKAGED_APIS[@]}" | grep -qx "${DEFAULT_API_LEVEL}"; then
    DEFAULT_API_LEVEL="${PACKAGED_APIS[0]}"
fi
cat > "${SYSROOT_STAGING}/toolchain.cmake" << EOF
# Android CMake Toolchain for TinaIDE (unified sysroot)
# ABI: ${ANDROID_ABI}
# Triple: ${TOOLCHAIN_TRIPLE}
#
# Usage:
#   cmake -S . -B build \\
#     -DCMAKE_TOOLCHAIN_FILE=/android-sysroot/toolchain.cmake \\
#     -DTINA_ANDROID_API_LEVEL=${DEFAULT_API_LEVEL}

set(CMAKE_SYSTEM_NAME Android)

if(NOT DEFINED TINA_ANDROID_API_LEVEL)
  set(TINA_ANDROID_API_LEVEL ${DEFAULT_API_LEVEL})
endif()

set(CMAKE_SYSTEM_VERSION \${TINA_ANDROID_API_LEVEL})
set(CMAKE_ANDROID_ARCH_ABI ${ANDROID_ABI})
set(ANDROID_ABI ${ANDROID_ABI})
set(ANDROID_PLATFORM android-\${TINA_ANDROID_API_LEVEL})

set(ANDROID_TARGET ${TOOLCHAIN_TRIPLE}\${TINA_ANDROID_API_LEVEL})
set(CMAKE_C_COMPILER_TARGET \${ANDROID_TARGET})
set(CMAKE_CXX_COMPILER_TARGET \${ANDROID_TARGET})

set(CMAKE_C_COMPILER clang)
set(CMAKE_CXX_COMPILER clang++)

set(CMAKE_SYSROOT /android-sysroot)
set(CMAKE_FIND_ROOT_PATH /android-sysroot)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)

set(CMAKE_C_FLAGS_INIT "-target \${ANDROID_TARGET} --sysroot=/android-sysroot")
set(CMAKE_CXX_FLAGS_INIT "-target \${ANDROID_TARGET} --sysroot=/android-sysroot")

set(CMAKE_EXE_LINKER_FLAGS_INIT "-fuse-ld=lld")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld=lld")

set(ANDROID TRUE)
EOF

# 创建版本信息文件
cat > "${SYSROOT_STAGING}/.version" << EOF
ARCH=${SHORT_ARCH}
ABI=${ANDROID_ABI}
API_LEVELS=${PACKAGED_APIS[*]}
TOOLCHAIN_TRIPLE=${TOOLCHAIN_TRIPLE}
NDK_VERSION=${NDK_VERSION}
NDK_LLVM_VERSION=${LLVM_VERSION}
LLVM_VERSION=${LLVM_VERSION}
CREATED_AT=$(date -Iseconds)
EOF

# 输出文件名（统一打包）
if [ -n "${SYSROOT_OUTPUT_NAME:-}" ]; then
    OUTPUT_FILE="${OUTPUT_DIR}/${SYSROOT_OUTPUT_NAME}"
else
    OUTPUT_FILE="${OUTPUT_DIR}/android-sysroot-${SHORT_ARCH}-all.tar.xz"
fi

log_info "  打包中..."
tar -cJf "${OUTPUT_FILE}" -C "${TEMP_DIR}" android-sysroot

# 显示文件大小
SIZE=$(du -h "${OUTPUT_FILE}" | cut -f1)
log_success "  完成: $(basename ${OUTPUT_FILE}) (${SIZE})"
log_info "  包含 API 级别: ${PACKAGED_APIS[*]}"

# ============ 完成 ============

echo ""
echo "============================================"
log_success "Sysroot 打包完成！"
echo "============================================"
echo ""
echo "输出文件:"
ls -lh "${OUTPUT_DIR}/"*.tar.xz 2>/dev/null || echo "  (无输出文件)"
echo ""
echo "下一步:"
echo "  将文件复制到项目:"
echo "    android-sysroot-${SHORT_ARCH}-all.tar.xz → app/src/${SHORT_ARCH}/assets/android-sysroot/"
