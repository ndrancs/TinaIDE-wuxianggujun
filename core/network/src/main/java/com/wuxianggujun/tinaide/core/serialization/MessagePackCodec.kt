package com.wuxianggujun.tinaide.core.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.msgpack.core.MessageBufferPacker
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import org.msgpack.value.ValueType

/**
 * MessagePack 编解码器
 *
 * 约束：
 * 1. 网络协议与本地缓存统一使用 MessagePack；
 * 2. 不再回退 JSON，避免掩盖协议错误。
 */
object MessagePackCodec {
    const val CODE_OK = "OK"

    // 必须是 public 才能被 public inline 函数访问
    val json = JsonSerializer.default

    /**
     * 解码数据为对象（严格 MessagePack）
     */
    inline fun <reified T> decode(bytes: ByteArray): T {
        val element = decodeMessagePackToJsonElement(bytes)
        return json.decodeFromJsonElement(element)
    }

    /**
     * 编码对象为 MessagePack
     */
    inline fun <reified T> encode(value: T): ByteArray {
        val element = json.encodeToJsonElement(value)
        return encodeJsonElementToMessagePack(element)
    }

    /**
     * 解码带 envelope 的数据（严格 MessagePack）
     */
    inline fun <reified T> decodeOkEnvelope(bytes: ByteArray): T {
        val root = decodeMessagePackToJsonElement(bytes).jsonObject
        val code = root["code"]?.jsonPrimitive?.content
            ?: throw SerializationException("Missing envelope code")
        check(code == CODE_OK) { "API code=$code" }
        val dataElement = root["data"]
            ?: throw SerializationException("Missing envelope data")
        return json.decodeFromJsonElement(dataElement)
    }

    @PublishedApi
    internal fun decodeMessagePackToJsonElement(bytes: ByteArray): JsonElement {
        MessagePack.newDefaultUnpacker(bytes).use { unpacker ->
            val element = unpackValue(unpacker)
            if (unpacker.hasNext()) {
                throw SerializationException("Unexpected trailing MessagePack payload")
            }
            return element
        }
    }

    @PublishedApi
    internal fun encodeJsonElementToMessagePack(element: JsonElement): ByteArray {
        MessagePack.newDefaultBufferPacker().use { packer ->
            packValue(packer, element)
            packer.flush()
            return packer.toByteArray()
        }
    }

    private fun unpackValue(unpacker: MessageUnpacker): JsonElement = when (unpacker.nextFormat.valueType) {
        ValueType.NIL -> {
            unpacker.unpackNil()
            JsonNull
        }

        ValueType.BOOLEAN -> JsonPrimitive(unpacker.unpackBoolean())
        ValueType.INTEGER -> unpackInteger(unpacker)
        ValueType.FLOAT -> JsonPrimitive(unpacker.unpackDouble())
        ValueType.STRING -> JsonPrimitive(unpacker.unpackString())
        ValueType.BINARY -> unpackBinaryAsJsonArray(unpacker)

        ValueType.ARRAY -> {
            val size = unpacker.unpackArrayHeader()
            JsonArray(List(size) { unpackValue(unpacker) })
        }

        ValueType.MAP -> {
            val size = unpacker.unpackMapHeader()
            val map = LinkedHashMap<String, JsonElement>(size)
            repeat(size) {
                val key = unpackValue(unpacker).jsonKey()
                map[key] = unpackValue(unpacker)
            }
            JsonObject(map)
        }

        ValueType.EXTENSION -> {
            val header = unpacker.unpackExtensionTypeHeader()
            val payload = ByteArray(header.length)
            unpacker.readPayload(payload)
            JsonObject(
                mapOf(
                    "_ext_type" to JsonPrimitive(header.type),
                    "_ext_data" to byteArrayToJsonArray(payload)
                )
            )
        }
    }

    private fun unpackInteger(unpacker: MessageUnpacker): JsonPrimitive = runCatching {
        JsonPrimitive(unpacker.unpackLong())
    }.getOrElse {
        JsonPrimitive(unpacker.unpackBigInteger().toString())
    }

    private fun unpackBinaryAsJsonArray(unpacker: MessageUnpacker): JsonArray {
        val len = unpacker.unpackBinaryHeader()
        val payload = ByteArray(len)
        unpacker.readPayload(payload)
        return byteArrayToJsonArray(payload)
    }

    private fun byteArrayToJsonArray(bytes: ByteArray): JsonArray {
        // ByteArray 反序列化要求元素在 [-128, 127] 区间
        return JsonArray(bytes.map { byte -> JsonPrimitive(byte.toInt()) })
    }

    private fun JsonElement.jsonKey(): String = when (this) {
        JsonNull -> "null"
        is JsonPrimitive -> content
        else -> toString()
    }

    private fun packValue(packer: MessageBufferPacker, element: JsonElement) {
        when (element) {
            JsonNull -> packer.packNil()
            is JsonPrimitive -> packPrimitive(packer, element)
            is JsonArray -> {
                packer.packArrayHeader(element.size)
                element.forEach { packValue(packer, it) }
            }

            is JsonObject -> {
                packer.packMapHeader(element.size)
                element.forEach { (key, value) ->
                    packer.packString(key)
                    packValue(packer, value)
                }
            }
        }
    }

    private fun packPrimitive(packer: MessageBufferPacker, primitive: JsonPrimitive) {
        if (primitive.isString) {
            packer.packString(primitive.content)
            return
        }

        primitive.booleanOrNull?.let {
            packer.packBoolean(it)
            return
        }
        primitive.longOrNull?.let {
            packer.packLong(it)
            return
        }
        primitive.doubleOrNull?.let {
            packer.packDouble(it)
            return
        }

        // 非法数字等兜底，按字符串写入，避免序列化阶段直接崩溃
        packer.packString(primitive.content)
    }
}
