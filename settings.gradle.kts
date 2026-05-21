@file:Suppress("UnstableApiUsage")

import java.util.Properties

fun findAndroidSdkDir(): String? {
    val localPropertiesFile = file("local.properties")
    if (localPropertiesFile.isFile) {
        val props = Properties()
        localPropertiesFile.inputStream().use(props::load)
        props.getProperty("sdk.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }

    return System.getenv("ANDROID_HOME")?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.trim()?.takeIf { it.isNotEmpty() }
}

fun escapePropertiesValue(value: String): String =
    value.replace("\\", "\\\\").replace(":", "\\:")

fun ensureIncludedBuildLocalProperties(includedBuildDir: File) {
    val target = includedBuildDir.resolve("local.properties")
    if (target.exists()) return

    val sdkDir = findAndroidSdkDir() ?: return
    target.writeText("sdk.dir=${escapePropertiesValue(sdkDir)}\n", Charsets.UTF_8)
}

// 让 composite build 的 Android 工程也能找到 SDK（避免单独配置时失败）
ensureIncludedBuildLocalProperties(file("external/tina-android-tree-sitter"))

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.google.devtools.ksp") {
                useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
            }
        }
    }
    val preferOfficialRepositories = System.getenv("CI").equals("true", ignoreCase = true)
    repositories {
        if (preferOfficialRepositories) {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            mavenCentral()
            gradlePluginPortal()
        }
        // Windows/国内网络环境下，Maven Central 偶发 TLS 握手失败时优先走镜像。
        maven("https://maven.aliyun.com/repository/google") {
            name = "AliyunGoogleMirror"
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/public") {
            name = "AliyunPublicMirror"
        }
        maven("https://maven.aliyun.com/repository/gradle-plugin") {
            name = "AliyunGradlePluginMirror"
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

// 包含本地的 tina-android-tree-sitter 项目，替换 Maven 依赖
includeBuild("external/tina-android-tree-sitter") {
    dependencySubstitution {
        substitute(module("com.itsaky.androidide.treesitter:android-tree-sitter")).using(project(":android-tree-sitter"))
        substitute(module("com.itsaky.androidide.treesitter:tree-sitter-java")).using(project(":tree-sitter-java"))
        substitute(module("com.itsaky.androidide.treesitter:tree-sitter-json")).using(project(":tree-sitter-json"))
        substitute(module("com.itsaky.androidide.treesitter:tree-sitter-c")).using(project(":tree-sitter-c"))
        substitute(module("com.itsaky.androidide.treesitter:tree-sitter-cpp")).using(project(":tree-sitter-cpp"))
        substitute(module("com.itsaky.androidide.treesitter:tree-sitter-bash")).using(project(":tree-sitter-bash"))
        substitute(module("com.itsaky.androidide.treesitter:tree-sitter-yaml")).using(project(":tree-sitter-yaml"))
        substitute(module("com.itsaky.androidide.treesitter:tree-sitter-make")).using(project(":tree-sitter-make"))
        substitute(module("com.itsaky.androidide.treesitter:tree-sitter-cmake")).using(project(":tree-sitter-cmake"))
        substitute(module("com.itsaky.androidide.treesitter:tree-sitter-rust")).using(project(":tree-sitter-rust"))
        substitute(module("com.itsaky.androidide.treesitter:tree-sitter-toml")).using(project(":tree-sitter-toml"))
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val preferOfficialRepositories = System.getenv("CI").equals("true", ignoreCase = true)
    repositories {
        if (preferOfficialRepositories) {
            google()
            mavenCentral()
        }
        // Windows/国内网络环境下，Maven Central 偶发 TLS 握手失败时优先走镜像。
        maven("https://maven.aliyun.com/repository/google") {
            name = "AliyunGoogleMirror"
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/public") {
            name = "AliyunPublicMirror"
        }
        google()
        mavenCentral()
        // 本地 AAR 目录（用于将无法从网络下载的 JitPack 依赖内嵌到项目中）
        // 将 AAR 文件放到 libs/ 目录后，Gradle 会优先从此处查找
        flatDir {
            dirs(rootProject.projectDir.resolve("libs"))
        }
        // For libraries published on JitPack (e.g., getActivity/XXPermissions)
        // ImmersionBar AAR 已内嵌到 libs/ 目录，但保留 JitPack 作为其他库的备用源
        maven("https://jitpack.io")
        // 字节系依赖备用仓库
        maven("https://artifact.bytedance.com/repository/pangle")
    }
}

rootProject.name = "TinaIDE"
include(":app")

// ===== 基础层 =====
include(":core:cmake")
include(":core:common")
include(":core:config")
include(":core:database")
include(":core:designsystem")
include(":core:i18n")
include(":core:logging")
include(":core:model")
include(":core:network")
include(":core:security")
include(":core:storage")
include(":core:text-engine")
include(":core:tree-sitter")
include(":core:editor-view")
include(":core:editor-lsp")

// ===== 功能层 =====
include(":feature:ai")
include(":feature:editor")
include(":feature:help")
include(":feature:output")
include(":feature:packages")
include(":feature:projectlist")
include(":feature:settings")
include(":feature:terminal")
include(":feature:tutorial")
include(":feature:viewer")
include(":feature:wizard")
include(":feature:workspace")

// ===== 中间层 =====
include(":core:apk-builder")
include(":core:compile")
include(":core:crash")
include(":core:debug")
include(":core:git")
include(":core:linux-distro")
include(":core:lsp")
include(":core:ndk")
include(":core:packages")
include(":core:plugin")
include(":core:project")
include(":core:proot")
include(":core:search")

// Include Termux terminal modules (Apache 2.0 license)
include(":termux-terminal:terminal-emulator")
include(":termux-terminal:terminal-view")
project(":termux-terminal").projectDir = file("external/termux-terminal")
project(":termux-terminal:terminal-emulator").projectDir = file("external/termux-terminal/terminal-emulator")
project(":termux-terminal:terminal-view").projectDir = file("external/termux-terminal/terminal-view")


// Include Tina exec runtime modules (vendored from upstream termux-exec/core with local wrapping)
include(":tina-exec:runtime")
include(":tina-exec:integration")
project(":tina-exec").projectDir = file("external/tina-exec")
project(":tina-exec:runtime").projectDir = file("external/tina-exec/runtime")
project(":tina-exec:integration").projectDir = file("external/tina-exec/integration")

// Include xCrash (local build with 16KB page alignment)
include(":xcrash")
project(":xcrash").projectDir = file("external/xcrash")

// Include ImmersionBar local modules (避免依赖 JitPack，使用本地源码)
include(":immersionbar-local")
project(":immersionbar-local").projectDir = file("external/immersionbar/immersionbar")
include(":immersionbar-ktx-local")
project(":immersionbar-ktx-local").projectDir = file("external/immersionbar/immersionbar-ktx")

// Include XXPermissions + DeviceCompat local modules (避免依赖 JitPack)
include(":devicecompat-local")
project(":devicecompat-local").projectDir = file("external/devicecompat/library")
include(":xxpermissions-local")
project(":xxpermissions-local").projectDir = file("external/xxpermissions/library")

// APK template modules (built on-demand, not part of default build)
include(":tools:template-common")
include(":tools:template-native-activity")
include(":tools:template-sdl3")
include(":tools:template-terminal")
