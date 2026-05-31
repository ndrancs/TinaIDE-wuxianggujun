plugins {
    id("tina.android.library")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.editorview"
    buildFeatures {
        compose = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:text-engine"))
    implementation(project(":core:config"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:i18n"))
    implementation(project(":core:editor-lsp"))

    implementation(libs.kotlinx.coroutines)
    implementation(libs.timber)
    implementation(libs.androidx.collection)
    coreLibraryDesugaring(libs.desugar)

    implementation(platform(libs.compose.bom))
    testImplementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation("androidx.compose.foundation:foundation")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.tree.sitter)
    api(project(":core:tree-sitter"))
}

