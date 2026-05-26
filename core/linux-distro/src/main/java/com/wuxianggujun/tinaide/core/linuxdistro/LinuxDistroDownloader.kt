package com.wuxianggujun.tinaide.core.linuxdistro

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class DistroDownloadRequest(
    val url: String,
    val targetFile: File,
    val resume: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
)

data class DistroDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    val fraction: Float? = totalBytes?.takeIf { it > 0L }?.let { total ->
        (downloadedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
}

interface LinuxDistroDownloader {
    suspend fun download(
        request: DistroDownloadRequest,
        progress: (DistroDownloadProgress) -> Unit = {},
    ): File
}

class OkHttpLinuxDistroDownloader(
    private val client: OkHttpClient = OkHttpClient(),
) : LinuxDistroDownloader {

    override suspend fun download(
        request: DistroDownloadRequest,
        progress: (DistroDownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        request.targetFile.parentFile?.mkdirs()
        val startByte = if (request.resume && request.targetFile.isFile) request.targetFile.length() else 0L
        val httpRequest = Request.Builder()
            .url(request.url)
            .apply {
                request.headers.forEach { (name, value) -> header(name, value) }
                if (startByte > 0L) {
                    header("Range", "bytes=$startByte-")
                }
            }
            .build()

        val call = client.newCall(httpRequest)
        val coroutineContext = currentCoroutineContext()
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                call.cancel()
            }
        }
        try {
            runInterruptible { call.execute() }.use { response ->
                if (!response.isSuccessful) {
                    error("Failed to download ${request.url}: HTTP ${response.code}")
                }
                val body = response.body ?: error("Empty response body: ${request.url}")
                val isResumed = response.code == 206 && startByte > 0L
                val contentLength = body.contentLength().takeIf { it >= 0L }
                val totalBytes = when {
                    isResumed && contentLength != null -> startByte + contentLength
                    response.code == 200 && contentLength != null -> contentLength
                    else -> null
                }

                RandomAccessFile(request.targetFile, "rw").use { output ->
                    if (isResumed) {
                        output.seek(startByte)
                    } else {
                        output.setLength(0L)
                    }

                    var downloaded = if (isResumed) startByte else 0L
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = runInterruptible { input.read(buffer) }
                            coroutineContext.ensureActive()
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read.toLong()
                            progress(DistroDownloadProgress(downloaded, totalBytes))
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } finally {
            cancelHandle.dispose()
        }
        request.targetFile
    }

    private companion object {
        private const val BUFFER_SIZE = 8192
    }
}
