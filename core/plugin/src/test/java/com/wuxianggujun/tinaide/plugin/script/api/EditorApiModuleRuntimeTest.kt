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
class EditorApiModuleRuntimeTest {

    private lateinit var context: Application
    private lateinit var permissionManager: PluginPermissionManager
    private lateinit var logManager: PluginLogManager
    private val runtimes = mutableListOf<ScriptPluginRuntime>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        permissionManager = PluginPermissionManager.getInstance(context)
        logManager = PluginLogManager.getInstance(context)
        logManager.clearAll()
        PluginEditorBridgeHolder.clear()
    }

    @After
    fun tearDown() {
        runtimes.forEach { it.destroy() }
        permissionManager.revokeAllPermissions("test.editor.runtime")
        permissionManager.revokeAllPermissions("test.editor.permission.denied")
        permissionManager.revokeAllPermissions("test.editor.runtime.a")
        permissionManager.revokeAllPermissions("test.editor.runtime.b")
        PluginEditorBridgeHolder.clear()
        logManager.clearAll()
    }

    @Test
    fun `editor api should expose active editor snapshot and synchronous writes`() = runBlocking {
        val pluginId = "test.editor.runtime"
        permissionManager.grantPermissions(pluginId, setOf(PluginPermission.EDITOR_WRITE))
        val runtime = createRuntime(
            pluginId = pluginId,
            permissions = listOf("editor.read", "editor.write")
        )
        val bridge = FakePluginEditorBridge()
        val registry = PluginApiRegistry.createDefaultRegistry(context)
        PluginEditorBridgeHolder.set(bridge)

        try {
            initializeRuntimeOrSkip(runtime)
            registry.initializeForRuntime(runtime)

            val defineResult = runtime.execute(
                """
                function exercise_editor_api()
                  local editor = tina.editor.getActiveEditor()
                  if editor == nil then error("active editor missing") end

                  local insert_ok = tina.editor.insertText("hello", 2, 4)
                  if not insert_ok then error("insertText should return true") end

                  local replace_ok = tina.editor.replaceSelection("done")
                  if not replace_ok then error("replaceSelection should return true") end

                  return editor.tabId .. "|" ..
                    editor.fileName .. "|" ..
                    editor.languageId .. "|" ..
                    tostring(editor.isDirty) .. "|" ..
                    editor.cursor.line .. ":" .. editor.cursor.column .. "|" ..
                    tostring(insert_ok) .. "|" ..
                    tostring(replace_ok)
                end
                """.trimIndent()
            )
            assertThat(defineResult).isInstanceOf(PluginExecutionResult.Success::class.java)

            val result = runtime.callFunction("exercise_editor_api")

            assertThat(result).isEqualTo(
                PluginExecutionResult.Success("tab-1|Main.kt|kotlin|true|3:4|true|true")
            )
            assertThat(bridge.insertedText).isEqualTo("hello")
            assertThat(bridge.insertedLine).isEqualTo(2)
            assertThat(bridge.insertedColumn).isEqualTo(4)
            assertThat(bridge.replacedSelectionText).isEqualTo("done")
        } finally {
            registry.cleanup()
        }
    }

    @Test
    fun `editor write permission denial should be visible in plugin logs`() = runBlocking {
        val pluginId = "test.editor.permission.denied"
        val runtime = createRuntime(
            pluginId = pluginId,
            permissions = listOf("editor.read")
        )
        val registry = PluginApiRegistry.createDefaultRegistry(context)
        PluginEditorBridgeHolder.set(FakePluginEditorBridge())

        try {
            initializeRuntimeOrSkip(runtime)
            registry.initializeForRuntime(runtime)

            val defineResult = runtime.execute(
                """
                function write_without_permission()
                  local ok, err = tina.editor.insertText("hello", 1, 1)
                  if ok ~= nil then error("insertText should have been denied") end
                  return err
                end
                """.trimIndent()
            )
            assertThat(defineResult).isInstanceOf(PluginExecutionResult.Success::class.java)

            val result = runtime.callFunction("write_without_permission")
            val entry = logManager.getLogsForPlugin(pluginId).single()

            assertThat(result).isInstanceOf(PluginExecutionResult.Success::class.java)
            assertThat((result as PluginExecutionResult.Success).value as String)
                .contains(PluginPermission.EDITOR_WRITE.id)
            assertThat(entry.eventCode).isEqualTo(PluginLogEventCodes.PERMISSION_DENIED)
            assertThat(entry.attributes[PluginLogEventKeys.API_NAMESPACE]).isEqualTo("editor")
            assertThat(entry.attributes[PluginLogEventKeys.API_METHOD]).isEqualTo("insertText")
            assertThat(entry.attributes[PluginLogEventKeys.PERMISSION_ID]).isEqualTo("editor.write")
            assertThat(entry.attributes[PluginLogEventKeys.DENIAL_REASON]).isEqualTo("NOT_DECLARED")
        } finally {
            registry.cleanup()
        }
    }

    @Test
    fun `editor api should keep runtime permissions isolated after another plugin initializes`() = runBlocking {
        val firstPluginId = "test.editor.runtime.a"
        val secondPluginId = "test.editor.runtime.b"
        permissionManager.grantPermissions(firstPluginId, setOf(PluginPermission.EDITOR_WRITE))
        val firstRuntime = createRuntime(
            pluginId = firstPluginId,
            permissions = listOf("editor.write")
        )
        val secondRuntime = createRuntime(
            pluginId = secondPluginId,
            permissions = emptyList()
        )
        val bridge = FakePluginEditorBridge()
        val registry = PluginApiRegistry.createDefaultRegistry(context)
        PluginEditorBridgeHolder.set(bridge)

        try {
            initializeRuntimeOrSkip(firstRuntime)
            registry.initializeForRuntime(firstRuntime)
            val defineResult = firstRuntime.execute(
                """
                function insert_after_second_runtime_loads()
                  return tina.editor.insertText("from-first", 6, 8)
                end
                """.trimIndent()
            )
            assertThat(defineResult).isInstanceOf(PluginExecutionResult.Success::class.java)

            initializeRuntimeOrSkip(secondRuntime)
            registry.initializeForRuntime(secondRuntime)

            val result = firstRuntime.callFunction("insert_after_second_runtime_loads")

            assertThat(result).isEqualTo(PluginExecutionResult.Success(true))
            assertThat(bridge.insertedText).isEqualTo("from-first")
            assertThat(bridge.insertedLine).isEqualTo(6)
            assertThat(bridge.insertedColumn).isEqualTo(8)
            assertThat(logManager.getLogsForPlugin(firstPluginId)).isEmpty()
        } finally {
            registry.cleanup()
        }
    }

    private fun createRuntime(
        pluginId: String,
        permissions: List<String>
    ): ScriptPluginRuntime {
        return ScriptPluginRuntime(
            context = context,
            manifest = PluginManifest(
                id = pluginId,
                name = "Editor Runtime Test",
                version = "1.0.0",
                type = "script",
                permissions = permissions
            ),
            pluginDir = File(context.cacheDir, pluginId).apply { mkdirs() },
            permissionManager = permissionManager
        ).also(runtimes::add)
    }

    private suspend fun initializeRuntimeOrSkip(runtime: ScriptPluginRuntime) {
        try {
            assertThat(runtime.initialize().isSuccess).isTrue()
        } catch (error: LinkageError) {
            assumeNoException("Lua native runtime unavailable in this test environment", error)
        }
    }

    private class FakePluginEditorBridge : PluginEditorBridge {
        var insertedText: String? = null
        var insertedLine: Int? = null
        var insertedColumn: Int? = null
        var replacedSelectionText: String? = null

        override fun getActiveEditor(): PluginActiveEditor {
            return PluginActiveEditor(
                tabId = "tab-1",
                filePath = "/workspace/src/Main.kt",
                fileName = "Main.kt",
                languageId = "kotlin",
                isDirty = true,
                cursor = CursorPosition(line = 3, column = 4)
            )
        }

        override fun getActiveFile(): File = File("/workspace/src/Main.kt")

        override fun getActiveTabId(): String = "tab-1"

        override fun getText(): String = "fun main()"

        override fun setText(text: String): Boolean = true

        override fun getSelection(): EditorSelection? = null

        override fun setSelection(
            startLine: Int,
            startColumn: Int,
            endLine: Int,
            endColumn: Int
        ): Boolean = true

        override fun insertText(text: String, line: Int?, column: Int?): Boolean {
            insertedText = text
            insertedLine = line
            insertedColumn = column
            return true
        }

        override fun replaceSelection(text: String): Boolean {
            replacedSelectionText = text
            return true
        }

        override fun getLanguage(): String = "kotlin"

        override fun getCursorPosition(): CursorPosition = CursorPosition(line = 3, column = 4)

        override fun setCursorPosition(line: Int, column: Int): Boolean = true
    }
}
