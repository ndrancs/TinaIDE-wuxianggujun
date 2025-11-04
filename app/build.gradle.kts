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

        // Prefer offline bootstrap by default; can be toggled per buildType if needed
        buildConfigField("boolean", "ENABLE_NETWORK_BOOTSTRAP", "false")
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
        // Required because :termux-shared enables coreLibraryDesugaring
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        buildConfig = true
    }

    // 将构建时生成的 termux-exec 资产目录加入 assets 搜索路径
    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/termux-exec-assets"))
        }
    }

    // Termux 库要求提取 native 库（其 Manifest 设置了 extractNativeLibs=true）
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    
    // Termux terminal components
    implementation(project(":terminal-view"))
    implementation(project(":terminal-emulator"))
    implementation(project(":termux-shared"))
    implementation(project(":termux-application"))
    
    // SoraEditor components
    implementation(project(":sora-editor:editor"))
    implementation(project(":sora-editor:language-textmate"))
    
    // JSON processing for configuration
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Kotlin Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Use desugar runtime compatible with compileSdk 35+
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.2")

    // Permissions library by 轮子哥（XXPermissions）
    implementation("com.github.getActivity:XXPermissions:21.3")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// (Removed) automated packaging of cpp_cmake template; user provides zip manually

// Resolve duplicate class: guava vs listenablefuture
configurations.all {
    exclude(group = "com.google.guava", module = "listenablefuture")
}

// --- termux-exec 预编译库 -> 生成到构建资产目录（KISS/YAGNI：只同步已存在的预编译文件）
val syncTermuxExecToAssets by tasks.registering {
    val srcBase = rootProject.layout.projectDirectory.dir("external/termux-exec/prebuilt")
    val outBase = layout.buildDirectory.dir("generated/termux-exec-assets/termux-exec")
    val archs = listOf("aarch64", "arm", "x86_64", "i686")
    doLast {
        val outRoot = outBase.get().asFile
        archs.forEach { arch ->
            val src = srcBase.file("$arch/libtermux-exec.so").asFile
            if (src.exists()) {
                val outDir = File(outRoot, arch)
                outDir.mkdirs()
                src.copyTo(File(outDir, "libtermux-exec.so"), overwrite = true)
                println("[termux-exec] synced prebuilt: $arch")
            } else {
                println("[termux-exec] skip: prebuilt not found for $arch")
            }
        }
    }
}

// 在构建前执行资产同步（若没有预编译文件不会失败）
tasks.named("preBuild").configure { dependsOn(syncTermuxExecToAssets) }

// Kotlin 2.x 编译器选项（替代已废弃的 kotlinOptions.jvmTarget）
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
