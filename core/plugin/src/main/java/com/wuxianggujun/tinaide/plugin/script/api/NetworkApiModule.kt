package com.wuxianggujun.tinaide.plugin.script.api

import android.content.Context
import com.wuxianggujun.tinaide.core.network.OkHttpClientProvider
import com.wuxianggujun.tinaide.plugin.PluginNetworkHostRules
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import party.iroiro.luajava.Lua
import timber.log.Timber

class NetworkApiModule(
    private val context: Context,
    private val allowedHosts: Set<String> = emptySet()
) : PluginApiModule {
    override val namespace = "network"

    private var runtime: ScriptPluginRuntime? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val normalizedAllowedHosts = PluginNetworkHostRules.normalizeDeclaredHosts(allowedHosts)

    private val httpClient by lazy {
        OkHttpClientProvider.default
    }

    override fun register(runtime: ScriptPluginRuntime, lua: Lua) {
        this.runtime = runtime

        lua.push { L: Lua ->
            withPermission(
                this.runtime,
                L,
                namespace,
                "fetch",
                PluginPermission.NETWORK_FETCH,
                PluginPermission.NETWORK_UNRESTRICTED
            ) {
                val rt = this.runtime ?: return@withPermission 0
                if (!rt.checkNetworkLimit()) {
                    L.pushNil()
                    L.push("Network request rate limit exceeded")
                    return@withPermission 2
                }
                val url = L.getStringArg(1)
                if (url == null) {
                    L.pushNil()
                    L.push("URL is required")
                    return@withPermission 2
                }
                if (!rt.checkPermission(PluginPermission.NETWORK_UNRESTRICTED) &&
                    !PluginNetworkHostRules.isUrlAllowed(url, rt.getAllowedHosts(), normalizedAllowedHosts)
                ) {
                    L.pushNil()
                    L.push("Host not in whitelist: ${PluginNetworkHostRules.extractRequestHost(url)}")
                    return@withPermission 2
                }
                val method = L.getStringArg(2)?.uppercase() ?: "GET"
                val body = L.getStringArg(3)
                val contentType = L.getStringArg(4) ?: "application/json"
                try {
                    val requestBuilder = Request.Builder().url(url)
                    when (method) {
                        "GET" -> requestBuilder.get()
                        "POST" -> requestBuilder.post((body ?: "").toRequestBody(contentType.toMediaTypeOrNull()))
                        "PUT" -> requestBuilder.put((body ?: "").toRequestBody(contentType.toMediaTypeOrNull()))
                        "DELETE" -> requestBuilder.delete()
                        "PATCH" -> requestBuilder.patch((body ?: "").toRequestBody(contentType.toMediaTypeOrNull()))
                        else -> {
                            L.pushNil()
                            L.push("Unsupported HTTP method: $method")
                            return@withPermission 2
                        }
                    }
                    val response = httpClient.newCall(requestBuilder.build()).execute()
                    L.createTable(0, 4)
                    L.push(response.code)
                    L.setField(-2, "status")
                    L.push(response.isSuccessful)
                    L.setField(-2, "ok")
                    L.push(response.body?.string() ?: "")
                    L.setField(-2, "body")
                    L.createTable(0, response.headers.size)
                    response.headers.forEach { (name, value) ->
                        L.push(value)
                        L.setField(-2, name)
                    }
                    L.setField(-2, "headers")
                    response.close()
                    1
                } catch (e: Exception) {
                    Timber.w(e, "Network request failed: $url")
                    L.pushNil()
                    L.push("Network request failed: ${e.message}")
                    2
                }
            }
        }
        lua.setField(-2, "fetch")

        lua.push { L: Lua ->
            withPermission(
                this.runtime,
                L,
                namespace,
                "get",
                PluginPermission.NETWORK_FETCH,
                PluginPermission.NETWORK_UNRESTRICTED
            ) {
                val rt = this.runtime ?: return@withPermission 0
                val url = L.getStringArg(1) ?: run {
                    L.pushNil()
                    L.push("URL is required")
                    return@withPermission 2
                }
                if (!rt.checkPermission(PluginPermission.NETWORK_UNRESTRICTED) &&
                    !PluginNetworkHostRules.isUrlAllowed(url, rt.getAllowedHosts(), normalizedAllowedHosts)
                ) {
                    L.pushNil()
                    L.push("Host not in whitelist: ${PluginNetworkHostRules.extractRequestHost(url)}")
                    return@withPermission 2
                }
                try {
                    val response = httpClient.newCall(Request.Builder().url(url).get().build()).execute()
                    val body = response.body?.string() ?: ""
                    response.close()
                    L.push(body)
                    1
                } catch (e: Exception) {
                    Timber.w(e, "GET request failed: $url")
                    L.pushNil()
                    L.push("GET request failed: ${e.message}")
                    2
                }
            }
        }
        lua.setField(-2, "get")

        lua.push { L: Lua ->
            withPermission(
                this.runtime,
                L,
                namespace,
                "post",
                PluginPermission.NETWORK_FETCH,
                PluginPermission.NETWORK_UNRESTRICTED
            ) {
                val rt = this.runtime ?: return@withPermission 0
                val url = L.getStringArg(1) ?: run {
                    L.pushNil()
                    L.push("URL is required")
                    return@withPermission 2
                }
                val body = L.getStringArg(2)
                val contentType = L.getStringArg(3) ?: "application/json"
                if (!rt.checkPermission(PluginPermission.NETWORK_UNRESTRICTED) &&
                    !PluginNetworkHostRules.isUrlAllowed(url, rt.getAllowedHosts(), normalizedAllowedHosts)
                ) {
                    L.pushNil()
                    L.push("Host not in whitelist: ${PluginNetworkHostRules.extractRequestHost(url)}")
                    return@withPermission 2
                }
                try {
                    val response = httpClient.newCall(
                        Request.Builder().url(url).post((body ?: "").toRequestBody(contentType.toMediaTypeOrNull())).build()
                    ).execute()
                    val responseBody = response.body?.string() ?: ""
                    response.close()
                    L.push(responseBody)
                    1
                } catch (e: Exception) {
                    Timber.w(e, "POST request failed: $url")
                    L.pushNil()
                    L.push("POST request failed: ${e.message}")
                    2
                }
            }
        }
        lua.setField(-2, "post")
    }

    override fun unregister() {
        runtime = null
    }
}
