plugins {
    id("tina.android.library")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wuxianggujun.tinaide.feature.tutorial"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:i18n"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.lifecycle.viewmodel)
}
