package com.wuxianggujun.tinaide.core.serialization

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Test
import org.msgpack.core.MessagePack

class MessagePackCodecTest {

    @Test
    fun `decodeOkEnvelope should parse msgpack binary bytes above 127`() {
        val protocolVersion = 1
        val timestamp = 1_700_000_000L
        // 包含 > 127 的字节，覆盖历史解析失败场景
        val data = byteArrayOf(46, 48, 0xAC.toByte(), 102, 111, 114, 99, 101, 0xC2.toByte())
        val signature = byteArrayOf(0x86.toByte(), 0x7F, 0x00, 0x80.toByte())
        val bytes = buildOkEnvelope(protocolVersion, timestamp, data, signature)

        val payloadObject = MessagePackCodec.decodeOkEnvelope<JsonObject>(bytes)
        val decodedData = MessagePackCodec.json.decodeFromJsonElement<ByteArray>(
            payloadObject.getValue("data")
        )
        val decodedSignature = MessagePackCodec.json.decodeFromJsonElement<ByteArray>(
            payloadObject.getValue("signature")
        )
        val decodedProtocolVersion =
            (payloadObject.getValue("protocol_version") as JsonPrimitive).content.toInt()
        val decodedTimestamp =
            (payloadObject.getValue("timestamp") as JsonPrimitive).content.toLong()

        assertThat(decodedProtocolVersion).isEqualTo(protocolVersion)
        assertThat(decodedTimestamp).isEqualTo(timestamp)
        assertThat(decodedData.asList()).containsExactlyElementsIn(data.asList()).inOrder()
        assertThat(decodedSignature.asList()).containsExactlyElementsIn(signature.asList()).inOrder()
    }

    private fun buildOkEnvelope(
        protocolVersion: Int,
        timestamp: Long,
        data: ByteArray,
        signature: ByteArray
    ): ByteArray = MessagePack.newDefaultBufferPacker().use { packer ->
        packer.packMapHeader(2)
        packer.packString("code")
        packer.packString(MessagePackCodec.CODE_OK)
        packer.packString("data")
        packer.packMapHeader(4)

        packer.packString("protocol_version")
        packer.packInt(protocolVersion)
        packer.packString("timestamp")
        packer.packLong(timestamp)

        packer.packString("data")
        packer.packBinaryHeader(data.size)
        packer.writePayload(data)

        packer.packString("signature")
        packer.packBinaryHeader(signature.size)
        packer.writePayload(signature)

        packer.flush()
        packer.toByteArray()
    }
}
