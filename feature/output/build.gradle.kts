plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.feature.output"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:i18n"))
}
