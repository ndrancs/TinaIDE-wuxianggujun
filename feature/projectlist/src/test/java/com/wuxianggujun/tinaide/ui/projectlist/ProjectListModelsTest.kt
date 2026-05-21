package com.wuxianggujun.tinaide.ui.projectlist

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectSourceLocation
import org.junit.Test

class ProjectListModelsTest {

    @Test
    fun projectTag_shouldMapSourceLocationAndLanguages() {
        assertThat(ProjectTag.fromSourceLocation(ProjectSourceLocation.PUBLIC))
            .isEqualTo(ProjectTag.PUBLIC_SOURCE)
        assertThat(ProjectTag.fromSourceLocation(ProjectSourceLocation.PRIVATE))
            .isEqualTo(ProjectTag.PRIVATE_SOURCE)
        assertThat(ProjectTag.fromSourceLocation(null)).isNull()

        assertThat(ProjectTag.fromLanguage(ProjectLanguage.C)).isEqualTo(ProjectTag.C_CPP)
        assertThat(ProjectTag.fromLanguage(ProjectLanguage.CPP)).isEqualTo(ProjectTag.C_CPP)
        assertThat(ProjectTag.fromLanguage(ProjectLanguage.KOTLIN)).isEqualTo(ProjectTag.KOTLIN)
        assertThat(ProjectTag.fromLanguage(ProjectLanguage.UNKNOWN)).isNull()
        assertThat(ProjectTag.fromLanguage(ProjectLanguage.MIXED)).isNull()
    }

}
