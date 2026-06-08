package com.wuxianggujun.tinaide.core.git

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidGitCredentialManager(
    private val context: Context
) : GitCredentialManager {

    private val appContext = context.applicationContext

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun saveHttpsCredential(host: String, username: String, token: String) {
        val safeHost = host.trim()
        if (safeHost.isEmpty()) return

        withContext(Dispatchers.IO) {
            val hosts = (prefs.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet()).toMutableSet()
            hosts.add(safeHost)
            prefs.edit()
                .putString(keyUsername(safeHost), username)
                .putString(keyToken(safeHost), token)
                .putStringSet(KEY_HOSTS, hosts)
                .apply()
        }
    }

    override suspend fun getHttpsCredential(host: String): GitCredential? {
        val safeHost = host.trim()
        if (safeHost.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            val username = prefs.getString(keyUsername(safeHost), null)?.trim().orEmpty()
            val token = prefs.getString(keyToken(safeHost), null)?.trim().orEmpty()
            if (token.isBlank()) return@withContext null

            GitCredential(
                protocol = "https",
                host = safeHost,
                username = username.ifBlank { DEFAULT_USERNAME },
                password = token
            )
        }
    }

    override suspend fun deleteHttpsCredential(host: String) {
        val safeHost = host.trim()
        if (safeHost.isEmpty()) return

        withContext(Dispatchers.IO) {
            val hosts = (prefs.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet()).toMutableSet()
            hosts.remove(safeHost)
            prefs.edit()
                .remove(keyUsername(safeHost))
                .remove(keyToken(safeHost))
                .putStringSet(KEY_HOSTS, hosts)
                .apply()
        }
    }

    override suspend fun listHttpsHosts(): List<String> = withContext(Dispatchers.IO) {
        (prefs.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun keyUsername(host: String) = "https:$host:username"
    private fun keyToken(host: String) = "https:$host:token"

    private companion object {
        private const val PREFS_NAME = "git_credentials"
        private const val DEFAULT_USERNAME = "oauth2"
        private const val KEY_HOSTS = "https_hosts"
    }
}
