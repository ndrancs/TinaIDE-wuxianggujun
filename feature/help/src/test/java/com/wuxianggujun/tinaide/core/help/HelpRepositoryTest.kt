package com.wuxianggujun.tinaide.core.help

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class HelpRepositoryTest {

    @Test
    fun pluginQuickStart_shouldBeListedLoadedAndSearchable() = runTest {
        val repository = HelpRepository(RuntimeEnvironment.getApplication())

        val document = repository.getDocumentById("plugin-quick-start")

        assertThat(document).isNotNull()
        assertThat(document!!.category).isEqualTo(HelpCategory.GETTING_STARTED)
        assertThat(repository.getDocumentsByCategory(HelpCategory.GETTING_STARTED))
            .contains(document)

        val content = repository.loadDocumentContent(document).getOrThrow()
        assertThat(content).contains("# 插件开发快速开始")
        assertThat(content).contains(".tinaplug")
        assertThat(content).contains("热安装")

        val searchResults = repository.search("tinaplug")
        assertThat(searchResults.map { result -> result.document.id })
            .contains("plugin-quick-start")
    }

    @Test
    fun quickActions_shouldOnlyAppearForPluginQuickStart() {
        val repository = HelpRepository(RuntimeEnvironment.getApplication())
        val pluginQuickStart = repository.getDocumentById("plugin-quick-start")!!
        val gettingStarted = repository.getDocumentById("getting-started")!!

        assertThat(HelpQuickActionSupport.resolveActions(pluginQuickStart)).containsExactly(
            HelpQuickAction.CREATE_PLUGIN_PROJECT,
            HelpQuickAction.OPEN_PLUGIN_SETTINGS,
        ).inOrder()
        assertThat(HelpQuickActionSupport.resolveActions(gettingStarted)).isEmpty()
    }

    @Test
    fun quickActionStrings_shouldHaveResources() {
        val context = RuntimeEnvironment.getApplication() as Application

        assertThat(context.getString(Strings.help_quick_actions_title)).isNotEmpty()
        assertThat(context.getString(Strings.help_action_create_plugin_project)).isNotEmpty()
        assertThat(context.getString(Strings.help_action_open_plugin_settings)).isNotEmpty()
    }

    @Test
    fun resolveDocumentByLinkTarget_shouldHandleRelativeLinksAndFragments() {
        val repository = HelpRepository(RuntimeEnvironment.getApplication())

        assertThat(repository.resolveDocumentByLinkTarget("plugin-quick-start.md")?.id)
            .isEqualTo("plugin-quick-start")
        assertThat(repository.resolveDocumentByLinkTarget("./plugins-settings.md")?.id)
            .isEqualTo("plugins-settings")
        assertThat(repository.resolveDocumentByLinkTarget("help/known-issues.md#common-issues")?.id)
            .isEqualTo("known-issues")
        assertThat(repository.resolveDocumentByLinkTarget("https://example.com/help.md"))
            .isNull()
    }
}
