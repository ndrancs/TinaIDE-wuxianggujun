package com.wuxianggujun.tinaide.project

import java.io.File

sealed interface ProjectTemplateSpec {
    val buildSystem: ProjectBuildSystem
    val primaryLanguage: ProjectLanguage
    val isNdkTemplate: Boolean

    data class Zip(
        val id: String,
        val zipFile: File,
        override val buildSystem: ProjectBuildSystem,
        override val primaryLanguage: ProjectLanguage = ProjectLanguage.CPP,
        override val isNdkTemplate: Boolean = false,
        val variables: Map<String, String> = emptyMap(),
    ) : ProjectTemplateSpec
}

data class ProjectTemplateOption(
    val id: String,
    val displayName: String,
    val description: String,
    val spec: ProjectTemplateSpec.Zip,
    val isRecommended: Boolean = false
)

object BuiltInProjectTemplates {
    const val PLUGIN_ID: String = "tinaide.project.templates"
    const val DEFAULT_TEMPLATE_ID: String = "plugin:tinaide.project.templates:cpp-single-file"
}
