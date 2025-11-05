LOCAL_PATH:= $(call my-dir)

# Termux Bootstrap 库
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

# Termux Prefix Hook 库
include $(CLEAR_VARS)
LOCAL_MODULE := termux-prefix-hook
LOCAL_SRC_FILES := prefix-hook.c elf-patcher.c
LOCAL_LDLIBS := -llog -ldl
LOCAL_CFLAGS := -Wall -Wextra -O2
include $(BUILD_SHARED_LIBRARY)
