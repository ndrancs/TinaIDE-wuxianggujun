package com.wuxianggujun.tinaide.update

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.wuxianggujun.tinaide.core.common.AppVersionInfoReader
import com.wuxianggujun.tinaide.core.config.AppPreferences
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppUpdateInfo(
    val tagName: String,
    val releaseName: String,
    val currentVersionName: String,
    val releaseNotes: String?,
    val releasePageUrl: String,
    val downloadUrl: String,
    val assetName: String?,
)

class AppUpdateChecker(
    context: Context,
    private val client: OkHttpClient = OkHttpClientProvider.probe,
) {
    private val appContext = context.applicationContext
    private val prefs = AppPreferences.get(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdate(): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val currentVersionName = AppVersionInfoReader.read(appContext).versionName
            val release = fetchLatestRelease().getOrElse {
                fetchLatestReleaseFromRedirect().getOrThrow()
            }

            if (release.draft || release.prerelease) return@runCatching null
            if (!AppUpdateVersioning.isRemoteNewer(currentVersionName, release.tagName)) {
                return@runCatching null
            }
            if (release.tagName == dismissedTagName()) return@runCatching null

            val preferredAsset = AppUpdateVersioning.selectPreferredApkAsset(
                assets = release.assets,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            )
            AppUpdateInfo(
                tagName = release.tagName,
                releaseName = release.name?.takeIf(String::isNotBlank) ?: release.tagName,
                currentVersionName = currentVersionName,
                releaseNotes = release.body?.takeIf(String::isNotBlank),
                releasePageUrl = release.htmlUrl,
                downloadUrl = preferredAsset?.browserDownloadUrl ?: release.htmlUrl,
                assetName = preferredAsset?.name,
            )
        }
    }

    fun markDismissed(tagName: String) {
        prefs.edit { putString(PREF_KEY_DISMISSED_TAG, tagName) }
    }

    private fun dismissedTagName(): String? = prefs.getString(PREF_KEY_DISMISSED_TAG, null)

    private fun fetchLatestRelease(): Result<GitHubRelease> = runCatching {
        val request = Request.Builder()
            .url(GITHUB_RELEASES_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("GitHub releases API failed: HTTP ${response.code}")
            }
            val body = response.body?.string()?.takeIf(String::isNotBlank)
                ?: error("GitHub releases API returned empty body")
            json.decodeFromString<GitHubRelease>(body)
        }
    }

    private fun fetchLatestReleaseFromRedirect(): Result<GitHubRelease> = runCatching {
        val request = Request.Builder()
            .url(GITHUB_RELEASES_LATEST_URL)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("GitHub latest release redirect failed: HTTP ${response.code}")
            }
            val finalUrl = response.request.url.toString()
            val tagName = finalUrl.substringAfterLast("/releases/tag/", missingDelimiterValue = "")
                .takeIf(String::isNotBlank)
                ?: error("GitHub latest release redirect did not expose a tag")
            GitHubRelease(
                tagName = tagName,
                name = tagName,
                body = null,
                htmlUrl = finalUrl,
                draft = false,
                prerelease = false,
                assets = emptyList(),
            )
        }
    }

    private companion object {
        private const val PREF_KEY_DISMISSED_TAG = "app_update_dismissed_tag"
        private const val GITHUB_RELEASES_API_URL =
            "https://api.github.com/repos/wuxianggujun/TinaIDE/releases/latest"
        private const val GITHUB_RELEASES_LATEST_URL =
            "https://github.com/wuxianggujun/TinaIDE/releases/latest"
    }
}

internal object AppUpdateVersioning {
    fun isRemoteNewer(currentVersionName: String, remoteTagName: String): Boolean {
        val current = versionSegments(currentVersionName)
        val remote = versionSegments(remoteTagName)
        if (current.isEmpty() || remote.isEmpty()) return false

        val maxSize = maxOf(current.size, remote.size)
        for (index in 0 until maxSize) {
            val currentPart = current.getOrElse(index) { 0 }
            val remotePart = remote.getOrElse(index) { 0 }
            if (remotePart != currentPart) return remotePart > currentPart
        }
        return false
    }

    internal fun selectPreferredApkAsset(
        assets: List<GitHubReleaseAsset>,
        supportedAbis: List<String>,
    ): GitHubReleaseAsset? {
        val apkAssets = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                asset.browserDownloadUrl.isNotBlank()
        }
        if (apkAssets.isEmpty()) return null

        val normalizedAbis = supportedAbis
            .map(String::lowercase)
            .flatMap { abi -> listOf(abi, abi.replace("-", "_")) }
        return apkAssets.firstOrNull { asset ->
            val assetName = asset.name.lowercase()
            normalizedAbis.any(assetName::contains)
        } ?: apkAssets.first()
    }

    private fun versionSegments(value: String): List<Int> {
        val match = VERSION_PATTERN.find(value.trim().removePrefix("v").removePrefix("V"))
            ?: return emptyList()
        return match.value.split('.').mapNotNull(String::toIntOrNull)
    }

    private val VERSION_PATTERN = Regex("""\d+(?:\.\d+){0,3}""")
}

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url")
    val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
internal data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
)
