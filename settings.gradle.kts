@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("external/sora-editor/build-logic")
    repositories {
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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // For libraries published on JitPack (e.g., getActivity/XXPermissions)
        maven("https://jitpack.io")
    }
}

rootProject.name = "TinaIDE"
include(":app")


// Include AIDE-Termux modules (library)
// include(":termux-app", ":terminal-emulator", ":terminal-view", ":termux-shared")
// project(":termux-app").projectDir = file("external/AIDE-Termux/app")
// project(":terminal-emulator").projectDir = file("external/AIDE-Termux/terminal-emulator")
// project(":terminal-view").projectDir = file("external/AIDE-Termux/terminal-view")
// project(":termux-shared").projectDir = file("external/AIDE-Termux/termux-shared")
// Include SoraEditor modules
include(":sora-editor:editor")
include(":sora-editor:language-textmate")
include(":sora-editor:language-treesitter")
project(":sora-editor:editor").projectDir = file("external/sora-editor/editor")
project(":sora-editor:language-textmate").projectDir = file("external/sora-editor/language-textmate")
project(":sora-editor:language-treesitter").projectDir = file("external/sora-editor/language-treesitter")
include(":treeview")
