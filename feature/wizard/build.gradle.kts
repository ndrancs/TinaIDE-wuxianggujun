plugins {
    id("tina.android.library")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wuxianggujun.tinaide.feature.wizard"
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:compile"))
    implementation(project(":core:config"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:i18n"))
    implementation(project(":core:model"))
    implementation(project(":core:plugin"))
    implementation(project(":core:project"))
    implementation(project(":core:storage"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.lifecycle.viewmodel)
    implementation(project(":immersionbar-local"))
    implementation(project(":immersionbar-ktx-local"))
}
