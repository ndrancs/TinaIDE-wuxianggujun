plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.core.apkbuilder"
}

dependencies {
    implementation(project(":core:i18n"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.android.tools.apksig)
    implementation(libs.arsclib)
}
