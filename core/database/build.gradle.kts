plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.wuxianggujun.tinaide.database"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    // 依赖 core:common（接口层）
    implementation(project(":core:common"))
    // 依赖 core:network（API 客户端）
    implementation(project(":core:network"))
    // Room 数据库
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Kotlin 协程
    implementation(libs.kotlinx.coroutines)

    // Koin DI
    implementation(libs.koin.android)

    // WorkManager（后台同步）
    implementation(libs.work.runtime)
}
