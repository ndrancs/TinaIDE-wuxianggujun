/*
 * CMake Parser Module for TinaIDE
 * 用于解析 CMakeLists.txt 文件，支持语法高亮和代码补全
 *
 * 参考实现: https://github.com/rust-utility/cmake-parser
 * CMake 版本: v3.26
 */

plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.cmake"
}

dependencies {
    implementation(project(":core:i18n"))

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
