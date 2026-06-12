package com.wuxianggujun.tinaide.ui.runtime

/**
 * 由 Android 操作系统/NDK 在运行时直接提供的 native 系统库集合。
 *
 * 这些库始终由系统动态链接器加载，既不需要随项目产物预加载，也不需要打进导出的 APK。
 * 因此它们在依赖闭包解析时应被视为“已满足”，绝不能进入 missing/缺失列表。
 *
 * 注意：`libc++_shared.so` **不在**此集合中。它是 NDK 运行时库而非 OS 系统库：
 * - IDE 直接运行（RUN）时由 sysroot 经 `LD_LIBRARY_PATH` 注入；
 * - 导出 APK 时需要被打包进去。
 * 两种场景对它的处理不同，故由各自的调用方按需追加，而不放入公共集合。
 */
object AndroidSystemLibraries {
    /**
     * 标准 Android NDK 系统库 soname 列表（永远由 OS 提供）。
     */
    val ndkProvided: Set<String> = setOf(
        "libc.so",
        "libm.so",
        "libdl.so",
        "liblog.so",
        "libandroid.so",
        "libEGL.so",
        "libGLES_CM.so",
        "libGLESv1_CM.so",
        "libGLESv2.so",
        "libGLESv3.so",
        "libOpenSLES.so",
        "libjnigraphics.so",
        "libz.so",
        "libmediandk.so",
        "libcamera2ndk.so",
        "libaaudio.so",
        "libvulkan.so",
        "libnativewindow.so",
    )
}
