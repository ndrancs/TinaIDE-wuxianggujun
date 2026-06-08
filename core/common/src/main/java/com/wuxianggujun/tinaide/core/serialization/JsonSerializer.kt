package com.wuxianggujun.tinaide.core.serialization

import java.io.File
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

/**
 * 统一 JSON 序列化工具
 *
 * 双轨 API 设计：
 * - **显式 serializer 版**：接受 [SerializationStrategy] / [DeserializationStrategy]，
 *   编译期绑定序列化器，即使泛型被擦除为 Any 也能正确序列化
 * - **reified 便捷版**：适用于类型在调用点明确的场景
 *
 * 典型防擦除用法：
 * ```kotlin
 * val strategy = serializer<MyData>()       // 类型明确处捕获
 * JsonSerializer.encode(strategy, instance)  // 经过 Any 边界仍安全
 * ```
 */
object JsonSerializer {

    /** 默认配置：忽略未知字段、宽松解析、编码默认值 */
    val default: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** 美化输出，用于配置文件、日志等需要可读性的场景 */
    val pretty: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 严格模式，用于需要严格校验的场景 */
    val strict: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
    }

    // ── encode: 对象 → String ──

    fun <T> encode(serializer: SerializationStrategy<T>, value: T): String = default.encodeToString(serializer, value)

    inline fun <reified T> encode(value: T): String = encode(serializer<T>(), value)

    fun <T> encodeOrNull(serializer: SerializationStrategy<T>, value: T): String? = runCatching { encode(serializer, value) }.getOrNull()

    inline fun <reified T> encodeOrNull(value: T): String? = runCatching { encode(value) }.getOrNull()

    // ── decode: String → 对象 ──

    fun <T> decode(deserializer: DeserializationStrategy<T>, string: String): T = default.decodeFromString(deserializer, string)

    inline fun <reified T> decode(string: String): T = decode(serializer<T>(), string)

    fun <T> decodeOrNull(deserializer: DeserializationStrategy<T>, string: String): T? = runCatching { decode(deserializer, string) }.getOrNull()

    inline fun <reified T> decodeOrNull(string: String): T? = runCatching { decode<T>(string) }.getOrNull()

    // ── JsonElement 互转 ──

    fun <T> toJsonElement(serializer: SerializationStrategy<T>, value: T): JsonElement = default.encodeToJsonElement(serializer, value)

    inline fun <reified T> toJsonElement(value: T): JsonElement = default.encodeToJsonElement(value)

    fun <T> fromJsonElement(deserializer: DeserializationStrategy<T>, element: JsonElement): T = default.decodeFromJsonElement(deserializer, element)

    inline fun <reified T> fromJsonElement(element: JsonElement): T = default.decodeFromJsonElement(element)

    fun <T> fromJsonElementOrNull(deserializer: DeserializationStrategy<T>, element: JsonElement): T? = runCatching { fromJsonElement(deserializer, element) }.getOrNull()

    inline fun <reified T> fromJsonElementOrNull(element: JsonElement): T? = runCatching { fromJsonElement<T>(element) }.getOrNull()

    // ── parse: String → JsonElement ──

    fun parseToJsonElement(string: String): JsonElement = default.parseToJsonElement(string)

    fun parseToJsonElementOrNull(string: String): JsonElement? = runCatching { default.parseToJsonElement(string) }.getOrNull()

    // ── 文件 I/O 便捷方法 ──

    inline fun <reified T> decodeFromFile(file: File): T = decode(file.readText())

    inline fun <reified T> decodeFromFileOrNull(file: File): T? = runCatching { decodeFromFile<T>(file) }.getOrNull()

    inline fun <reified T> encodeToFile(file: File, value: T) {
        file.writeText(encode(value))
    }

    fun <T> encodeToFile(file: File, serializer: SerializationStrategy<T>, value: T) {
        file.writeText(encode(serializer, value))
    }

    // pretty 版本（用于配置文件等需要可读性的场景）
    inline fun <reified T> encodePrettyToFile(file: File, value: T) {
        file.writeText(pretty.encodeToString(serializer<T>(), value))
    }
}
