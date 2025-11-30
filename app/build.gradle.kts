import java.util.Properties
import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Load release signing config from keystore.properties if present
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystoreProps.load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.wuxianggujun.tinaide"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wuxianggujun.tinaide"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK 配置
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared"
                )
            }
        }

    }

    // Define signing config before buildTypes so it can be referenced below
    if (keystoreProps.isNotEmpty()) {
        signingConfigs {
            create("release") {
                val storeFileProp = keystoreProps.getProperty("storeFile")
                val storePasswordProp = keystoreProps.getProperty("storePassword")
                val keyAliasProp = keystoreProps.getProperty("keyAlias")
                val keyPasswordProp = keystoreProps.getProperty("keyPassword")

                if (!storeFileProp.isNullOrBlank()) storeFile = file(storeFileProp)
                if (!storePasswordProp.isNullOrBlank()) storePassword = storePasswordProp
                if (!keyAliasProp.isNullOrBlank()) keyAlias = keyAliasProp
                if (!keyPasswordProp.isNullOrBlank()) keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Attach signing config when keystore.properties is available
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Native packaging: old devices still rely on legacy jni layout
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // 排除 CMake 自动打包的 libc++_shared.so
            // 我们使用 sysroot 中的版本，避免重复和冲突
            excludes += setOf("**/libc++_shared.so")
        }
    }

    // Ensure AAPT does not ignore libc++ private headers under c++/v1/__ios
    // Some default ignore patterns may exclude double-underscore directories in assets.
    @Suppress("UnstableApiUsage")
    androidResources {
        // Do not ignore any assets to ensure libc++ internals like c++/v1/__ios are packaged
        ignoreAssetsPattern = ""
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    
    // AndroidX Preference (Material Design)
    implementation("androidx.preference:preference-ktx:1.2.1")

    // AIDE-Termux components removed
    // implementation(project(":termux-app"))
    // implementation(project(":terminal-view"))
    // implementation(project(":terminal-emulator"))
    // implementation(project(":termux-shared"))

    // SoraEditor components
    implementation(project(":sora-editor:editor"))
    implementation(project(":sora-editor:language-textmate"))

    implementation(project(":treeview"))

    // JSON processing for configuration
    implementation("com.google.code.gson:gson:2.11.0")
    
    // Kotlin Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // AndroidX Lifecycle (ViewModel + StateFlow)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")

    // Permissions library by 轮子哥（XXPermissions）- 最新版
    implementation("com.github.getActivity:XXPermissions:20.0")
    
    // 沉浸式状态栏和导航栏（ImmersionBar - OCNYang fork，支持 Android 15/16）
    implementation("com.github.OCNYang.ImmersionBar:immersionbar:3.4.0")
    implementation("com.github.OCNYang.ImmersionBar:immersionbar-ktx:3.4.0")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}


// Kotlin 2.x 编译器选项（替代已废弃的 kotlinOptions.jvmTarget）
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
