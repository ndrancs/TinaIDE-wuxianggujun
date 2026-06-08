plugins {
    id("tina.android.library")
}
android {
    namespace = "com.wuxianggujun.tinaide.core.editorlsp"
}

dependencies {
    implementation(project(":core:text-engine"))
    implementation(project(":core:lsp"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines)
    implementation(libs.timber)
    api(libs.lsp4j)
}
