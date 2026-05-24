package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.project.ProjectApkExportType
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import java.io.File
import timber.log.Timber

/**
 * 在项目创建完成后，为需要显式运行配置的项目补齐默认 run config。
 */
object ProjectRunConfigBootstrapper {
    private const val TAG = "ProjectRunConfigBootstrapper"

    fun initializeIfMissing(projectDir: File): Boolean {
        if (!projectDir.isDirectory) return false

        val projectPath = projectDir.absolutePath
        val configFile = RunConfigurationManager.configFile(projectPath)
        if (configFile.exists()) return false

        val metadata = ProjectMetadataStore.read(projectDir) ?: return false
        if (metadata.apkExportType != ProjectApkExportType.SDL3) return false

        val defaultManager = RunConfigurationManager.load(projectPath)
        val saved = RunConfigurationManager.save(projectPath, defaultManager)
        if (saved) {
            Timber.tag(TAG).i("Initialized explicit SDL run config for SDL3 project: %s", projectPath)
        }
        return saved
    }
}
