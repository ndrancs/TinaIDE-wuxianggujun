package com.wuxianggujun.tinaide.plugin.marketplace

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

class PluginMarketplaceApi private constructor(
    private val indexUrl: String,
    private val client: OkHttpClient,
) {
    private val json = JsonSerializer.default
    private val indexMutex = Mutex()
    private var cachedIndex: PluginRegistryIndex? = null

    companion object {
        private const val TAG = "PluginMarketplaceApi"

        fun create(@Suppress("UNUSED_PARAMETER") context: Context): PluginMarketplaceApi {
            return PluginMarketplaceApi(
                indexUrl = GitHubRegistryConfig.PLUGINS_INDEX_URL,
                client = OkHttpClientProvider.default,
            )
        }
    }

    suspend fun listPlugins(
        page: Int = 1,
        limit: Int = 20,
        category: String? = null,
        search: String? = null,
        sort: String? = null,
    ): ApiResult<PluginListData> = withIndex { index ->
        val filtered = index.plugins
            .asSequence()
            .filter { plugin -> category.isNullOrBlank() || plugin.category == category }
            .filter { plugin ->
                val query = search?.trim().orEmpty()
                query.isBlank() ||
                    plugin.name.contains(query, ignoreCase = true) ||
                    plugin.pluginId.contains(query, ignoreCase = true) ||
                    plugin.description?.contains(query, ignoreCase = true) == true ||
                    plugin.tags.any { it.contains(query, ignoreCase = true) }
            }
            .sortedWith(pluginSortComparator(sort))
            .toList()

        val safeLimit = limit.coerceAtLeast(1)
        val safePage = page.coerceAtLeast(1)
        val total = filtered.size
        val totalPages = if (total == 0) 1 else ((total + safeLimit - 1) / safeLimit)
        val pageItems = filtered
            .drop((safePage - 1) * safeLimit)
            .take(safeLimit)
            .map { it.toSummary() }

        PluginListData(
            plugins = pageItems,
            pagination = Pagination(
                page = safePage,
                limit = safeLimit,
                total = total.toLong(),
                totalPages = totalPages,
            ),
        )
    }

    suspend fun getPluginDetail(pluginId: String): ApiResult<PluginDetail> = withIndex { index ->
        index.plugins.firstOrNull { it.pluginId == pluginId || it.id == pluginId }
            ?.toDetail()
            ?: throw NoSuchElementException(Strings.plugin_marketplace_error_plugin_not_found.str(pluginId))
    }

    suspend fun ratePlugin(pluginId: String, rating: Int): ApiResult<RatePluginResponse> {
        return ApiResult.Error(405, Strings.market_open_source_interaction_unavailable.str())
    }

    suspend fun submitPluginComment(pluginId: String, content: String): ApiResult<PluginComment> {
        return ApiResult.Error(405, Strings.market_open_source_interaction_unavailable.str())
    }

    suspend fun reportPluginComment(
        pluginId: String,
        commentId: String,
        reason: String,
        details: String?,
    ): ApiResult<ReportPluginCommentResponse> {
        return ApiResult.Error(405, Strings.market_open_source_interaction_unavailable.str())
    }

    suspend fun checkUpdates(
        plugins: List<CheckUpdateItem>,
    ): ApiResult<CheckUpdateData> = withIndex { index ->
        val updates = plugins.mapNotNull { installed ->
            val remote = index.plugins.firstOrNull { it.pluginId == installed.pluginId } ?: return@mapNotNull null
            val latest = remote.latestVersionEntry() ?: return@mapNotNull null
            if (!isNewerVersion(latest.version, installed.version)) return@mapNotNull null
            PluginUpdateInfo(
                pluginId = installed.pluginId,
                currentVersion = installed.version,
                latestVersion = latest.version,
                downloadUrl = latest.downloadUrl?.let(GitHubRegistryConfig::resolveRawUrl).orEmpty(),
                changelog = latest.changelog,
                fileSize = latest.fileSize,
            )
        }
        CheckUpdateData(updates)
    }

    suspend fun downloadPlugin(
        pluginId: String,
        version: String? = null,
        targetFile: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): ApiResult<File> = withContext(Dispatchers.IO) {
        try {
            val index = when (val indexResult = loadIndex()) {
                is ApiResult.Success -> indexResult.data
                is ApiResult.Error -> return@withContext indexResult
                is ApiResult.NetworkError -> return@withContext indexResult
            }
            val plugin = index.plugins.firstOrNull { it.pluginId == pluginId || it.id == pluginId }
                ?: return@withContext ApiResult.Error(
                    404,
                    Strings.plugin_marketplace_error_plugin_not_found.str(pluginId),
                )
            val pluginVersion = plugin.resolveVersion(version)
                ?: return@withContext ApiResult.Error(
                    404,
                    Strings.plugin_marketplace_error_plugin_version_not_found.str(version ?: "latest"),
                )
            val downloadUrl = pluginVersion.downloadUrl?.let(GitHubRegistryConfig::resolveRawUrl)
                ?: return@withContext ApiResult.Error(
                    -1,
                    Strings.plugin_marketplace_error_download_url_missing.str(pluginId),
                )

            downloadFile(
                url = downloadUrl,
                targetFile = targetFile,
                expectedHash = pluginVersion.fileHash,
                onProgress = onProgress,
            )
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Download plugin failed")
            ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Download plugin unknown error")
            ApiResult.Error(-1, e.message ?: Strings.error_unknown.str())
        }
    }

    private suspend fun <T> withIndex(block: (PluginRegistryIndex) -> T): ApiResult<T> {
        return when (val result = loadIndex()) {
            is ApiResult.Success -> runCatching { ApiResult.Success(block(result.data)) }
                .getOrElse { error -> ApiResult.Error(-1, error.message ?: Strings.error_unknown.str()) }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    private suspend fun loadIndex(): ApiResult<PluginRegistryIndex> = withContext(Dispatchers.IO) {
        cachedIndex?.let { return@withContext ApiResult.Success(it) }
        indexMutex.withLock {
            cachedIndex?.let { return@withLock ApiResult.Success(it) }
            try {
                val response = client.newCall(
                    Request.Builder()
                        .url(indexUrl)
                        .get()
                        .build()
                ).execute()
                response.use { resp ->
                    val body = resp.body?.string()
                    if (!resp.isSuccessful) {
                        return@use ApiResult.Error(resp.code, "GitHub registry request failed: HTTP ${resp.code}")
                    }
                    if (body.isNullOrBlank()) {
                        return@use ApiResult.Error(-1, Strings.error_response_empty.str())
                    }
                    val index = json.decodeFromString<PluginRegistryIndex>(body)
                    cachedIndex = index
                    ApiResult.Success(index)
                }
            } catch (e: IOException) {
                Timber.tag(TAG).e(e, "Load plugin registry failed")
                ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Parse plugin registry failed")
                ApiResult.Error(-1, e.message ?: Strings.error_response_parse_failed.str())
            }
        }
    }

    private fun downloadFile(
        url: String,
        targetFile: File,
        expectedHash: String?,
        onProgress: ((downloaded: Long, total: Long) -> Unit)?,
    ): ApiResult<File> {
        var startByte = 0L
        if (targetFile.exists()) {
            startByte = targetFile.length()
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        if (startByte > 0) {
            requestBuilder.addHeader("Range", "bytes=$startByte-")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        response.use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                return ApiResult.Error(resp.code, "${Strings.error_download_failed.str()} (HTTP ${resp.code})")
            }

            val body = resp.body ?: return ApiResult.Error(-1, Strings.error_download_failed.str())
            val contentLength = body.contentLength()
            val total = if (resp.code == 206) {
                resp.header("Content-Range")?.substringAfter("/")?.toLongOrNull() ?: (startByte + contentLength)
            } else {
                contentLength
            }

            val isResume = resp.code == 206
            RandomAccessFile(targetFile, "rw").use { raf ->
                if (isResume) {
                    raf.seek(startByte)
                } else {
                    raf.setLength(0)
                }

                val buffer = ByteArray(8192)
                var downloaded = if (isResume) startByte else 0L
                body.byteStream().use { inputStream ->
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress?.invoke(downloaded, total)
                    }
                }
            }

            if (!expectedHash.isNullOrBlank()) {
                val actualHash = calculateSha256(targetFile)
                val expectedHashValue = expectedHash.substringAfter("sha256:", expectedHash)
                if (!actualHash.equals(expectedHashValue, ignoreCase = true)) {
                    targetFile.delete()
                    return ApiResult.Error(-1, Strings.error_file_hash_mismatch.str())
                }
            }

            return ApiResult.Success(targetFile)
        }
    }

    private fun pluginSortComparator(sort: String?): Comparator<PluginRegistryEntry> {
        return when (sort) {
            PluginSortType.RATING.value -> compareByDescending<PluginRegistryEntry> { it.ratingAvg }
                .thenByDescending { it.ratingCount }
            PluginSortType.NEWEST.value -> compareByDescending { it.createdAt }
            PluginSortType.UPDATED.value -> compareByDescending { it.updatedAt }
            else -> compareByDescending<PluginRegistryEntry> { it.downloadCount }
                .thenByDescending { it.updatedAt }
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val localParts = local.split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        for (index in 0 until maxOf(remoteParts.size, localParts.size)) {
            val left = remoteParts.getOrElse(index) { 0 }
            val right = localParts.getOrElse(index) { 0 }
            if (left > right) return true
            if (left < right) return false
        }
        return remote != local
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class PluginRegistryIndex(
    val plugins: List<PluginRegistryEntry> = emptyList(),
)

@Serializable
data class PluginRegistryEntry(
    val id: String,
    @SerialName("plugin_id")
    val pluginId: String,
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("icon_url")
    val iconUrl: String? = null,
    @SerialName("repository_url")
    val repositoryUrl: String? = null,
    @SerialName("homepage_url")
    val homepageUrl: String? = null,
    val license: String? = null,
    val publisher: PluginPublisher,
    val versions: List<PluginVersion> = emptyList(),
    @SerialName("download_count")
    val downloadCount: Long = 0,
    @SerialName("rating_avg")
    val ratingAvg: Double = 0.0,
    @SerialName("rating_count")
    val ratingCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
) {
    fun latestVersionEntry(): PluginVersion? {
        return versions.maxWithOrNull(
            compareBy<PluginVersion> { it.versionCode }.thenBy { it.version }
        )
    }

    fun resolveVersion(version: String?): PluginVersion? {
        return if (version.isNullOrBlank()) {
            latestVersionEntry()
        } else {
            versions.firstOrNull { it.version == version }
        }
    }

    fun toSummary(): PluginSummary {
        return PluginSummary(
            id = id,
            pluginId = pluginId,
            name = name,
            description = description,
            category = category,
            tags = tags,
            iconUrl = iconUrl,
            publisher = publisher,
            latestVersion = latestVersionEntry()?.version,
            downloadCount = downloadCount,
            ratingAvg = ratingAvg,
            ratingCount = ratingCount,
            updatedAt = updatedAt,
        )
    }

    fun toDetail(): PluginDetail {
        return PluginDetail(
            id = id,
            pluginId = pluginId,
            name = name,
            description = description,
            category = category,
            tags = tags,
            iconUrl = iconUrl,
            repositoryUrl = repositoryUrl,
            homepageUrl = homepageUrl,
            license = license,
            publisher = publisher,
            versions = versions.sortedWith(compareByDescending<PluginVersion> { it.versionCode }.thenByDescending { it.version }),
            comments = emptyList(),
            canComment = false,
            commentDisabledReason = Strings.market_open_source_interaction_unavailable.str(),
            downloadCount = downloadCount,
            ratingAvg = ratingAvg,
            ratingCount = ratingCount,
            myRating = null,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
