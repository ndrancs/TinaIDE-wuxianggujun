plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.compile"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":core:common"))
    implementation(project(":core:config"))
    implementation(project(":core:i18n"))
    implementation(project(":core:ndk"))
    implementation(project(":core:packages"))
    implementation(project(":core:project"))
    implementation(project(":core:proot"))
    implementation(project(":core:security"))
    implementation(project(":core:storage"))
    implementation(project(":core:cmake"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.annotation)
    implementation(libs.koin.android)
}
