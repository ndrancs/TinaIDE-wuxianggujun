package com.wuxianggujun.tinaide.core.packages.api

import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.str
import com.wuxianggujun.tinaide.core.network.ApiResult
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import com.wuxianggujun.tinaide.core.network.registry.GitHubRegistryConfig
import com.wuxianggujun.tinaide.core.packages.model.DownloadInfo
import com.wuxianggujun.tinaide.core.packages.model.DownloadSource
import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.PackageCategory
import com.wuxianggujun.tinaide.core.packages.model.PackageVersion
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.serialization.JsonSerializer
import java.io.IOException
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

class PackageApiClient private constructor(
    private val indexUrl: String,
    private val client: OkHttpClient,
) {
    private val json = JsonSerializer.default
    private val indexMutex = Mutex()
    private var cachedIndex: PackageRegistryIndex? = null

    companion object {
        private const val TAG = "PackageApiClient"

        @Volatile
        private var instance: PackageApiClient? = null

        fun getInstance(): PackageApiClient {
            return instance ?: synchronized(this) {
                instance ?: createInstance().also { instance = it }
            }
        }

        private fun createInstance(): PackageApiClient {
            return PackageApiClient(
                indexUrl = GitHubRegistryConfig.PACKAGES_INDEX_URL,
                client = OkHttpClientProvider.default,
            )
        }

        fun resetInstance() {
            instance = null
        }
    }

    suspend fun getPackages(
        page: Int = 1,
        pageSize: Int = 50,
        category: String? = null,
        platform: String? = null,
        search: String? = null,
    ): ApiResult<PackageListResponse> = withIndex { index ->
        val filtered = index.packages
            .asSequence()
            .filter { pkg -> category.isNullOrBlank() || pkg.category == category }
            .filter { pkg -> platform.isNullOrBlank() || pkg.hasPlatform(platform) }
            .filter { pkg ->
                val query = search?.trim().orEmpty()
                query.isBlank() ||
                    pkg.id.contains(query, ignoreCase = true) ||
                    pkg.name.contains(query, ignoreCase = true) ||
                    pkg.description?.contains(query, ignoreCase = true) == true
            }
            .sortedBy { it.name.lowercase() }
            .toList()

        val safePageSize = pageSize.coerceAtLeast(1)
        val safePage = page.coerceAtLeast(1)
        PackageListResponse(
            packages = filtered.drop((safePage - 1) * safePageSize).take(safePageSize),
            total = filtered.size,
            page = safePage,
            pageSize = safePageSize,
        )
    }

    suspend fun getCategories(): ApiResult<List<PackageCategory>> = withIndex { index ->
        index.categories.takeIf { it.isNotEmpty() } ?: deriveCategories(index.packages)
    }

    suspend fun getPackageDetail(packageId: String): ApiResult<GUIPackage> = withIndex { index ->
        index.packages.firstOrNull { it.id == packageId }
            ?: throw NoSuchElementException(Strings.pkg_manager_error_package_not_found.str(packageId))
    }

    suspend fun getPackageVersions(packageId: String): ApiResult<PackageVersionsResponse> = withIndex { index ->
        index.versions[packageId]
            ?: index.packages.firstOrNull { it.id == packageId }?.toVersions(packageId)
            ?: throw NoSuchElementException(Strings.pkg_manager_error_package_versions_not_found.str(packageId))
    }

    suspend fun getDownloadInfo(packageId: String, versionId: Int): ApiResult<DownloadInfo> = withIndex { index ->
        index.downloads["$packageId:$versionId"]
            ?.withResolvedSources()
            ?: resolveDownloadInfo(packageId, versionId, index)
            ?: throw NoSuchElementException(
                Strings.pkg_manager_error_download_info_not_found.str(packageId, versionId),
            )
    }

    private suspend fun <T> withIndex(block: (PackageRegistryIndex) -> T): ApiResult<T> {
        return when (val result = loadIndex()) {
            is ApiResult.Success -> runCatching { ApiResult.Success(block(result.data)) }
                .getOrElse { error -> ApiResult.Error(-1, error.message ?: Strings.error_unknown.str()) }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    private suspend fun loadIndex(): ApiResult<PackageRegistryIndex> = withContext(Dispatchers.IO) {
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
                    val index = json.decodeFromString<PackageRegistryIndex>(body)
                    cachedIndex = index
                    ApiResult.Success(index)
                }
            } catch (e: IOException) {
                Timber.tag(TAG).e(e, "Load package registry failed")
                ApiResult.NetworkError(e.message ?: Strings.error_network_connection_failed.str())
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Parse package registry failed")
                ApiResult.Error(-1, e.message ?: Strings.error_response_parse_failed.str())
            }
        }
    }

    private fun resolveDownloadInfo(
        packageId: String,
        versionId: Int,
        index: PackageRegistryIndex,
    ): DownloadInfo? {
        val versions = index.versions[packageId]
            ?: index.packages.firstOrNull { it.id == packageId }?.toVersions(packageId)
            ?: return null
        val version = versions.allVersions().firstOrNull { it.id == versionId } ?: return null
        val sources = when {
            !version.downloadSources.isNullOrEmpty() -> version.downloadSources
            !version.downloadUrl.isNullOrBlank() -> listOf(
                DownloadSource(
                    id = 1,
                    name = "GitHub",
                    url = version.downloadUrl,
                    region = null,
                    priority = 100,
                    supportsRange = true,
                )
            )
            else -> emptyList()
        }.map { it.copy(url = GitHubRegistryConfig.resolveRawUrl(it.url)) }

        if (sources.isEmpty()) return null

        return DownloadInfo(
            packageId = packageId,
            version = version.version,
            platform = version.platform,
            installType = version.installType,
            size = version.downloadSize,
            checksum = version.checksum,
            sources = sources,
        )
    }

    private fun deriveCategories(packages: List<GUIPackage>): List<PackageCategory> {
        return packages.mapNotNull { it.category }
            .distinct()
            .sorted()
            .mapIndexed { index, category ->
                PackageCategory(
                    id = category,
                    name = category.replaceFirstChar { it.uppercase() },
                    sortOrder = index,
                )
            }
    }

    private fun GUIPackage.hasPlatform(platform: String): Boolean {
        return when (platform.lowercase()) {
            Platform.LINUX.name.lowercase() -> linux != null
            Platform.ANDROID.name.lowercase() -> android != null
            else -> true
        }
    }

    private fun GUIPackage.toVersions(packageId: String): PackageVersionsResponse {
        return PackageVersionsResponse(
            linux = linux?.let { pkg ->
                listOf(
                    PackageVersion(
                        id = 1,
                        packageId = packageId,
                        platform = Platform.LINUX,
                        version = pkg.version,
                        installType = pkg.installType,
                        aptPackage = pkg.aptPackage,
                        downloadSize = pkg.size,
                        downloadUrl = pkg.downloadUrl,
                        downloadSources = pkg.downloadSources,
                        checksum = pkg.checksum,
                        abi = pkg.abi,
                        dependencies = pkg.dependencies,
                        releaseNotes = pkg.releaseNotes,
                        isLatest = true,
                    )
                )
            },
            android = android?.let { pkg ->
                listOf(
                    PackageVersion(
                        id = 2,
                        packageId = packageId,
                        platform = Platform.ANDROID,
                        version = pkg.version,
                        installType = pkg.installType,
                        aptPackage = pkg.aptPackage,
                        downloadSize = pkg.size,
                        downloadUrl = pkg.downloadUrl,
                        downloadSources = pkg.downloadSources,
                        checksum = pkg.checksum,
                        abi = pkg.abi,
                        dependencies = pkg.dependencies,
                        releaseNotes = pkg.releaseNotes,
                        isLatest = true,
                    )
                )
            },
        )
    }

    private fun PackageVersionsResponse.allVersions(): List<PackageVersion> {
        return linux.orEmpty() + android.orEmpty()
    }

    private fun DownloadInfo.withResolvedSources(): DownloadInfo {
        return copy(
            sources = sources.map { source ->
                source.copy(url = GitHubRegistryConfig.resolveRawUrl(source.url))
            }
        )
    }
}

@Serializable
data class PackageRegistryIndex(
    val packages: List<GUIPackage> = emptyList(),
    val categories: List<PackageCategory> = emptyList(),
    val versions: Map<String, PackageVersionsResponse> = emptyMap(),
    val downloads: Map<String, DownloadInfo> = emptyMap(),
)

@Serializable
data class PackageListResponse(
    val packages: List<GUIPackage>,
    val total: Int,
    val page: Int,
    @SerialName("page_size") val pageSize: Int,
)

@Serializable
data class PackageVersionsResponse(
    val linux: List<PackageVersion>? = null,
    val android: List<PackageVersion>? = null,
)
