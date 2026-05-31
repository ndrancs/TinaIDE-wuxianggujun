plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wuxianggujun.tinaide.feature.settings"
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":core:common"))
    implementation(project(":core:compile"))
    implementation(project(":core:config"))
    implementation(project(":core:crash"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:git"))
    implementation(project(":core:i18n"))
    implementation(project(":core:logging"))
    implementation(project(":core:lsp"))
    implementation(project(":core:model"))
    implementation(project(":core:ndk"))
    implementation(project(":core:network"))
    // BundledPackagesInstaller
    implementation(project(":core:packages"))
    implementation(project(":core:plugin"))
    implementation(project(":core:project"))
    implementation(project(":core:proot"))
    implementation(project(":core:storage"))
    // feature:terminal 依赖已移除，通过 Koin DI 注入接口
    implementation(libs.androidx.activity)
    implementation(libs.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.compose)
}
