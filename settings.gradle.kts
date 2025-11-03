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

// Include Termux modules from the cloned source at external/termux-app
include(":termux-shared", ":terminal-emulator", ":terminal-view")
project(":termux-shared").projectDir = file("external/termux-app/termux-shared")
project(":terminal-emulator").projectDir = file("external/termux-app/terminal-emulator")
project(":terminal-view").projectDir = file("external/termux-app/terminal-view")


// Include SoraEditor modules for code editing
include(":sora-editor:editor")
include(":sora-editor:language-textmate")
project(":sora-editor:editor").projectDir = file("external/sora-editor/editor")
project(":sora-editor:language-textmate").projectDir = file("external/sora-editor/language-textmate")
