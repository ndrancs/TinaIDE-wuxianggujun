package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.core.compile.artifact.Artifact
import java.io.File
import timber.log.Timber

/**
 * 把内部构建产物映射成 UI 可消费的展示模型，并尽量导出到项目目录。
 *
 * 真实产物仍保留在私有 workspace/build；这里额外导出一个可见副本到
 * `<project>/.tinaide/artifacts[/variant]/`，供文件树展示和用户查找。
 */
internal object PresentedBuildArtifactResolver {
    private const val TAG = "PresentedArtifact"
    private const val DEFAULT_VARIANT = "default"

    fun resolve(
        projectRoot: File,
        artifact: Artifact,
        kind: CompileProjectUseCase.BuildArtifactKind,
    ): CompileProjectUseCase.BuildArtifact {
        Timber.tag(TAG).i(
            "Resolving presented artifact: source=%s variant=%s kind=%s project=%s",
            artifact.absolutePath,
            artifact.id.variant,
            kind,
            projectRoot.absolutePath,
        )
        val exportedPath = BuildArtifactExporter.export(
            projectRoot = projectRoot,
            artifactPath = artifact.absolutePath,
            variantName = artifact.id.variant.toExportVariantName(),
        )
            .onSuccess { export ->
                Timber.tag(TAG).i(
                    "Exported build artifact: source=%s exported=%s",
                    export.sourceArtifact.absolutePath,
                    export.exportedArtifact.absolutePath,
                )
            }
            .onFailure { error ->
                Timber.tag(TAG).w(
                    error,
                    "Failed to export build artifact for visibility: %s",
                    artifact.absolutePath,
                )
            }
            .getOrNull()
            ?.exportedArtifact
            ?.absolutePath

        return CompileProjectUseCase.BuildArtifact(
            path = artifact.absolutePath,
            exportedPath = exportedPath,
            kind = kind,
        )
    }

    private fun String?.toExportVariantName(): String? {
        val normalized = this?.trim().orEmpty()
        if (normalized.isBlank()) return null
        if (normalized.equals(DEFAULT_VARIANT, ignoreCase = true)) return null
        return normalized
    }
}
