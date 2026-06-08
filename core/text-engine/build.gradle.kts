plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.core.textengine"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.timber)
}
