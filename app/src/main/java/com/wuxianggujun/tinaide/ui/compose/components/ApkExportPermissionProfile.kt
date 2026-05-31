package com.wuxianggujun.tinaide.ui.compose.components

import android.Manifest
import androidx.annotation.StringRes
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.storage.ProjectDirStructure
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ApkPermissionOption(
    val permission: String,
    @param:StringRes val labelRes: Int,
    val maxSdkVersion: Int? = null,
    val isHighRisk: Boolean = false
)

internal data class RememberedApkExportPermissions(
    val selectedBuiltinPermissions: Set<String>,
    val versionCode: Int? = null,
    val iconFilePath: String? = null,
    val additionalRuntimeLibraryPaths: List<String> = emptyList()
)

private const val PERMISSION_MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"
private const val PERMISSION_READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
private const val PERMISSION_READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
private const val PERMISSION_READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
private const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
private const val PERMISSION_SCHEDULE_EXACT_ALARM = "android.permission.SCHEDULE_EXACT_ALARM"

internal val apkPermissionOptions = listOf(
    ApkPermissionOption(
        permission = Manifest.permission.READ_EXTERNAL_STORAGE,
        labelRes = Strings.apk_builder_permission_read_external_storage
    ),
    ApkPermissionOption(
        permission = Manifest.permission.WRITE_EXTERNAL_STORAGE,
        labelRes = Strings.apk_builder_permission_write_external_storage,
        maxSdkVersion = 29
    ),
    ApkPermissionOption(
        permission = PERMISSION_MANAGE_EXTERNAL_STORAGE,
        labelRes = Strings.apk_builder_permission_manage_external_storage,
        isHighRisk = true
    ),
    ApkPermissionOption(
        permission = PERMISSION_READ_MEDIA_IMAGES,
        labelRes = Strings.apk_builder_permission_read_media_images
    ),
    ApkPermissionOption(
        permission = PERMISSION_READ_MEDIA_VIDEO,
        labelRes = Strings.apk_builder_permission_read_media_video
    ),
    ApkPermissionOption(
        permission = PERMISSION_READ_MEDIA_AUDIO,
        labelRes = Strings.apk_builder_permission_read_media_audio
    ),
    ApkPermissionOption(
        permission = Manifest.permission.CAMERA,
        labelRes = Strings.apk_builder_permission_camera
    ),
    ApkPermissionOption(
        permission = Manifest.permission.RECORD_AUDIO,
        labelRes = Strings.apk_builder_permission_record_audio
    ),
    ApkPermissionOption(
        permission = PERMISSION_POST_NOTIFICATIONS,
        labelRes = Strings.apk_builder_permission_post_notifications
    ),
    ApkPermissionOption(
        permission = Manifest.permission.REQUEST_INSTALL_PACKAGES,
        labelRes = Strings.apk_builder_permission_request_install_packages,
        isHighRisk = true
    ),
    ApkPermissionOption(
        permission = Manifest.permission.SYSTEM_ALERT_WINDOW,
        labelRes = Strings.apk_builder_permission_system_alert_window,
        isHighRisk = true
    ),
    ApkPermissionOption(
        permission = Manifest.permission.WRITE_SETTINGS,
        labelRes = Strings.apk_builder_permission_write_settings,
        isHighRisk = true
    ),
    ApkPermissionOption(
        permission = PERMISSION_SCHEDULE_EXACT_ALARM,
        labelRes = Strings.apk_builder_permission_schedule_exact_alarm,
        isHighRisk = true
    ),
    ApkPermissionOption(
        permission = Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        labelRes = Strings.apk_builder_permission_ignore_battery_optimizations
    )
)

internal val defaultApkPermissionSet: Set<String> =
    linkedSetOf()

internal fun buildRequestedPermissions(
    selectedBuiltinPermissions: Set<String>
): List<String> {
    val orderedPermissions = linkedSetOf<String>()

    apkPermissionOptions
        .asSequence()
        .map { it.permission }
        .filter { it in selectedBuiltinPermissions }
        .forEach(orderedPermissions::add)

    return orderedPermissions.toList()
}

internal fun hasHighRiskPermissionSelected(
    selectedBuiltinPermissions: Set<String>
): Boolean {
    val highRiskNames = apkPermissionOptions
        .asSequence()
        .filter { it.isHighRisk }
        .map { it.permission }
        .toSet()
    if (highRiskNames.isEmpty()) return false
    return selectedBuiltinPermissions.any { it in highRiskNames }
}

internal fun loadRememberedApkExportPermissions(
    outputDir: File,
    fallbackRoot: File
): RememberedApkExportPermissions? {
    val profileFile = resolveApkPermissionProfileFile(outputDir, fallbackRoot)
    if (!profileFile.exists()) return null

    return runCatching {
        val root = Json.parseToJsonElement(profileFile.readText()).jsonObject
        val selectedBuiltinPermissions = root["selectedBuiltinPermissions"]
            ?.jsonArray
            ?.toStringList()
            ?.toSet()
            .orEmpty()
        val versionCode = root["versionCode"]
            ?.jsonPrimitive
            ?.intOrNull
            ?.takeIf { it > 0 }
        val iconFilePath = root["iconFilePath"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val additionalRuntimeLibraryPaths = root["additionalRuntimeLibraryPaths"]
            ?.jsonArray
            ?.toStringList()
            ?.filter { path -> File(path).isFile }
            .orEmpty()
        RememberedApkExportPermissions(
            selectedBuiltinPermissions = selectedBuiltinPermissions,
            versionCode = versionCode,
            iconFilePath = iconFilePath,
            additionalRuntimeLibraryPaths = additionalRuntimeLibraryPaths
        )
    }.getOrNull()
}

internal fun rememberApkExportPermissions(
    outputDir: File,
    fallbackRoot: File,
    selectedBuiltinPermissions: Set<String>,
    versionCode: Int,
    iconFilePath: String?,
    additionalRuntimeLibraryPaths: List<String> = emptyList()
) {
    val profileFile = resolveApkPermissionProfileFile(outputDir, fallbackRoot)
    profileFile.parentFile?.mkdirs()

    runCatching {
        val root = buildJsonObject {
            put(
                "selectedBuiltinPermissions",
                buildJsonArray {
                    selectedBuiltinPermissions.forEach { permission ->
                        add(JsonPrimitive(permission))
                    }
                }
            )
            put("versionCode", JsonPrimitive(versionCode))

            if (!iconFilePath.isNullOrBlank()) {
                put("iconFilePath", JsonPrimitive(iconFilePath))
            }

            val runtimeLibraryPaths = additionalRuntimeLibraryPaths
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
            if (runtimeLibraryPaths.isNotEmpty()) {
                put(
                    "additionalRuntimeLibraryPaths",
                    buildJsonArray {
                        runtimeLibraryPaths.forEach { path ->
                            add(JsonPrimitive(path))
                        }
                    }
                )
            }
        }

        profileFile.writeText(root.toString())
    }
}

private fun resolveApkPermissionProfileFile(outputDir: File, fallbackRoot: File): File {
    val projectRoot = resolveProjectRootFromOutputDir(outputDir)
    return if (projectRoot != null) {
        ProjectDirStructure.getApkPermissionsFile(projectRoot.absolutePath)
    } else {
        File(fallbackRoot, "apk-export-permissions.json")
    }
}

private fun resolveProjectRootFromOutputDir(outputDir: File): File? {
    val outputDirAbsolute = outputDir.absoluteFile
    val ancestors = generateSequence(outputDirAbsolute) { current -> current.parentFile }
        .toList()

    ancestors.firstOrNull { candidate ->
        ProjectDirStructure.getTinaideDir(candidate.absolutePath).isDirectory
    }?.let { return it }

    val buildDir = ancestors.firstOrNull { candidate ->
        candidate.name.equals("build", ignoreCase = true)
    } ?: return null
    return buildDir.parentFile
}

private fun JsonArray.toStringList(): List<String> = mapNotNull { element ->
    element.jsonPrimitive.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
