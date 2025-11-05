# Enable termux-exec only if the library exists
if [ -f "$PREFIX/lib/libtermux-exec.so" ]; then
  # Do NOT chain previous $LD_PRELOAD to avoid contaminating interactive shells
  export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so"
fi
