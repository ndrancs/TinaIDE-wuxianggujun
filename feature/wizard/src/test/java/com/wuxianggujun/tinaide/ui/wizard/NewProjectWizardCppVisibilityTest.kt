package com.wuxianggujun.tinaide.ui.wizard

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectTemplateOption
import com.wuxianggujun.tinaide.project.ProjectTemplateSpec
import java.io.File
import org.junit.Test

class NewProjectWizardCppVisibilityTest {

    @Test
    fun shouldShowCppStandard_shouldOnlyApplyToCAndCppTemplates() {
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(null)).isTrue()
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(template(ProjectLanguage.C))).isTrue()
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(template(ProjectLanguage.CPP))).isTrue()
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(template(ProjectLanguage.JAVA))).isFalse()
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(template(ProjectLanguage.KOTLIN))).isFalse()
        assertThat(NewProjectWizardSupport.shouldShowCppStandard(template(ProjectLanguage.MIXED))).isFalse()
    }

    private fun template(language: ProjectLanguage): ProjectTemplateOption = ProjectTemplateOption(
        id = "template-${language.name.lowercase()}",
        displayName = language.name,
        description = language.name,
        spec = ProjectTemplateSpec.Zip(
            id = "spec-${language.name.lowercase()}",
            zipFile = File("${language.name.lowercase()}.zip"),
            buildSystem = ProjectBuildSystem.CMAKE,
            primaryLanguage = language,
        ),
    )
}
