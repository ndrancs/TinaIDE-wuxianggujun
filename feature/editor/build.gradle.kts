plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.wuxianggujun.tinaide.feature.editor"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":core:common"))
    implementation(project(":core:config"))
    implementation(project(":core:i18n"))
    implementation(project(":core:lsp"))
    implementation(project(":core:plugin"))
    implementation(project(":core:project"))
    implementation(project(":core:search"))
    implementation(project(":core:storage"))
    implementation(project(":core:cmake"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.core.ktx)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Tree-sitter language grammars
    implementation("com.itsaky.androidide.treesitter:tree-sitter-aidl:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-bash:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-c:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-cmake:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-cpp:4.3.2")
    implementation(libs.tree.sitter.java)
    implementation("com.itsaky.androidide.treesitter:tree-sitter-json:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-kotlin:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-log:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-make:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-properties:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-python:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-rust:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-toml:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-xml:4.3.2")
    implementation("com.itsaky.androidide.treesitter:tree-sitter-yaml:4.3.2")
}
