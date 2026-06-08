package com.wuxianggujun.tinaide.ui.projectlist

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectSourceLocation

enum class ProjectTag(private val displayNameResId: Int) {
    PUBLIC_SOURCE(Strings.tag_public_source),
    PRIVATE_SOURCE(Strings.tag_private_source),
    GIT(Strings.tag_git),
    CMAKE(Strings.tag_cmake),
    MAKEFILE(Strings.tag_makefile),
    PLUGIN(Strings.tag_plugin),
    C_CPP(Strings.tag_c_cpp),
    JAVA(Strings.tag_java),
    KOTLIN(Strings.tag_kotlin),
    PYTHON(Strings.tag_python),
    RUST(Strings.tag_rust),
    GO(Strings.tag_go),
    JAVASCRIPT(Strings.tag_javascript),
    TYPESCRIPT(Strings.tag_typescript),
    SHELL(Strings.tag_shell);

    fun getDisplayName(context: Context): String = context.getString(displayNameResId)

    companion object {
        fun fromSourceLocation(sourceLocation: ProjectSourceLocation?): ProjectTag? = when (sourceLocation) {
            ProjectSourceLocation.PUBLIC -> PUBLIC_SOURCE
            ProjectSourceLocation.PRIVATE -> PRIVATE_SOURCE
            null -> null
        }

        fun fromLanguage(language: ProjectLanguage): ProjectTag? = when (language) {
            ProjectLanguage.C, ProjectLanguage.CPP -> C_CPP
            ProjectLanguage.JAVA -> JAVA
            ProjectLanguage.KOTLIN -> KOTLIN
            ProjectLanguage.PYTHON -> PYTHON
            ProjectLanguage.RUST -> RUST
            ProjectLanguage.GO -> GO
            ProjectLanguage.JAVASCRIPT -> JAVASCRIPT
            ProjectLanguage.TYPESCRIPT -> TYPESCRIPT
            ProjectLanguage.SHELL -> SHELL
            ProjectLanguage.MIXED, ProjectLanguage.UNKNOWN -> null
        }
    }
}

enum class ProjectAction {
    OPEN,
    RENAME,
    EXPORT,
    SETTINGS,
    INFO,
    DELETE,
}
