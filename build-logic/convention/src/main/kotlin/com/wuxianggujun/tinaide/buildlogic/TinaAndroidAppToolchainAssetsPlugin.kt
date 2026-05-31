package com.wuxianggujun.tinaide.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers the `verifyTinaToolchainAssets` task and wires it into the
 * standard packaging lifecycle tasks (`assemble*`, `bundle*`, `install*`).
 *
 * The plugin intentionally keeps the task name and semantics stable for
 * downstream tooling and CI pipelines.
 */
class TinaAndroidAppToolchainAssetsPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val buildAllAbiRequested = TinaToolchainAssetsVerification.resolveBuildAllAbiRequested(this)
            val localDevAbi = TinaToolchainAssetsVerification.resolveLocalDevAbi(this)
            val hostProject = this

            val verifyTask = tasks.register("verifyTinaToolchainAssets") {
                description =
                    "Verify tina-toolchain assets referenced by current.properties exist and are consistent."
                group = "verification"
                doLast {
                    TinaToolchainAssetsVerification.verify(
                        project = hostProject,
                        logger = logger,
                        buildAllAbiRequested = buildAllAbiRequested,
                        localDevAbi = localDevAbi,
                    )
                }
            }

            // Fail-fast before packaging APK/AAB so missing assets don't
            // surface as runtime install errors.
            tasks.matching { task ->
                task.name.startsWith("assemble") ||
                    task.name.startsWith("bundle") ||
                    task.name.startsWith("install")
            }.configureEach {
                dependsOn(verifyTask)
            }
        }
    }
}
