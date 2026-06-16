package com.wuxianggujun.tinaide.plugin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class PluginConfigurationPropertyType(val id: String) {
    STRING("string"),
    NUMBER("number"),
    BOOLEAN("boolean");

    companion object {
        fun from(rawType: String): PluginConfigurationPropertyType? = entries.firstOrNull { type -> type.id == rawType.trim().lowercase() }
    }
}

enum class PluginConfigurationValidationReason {
    INVALID_KEY,
    UNSUPPORTED_TYPE,
    INVALID_DEFAULT,
    INVALID_ENUM,
}

data class PluginConfigurationValidationIssue(
    val key: String,
    val reason: PluginConfigurationValidationReason,
    val value: String? = null,
)

data class ResolvedPluginConfigurationProperty(
    val key: String,
    val type: PluginConfigurationPropertyType,
    val description: String?,
    val defaultValue: JsonElement?,
    val enumValues: List<String>,
) {
    val isEnum: Boolean
        get() = enumValues.isNotEmpty()
}

object PluginConfigurationSchema {
    private val propertyKeyPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun isValidPropertyKey(key: String): Boolean = key.isNotBlank() && propertyKeyPattern.matches(key)

    fun resolveProperties(manifest: PluginManifest): List<ResolvedPluginConfigurationProperty> = resolveProperties(manifest.configuration)

    fun resolveProperties(configuration: PluginConfiguration?): List<ResolvedPluginConfigurationProperty> = configuration?.properties.orEmpty()
        .mapNotNull { (key, property) -> resolveProperty(key, property) }
        .sortedBy { property -> property.key }

    fun resolveProperty(
        manifest: PluginManifest,
        propertyKey: String,
    ): ResolvedPluginConfigurationProperty? = manifest.configuration
        ?.properties
        ?.get(propertyKey)
        ?.let { property -> resolveProperty(propertyKey, property) }

    fun resolveProperty(
        propertyKey: String,
        property: PluginConfigurationProperty,
    ): ResolvedPluginConfigurationProperty? {
        if (!isValidPropertyKey(propertyKey)) return null
        val type = PluginConfigurationPropertyType.from(property.type) ?: return null
        val enumValues = property.enumValues.orEmpty()
            .map { value -> value.trim() }
            .filter { value -> value.isNotBlank() }
            .distinct()
            .takeIf { values -> values.isNotEmpty() && type == PluginConfigurationPropertyType.STRING }
            .orEmpty()
        val resolved = ResolvedPluginConfigurationProperty(
            key = propertyKey,
            type = type,
            description = property.description?.trim()?.takeIf { description -> description.isNotBlank() },
            defaultValue = null,
            enumValues = enumValues,
        )
        val defaultValue = property.default?.let { value -> normalizeValue(resolved, value) }
        if (property.default != null && defaultValue == null) return null
        return resolved.copy(defaultValue = defaultValue)
    }

    fun validateConfiguration(configuration: PluginConfiguration?): List<PluginConfigurationValidationIssue> = configuration?.properties.orEmpty()
        .flatMap { (key, property) -> validateProperty(key, property) }

    fun normalizeValue(
        property: ResolvedPluginConfigurationProperty,
        value: JsonElement,
    ): JsonElement? {
        val primitive = runCatching { value.jsonPrimitive }.getOrNull() ?: return null
        return when (property.type) {
            PluginConfigurationPropertyType.BOOLEAN -> {
                if (primitive.isString) {
                    null
                } else {
                    primitive.booleanOrNull?.let { booleanValue -> JsonPrimitive(booleanValue) }
                }
            }
            PluginConfigurationPropertyType.NUMBER -> {
                if (primitive.isString) {
                    null
                } else {
                    primitive.doubleOrNull
                        ?.takeIf { numberValue -> numberValue.isFinite() }
                        ?.let { numberValue -> JsonPrimitive(numberValue) }
                }
            }
            PluginConfigurationPropertyType.STRING -> {
                if (!primitive.isString) {
                    null
                } else {
                    primitive.content
                        .takeIf { stringValue ->
                            property.enumValues.isEmpty() || stringValue in property.enumValues
                        }
                        ?.let { stringValue -> JsonPrimitive(stringValue) }
                }
            }
        }
    }

    fun stringValue(value: JsonElement?): String? {
        val primitive = value?.jsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    fun numberText(value: JsonElement?): String {
        val primitive = value?.jsonPrimitive ?: return ""
        val number = primitive.doubleOrNull ?: return ""
        return if (number % 1.0 == 0.0) {
            number.toLong().toString()
        } else {
            number.toString()
        }
    }

    fun booleanValue(value: JsonElement?): Boolean = value?.jsonPrimitive?.booleanOrNull ?: false

    fun toJsonPrimitive(
        property: ResolvedPluginConfigurationProperty,
        rawText: String,
    ): JsonElement? = when (property.type) {
        PluginConfigurationPropertyType.STRING -> JsonPrimitive(rawText)
        PluginConfigurationPropertyType.NUMBER ->
            rawText
                .trim()
                .toDoubleOrNull()
                ?.takeIf { numberValue -> numberValue.isFinite() }
                ?.let { numberValue -> JsonPrimitive(numberValue) }
        PluginConfigurationPropertyType.BOOLEAN -> when (rawText.trim().lowercase()) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> null
        }
    }?.let { value -> normalizeValue(property, value) }

    private fun validateProperty(
        key: String,
        property: PluginConfigurationProperty,
    ): List<PluginConfigurationValidationIssue> {
        val issues = mutableListOf<PluginConfigurationValidationIssue>()
        if (!isValidPropertyKey(key)) {
            issues += PluginConfigurationValidationIssue(
                key = key,
                reason = PluginConfigurationValidationReason.INVALID_KEY,
            )
            return issues
        }

        val type = PluginConfigurationPropertyType.from(property.type)
        if (type == null) {
            issues += PluginConfigurationValidationIssue(
                key = key,
                reason = PluginConfigurationValidationReason.UNSUPPORTED_TYPE,
                value = property.type,
            )
            return issues
        }

        val hasEnum = property.enumValues.orEmpty().any { value -> value.isNotBlank() }
        if (hasEnum && type != PluginConfigurationPropertyType.STRING) {
            issues += PluginConfigurationValidationIssue(
                key = key,
                reason = PluginConfigurationValidationReason.INVALID_ENUM,
                value = property.type,
            )
        }

        val resolved = ResolvedPluginConfigurationProperty(
            key = key,
            type = type,
            description = property.description,
            defaultValue = null,
            enumValues = if (type == PluginConfigurationPropertyType.STRING) {
                property.enumValues.orEmpty()
                    .map { value -> value.trim() }
                    .filter { value -> value.isNotBlank() }
                    .distinct()
            } else {
                emptyList()
            },
        )
        if (property.default != null && normalizeValue(resolved, property.default) == null) {
            issues += PluginConfigurationValidationIssue(
                key = key,
                reason = PluginConfigurationValidationReason.INVALID_DEFAULT,
                value = property.default.toString(),
            )
        }
        return issues
    }
}
