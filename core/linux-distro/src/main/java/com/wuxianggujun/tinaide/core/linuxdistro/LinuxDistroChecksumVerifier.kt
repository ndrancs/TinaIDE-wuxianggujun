package com.wuxianggujun.tinaide.core.linuxdistro

import java.io.File
import java.security.MessageDigest

data class DistroChecksumResult(
    val expected: String,
    val actual: String,
) {
    val isValid: Boolean get() = expected.equals(actual, ignoreCase = true)
}

interface LinuxDistroChecksumVerifier {
    fun verify(file: File, checksum: DistroChecksum): DistroChecksumResult

    fun requireValid(file: File, checksum: DistroChecksum) {
        val result = verify(file, checksum)
        check(result.isValid) {
            "Checksum mismatch for ${file.name}: expected ${result.expected}, actual ${result.actual}"
        }
    }
}

class MessageDigestLinuxDistroChecksumVerifier : LinuxDistroChecksumVerifier {
    override fun verify(file: File, checksum: DistroChecksum): DistroChecksumResult {
        require(file.isFile) { "Checksum target is not a file: ${file.absolutePath}" }
        val digest = when (checksum.algorithm) {
            DistroChecksumAlgorithm.SHA256 -> MessageDigest.getInstance("SHA-256")
        }
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        return DistroChecksumResult(
            expected = checksum.normalizedValue,
            actual = actual,
        )
    }

    private companion object {
        private const val BUFFER_SIZE = 8192
    }
}
