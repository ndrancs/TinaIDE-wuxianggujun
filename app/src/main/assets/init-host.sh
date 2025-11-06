#!/system/bin/sh
# ReTerminal - 宿主侧初始化脚本
# 准备 proot 与挂载，进入 Alpine 容器

ALPINE_DIR=$PREFIX/local/alpine

mkdir -p $ALPINE_DIR

# 首次解包 rootfs（若为空，仅忽略 root/tmp 目录）
if [ -z "$(ls -A "$ALPINE_DIR" | grep -vE '^(root|tmp)$')" ]; then
    # 避免非 root 解包时的 chown 警告导致退出；忽略非致命错误
    # 解包 gzip 压缩的 rootfs（.tar.gz）；使用 -z 以启用解压
    tar -zxf "$PREFIX/files/alpine.tar.gz" -C "$ALPINE_DIR" 2>/dev/null || true
    # 简单校验 rootfs 是否基本可用；若缺失则尝试强制再解包一次
    if [ ! -x "$ALPINE_DIR/bin/sh" ]; then
      echo "[reterminal] WARNING: rootfs unpack may be incomplete (missing $ALPINE_DIR/bin/sh), retrying..." >&2
      tar -zxf "$PREFIX/files/alpine.tar.gz" -C "$ALPINE_DIR" 2>/dev/null || true
    fi
    # 确保 /bin/sh 及其目标可执行
    if [ -e "$ALPINE_DIR/bin/busybox" ]; then
      chmod 0755 "$ALPINE_DIR/bin/busybox" 2>/dev/null || true
    fi
    if [ -e "$ALPINE_DIR/bin/ash" ]; then
      chmod 0755 "$ALPINE_DIR/bin/ash" 2>/dev/null || true
    fi
    if [ -e "$ALPINE_DIR/bin/sh" ]; then
      chmod 0755 "$ALPINE_DIR/bin/sh" 2>/dev/null || true
    fi
fi

# 准备 proot 与依赖库
if [ ! -e "$PREFIX/local/bin/proot" ]; then
  cp "$PREFIX/files/proot" "$PREFIX/local/bin" 2>/dev/null || true
fi
# 确保可执行位（某些 cp/umask 情况下可能丢失）
chmod 0755 "$PREFIX/local/bin/proot" 2>/dev/null || true

for sofile in "$PREFIX/files/"*.so.2; do
    dest="$PREFIX/local/lib/$(basename "$sofile")"
    [ ! -e "$dest" ] && cp "$sofile" "$dest"
done

# 调试信息（便于定位架构/路径问题）
{ 
  echo "[reterminal] LINKER=$LINKER"; 
  echo "[reterminal] PROOT_LOADER=$PROOT_LOADER";
  echo "[reterminal] LD_LIBRARY_PATH=$LD_LIBRARY_PATH";
  echo "[reterminal] PROOT=$PREFIX/local/bin/proot"; 
  echo "[reterminal] PROOT_TMP_DIR=$PROOT_TMP_DIR";
  echo "[reterminal] TMPDIR=$TMPDIR";
  ls -l "$PREFIX/local/bin/proot" 2>/dev/null || true; 
} >&2

# 确保临时目录存在并权限正确
if [ -n "$PROOT_TMP_DIR" ]; then
  mkdir -p "$PROOT_TMP_DIR" 2>/dev/null || true
  chmod 700 "$PROOT_TMP_DIR" 2>/dev/null || true
fi
if [ -n "$TMPDIR" ]; then
  mkdir -p "$TMPDIR" 2>/dev/null || true
  chmod 700 "$TMPDIR" 2>/dev/null || true
fi

# 构建 proot 参数
ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

# 常见系统路径只读绑定
for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

# 可写/必要绑定
ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PREFIX"

# 仅当本地文件存在时再覆盖 /proc（防止缺失导致 proot 启动失败）
if [ -f "$PREFIX/local/stat" ]; then
  ARGS="$ARGS -b $PREFIX/local/stat:/proc/stat"
fi
if [ -f "$PREFIX/local/vmstat" ]; then
  ARGS="$ARGS -b $PREFIX/local/vmstat:/proc/vmstat"
fi

# /dev/fd 映射
if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi

ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b /sys"

# /dev/shm 映射到 alpine/tmp
if [ ! -d "$PREFIX/local/alpine/tmp" ]; then
 mkdir -p "$PREFIX/local/alpine/tmp"
 chmod 1777 "$PREFIX/local/alpine/tmp"
fi
ARGS="$ARGS -b $PREFIX/local/alpine/tmp:/dev/shm"

# 指定根目录与特性
ARGS="$ARGS -r $PREFIX/local/alpine"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

# 进入容器执行 init.sh（可带参）
# 说明：PROOT_LOADER 通过环境变量传递给 proot 使用，通常不需要作为可执行传给 linker。
# 按经典做法直接用 linker 启动 proot，由 proot 自行读取 PROOT_LOADER。
# 直接执行 proot，由其根据 PROOT_LOADER 环境变量加载 loader（更兼容部分设备）
# 显式使用容器内 /bin/sh，避免在宿主 PATH 中解析到 /system/bin/sh
exec "$PREFIX/local/bin/proot" $ARGS /bin/sh "$PREFIX/local/bin/init" "$@"
