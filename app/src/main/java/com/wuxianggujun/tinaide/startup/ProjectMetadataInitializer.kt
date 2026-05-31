package com.wuxianggujun.tinaide.startup

import android.content.Context
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import timber.log.Timber

/**
 * 项目元数据与文件提供器初始化
 *
 * - 设置 ProjectMetadataStore 的 IDE 版本信息
 */
class ProjectMetadataInitializer(private val context: Context) {

    companion object {
        private const val TAG = "ProjectMetadataInitializer"
    }

    fun execute() {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            ProjectMetadataStore.currentIdeVersion = packageInfo.versionName ?: "unknown"
            Timber.tag(TAG).i("ProjectMetadataStore initialized with IDE version: %s", ProjectMetadataStore.currentIdeVersion)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get app version")
            ProjectMetadataStore.currentIdeVersion = "unknown"
        }
    }
}
