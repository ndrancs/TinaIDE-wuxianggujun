package com.wuxianggujun.tinaide.plugin.script.api

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.commands.HostCommandInvocation
import com.wuxianggujun.tinaide.plugin.script.PluginExecutionResult
import com.wuxianggujun.tinaide.plugin.script.PluginPermission
import com.wuxianggujun.tinaide.plugin.script.ScriptPluginRuntime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test

class PluginCommandRegistryTest {

    @Before
    fun setUp() {
        PluginCommandRegistry.clear()
        PluginCommandRegistry.setRuntimeProvider { null }
    }

    @After
    fun tearDown() {
        PluginCommandRegistry.clear()
        PluginCommandRegistry.setRuntimeProvider { null }
    }

    @Test
    fun `register should reject duplicate command ids across plugins`() {
        val first = PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello"
        )
        val second = PluginCommandRegistry.register(
            pluginId = "plugin.two",
            pluginName = "Plugin Two",
            commandId = "plugin.sayHello",
            callbackName = "handleHelloAgain"
        )

        assertThat(first.isSuccess).isTrue()
        assertThat(second.isFailure).isTrue()
        assertThat(second.exceptionOrNull()?.message).contains("already registered")
        assertThat(
            PluginCommandRegistry.registrationIssue(
                commandId = "plugin.sayHello",
                pluginId = "plugin.two",
            )?.message
        ).contains("already registered")
    }

    @Test
    fun `register should clear registration issue after command registers successfully`() {
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello"
        ).getOrThrow()
        PluginCommandRegistry.register(
            pluginId = "plugin.two",
            pluginName = "Plugin Two",
            commandId = "plugin.sayHello",
            callbackName = "handleHelloAgain"
        )

        PluginCommandRegistry.unregisterAll("plugin.one")
        val retry = PluginCommandRegistry.register(
            pluginId = "plugin.two",
            pluginName = "Plugin Two",
            commandId = "plugin.sayHello",
            callbackName = "handleHelloAgain"
        )

        assertThat(retry.isSuccess).isTrue()
        assertThat(
            PluginCommandRegistry.registrationIssue(
                commandId = "plugin.sayHello",
                pluginId = "plugin.two",
            )
        ).isNull()
    }

    @Test
    fun `unregisterAll should clear registration issues blocked by removed plugin`() {
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello"
        ).getOrThrow()
        PluginCommandRegistry.register(
            pluginId = "plugin.two",
            pluginName = "Plugin Two",
            commandId = "plugin.sayHello",
            callbackName = "handleHelloAgain"
        )

        assertThat(
            PluginCommandRegistry.registrationIssue(
                commandId = "plugin.sayHello",
                pluginId = "plugin.two",
            )?.conflictingPluginId
        ).isEqualTo("plugin.one")

        PluginCommandRegistry.unregisterAll("plugin.one")

        assertThat(
            PluginCommandRegistry.registrationIssue(
                commandId = "plugin.sayHello",
                pluginId = "plugin.two",
            )
        ).isNull()
    }

    @Test
    fun `stateRevision should advance when command registration state changes`() {
        val initialRevision = PluginCommandRegistry.stateRevision.value

        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello"
        ).getOrThrow()
        val registeredRevision = PluginCommandRegistry.stateRevision.value

        PluginCommandRegistry.register(
            pluginId = "plugin.two",
            pluginName = "Plugin Two",
            commandId = "plugin.sayHello",
            callbackName = "handleHelloAgain"
        )
        val failedRegistrationRevision = PluginCommandRegistry.stateRevision.value

        PluginCommandRegistry.unregisterAll("plugin.one")
        val unregisteredRevision = PluginCommandRegistry.stateRevision.value

        assertThat(registeredRevision).isGreaterThan(initialRevision)
        assertThat(failedRegistrationRevision).isGreaterThan(registeredRevision)
        assertThat(unregisteredRevision).isGreaterThan(failedRegistrationRevision)
    }

    @Test
    fun `dispatch should invoke runtime callback with invocation payload`() {
        val runtime = mockk<ScriptPluginRuntime>()
        every { runtime.checkPermission(PluginPermission.COMMAND_EXECUTE) } returns true
        coEvery { runtime.callFunction("handleHello", any()) } returns PluginExecutionResult.Success(Unit)
        PluginCommandRegistry.setRuntimeProvider { pluginId ->
            if (pluginId == "plugin.one") runtime else null
        }
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
            title = "Say hello"
        ).getOrThrow()
        val targetFile = File("C:/workspace/src/Main.kt")

        val dispatched = PluginCommandRegistry.dispatch(
            commandId = "plugin.sayHello",
            invocation = HostCommandInvocation(
                file = targetFile,
                isDirectory = false,
                isDirty = true
            )
        )

        assertThat(dispatched).isTrue()
        coVerify(timeout = 1_000, exactly = 1) {
            runtime.callFunction(
                "handleHello",
                match<Map<String, Any?>> { payload ->
                    payload["commandId"] == "plugin.sayHello" &&
                        payload["filePath"] == targetFile.absolutePath &&
                        payload["fileName"] == targetFile.name &&
                        payload["isDirectory"] == false &&
                        payload["isDirty"] == true
                }
            )
        }
    }

    @Test
    fun `dispatchWithResult should record execution issue when runtime callback fails`() {
        val runtime = mockk<ScriptPluginRuntime>()
        every { runtime.checkPermission(PluginPermission.COMMAND_EXECUTE) } returns true
        coEvery { runtime.callFunction("handleHello", any()) } returns PluginExecutionResult.Error("Boom")
        PluginCommandRegistry.setRuntimeProvider { pluginId ->
            if (pluginId == "plugin.one") runtime else null
        }
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
            title = "Say hello"
        ).getOrThrow()
        val revisionBeforeDispatch = PluginCommandRegistry.stateRevision.value

        val result = PluginCommandRegistry.dispatchWithResult(
            commandId = "plugin.sayHello",
            invocation = HostCommandInvocation()
        )

        assertThat(result.handled).isTrue()
        waitUntil {
            PluginCommandRegistry.executionIssue(
                commandId = "plugin.sayHello",
                pluginId = "plugin.one",
            ) != null
        }
        val issue = PluginCommandRegistry.executionIssue(
            commandId = "plugin.sayHello",
            pluginId = "plugin.one",
        )
        assertThat(issue?.message).isEqualTo("Boom")
        assertThat(PluginCommandRegistry.stateRevision.value).isGreaterThan(revisionBeforeDispatch)
    }

    @Test
    fun `dispatchWithResult should record timeout and runtime permission execution issues`() {
        val runtime = mockk<ScriptPluginRuntime>()
        every { runtime.checkPermission(PluginPermission.COMMAND_EXECUTE) } returns true
        coEvery { runtime.callFunction("handleHello", any()) } returns PluginExecutionResult.Timeout
        PluginCommandRegistry.setRuntimeProvider { pluginId ->
            if (pluginId == "plugin.one") runtime else null
        }
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
            title = "Say hello"
        ).getOrThrow()

        PluginCommandRegistry.dispatchWithResult(
            commandId = "plugin.sayHello",
            invocation = HostCommandInvocation()
        )

        waitUntil {
            PluginCommandRegistry.executionIssue(
                commandId = "plugin.sayHello",
                pluginId = "plugin.one",
            )?.message == "Command execution timed out"
        }
        coEvery { runtime.callFunction("handleHello", any()) } returns PluginExecutionResult.PermissionDenied

        PluginCommandRegistry.dispatchWithResult(
            commandId = "plugin.sayHello",
            invocation = HostCommandInvocation()
        )

        waitUntil {
            PluginCommandRegistry.executionIssue(
                commandId = "plugin.sayHello",
                pluginId = "plugin.one",
            )?.message == "Command execution was denied by runtime permission check"
        }
    }

    @Test
    fun `dispatchWithResult should clear execution issue after runtime callback succeeds`() {
        val runtime = mockk<ScriptPluginRuntime>()
        every { runtime.checkPermission(PluginPermission.COMMAND_EXECUTE) } returns true
        coEvery { runtime.callFunction("handleHello", any()) } returns PluginExecutionResult.Error("Boom")
        PluginCommandRegistry.setRuntimeProvider { pluginId ->
            if (pluginId == "plugin.one") runtime else null
        }
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
            title = "Say hello"
        ).getOrThrow()

        PluginCommandRegistry.dispatchWithResult(
            commandId = "plugin.sayHello",
            invocation = HostCommandInvocation()
        )
        waitUntil {
            PluginCommandRegistry.executionIssue(
                commandId = "plugin.sayHello",
                pluginId = "plugin.one",
            ) != null
        }
        val failedRevision = PluginCommandRegistry.stateRevision.value
        coEvery { runtime.callFunction("handleHello", any()) } returns PluginExecutionResult.Success(Unit)

        PluginCommandRegistry.dispatchWithResult(
            commandId = "plugin.sayHello",
            invocation = HostCommandInvocation()
        )

        waitUntil {
            PluginCommandRegistry.executionIssue(
                commandId = "plugin.sayHello",
                pluginId = "plugin.one",
            ) == null
        }
        assertThat(PluginCommandRegistry.stateRevision.value).isGreaterThan(failedRevision)
    }

    @Test
    fun `dispatch should reject command when command permission is denied`() {
        val runtime = mockk<ScriptPluginRuntime>()
        every { runtime.checkPermission(PluginPermission.COMMAND_EXECUTE) } returns false
        every {
            runtime.reportPermissionDenied(
                "commands",
                "execute",
                PluginPermission.COMMAND_EXECUTE
            )
        } returns "Permission denied"
        PluginCommandRegistry.setRuntimeProvider { pluginId ->
            if (pluginId == "plugin.one") runtime else null
        }
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
            title = "Say hello"
        ).getOrThrow()

        val dispatched = PluginCommandRegistry.dispatch(
            commandId = "plugin.sayHello",
            invocation = HostCommandInvocation()
        )

        assertThat(dispatched).isFalse()
        verify(exactly = 1) {
            runtime.reportPermissionDenied(
                "commands",
                "execute",
                PluginPermission.COMMAND_EXECUTE
            )
        }
        coVerify(exactly = 0) {
            runtime.callFunction(any(), any())
        }
    }

    @Test
    fun `dispatchWithResult should expose permission denial message`() {
        val runtime = mockk<ScriptPluginRuntime>()
        every { runtime.checkPermission(PluginPermission.COMMAND_EXECUTE) } returns false
        every {
            runtime.reportPermissionDenied(
                "commands",
                "execute",
                PluginPermission.COMMAND_EXECUTE
            )
        } returns "Permission denied"
        PluginCommandRegistry.setRuntimeProvider { pluginId ->
            if (pluginId == "plugin.one") runtime else null
        }
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
            title = "Say hello"
        ).getOrThrow()

        val result = PluginCommandRegistry.dispatchWithResult(
            commandId = "plugin.sayHello",
            invocation = HostCommandInvocation()
        )

        assertThat(result.handled).isFalse()
        assertThat(result.errorMessage).isEqualTo("Permission denied")
        coVerify(exactly = 0) {
            runtime.callFunction(any(), any())
        }
    }

    @Test
    fun `availability should expose permission denial message without logging denial`() {
        val runtime = mockk<ScriptPluginRuntime>()
        every { runtime.checkPermission(PluginPermission.COMMAND_EXECUTE) } returns false
        every {
            runtime.describePermissionDenial(PluginPermission.COMMAND_EXECUTE)
        } returns "Permission not granted"
        PluginCommandRegistry.setRuntimeProvider { pluginId ->
            if (pluginId == "plugin.one") runtime else null
        }
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
            title = "Say hello"
        ).getOrThrow()

        val availability = PluginCommandRegistry.availability(
            commandId = "plugin.sayHello",
            pluginId = "plugin.one"
        )

        assertThat(availability.available).isFalse()
        assertThat(availability.errorMessage).isEqualTo("Permission not granted")
        verify(exactly = 0) {
            runtime.reportPermissionDenied(any(), any(), any())
        }
    }

    @Test
    fun `dispatch should return false when runtime provider has no runtime`() {
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello",
            title = "Say hello"
        ).getOrThrow()

        val dispatched = PluginCommandRegistry.dispatch(
            commandId = "plugin.sayHello",
            invocation = HostCommandInvocation()
        )

        assertThat(dispatched).isFalse()
    }

    @Test
    fun `unregisterAll should remove all commands from plugin`() {
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayHello",
            callbackName = "handleHello"
        ).getOrThrow()
        PluginCommandRegistry.register(
            pluginId = "plugin.one",
            pluginName = "Plugin One",
            commandId = "plugin.sayBye",
            callbackName = "handleBye"
        ).getOrThrow()

        PluginCommandRegistry.unregisterAll("plugin.one")

        assertThat(PluginCommandRegistry.isRegistered("plugin.sayHello")).isFalse()
        assertThat(PluginCommandRegistry.isRegistered("plugin.sayBye")).isFalse()
    }

    @Test
    fun `commands module should normalize command ids`() {
        val module = CommandsApiModule()

        assertThat(module.normalizeCommandId(" plugin.sayHello ")).isEqualTo("plugin.sayHello")
        assertThat(module.normalizeCommandId(" ")).isNull()
        assertThat(module.normalizeCommandId(null)).isNull()
    }

    @Test
    fun `commands module should reject invocation target in sibling directory with shared prefix`() {
        val projectRoot = Files.createTempDirectory("tina-command-root").toFile()
        val siblingRoot = File(projectRoot.parentFile, "${projectRoot.name}-escape")
        try {
            val projectFile = File(projectRoot, "src/Main.kt").apply {
                parentFile?.mkdirs()
                writeText("fun main() = Unit")
            }
            val outsideFile = File(siblingRoot, "src/Main.kt").apply {
                parentFile?.mkdirs()
                writeText("fun outside() = Unit")
            }
            val module = CommandsApiModule { projectRoot.absolutePath }

            assertThat(module.resolveInvocationFile(projectFile.absolutePath)?.canonicalFile)
                .isEqualTo(projectFile.canonicalFile)
            assertThat(module.resolveInvocationFile(outsideFile.absolutePath)).isNull()
        } finally {
            projectRoot.deleteRecursively()
            siblingRoot.deleteRecursively()
        }
    }

    private fun waitUntil(
        timeoutMillis: Long = 1_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(10)
        }
        assertThat(condition()).isTrue()
    }
}
