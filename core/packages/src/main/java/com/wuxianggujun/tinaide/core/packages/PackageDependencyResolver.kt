package com.wuxianggujun.tinaide.core.packages

import com.wuxianggujun.tinaide.core.packages.model.GUIPackage
import com.wuxianggujun.tinaide.core.packages.model.Platform
import com.wuxianggujun.tinaide.core.packages.model.PlatformPackage

internal data class PackageInstallDescriptor(
    val packageId: String,
    val packageName: String,
    val platform: Platform,
    val platformPackage: PlatformPackage,
    val dependencies: List<String>
)

internal object PackageDependencyResolver {

    suspend fun resolveInstallPlan(
        rootPackageId: String,
        platform: Platform,
        loadPackage: suspend (String) -> Result<PackageInstallDescriptor>,
        isInstalled: (String, Platform) -> Boolean
    ): Result<List<PackageInstallDescriptor>> {
        val ordered = mutableListOf<PackageInstallDescriptor>()
        val activeStack = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        suspend fun visit(packageId: String, isRoot: Boolean) {
            if (packageId in visited) return

            val cycleStart = activeStack.indexOf(packageId)
            if (cycleStart >= 0) {
                val cycle = (activeStack.drop(cycleStart) + packageId).joinToString(" -> ")
                throw IllegalStateException("Circular package dependency: $cycle")
            }

            activeStack += packageId
            try {
                val descriptor = loadPackage(packageId).getOrElse { error ->
                    throw IllegalStateException(
                        "Failed to resolve package dependency '$packageId': ${error.message}",
                        error
                    )
                }

                descriptor.dependencies.forEach { dependencyId ->
                    visit(dependencyId, isRoot = false)
                }

                visited += packageId
                if (isRoot || !isInstalled(packageId, platform)) {
                    ordered += descriptor
                }
            } finally {
                activeStack.removeAt(activeStack.lastIndex)
            }
        }

        return try {
            visit(rootPackageId, isRoot = true)
            Result.success(ordered)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun findDirectDependents(
        packageId: String,
        platform: Platform,
        packages: List<GUIPackage>,
        dependenciesForPackage: suspend (GUIPackage, PlatformPackage) -> List<String>
    ): List<String> {
        val dependents = mutableListOf<String>()
        packages
            .filterNot { it.id == packageId }
            .forEach { pkg ->
                val platformPackage = pkg.platformPackage(platform) ?: return@forEach
                val dependencies = dependenciesForPackage(pkg, platformPackage)
                if (packageId in dependencies) {
                    dependents += pkg.name.ifBlank { pkg.id }
                }
            }

        return dependents
            .distinct()
            .sortedBy { it.lowercase() }
    }
}

internal fun GUIPackage.platformPackage(platform: Platform): PlatformPackage? = when (platform) {
    Platform.LINUX -> linux
    Platform.ANDROID -> android
}

internal fun List<String>?.normalizedPackageDependencies(): List<String> = orEmpty()
    .asSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .toList()
