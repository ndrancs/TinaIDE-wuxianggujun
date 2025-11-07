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
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }

    // Native packaging: 保留 libproot.so 调试符号，避免被 strip 导致体积大幅缩小
    packaging {
        jniLibs {
            // 以传统方式打包 .so（与部分设备兼容性更好）
            useLegacyPackaging = true
            // 默认 AGP 会 strip 调试符号，导致体积从 ~800KB 缩到 ~200KB。
            // 显式保留 libproot.so 的符号，确保 APK 中体积与源文件一致。
            keepDebugSymbols += setOf("**/libproot.so")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    
    // AIDE-Termux components（已扁平化路径）
    implementation(project(":termux-app"))
    implementation(project(":terminal-view"))
    implementation(project(":terminal-emulator"))
    implementation(project(":termux-shared"))
    
    // SoraEditor components
    implementation(project(":sora-editor:editor"))
    implementation(project(":sora-editor:language-textmate"))
    
    // JSON processing for configuration
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Kotlin Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 移除运行时下载器后不再需要 OkHttp 依赖

    // Permissions library by 轮子哥（XXPermissions）
    implementation("com.github.getActivity:XXPermissions:21.3")

    // Avoid Guava vs listenablefuture duplicate classes
    implementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")
    
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
