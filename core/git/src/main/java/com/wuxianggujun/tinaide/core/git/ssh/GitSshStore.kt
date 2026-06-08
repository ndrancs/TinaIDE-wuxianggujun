package com.wuxianggujun.tinaide.core.git.ssh

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GitSshStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val mutex = Mutex()

    suspend fun read(): GitSshState = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext GitSshState()
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
            if (text.isBlank()) return@withContext GitSshState()
            runCatching { parseState(JSONObject(text)) }.getOrNull() ?: GitSshState()
        }
    }

    suspend fun write(state: GitSshState) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(toJson(state).toString(2), Charsets.UTF_8)
        }
    }

    private fun parseState(json: JSONObject): GitSshState {
        val defaultKeyName = json.optString("defaultKeyName", "").takeIf { it.isNotEmpty() }

        val keysArray = json.optJSONArray("keys") ?: JSONArray()
        val keys = (0 until keysArray.length()).map { i ->
            val obj = keysArray.getJSONObject(i)
            GitSshKeyMeta(
                name = obj.getString("name"),
                type = obj.optString("type", "ed25519"),
                comment = obj.optString("comment", "").takeIf { it.isNotEmpty() },
                createdAtEpochMs = obj.optLong("createdAtEpochMs", System.currentTimeMillis()),
            )
        }

        val bindingsArray = json.optJSONArray("hostBindings") ?: JSONArray()
        val hostBindings = (0 until bindingsArray.length()).map { i ->
            val obj = bindingsArray.getJSONObject(i)
            GitSshHostBinding(
                host = obj.getString("host"),
                keyName = obj.getString("keyName"),
                port = if (obj.has("port") && !obj.isNull("port")) obj.getInt("port") else null,
            )
        }

        return GitSshState(defaultKeyName = defaultKeyName, keys = keys, hostBindings = hostBindings)
    }

    private fun toJson(state: GitSshState): JSONObject {
        val json = JSONObject()
        json.put("defaultKeyName", state.defaultKeyName ?: JSONObject.NULL)

        val keysArray = JSONArray()
        state.keys.forEach { key ->
            keysArray.put(
                JSONObject().apply {
                    put("name", key.name)
                    put("type", key.type)
                    put("comment", key.comment ?: JSONObject.NULL)
                    put("createdAtEpochMs", key.createdAtEpochMs)
                }
            )
        }
        json.put("keys", keysArray)

        val bindingsArray = JSONArray()
        state.hostBindings.forEach { binding ->
            bindingsArray.put(
                JSONObject().apply {
                    put("host", binding.host)
                    put("keyName", binding.keyName)
                    put("port", binding.port ?: JSONObject.NULL)
                }
            )
        }
        json.put("hostBindings", bindingsArray)

        return json
    }

    private companion object {
        private const val FILE_NAME = "git-ssh.json"
    }
}
