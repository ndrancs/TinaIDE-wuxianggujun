package com.wuxianggujun.tinaide.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import java.io.File
import java.util.Properties

/**
 * Shared verification logic for `tina-toolchain` assets declared under
 * `app/src/<flavor>/assets/tina-toolchain/current.properties`.
 *
 * Kept in the convention plugin so the app build script does not need to
 * duplicate Gradle task logic or depend directly on Android DSL internals.
 */
internal object TinaToolchainAssetsVerification {

    private const val SYSROOT_ASSET_DIR = "android-sysroot"
    private const val SYSROOT_PROFILE_MANIFEST = "profiles.json"

    /**
     * ABI flavor names that Tina supports end-to-end today. Kept in the
     * convention plugin so that tools / plugins depending on the same
     * matrix can share a single source of truth.
     */
    val supportedDevAbis: Set<String> = setOf("arm64", "x86_64")

    /**
     * Resolve the dev ABI flavor that should stay enabled.
     *
     * Precedence:
     *  1. `android.injected.build.abi` — AGP injects this when you Run/Install
     *     from Android Studio to a connected device, so the active flavor
     *     follows the device automatically.
     *  2. `-Ptina.devAbi=` / `tina.devAbi` in gradle.properties — explicit
     *     CLI / IDE override.
     *  3. Fallback `arm64` to match historic behaviour.
     */
    fun resolveLocalDevAbi(project: Project): String {
        injectedAbiFlavor(project)?.let { return it }
        val raw = project.providers.gradleProperty("tina.devAbi").orNull?.trim().orEmpty()
        val abi = if (raw.isBlank()) "arm64" else raw
        require(abi in supportedDevAbis) {
            "Unsupported -Ptina.devAbi=$abi. Expected one of $supportedDevAbis."
        }
        return abi
    }

    /**
     * Map the AGP-injected device ABI list to one of our flavor names.
     * AGP passes a comma separated list ordered by device preference, e.g.
     * `arm64-v8a,armeabi-v7a` or `x86_64,x86`. We pick the first entry that
     * matches a supported flavor.
     */
    private fun injectedAbiFlavor(project: Project): String? {
        val injected = project.providers.gradleProperty("android.injected.build.abi").orNull
            ?: return null
        return injected.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { abi ->
                when {
                    abi.startsWith("arm64") || abi.startsWith("aarch64") -> "arm64"
                    abi.startsWith("x86_64") -> "x86_64"
                    else -> null
                }
            }
            .firstOrNull()
    }

    /**
     * Resolve whether the current invocation needs to cover all ABI
     * flavors. An explicit `-Ptina.allAbi=true/false` wins first so CI
     * release matrix jobs can intentionally build one ABI per runner.
     * Without an explicit value, CI and `AllAbi` tasks still enable all
     * supported ABI flavors.
     */
    fun resolveBuildAllAbiRequested(project: Project): Boolean {
        project.providers.gradleProperty("tina.allAbi").orNull?.let {
            return project.resolveBooleanGradleProperty("tina.allAbi", default = false)
        }
        if (System.getenv("CI")?.equals("true", ignoreCase = true) == true) return true
        return project.gradle.startParameter.taskNames.any { it.contains("AllAbi", ignoreCase = true) }
    }

    /**
     * Perform the actual verification. Throws a [GradleException] with a
     * descriptive message when the declared assets are missing or the
     * spec file is malformed.
     */
    fun verify(project: Project, logger: Logger, buildAllAbiRequested: Boolean, localDevAbi: String) {
        val expectedFlavorSpecs = (if (buildAllAbiRequested) supportedDevAbis else setOf(localDevAbi))
            .sorted()
            .associateWith { flavor ->
                project.file("src/$flavor/assets/tina-toolchain/current.properties")
            }

        val missingFlavorSpecs = expectedFlavorSpecs.filterValues { !it.isFile }.toSortedMap()
        if (missingFlavorSpecs.isNotEmpty()) {
            val details = missingFlavorSpecs.entries.joinToString("\n") { (flavor, file) ->
                " - $flavor: ${file.relativeTo(project.projectDir).invariantSeparatorsPath}"
            }
            throw GradleException(
                "Missing tina-toolchain spec for required ABI flavor(s):\n$details\n" +
                    "Fix: generate/sync toolchain assets for the missing ABI before building.",
            )
        }
        verifyRequiredSysrootManifests(project, expectedFlavorSpecs.keys)

        val srcRoot = project.file("src")
        val specFiles = if (srcRoot.isDirectory) {
            srcRoot.walkTopDown()
                .filter {
                    it.isFile &&
                        it.name == "current.properties" &&
                        it.parentFile?.name == "tina-toolchain" &&
                        it.parentFile?.parentFile?.name == "assets"
                }
                .sortedBy { it.invariantSeparatorsPath }
                .toList()
        } else {
            emptyList()
        }

        if (specFiles.isEmpty()) {
            logger.lifecycle(
                "No tina-toolchain spec files found under app/src/*/assets/tina-toolchain; skipping.",
            )
            return
        }

        for (specFile in specFiles) {
            verifySingleSpec(project, logger, specFile)
        }

        for (manifestFile in findSysrootManifests(srcRoot)) {
            verifySingleSysrootManifest(project, manifestFile)
        }
    }

    private fun verifyRequiredSysrootManifests(project: Project, requiredFlavors: Collection<String>) {
        val missingManifests = requiredFlavors
            .map { flavor ->
                flavor to project.file("src/$flavor/assets/$SYSROOT_ASSET_DIR/$SYSROOT_PROFILE_MANIFEST")
            }
            .filter { (_, file) -> !file.isFile }

        if (missingManifests.isNotEmpty()) {
            val details = missingManifests.joinToString("\n") { (flavor, file) ->
                " - $flavor: ${file.relativeTo(project.projectDir).invariantSeparatorsPath}"
            }
            throw GradleException(
                "Missing Android sysroot profile manifest for required ABI flavor(s):\n$details\n" +
                    "Fix: run tools/sync-tina-toolchain-assets.ps1 for the ABI or add $SYSROOT_PROFILE_MANIFEST " +
                    "under app/src/<abi>/assets/$SYSROOT_ASSET_DIR.",
            )
        }
    }

    private fun findSysrootManifests(srcRoot: File): List<File> {
        if (!srcRoot.isDirectory) return emptyList()
        return srcRoot.walkTopDown()
            .filter {
                it.isFile &&
                    it.name == SYSROOT_PROFILE_MANIFEST &&
                    it.parentFile?.name == SYSROOT_ASSET_DIR &&
                    it.parentFile?.parentFile?.name == "assets"
            }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
    }

    private fun verifySingleSysrootManifest(project: Project, manifestFile: File) {
        val relManifest = manifestFile.relativeTo(project.projectDir).invariantSeparatorsPath
        val relParts = relManifest.split('/')
        val flavorName = relParts.getOrNull(1) ?: "unknown"
        val expectedTriple = expectedSysrootTriple(flavorName)
        val expectedArchNames = expectedSysrootArchNames(flavorName)
        val assetsDir = manifestFile.parentFile?.parentFile
            ?: throw GradleException("Invalid Android sysroot manifest location: $relManifest")

        val parsed = try {
            JsonSlurper().parse(manifestFile)
        } catch (e: Exception) {
            throw GradleException("Invalid Android sysroot manifest JSON: $relManifest", e)
        }
        val manifest = parsed as? Map<*, *>
            ?: throw GradleException("Android sysroot manifest root must be a JSON object: $relManifest")

        val schemaVersion = readInt(manifest, "schemaVersion")
        if (schemaVersion != 1) {
            throw GradleException("Android sysroot manifest schemaVersion must be 1: $relManifest")
        }

        val defaultProfileId = readString(manifest, "defaultProfileId")
            ?: throw GradleException("Android sysroot manifest defaultProfileId is blank: $relManifest")
        val profiles = readList(manifest, "profiles")
            ?: throw GradleException("Android sysroot manifest profiles must be an array: $relManifest")
        if (profiles.isEmpty()) {
            throw GradleException("Android sysroot manifest contains no profiles: $relManifest")
        }

        val profileIds = linkedSetOf<String>()
        for ((index, rawProfile) in profiles.withIndex()) {
            val profile = rawProfile as? Map<*, *>
                ?: throw GradleException("Android sysroot profile at index $index must be an object: $relManifest")
            val id = readString(profile, "id")
                ?: throw GradleException("Android sysroot profile id is blank at index $index: $relManifest")
            if (!profileIds.add(id)) {
                throw GradleException("Duplicate Android sysroot profile id '$id': $relManifest")
            }

            val arch = readString(profile, "arch")
                ?: throw GradleException("Android sysroot profile arch is blank: id=$id manifest=$relManifest")
            if (expectedArchNames.isNotEmpty() && arch !in expectedArchNames) {
                throw GradleException(
                    "Android sysroot profile arch mismatch: id=$id arch=$arch expected=$expectedArchNames " +
                        "manifest=$relManifest",
                )
            }

            val assetPath = readString(profile, "assetPath")
                ?: throw GradleException("Android sysroot profile assetPath is blank: id=$id manifest=$relManifest")
            requireSafeSysrootAssetPath(assetPath, id, relManifest)
            val assetFile = assetsDir.resolve(assetPath)
            if (!assetFile.isFile) {
                throw GradleException(
                    "Missing Android sysroot asset for profile '$id' in $relManifest: " +
                        "expected ${assetFile.relativeTo(project.projectDir).invariantSeparatorsPath}",
                )
            }
            if (!assetPath.endsWith(".tar.xz")) {
                throw GradleException(
                    "Unsupported Android sysroot asset extension: id=$id assetPath=$assetPath. " +
                        "Only .tar.xz is supported.",
                )
            }

            val sha256 = readString(profile, "sha256")
            if (sha256 != null && !sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
                throw GradleException("Invalid Android sysroot sha256: id=$id manifest=$relManifest")
            }

            val toolchainTriple = readString(profile, "toolchainTriple")
            if (expectedTriple != null && toolchainTriple != null && toolchainTriple != expectedTriple) {
                throw GradleException(
                    "Android sysroot profile triple mismatch: id=$id triple=$toolchainTriple " +
                        "expected=$expectedTriple manifest=$relManifest",
                )
            }

            val apiLevels = readList(profile, "apiLevels")
            if (apiLevels != null && apiLevels.any { (it as? Number)?.toInt() == null }) {
                throw GradleException("Android sysroot apiLevels must contain only numbers: id=$id manifest=$relManifest")
            }
        }

        if (defaultProfileId !in profileIds) {
            throw GradleException(
                "Android sysroot defaultProfileId '$defaultProfileId' does not match any profile id: $relManifest",
            )
        }
    }

    private fun verifySingleSpec(project: Project, logger: Logger, specFile: File) {
        val relSpec = specFile.relativeTo(project.projectDir).invariantSeparatorsPath
        val relParts = relSpec.split('/')
        val flavorName = relParts.getOrNull(1) ?: "unknown"
        val assetsDir = specFile.parentFile
        val forbiddenArchiveDir = assetsDir.resolve("archive")
        val forbiddenArchiveFile = if (forbiddenArchiveDir.isDirectory) {
            forbiddenArchiveDir.walkTopDown().firstOrNull { it.isFile }
        } else {
            null
        }
        if (forbiddenArchiveFile != null) {
            val relForbiddenDir = forbiddenArchiveDir.relativeTo(project.projectDir).invariantSeparatorsPath
            val relForbiddenFile = forbiddenArchiveFile.relativeTo(project.projectDir).invariantSeparatorsPath
            throw GradleException(
                "Found toolchain archive under assets: $relForbiddenDir\n" +
                    "Sample file: $relForbiddenFile\n" +
                    "Move archived versions out of app/src to avoid packaging into APK " +
                    "(recommended: app/.local/toolchain-archive/$flavorName).",
            )
        }

        val props = Properties()
        specFile.inputStream().use { props.load(it) }

        val version = readTrimmed(props, "version")
        val arch = readTrimmed(props, "arch")
        val full = readTrimmed(props, "full")
        val base = readTrimmed(props, "base")
        val tools = readTrimmed(props, "tools")
        val sha = readTrimmed(props, "sha256")

        if (version == null || arch == null || (full == null && base == null)) {
            throw GradleException(
                "Invalid tina-toolchain spec (missing version/arch and one of full/base): $relSpec",
            )
        }

        val shaFile = sha?.let { assetsDir.resolve(it) }
        val shaEntries = shaFile?.let { parseSha256File(it) }
        if (sha != null && shaEntries != null && shaEntries.isEmpty()) {
            throw GradleException("sha256 file is empty/unreadable: $relSpec (sha256=$sha)")
        }

        val mainArchive = full ?: base!!
        val mainKey = if (full != null) "full" else "base"

        // 主包（full/base）必填；tools/sha256 可选。
        requireAssetFile(project, specFile, assetsDir, flavorName, mainKey, mainArchive, shaEntries)
        if (full == null) {
            tools?.let {
                requireAssetFile(project, specFile, assetsDir, flavorName, "tools", it, shaEntries)
            }
        } else if (tools != null) {
            logger.warn(
                "tina-toolchain spec has both full and tools; tools will be ignored at runtime (spec=$relSpec)",
            )
        }
        sha?.let {
            requireAssetFile(project, specFile, assetsDir, flavorName, "sha256", it, null)
        }

        // Basic sanity check to reduce accidental version mismatches.
        if (!mainArchive.contains("v$version")) {
            logger.warn(
                "tina-toolchain spec version mismatch? version=$version but archive=$mainArchive (spec=$relSpec)",
            )
        }
    }

    private fun requireAssetFile(
        project: Project,
        specFile: File,
        assetsDir: File,
        flavorName: String,
        key: String,
        fileName: String,
        shaEntries: Set<String>?,
    ) {
        val f = assetsDir.resolve(fileName)
        if (!f.isFile) {
            val relSpec = specFile.relativeTo(project.projectDir).invariantSeparatorsPath
            val relExpected = f.relativeTo(project.projectDir).invariantSeparatorsPath
            val hint = "Fix: sync assets from build/tina-toolchain/release into $assetsDir " +
                "(try: pwsh -NoProfile -ExecutionPolicy Bypass -File tools/sync-tina-toolchain-assets.ps1 -Abi $flavorName -Clean)"
            throw GradleException(
                "Missing tina-toolchain asset for '$key' in $relSpec: expected $relExpected\n$hint",
            )
        }
        if (!fileName.endsWith(".tar.xz") && (key == "full" || key == "base" || key == "tools")) {
            val relSpec = specFile.relativeTo(project.projectDir).invariantSeparatorsPath
            throw GradleException(
                "Unsupported tina-toolchain archive extension in $relSpec ($key=$fileName). " +
                    "Only .tar.xz is supported at runtime.",
            )
        }
        if (shaEntries != null && shaEntries.isNotEmpty() && !shaEntries.contains(fileName)) {
            val relSpec = specFile.relativeTo(project.projectDir).invariantSeparatorsPath
            throw GradleException(
                "sha256 file does not contain an entry for $fileName (spec: $relSpec). " +
                    "Re-generate/check the sha256 asset.",
            )
        }
    }

    private fun requireSafeSysrootAssetPath(assetPath: String, profileId: String, relManifest: String) {
        val isUnsafe = assetPath.contains('\\') ||
            assetPath.startsWith("/") ||
            assetPath.contains("..") ||
            !assetPath.startsWith("$SYSROOT_ASSET_DIR/")
        if (isUnsafe) {
            throw GradleException(
                "Unsafe Android sysroot assetPath: id=$profileId assetPath=$assetPath manifest=$relManifest. " +
                    "Expected a path under $SYSROOT_ASSET_DIR/.",
            )
        }
    }

    private fun expectedSysrootTriple(flavorName: String): String? = when (flavorName) {
        "arm64" -> "aarch64-linux-android"
        "x86_64" -> "x86_64-linux-android"
        else -> null
    }

    private fun expectedSysrootArchNames(flavorName: String): Set<String> = when (flavorName) {
        "arm64" -> setOf("ARM64", "aarch64-linux-android")
        "x86_64" -> setOf("X86_64", "x86_64-linux-android")
        else -> emptySet()
    }

    private fun readString(map: Map<*, *>, key: String): String? =
        (map[key] as? String)?.trim()?.takeIf { it.isNotBlank() }

    private fun readInt(map: Map<*, *>, key: String): Int? =
        when (val value = map[key]) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }

    private fun readList(map: Map<*, *>, key: String): List<*>? =
        map[key] as? List<*>

    private fun readTrimmed(props: Properties, key: String): String? {
        return props.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseSha256File(shaFile: File): Set<String> {
        if (!shaFile.isFile) return emptySet()
        return shaFile.readLines(Charsets.UTF_8)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }
            .toSet()
    }
}
