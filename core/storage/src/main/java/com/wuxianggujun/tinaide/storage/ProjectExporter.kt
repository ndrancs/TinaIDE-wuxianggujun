package com.wuxianggujun.tinaide.storage

import android.content.Context
import android.content.Intent
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.utils.FileUtils
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 项目导出工具类
 * 支持将项目目录打包为 ZIP 文件并通过系统分享功能分享
 */
object ProjectExporter {

    private const val TAG = "ProjectExporter"
    private const val BUFFER_SIZE = 8192

    /**
     * 导出结果
     */
    sealed class ExportResult {
        data class Success(val zipFile: File) : ExportResult()
        data class Error(val message: String, val exception: Exception? = null) : ExportResult()
    }

    /**
     * 导出进度回调
     */
    interface ExportProgressListener {
        fun onProgress(current: Int, total: Int, currentFileName: String)
        fun onComplete(result: ExportResult)
    }

    /**
     * 导出项目为 ZIP 文件
     *
     * @param context 上下文
     * @param projectDir 项目目录
     * @param progressListener 进度监听器（可选）
     * @return 导出结果
     */
    suspend fun exportProject(
        context: Context,
        projectDir: File,
        progressListener: ExportProgressListener? = null
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            if (!projectDir.exists() || !projectDir.isDirectory) {
                return@withContext ExportResult.Error(Strings.export_dir_not_exist.strOr(context))
            }

            val projectName = projectDir.name
            val cacheDir = ProjectPaths.ensureDir(
                ProjectPaths.getExportCacheRoot(context)
            )

            // 清理旧的导出文件
            cleanOldExports(cacheDir)

            val zipFileName = "${projectName}_${System.currentTimeMillis()}.zip"
            val zipFile = File(cacheDir, zipFileName)

            // 统计文件数量
            val allFiles = projectDir.walkTopDown()
                .filter { it.isFile }
                .filter { !shouldExclude(it, projectDir) }
                .toList()

            val totalFiles = allFiles.size

            if (totalFiles == 0) {
                return@withContext ExportResult.Error(Strings.export_empty_project.strOr(context))
            }

            Timber.tag(TAG).i("Starting export of %s with %d files", projectName, totalFiles)

            // 创建 ZIP 文件
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zipOut ->
                var currentIndex = 0

                for (file in allFiles) {
                    val relativePath = file.relativeTo(projectDir).path

                    // 更新进度
                    currentIndex++
                    progressListener?.onProgress(currentIndex, totalFiles, file.name)

                    // 添加文件到 ZIP
                    addFileToZip(zipOut, file, relativePath)
                }
            }

            Timber.tag(TAG).i("Export completed: %s (%s)", zipFile.absolutePath, FileUtils.getFormattedSize(zipFile))

            val result = ExportResult.Success(zipFile)
            progressListener?.onComplete(result)
            result
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Export failed")
            val result = ExportResult.Error(Strings.export_failed.strOr(context, e.message ?: ""), e)
            progressListener?.onComplete(result)
            result
        }
    }

    /**
     * 分享导出的 ZIP 文件
     *
     * @param context 上下文
     * @param zipFile ZIP 文件
     * @param projectName 项目名称（用于分享标题）
     */
    fun shareZipFile(context: Context, zipFile: File, projectName: String) {
        try {
            val uri = ExternalFileIntents.getShareableUri(context, zipFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, Strings.export_share_subject.strOr(context, projectName))
                putExtra(Intent.EXTRA_TEXT, Strings.export_share_text.strOr(context, projectName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(shareIntent, Strings.export_share_title.strOr(context))
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooserIntent)

            Timber.tag(TAG).i("Share intent launched for %s", projectName)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to share zip file")
            throw e
        }
    }

    /**
     * 将文件添加到 ZIP
     */
    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        val entry = ZipEntry(entryName)
        entry.time = file.lastModified()
        zipOut.putNextEntry(entry)

        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var len: Int
            while (input.read(buffer).also { len = it } > 0) {
                zipOut.write(buffer, 0, len)
            }
        }

        zipOut.closeEntry()
    }

    /**
     * 判断文件是否应该被排除
     * 排除构建产物、缓存文件等
     */
    private fun shouldExclude(file: File, projectRoot: File): Boolean {
        val relativePath = file.relativeTo(projectRoot).path

        // 排除的目录和文件模式
        val excludePatterns = listOf(
            // 构建目录
            "build/",
            "cmake-build-",
            ".gradle/",

            // IDE 配置（可选保留）
            ".idea/",

            // 版本控制
            ".git/",
            ".svn/",

            // 编译产物
            "*.o",
            "*.obj",
            "*.exe",
            "*.dll",
            "*.so",
            "*.dylib",
            "*.a",
            "*.lib",

            // 临时文件
            "*.tmp",
            "*.temp",
            "*.swp",
            "*.bak",
            "*~",

            // macOS
            ".DS_Store",
            "._*",

            // Windows
            "Thumbs.db",
            "desktop.ini"
        )

        for (pattern in excludePatterns) {
            when {
                // 目录匹配
                pattern.endsWith("/") -> {
                    if (relativePath.startsWith(pattern) || relativePath.contains("/$pattern")) {
                        return true
                    }
                }
                // 通配符匹配
                pattern.startsWith("*.") -> {
                    val extension = pattern.removePrefix("*")
                    if (file.name.endsWith(extension)) {
                        return true
                    }
                }
                pattern.startsWith("*") -> {
                    val suffix = pattern.removePrefix("*")
                    if (file.name.endsWith(suffix)) {
                        return true
                    }
                }
                // 精确匹配
                else -> {
                    if (file.name == pattern || relativePath.contains("/$pattern")) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * 清理旧的导出文件（保留最近 5 个）
     */
    private fun cleanOldExports(exportDir: File) {
        try {
            val zipFiles = exportDir.listFiles { file -> file.extension == "zip" }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            // 保留最近 5 个，删除其余的
            if (zipFiles.size > 5) {
                zipFiles.drop(5).forEach { file ->
                    file.delete()
                    Timber.tag(TAG).d("Cleaned old export: %s", file.name)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clean old exports")
        }
    }

    /**
     * 获取导出文件的大小估算
     *
     * @param projectDir 项目目录
     * @return 估算的文件数量和大小
     */
    fun estimateExportSize(projectDir: File): Pair<Int, Long> {
        var fileCount = 0
        var totalSize = 0L

        projectDir.walkTopDown()
            .filter { it.isFile }
            .filter { !shouldExclude(it, projectDir) }
            .forEach { file ->
                fileCount++
                totalSize += file.length()
            }

        return Pair(fileCount, totalSize)
    }
}
