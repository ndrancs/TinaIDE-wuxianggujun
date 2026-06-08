package com.wuxianggujun.tinaide.ui.wizard

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectTemplateOption
import com.wuxianggujun.tinaide.project.ProjectTemplateSpec
import java.io.File
import org.junit.Test

class NewProjectWizardSupportTest {

    @Test
    fun pluginTemplateHelpers_shouldResolvePluginGuidance() {
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(NewProjectWizardSupport.isPluginTemplate(pluginTemplate)).isTrue()
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(pluginTemplate)).isFalse()
        assertThat(NewProjectWizardSupport.resolveTemplateBadgeRes(pluginTemplate))
            .isEqualTo(Strings.wizard_plugin_template_badge)
        assertThat(NewProjectWizardSupport.resolveTemplateCardGuideRes(pluginTemplate))
            .isEqualTo(Strings.wizard_plugin_template_card_hint)
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideTitleRes(pluginTemplate))
            .isEqualTo(Strings.wizard_plugin_template_config_guide_title)
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideBodyRes(pluginTemplate))
            .isEqualTo(Strings.wizard_plugin_template_config_guide_body)
        assertThat(NewProjectWizardSupport.resolveProjectCreatedMessageRes(pluginTemplate))
            .isEqualTo(Strings.success_plugin_project_created)
    }

    @Test
    fun nonPluginTemplateHelpers_shouldKeepDefaultWizardBehavior() {
        val cppTemplate = template(
            id = "template:cpp",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )

        assertThat(NewProjectWizardSupport.isPluginTemplate(cppTemplate)).isFalse()
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(cppTemplate)).isTrue()
        assertThat(NewProjectWizardSupport.resolveTemplateBadgeRes(cppTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveTemplateCardGuideRes(cppTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideTitleRes(cppTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideBodyRes(cppTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveProjectCreatedMessageRes(cppTemplate))
            .isEqualTo(Strings.success_project_created)
    }

    @Test
    fun userTemplateHelpers_shouldResolveCustomBadgeAndGuide() {
        val userTemplate = template(
            id = "${UserProjectTemplates.TEMPLATE_ID_PREFIX}cmake-demo",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )

        assertThat(NewProjectWizardSupport.isUserTemplate(userTemplate)).isTrue()
        assertThat(NewProjectWizardSupport.isPluginTemplate(userTemplate)).isFalse()
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(userTemplate)).isTrue()
        assertThat(NewProjectWizardSupport.resolveTemplateBadgeRes(userTemplate))
            .isEqualTo(Strings.wizard_user_template_badge)
        assertThat(NewProjectWizardSupport.resolveTemplateCardGuideRes(userTemplate))
            .isEqualTo(Strings.wizard_user_template_card_hint)
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideTitleRes(userTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveConfigurationGuideBodyRes(userTemplate)).isNull()
        assertThat(NewProjectWizardSupport.resolveProjectCreatedMessageRes(userTemplate))
            .isEqualTo(Strings.success_project_created)
    }

    @Test
    fun resolveSelectedTemplate_shouldFallbackToFirstOption() {
        val first = template(
            id = "template:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val second = template(
            id = "plugin:second",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveSelectedTemplate(
                selectedTemplateId = "plugin:second",
                templateOptions = listOf(first, second),
            )
        ).isEqualTo(second)
        assertThat(
            NewProjectWizardSupport.resolveSelectedTemplate(
                selectedTemplateId = "missing",
                templateOptions = listOf(first, second),
            )
        ).isEqualTo(first)
    }

    @Test
    fun resolveVisibleTemplateOptions_shouldKeepAllTemplatesByDefault() {
        val plainTemplate = template(
            id = "template:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveVisibleTemplateOptions(
                preferPluginTemplate = false,
                templateOptions = listOf(plainTemplate, pluginTemplate),
            )
        ).containsExactly(plainTemplate, pluginTemplate).inOrder()
    }

    @Test
    fun resolveVisibleTemplateOptions_shouldOnlyShowPluginTemplatesForPluginEntry() {
        val plainTemplate = template(
            id = "template:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveVisibleTemplateOptions(
                preferPluginTemplate = true,
                templateOptions = listOf(plainTemplate, pluginTemplate),
            )
        ).containsExactly(pluginTemplate)
    }

    @Test
    fun resolveVisibleTemplateOptions_shouldReturnEmptyWhenPluginEntryHasNoPluginTemplates() {
        val plainTemplate = template(
            id = "template:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )

        assertThat(
            NewProjectWizardSupport.resolveVisibleTemplateOptions(
                preferPluginTemplate = true,
                templateOptions = listOf(plainTemplate),
            )
        ).isEmpty()
    }

    @Test
    fun resolveTemplateCategory_shouldTreatUserTemplateAsCustomBeforeBuildSystem() {
        val userPluginTemplate = template(
            id = "${UserProjectTemplates.TEMPLATE_ID_PREFIX}plugin",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveTemplateCategory(userPluginTemplate)
        ).isEqualTo(ProjectTemplateCategory.USER)
    }

    @Test
    fun resolveTemplateCategoryGroups_shouldSplitNativePluginAndUserTemplates() {
        val plainTemplate = template(
            id = "template:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )
        val userTemplate = template(
            id = "${UserProjectTemplates.TEMPLATE_ID_PREFIX}custom",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )

        val groups = NewProjectWizardSupport.resolveTemplateCategoryGroups(
            listOf(plainTemplate, pluginTemplate, userTemplate)
        )

        assertThat(groups.map { it.category }).containsExactly(
            ProjectTemplateCategory.NATIVE,
            ProjectTemplateCategory.PLUGIN,
            ProjectTemplateCategory.USER,
        ).inOrder()
        assertThat(groups[0].options).containsExactly(plainTemplate)
        assertThat(groups[1].options).containsExactly(pluginTemplate)
        assertThat(groups[2].options).containsExactly(userTemplate)
    }

    @Test
    fun resolveSelectedTemplateCategory_shouldFindCategoryFromSelection() {
        val plainTemplate = template(
            id = "template:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )
        val groups = NewProjectWizardSupport.resolveTemplateCategoryGroups(
            listOf(plainTemplate, pluginTemplate)
        )

        assertThat(
            NewProjectWizardSupport.resolveSelectedTemplateCategory(
                selectedTemplateId = pluginTemplate.id,
                groups = groups,
            )
        ).isEqualTo(ProjectTemplateCategory.PLUGIN)
    }

    @Test
    fun resolveFirstTemplateInCategory_shouldReturnFirstTemplateForTabSwitch() {
        val firstPluginTemplate = template(
            id = "plugin:first",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )
        val secondPluginTemplate = template(
            id = "plugin:second",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )
        val groups = NewProjectWizardSupport.resolveTemplateCategoryGroups(
            listOf(firstPluginTemplate, secondPluginTemplate)
        )

        assertThat(
            NewProjectWizardSupport.resolveFirstTemplateInCategory(
                category = ProjectTemplateCategory.PLUGIN,
                groups = groups,
            )
        ).isEqualTo(firstPluginTemplate)
    }

    @Test
    fun resolveInitialTemplateSelection_shouldUseExplicitTemplateFirst() {
        val plainTemplate = template(
            id = "template:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveInitialTemplateSelection(
                initialTemplateId = plainTemplate.id,
                preferPluginTemplate = true,
                templateOptions = listOf(plainTemplate, pluginTemplate),
            )
        ).isEqualTo(plainTemplate)
    }

    @Test
    fun resolveInitialTemplateSelection_shouldPreferPluginTemplate() {
        val plainTemplate = template(
            id = "template:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )
        val pluginTemplate = template(
            id = "plugin:starter",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveInitialTemplateSelection(
                initialTemplateId = null,
                preferPluginTemplate = true,
                templateOptions = listOf(plainTemplate, pluginTemplate),
            )
        ).isEqualTo(pluginTemplate)
    }

    @Test
    fun resolveInitialTemplateSelection_shouldFallbackToFirstPluginWhenTargetMissing() {
        val pluginTemplate = template(
            id = "plugin:fallback",
            buildSystem = ProjectBuildSystem.PLUGIN,
            primaryLanguage = ProjectLanguage.MIXED,
        )

        assertThat(
            NewProjectWizardSupport.resolveInitialTemplateSelection(
                initialTemplateId = "plugin:missing",
                preferPluginTemplate = true,
                templateOptions = listOf(pluginTemplate),
            )
        ).isEqualTo(pluginTemplate)
    }

    @Test
    fun resolveInitialTemplateSelection_shouldWaitWhenTargetMissing() {
        val plainTemplate = template(
            id = "template:first",
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = ProjectLanguage.CPP,
        )

        assertThat(
            NewProjectWizardSupport.resolveInitialTemplateSelection(
                initialTemplateId = "plugin:missing",
                preferPluginTemplate = false,
                templateOptions = listOf(plainTemplate),
            )
        ).isNull()
    }

    private fun template(
        id: String,
        buildSystem: ProjectBuildSystem,
        primaryLanguage: ProjectLanguage,
    ): ProjectTemplateOption = ProjectTemplateOption(
        id = id,
        displayName = id,
        description = id,
        spec = ProjectTemplateSpec.Zip(
            id = id,
            zipFile = File("$id.zip"),
            buildSystem = buildSystem,
            primaryLanguage = primaryLanguage,
        ),
    )
}
