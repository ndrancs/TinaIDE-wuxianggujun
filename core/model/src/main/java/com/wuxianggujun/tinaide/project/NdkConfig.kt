package com.wuxianggujun.tinaide.project

/**
 * Android API Level 配置
 *
 * 支持 API 21-35（对应 NDK sysroot 压缩包版本）
 *
 * 普通 C/C++ 项目使用固定 API level（由编译策略内部决定），
 * NDK 项目模板允许用户选择 API level。
 */
enum class AndroidApiLevel(
    val level: Int,
    val codename: String
) {
    API_21(21, "Lollipop"),
    API_22(22, "Lollipop MR1"),
    API_23(23, "Marshmallow"),
    API_24(24, "Nougat"),
    API_25(25, "Nougat MR1"),
    API_26(26, "Oreo"),
    API_27(27, "Oreo MR1"),
    API_28(28, "Pie"),
    API_29(29, "Android 10"),
    API_30(30, "Android 11"),
    API_31(31, "Android 12"),
    API_32(32, "Android 12L"),
    API_33(33, "Android 13"),
    API_34(34, "Android 14"),
    API_35(35, "Android 15");

    companion object {
        /** NDK 项目模板的默认 API level */
        val DEFAULT = API_28

        fun fromLevel(level: Int): AndroidApiLevel = entries.find { it.level == level } ?: DEFAULT

        fun fromString(value: String?): AndroidApiLevel = entries.find { it.name == value } ?: DEFAULT
    }
}
