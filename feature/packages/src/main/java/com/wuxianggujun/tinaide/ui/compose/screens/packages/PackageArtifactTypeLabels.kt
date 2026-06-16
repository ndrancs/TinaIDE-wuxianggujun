package com.wuxianggujun.tinaide.ui.compose.screens.packages

import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.model.PackageArtifactType

@StringRes
internal fun PackageArtifactType.labelResId(): Int = when (this) {
    PackageArtifactType.SOURCE -> Strings.pkg_artifact_type_source
    PackageArtifactType.HEADER -> Strings.pkg_artifact_type_header
    PackageArtifactType.STATIC -> Strings.pkg_artifact_type_static
    PackageArtifactType.SHARED -> Strings.pkg_artifact_type_shared
    PackageArtifactType.EXECUTABLE -> Strings.pkg_artifact_type_executable
    PackageArtifactType.MIXED -> Strings.pkg_artifact_type_mixed
}
