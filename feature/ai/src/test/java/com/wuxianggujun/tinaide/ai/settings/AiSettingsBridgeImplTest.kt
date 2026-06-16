package com.wuxianggujun.tinaide.ai.settings

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ai.api.AiApiClient
import com.wuxianggujun.tinaide.ai.api.AuthStrategy
import com.wuxianggujun.tinaide.ai.channel.AiChannelRepository
import com.wuxianggujun.tinaide.ai.config.AiPreferences
import com.wuxianggujun.tinaide.ai.tools.AiTool
import com.wuxianggujun.tinaide.ai.tools.ToolCategory
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionContext
import com.wuxianggujun.tinaide.ai.tools.ToolExecutionResult
import com.wuxianggujun.tinaide.ai.tools.ToolRegistry
import com.wuxianggujun.tinaide.core.config.ai.AiChannelConfig
import com.wuxianggujun.tinaide.core.config.ai.AiConfig
import com.wuxianggujun.tinaide.core.config.ai.AiGenerationSettings
import com.wuxianggujun.tinaide.core.config.ai.AiModelLoadResult
import com.wuxianggujun.tinaide.core.config.ai.AiProvider
import com.wuxianggujun.tinaide.core.network.ApiResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class AiSettingsBridgeImplTest {

    private lateinit var context: Context
    private lateinit var preferences: AiPreferences
    private lateinit var channelRepository: AiChannelRepository
    private lateinit var bridge: AiSettingsBridgeImpl

    @Before
    fun setUp() {
        ToolRegistry.clear()
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getString(any<Int>()) } answers { "res-${firstArg<Int>()}" }
        preferences = mockk(relaxed = true)
        channelRepository = mockk(relaxed = true)
        bridge = newBridge()
    }

    @After
    fun tearDown() {
        ToolRegistry.clear()
    }

    @Test
    fun `tool items are sorted and reflect registry enabled state`() {
        ToolRegistry.register(fakeTool(name = "z_build", category = ToolCategory.BUILD, enabledByDefault = false))
        ToolRegistry.register(fakeTool(name = "a_editor", category = ToolCategory.EDITOR, enabledByDefault = true))

        val items = bridge.getToolItems(context)

        assertThat(items.map { it.name }).containsExactly("a_editor", "z_build").inOrder()
        assertThat(items.first().enabledByDefault).isTrue()
        assertThat(items.last().enabledByDefault).isFalse()
    }

    @Test
    fun `tool enabled states can be applied and persisted`() {
        ToolRegistry.register(fakeTool(name = "read_file", category = ToolCategory.FILE_SYSTEM, enabledByDefault = true))
        ToolRegistry.register(fakeTool(name = "delete_file", category = ToolCategory.FILE_SYSTEM, enabledByDefault = true))

        bridge.applyToolEnabledStates(mapOf("read_file" to false, "delete_file" to true))
        bridge.persistToolEnabledStates(mapOf("read_file" to false))

        assertThat(bridge.getToolEnabledStates()).containsAtLeast("read_file", false, "delete_file", true)
        verify(exactly = 1) { preferences.saveToolEnabledStates(mapOf("read_file" to false)) }
    }

    @Test
    fun `loadModels returns configuration required when byok channel id is missing`() = runTest {
        val result = bridge.loadModels(
            config = AiConfig(),
        )

        assertThat(result).isEqualTo(AiModelLoadResult.ConfigurationRequired)
        coVerify(exactly = 0) { channelRepository.getById(any()) }
        coVerify(exactly = 0) { channelRepository.getApiKey(any()) }
    }

    @Test
    fun `loadModels returns configuration required when byok channel is missing`() = runTest {
        coEvery { channelRepository.getById("missing") } returns null

        val result = bridge.loadModels(
            config = AiConfig(
                activeChannelId = "missing",
            ),
        )

        assertThat(result).isEqualTo(AiModelLoadResult.ConfigurationRequired)
        coVerify(exactly = 1) { channelRepository.getById("missing") }
        coVerify(exactly = 0) { channelRepository.getApiKey(any()) }
    }

    @Test
    fun `loadModels filters duplicate blank custom models and caches result`() = runTest {
        val client = mockk<AiApiClient>()
        var factoryCalls = 0
        coEvery { client.listModels() } returns ApiResult.Success(
            listOf("gpt-4o", "", "gpt-4o-mini", "gpt-4o", "   ")
        )
        bridge = newBridge(
            apiClientFactory = { config, endpoint, auth ->
                factoryCalls += 1
                assertThat(config.generation.model).isEqualTo("assistant-selected-model")
                assertThat(endpoint).isEqualTo("https://models.test/v1")
                assertThat(auth).isEqualTo(AuthStrategy.Bearer("secret"))
                client
            }
        )
        coEvery { channelRepository.getById("channel-1") } returns channelConfig(
            id = "channel-1",
            baseUrl = "https://models.test/v1",
        )
        coEvery { channelRepository.getApiKey("channel-1") } returns "secret"

        val config = AiConfig(
            activeChannelId = "channel-1",
            generation = AiGenerationSettings(model = "assistant-selected-model"),
        )

        val first = bridge.loadModels(config = config)
        val second = bridge.loadModels(config = config)

        assertThat(first).isEqualTo(AiModelLoadResult.Success(listOf("gpt-4o", "gpt-4o-mini")))
        assertThat(second).isEqualTo(AiModelLoadResult.Success(listOf("gpt-4o", "gpt-4o-mini")))
        assertThat(factoryCalls).isEqualTo(1)
        coVerify(exactly = 1) { client.listModels() }
    }

    @Test
    fun `loadModels returns provider fallback when custom model request fails`() = runTest {
        val client = mockk<AiApiClient>()
        coEvery { client.listModels() } returns ApiResult.Error(503, "models temporarily unavailable")
        bridge = newBridge(apiClientFactory = { _, _, _ -> client })
        coEvery { channelRepository.getById("channel-1") } returns channelConfig(
            id = "channel-1",
            provider = AiProvider.OPENAI,
        )
        coEvery { channelRepository.getApiKey("channel-1") } returns "secret"

        val result = bridge.loadModels(
            config = AiConfig(
                activeChannelId = "channel-1",
            ),
        )

        assertThat(result).isEqualTo(
            AiModelLoadResult.Failure(
                message = "models temporarily unavailable",
                fallbackModels = AiProvider.OPENAI.defaultModels,
            )
        )
    }

    private fun newBridge(
        channelRepository: AiChannelRepository = this.channelRepository,
        apiClientFactory: (AiConfig, String, AuthStrategy) -> AiApiClient = { config, endpoint, auth ->
            AiApiClient(config = config, endpoint = endpoint, auth = auth)
        },
    ): AiSettingsBridgeImpl = AiSettingsBridgeImpl(
        context = context,
        aiPreferences = preferences,
        channelRepository = channelRepository,
        apiClientFactory = apiClientFactory,
    )

    private fun channelConfig(
        id: String,
        baseUrl: String = "https://example.test/v1",
        provider: AiProvider = AiProvider.DEEPSEEK,
    ): AiChannelConfig = AiChannelConfig(
        id = id,
        name = "Test Channel",
        provider = provider,
        baseUrl = baseUrl,
        model = "deepseek-chat",
        createdAt = 100L,
        updatedAt = 200L,
    )

    private fun fakeTool(
        name: String,
        category: ToolCategory,
        enabledByDefault: Boolean,
    ): AiTool = object : AiTool {
        override val name: String = name
        override val description: String = "description for $name"
        override val category: ToolCategory = category
        override val enabledByDefault: Boolean = enabledByDefault
        override fun getParameters() = JsonObject(emptyMap())
        override suspend fun execute(
            toolCall: com.wuxianggujun.tinaide.ai.api.ToolCall,
            context: ToolExecutionContext,
        ): ToolExecutionResult = ToolExecutionResult.Success("ok")
    }
}
