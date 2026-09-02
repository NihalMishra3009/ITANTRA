package com.itantra.security

/**
 * Base64 codec abstracting over java.util.Base64 (JVM tests + Android API 26+)
 * and android.util.Base64 (Android API 24-25). This keeps the crypto layer
 * unit-testable on the JVM while working across the whole supported API range.
 */
object Base64Codec {

    fun encode(data: ByteArray): String {
        return try {
            val j = Class.forName("java.util.Base64")
            if (j != null) {
                java.util.Base64.getEncoder().encodeToString(data)
            } else {
                android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
            }
        } catch (e: Throwable) {
            android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        }
    }

    fun decode(str: String): ByteArray {
        return try {
            val j = Class.forName("java.util.Base64")
            if (j != null) {
                java.util.Base64.getDecoder().decode(str)
            } else {
                android.util.Base64.decode(str, android.util.Base64.NO_WRAP)
            }
        } catch (e: Throwable) {
            android.util.Base64.decode(str, android.util.Base64.NO_WRAP)
        }
    }
}
