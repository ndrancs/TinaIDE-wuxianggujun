package com.wuxianggujun.tinaide.plugin.script.api

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.plugin.PluginLogEventCodes
import com.wuxianggujun.tinaide.plugin.PluginLogEventKeys
import com.wuxianggujun.tinaide.plugin.PluginLogManager
import com.wuxianggujun.tinaide.plugin.PluginManifest
import com.wuxianggujun.tinaide.plugin.script.PluginExecutionResult
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.PluginPermissionManager
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
class WorkspaceApiModuleRuntimeTest {

    private lateinit var context: Application
    private lateinit var permissionManager: PluginPermissionManager
    private lateinit var logManager: PluginLogManager
    private lateinit var workspaceRoot: File
    private val runtimes = mutableListOf<ScriptPluginRuntime>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        permissionManager = PluginPermissionManager.getInstance(context)
        logManager = PluginLogManager.getInstance(context)
        workspaceRoot = Files.createTempDirectory("tina-workspace-api-runtime").toFile()
        logManager.clearAll()
    }

    @After
    fun tearDown() {
        runtimes.forEach { it.destroy() }
        permissionManager.revokeAllPermissions("test.workspace.runtime")
        permissionManager.revokeAllPermissions("test.workspace.permission.denied")
        logManager.clearAll()
        workspaceRoot.deleteRecursively()
    }

    @Test
    fun `workspace api should be mounted and callable from Lua runtime`() = runBlocking {
        val pluginId = "test.workspace.runtime"
        File(workspaceRoot, "src/Main.kt").apply {
            parentFile?.mkdirs()
            writeText("fun main() = Unit", Charsets.UTF_8)
        }
        permissionManager.grantPermissions(
            pluginId,
            setOf(PluginPermission.FILE_READ, PluginPermission.FILE_WRITE)
        )
        val runtime = createRuntime(
            pluginId = pluginId,
            permissions = listOf("workspace.read", "workspace.write")
        )
        val registry = PluginApiRegistry.createDefaultRegistry(context) { workspaceRoot.absolutePath }

        try {
            initializeRuntimeOrSkip(runtime)
            registry.initializeForRuntime(runtime)

            val defineResult = runtime.execute(
                """
                function exercise_workspace_api()
                  local ok, write_err = tina.workspace.writeFile("notes/result.txt", "hello workspace")
                  if not ok then error(write_err) end

                  local content, read_err = tina.workspace.readFile("notes/result.txt")
                  if content ~= "hello workspace" then error(read_err or "unexpected content") end

                  local files = tina.workspace.findFiles("*.kt", 10)
                  return tina.apiVersion .. "|" .. tina.pluginId .. "|" .. content .. "|" .. table.concat(files, ",")
                end
                """.trimIndent()
            )
            assertThat(defineResult).isInstanceOf(PluginExecutionResult.Success::class.java)

            val result = runtime.callFunction("exercise_workspace_api")

            assertThat(result).isEqualTo(
                PluginExecutionResult.Success(
                    "1|test.workspace.runtime|hello workspace|src/Main.kt"
                )
            )
            assertThat(File(workspaceRoot, "notes/result.txt").readText(Charsets.UTF_8))
                .isEqualTo("hello workspace")
        } finally {
            registry.cleanup()
        }
    }

    @Test
    fun `workspace api permission denial should be visible in plugin logs`() = runBlocking {
        val pluginId = "test.workspace.permission.denied"
        val runtime = createRuntime(
            pluginId = pluginId,
            permissions = emptyList()
        )
        val registry = PluginApiRegistry.createDefaultRegistry(context) { workspaceRoot.absolutePath }

        try {
            initializeRuntimeOrSkip(runtime)
            registry.initializeForRuntime(runtime)

            val defineResult = runtime.execute(
                """
                function read_without_permission()
                  local content, err = tina.workspace.readFile("README.md")
                  if content ~= nil then error("read should have been denied") end
                  return err
                end
                """.trimIndent()
            )
            assertThat(defineResult).isInstanceOf(PluginExecutionResult.Success::class.java)

            val result = runtime.callFunction("read_without_permission")
            val entry = logManager.getLogsForPlugin(pluginId).single()

            assertThat(result).isInstanceOf(PluginExecutionResult.Success::class.java)
            assertThat((result as PluginExecutionResult.Success).value as String)
                .contains(PluginPermission.FILE_READ.id)
            assertThat(entry.eventCode).isEqualTo(PluginLogEventCodes.PERMISSION_DENIED)
            assertThat(entry.attributes[PluginLogEventKeys.API_NAMESPACE]).isEqualTo("workspace")
            assertThat(entry.attributes[PluginLogEventKeys.API_METHOD]).isEqualTo("readFile")
            assertThat(entry.attributes[PluginLogEventKeys.PERMISSION_ID]).isEqualTo("file.read")
            assertThat(entry.attributes[PluginLogEventKeys.DENIAL_REASON]).isEqualTo("NOT_DECLARED")
        } finally {
            registry.cleanup()
        }
    }

    private fun createRuntime(
        pluginId: String,
        permissions: List<String>
    ): ScriptPluginRuntime = ScriptPluginRuntime(
        context = context,
        manifest = PluginManifest(
            id = pluginId,
            name = "Workspace Runtime Test",
            version = "1.0.0",
            type = "script",
            permissions = permissions
        ),
        pluginDir = File(context.cacheDir, pluginId).apply { mkdirs() },
        permissionManager = permissionManager
    ).also(runtimes::add)

    private suspend fun initializeRuntimeOrSkip(runtime: ScriptPluginRuntime) {
        try {
            assertThat(runtime.initialize().isSuccess).isTrue()
        } catch (error: LinkageError) {
            // JVM 单元测试环境可能缺少桌面 Lua native；有 native 的环境仍会执行完整链路。
            assumeNoException("Lua native runtime unavailable in this test environment", error)
        }
    }
}
