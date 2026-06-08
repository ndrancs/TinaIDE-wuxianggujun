package com.wuxianggujun.tinaide.core.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ServerConfigHmacVerifier {
    private const val ALGORITHM = "HmacSHA256"

    fun sign(secret: ByteArray, protocolVersion: Int, timestamp: Long, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret, ALGORITHM))
        mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(protocolVersion).array())
        mac.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(timestamp).array())
        mac.update(data)
        return mac.doFinal()
    }

    fun verify(
        secret: ByteArray,
        protocolVersion: Int,
        timestamp: Long,
        data: ByteArray,
        signature: ByteArray
    ): Boolean {
        val expected = sign(secret, protocolVersion, timestamp, data)
        return MessageDigest.isEqual(expected, signature)
    }
}
