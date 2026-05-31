plugins {
    id("tina.android.library")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.storage"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:i18n"))
    implementation(project(":core:project"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.core.ktx)

    // Compose（仅为 StoragePermissionRequester 提供 @Composable 宿主）
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.koin.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
