plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.packages"
}

dependencies {
    implementation(project(":core:common")) // 复用 TarExtractor
    implementation(project(":core:i18n"))
    implementation(project(":core:network"))
    implementation(project(":core:project"))
    implementation(project(":core:proot"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
