package com.wuxianggujun.tinaide.plugin.script

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.plugin.PluginLogEventCodes
import com.wuxianggujun.tinaide.plugin.PluginLogEventKeys
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.PluginNetworkHostRules
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException
import party.iroiro.luajava.lua54.Lua54
import party.iroiro.luajava.value.LuaFunction
import party.iroiro.luajava.value.LuaValue
import timber.log.Timber

sealed class PluginExecutionResult {
    data class Success(val value: Any?) : PluginExecutionResult()
    data class Error(val message: String, val stack: String? = null) : PluginExecutionResult()
    data object Timeout : PluginExecutionResult()
    data object PermissionDenied : PluginExecutionResult()
}

enum class PluginPermissionDenialReason {
    NOT_DECLARED,
    NOT_GRANTED
}

data class PluginPermissionAccessResult(
    val granted: Boolean,
    val grantedPermission: PluginPermission? = null,
    val deniedPermission: PluginPermission? = null,
    val denialReason: PluginPermissionDenialReason? = null
)

interface PluginRuntime {
    val pluginId: String
    val isInitialized: Boolean

    suspend fun initialize(): Result<Unit>
    suspend fun execute(script: String): PluginExecutionResult
    suspend fun callFunction(name: String, vararg args: Any?): PluginExecutionResult
    fun registerGlobal(name: String, value: Any?)
    fun destroy()
}

class ScriptPluginRuntime(
    private val context: Context,
    private val manifest: PluginManifest,
    private val pluginDir: File,
    private val config: PluginSandboxConfig = PluginSandboxConfig.DEFAULT,
    private val permissionManager: PluginPermissionManager
) : PluginRuntime {
    companion object {
        private const val TAG = "ScriptPluginRuntime"
    }

    override val pluginId: String = manifest.id
    val pluginName: String = manifest.name
    val apiVersion: Int = manifest.apiVersion
    private val declaredPermissions = PluginPermission.parseList(manifest.permissions) +
        PluginPermission.parseList(manifest.optionalPermissions)
    private val allowedHosts = PluginNetworkHostRules.normalizeDeclaredHosts(manifest.networkHosts)
    private val logManager = PluginLogManager.getInstance(context)

    private var _isInitialized = false
    override val isInitialized: Boolean get() = _isInitialized

    private val apiCallLimiter = RateLimiter(config.maxApiCallsPerSecond, 1000L)
    private val fileOpLimiter = RateLimiter(config.maxFileOpsPerMinute, 60_000L)
    private val networkLimiter = RateLimiter(config.maxNetworkRequestsPerMinute, 60_000L)

    private var luaState: Lua? = null

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            Timber.tag(TAG).d("Initializing Lua plugin: $pluginId")

            luaState = Lua54().apply {
                openLibraries()
                configureSandbox()
            }

            _isInitialized = true
            Timber.tag(TAG).i("Lua plugin initialized: $pluginId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to initialize Lua plugin: $pluginId")
            Result.failure(e)
        }
    }

    private fun Lua.configureSandbox() {
        run(
            """
            -- Remove dangerous functions for sandboxing
            os.execute = nil
            os.exit = nil
            os.remove = nil
            os.rename = nil
            os.tmpname = nil
            io = nil
            loadfile = nil
            dofile = nil
            
            -- Limit debug library (use rawset to safely nil out fields that may not exist across Lua versions)
            if debug then
                debug.debug = nil
                debug.setmetatable = nil
                debug.setlocal = nil
                debug.setupvalue = nil
                if debug.setfenv then debug.setfenv = nil end
                if debug.setcstacklimit then debug.setcstacklimit = nil end
            end
            """.trimIndent()
        )
    }

    override suspend fun execute(script: String): PluginExecutionResult = withContext(Dispatchers.Default) {
        val lua = luaState
        if (!_isInitialized || lua == null) {
            return@withContext PluginExecutionResult.Error("Plugin not initialized")
        }

        if (!apiCallLimiter.tryAcquire()) {
            return@withContext PluginExecutionResult.Error("API rate limit exceeded")
        }

        try {
            withTimeout(config.maxExecutionTimeMs) {
                Timber.tag(TAG).d("Executing Lua script in plugin: $pluginId")
                lua.run(script)
                val result = if (lua.top > 0) lua.get().toJavaObject() else null
                PluginExecutionResult.Success(result)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Timber.tag(TAG).w("Lua script execution timed out: $pluginId")
            PluginExecutionResult.Timeout
        } catch (e: LuaException) {
            Timber.tag(TAG).e(e, "Lua script execution failed: $pluginId")
            PluginExecutionResult.Error(e.message ?: "Unknown Lua error", e.stackTraceToString())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Lua script execution failed: $pluginId")
            PluginExecutionResult.Error(e.message ?: "Unknown error", e.stackTraceToString())
        }
    }

    override suspend fun callFunction(name: String, vararg args: Any?): PluginExecutionResult = withContext(Dispatchers.Default) {
        val lua = luaState
        if (!_isInitialized || lua == null) {
            return@withContext PluginExecutionResult.Error("Plugin not initialized")
        }

        if (!apiCallLimiter.tryAcquire()) {
            return@withContext PluginExecutionResult.Error("API rate limit exceeded")
        }

        try {
            withTimeout(config.maxExecutionTimeMs) {
                Timber.tag(TAG).d("Calling Lua function $name in plugin: $pluginId")

                lua.getGlobal(name)
                if (lua.isNil(-1)) {
                    lua.pop(1)
                    return@withTimeout PluginExecutionResult.Error("Function '$name' not found")
                }

                args.forEach { arg ->
                    lua.pushAny(arg)
                }

                lua.pCall(args.size, 1)
                val result = if (lua.top > 0) lua.get().toJavaObject() else null
                PluginExecutionResult.Success(result)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Timber.tag(TAG).w("Lua function call timed out: $pluginId.$name")
            PluginExecutionResult.Timeout
        } catch (e: LuaException) {
            Timber.tag(TAG).e(e, "Lua function call failed: $pluginId.$name")
            PluginExecutionResult.Error(e.message ?: "Unknown Lua error", e.stackTraceToString())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Lua function call failed: $pluginId.$name")
            PluginExecutionResult.Error(e.message ?: "Unknown error", e.stackTraceToString())
        }
    }

    override fun registerGlobal(name: String, value: Any?) {
        luaState?.let { lua ->
            lua.pushAny(value)
            lua.setGlobal(name)
        }
    }

    fun registerFunction(name: String, function: (Lua) -> Int) {
        luaState?.let { lua ->
            lua.push { L: Lua ->
                function(L)
            }
            lua.setGlobal(name)
        }
    }

    fun getLuaState(): Lua? = luaState

    override fun destroy() {
        Timber.tag(TAG).d("Destroying Lua plugin: $pluginId")
        try {
            luaState?.close()
            luaState = null
            _isInitialized = false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to destroy Lua plugin: $pluginId")
        }
    }

    fun checkPermission(permission: PluginPermission): Boolean = resolvePermissionAccess(permission).granted

    fun getManifest(): PluginManifest = manifest

    fun checkAnyPermission(vararg permissions: PluginPermission): Boolean = resolvePermissionAccess(*permissions).granted

    fun resolvePermissionAccess(vararg permissions: PluginPermission): PluginPermissionAccessResult {
        require(permissions.isNotEmpty()) { "At least one permission is required" }

        permissions.firstOrNull(::isPermissionGranted)?.let { grantedPermission ->
            return PluginPermissionAccessResult(
                granted = true,
                grantedPermission = grantedPermission
            )
        }

        permissions.firstOrNull { it in declaredPermissions }?.let { declaredPermission ->
            return PluginPermissionAccessResult(
                granted = false,
                deniedPermission = declaredPermission,
                denialReason = PluginPermissionDenialReason.NOT_GRANTED
            )
        }

        return PluginPermissionAccessResult(
            granted = false,
            deniedPermission = permissions.first(),
            denialReason = PluginPermissionDenialReason.NOT_DECLARED
        )
    }

    fun describePermissionDenial(permission: PluginPermission): String {
        val denialReason = if (permission in declaredPermissions) {
            PluginPermissionDenialReason.NOT_GRANTED
        } else {
            PluginPermissionDenialReason.NOT_DECLARED
        }
        return describePermissionDenial(permission, denialReason)
    }

    fun reportPermissionDenied(
        apiNamespace: String,
        apiMethod: String,
        vararg permissions: PluginPermission
    ): String {
        val accessResult = resolvePermissionAccess(*permissions)
        val deniedPermission = requireNotNull(accessResult.deniedPermission) {
            "Denied permission reporting requires a denied permission"
        }
        val denialReason = requireNotNull(accessResult.denialReason) {
            "Denied permission reporting requires a denial reason"
        }
        val denialMessage = describePermissionDenial(deniedPermission, denialReason)

        logManager.warn(
            pluginId,
            pluginName,
            buildPermissionDeniedLogMessage(
                apiNamespace = apiNamespace,
                apiMethod = apiMethod,
                permission = deniedPermission,
                denialReason = denialReason
            ),
            eventCode = PluginLogEventCodes.PERMISSION_DENIED,
            attributes = mapOf(
                PluginLogEventKeys.API_NAMESPACE to apiNamespace,
                PluginLogEventKeys.API_METHOD to apiMethod,
                PluginLogEventKeys.PERMISSION_ID to deniedPermission.id,
                PluginLogEventKeys.DENIAL_REASON to denialReason.name,
            ),
        )
        return denialMessage
    }

    fun getDeclaredPermissions(): Set<PluginPermission> = declaredPermissions

    fun checkFileOpLimit(): Boolean = fileOpLimiter.tryAcquire()

    fun checkNetworkLimit(): Boolean = networkLimiter.tryAcquire()

    fun getAllowedHosts(): Set<String> = allowedHosts

    private fun isPermissionGranted(permission: PluginPermission): Boolean {
        if (permission !in declaredPermissions) return false
        return permissionManager.hasPermission(pluginId, permission)
    }

    private fun describePermissionDenial(
        permission: PluginPermission,
        denialReason: PluginPermissionDenialReason
    ): String = when (denialReason) {
        PluginPermissionDenialReason.NOT_DECLARED ->
            Strings.plugin_error_permission_not_declared.strOr(context, permission.id)
        PluginPermissionDenialReason.NOT_GRANTED ->
            Strings.plugin_error_permission_not_granted.strOr(context, permission.id)
    }

    private fun buildPermissionDeniedLogMessage(
        apiNamespace: String,
        apiMethod: String,
        permission: PluginPermission,
        denialReason: PluginPermissionDenialReason
    ): String = when (denialReason) {
        PluginPermissionDenialReason.NOT_DECLARED ->
            Strings.plugin_log_permission_denied_not_declared.strOr(
                context,
                apiNamespace,
                apiMethod,
                permission.id
            )
        PluginPermissionDenialReason.NOT_GRANTED ->
            Strings.plugin_log_permission_denied_not_granted.strOr(
                context,
                apiNamespace,
                apiMethod,
                permission.id
            )
    }
}

private fun Lua.pushAny(value: Any?) {
    when (value) {
        null -> pushNil()
        is Boolean -> push(value)
        is Long -> push(value)
        is Number -> push(value)
        is String -> push(value)
        is ByteBuffer -> push(value)
        is Map<*, *> -> push(value)
        is Collection<*> -> push(value)
        is JFunction -> push(value)
        is LuaValue -> push(value)
        is LuaFunction -> push(value)
        else -> pushJavaObject(value)
    }
}
