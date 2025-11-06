package com.wuxianggujun.tinaide.terminal

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 首启下载 proot/libtalloc/alpine rootfs 到 Context.getFilesDir()
 * KISS: 仅下载 + 简单回调，不做复杂重试/校验。
 */
object RuntimeDownloader {
    private data class DownloadFile(val url: String, val outputFile: File)
    private data class AbiUrls(val talloc: String, val proot: String, val alpine: String)

    private val abiMap = mapOf(
        "x86_64" to AbiUrls(
            talloc = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/x86_64/libtalloc.so.2",
            proot = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/x86_64/proot",
            alpine = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-minirootfs-3.21.0-x86_64.tar.gz"
        ),
        "arm64-v8a" to AbiUrls(
            talloc = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/aarch64/libtalloc.so.2",
            proot = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/aarch64/proot",
            alpine = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"
        ),
        "armeabi-v7a" to AbiUrls(
            talloc = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/arm/libtalloc.so.2",
            proot = "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/arm/proot",
            alpine = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/armhf/alpine-minirootfs-3.21.0-armhf.tar.gz"
        )
    )

    suspend fun setupEnvironment(
        context: Context,
        onProgress: (completed: Int, total: Int, progress: Float) -> Unit = { _, _, _ -> },
        onComplete: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        withContext(Dispatchers.IO) {
            try {
                val abi = Build.SUPPORTED_ABIS.firstOrNull { it in abiMap }
                    ?: throw RuntimeException("Unsupported CPU ABI")

                val filesDir = context.filesDir
                val filesToDownload = listOf(
                    DownloadFile(abiMap[abi]!!.talloc, File(filesDir, "libtalloc.so.2")),
                    DownloadFile(abiMap[abi]!!.proot, File(filesDir, "proot")),
                    DownloadFile(abiMap[abi]!!.alpine, File(filesDir, "alpine.tar.gz")),
                )

                val total = filesToDownload.size
                var completed = 0

                filesToDownload.forEach { df ->
                    if (!df.outputFile.exists()) {
                        download(df.url, df.outputFile) { downloaded, totalBytes ->
                            val p = if (totalBytes > 0) downloaded.toFloat() / totalBytes else 1f
                            onProgress(completed, total, p)
                        }
                        if (df.outputFile.name == "proot") df.outputFile.setExecutable(true, false)
                    }
                    completed++
                    onProgress(completed, total, 1f)
                }
                onComplete()
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    private suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${'$'}{resp.code}")
                val body = resp.body ?: throw RuntimeException("Empty body")
                val total = body.contentLength()
                var downloaded = 0L
                dest.parentFile?.mkdirs()
                dest.outputStream().use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val r = inp.read(buf)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            downloaded += r
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
        }
    }
}
