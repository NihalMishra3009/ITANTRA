package com.itantra.security

import org.junit.Assert.*
import org.junit.Test

class SecurityTest {

    @Test
    fun testAeadEncryptDecryptRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val plain = "मुझे तत्काल सहायता चाहिए"

        val cipher = MessageSecurityManager.encryptPayload(plain, key)
        assertNotEquals(plain, cipher)
        assertTrue(cipher.isNotBlank())

        val decrypted = MessageSecurityManager.decryptPayload(cipher, key)
        assertEquals(plain, decrypted)
    }

    @Test
    fun testAeadTamperRejected() {
        val key = ByteArray(32) { 0x11 }
        val plain = "rescue route clear"
        val cipher = MessageSecurityManager.encryptPayload(plain, key)

        // Tamper: flip a char in the base64 payload
        val tampered = cipher.dropLast(1) + if (cipher.last() == 'A') 'B' else 'A'

        try {
            MessageSecurityManager.decryptPayload(tampered, key)
            fail("Tampered ciphertext must be rejected")
        } catch (expected: Exception) {
            // expected — AEAD authentication must reject tampering
        }
    }

    @Test
    fun testEcdhBothSidesDeriveSameKey() {
        val (pubA, privA) = MessageSecurityManager.generateKeyPair()
        val (pubB, privB) = MessageSecurityManager.generateKeyPair()

        val keyA = MessageSecurityManager.deriveSharedSessionKey(privA, pubB)
        val keyB = MessageSecurityManager.deriveSharedSessionKey(privB, pubA)

        // Both peers must derive the identical 32-byte session key
        assertArrayEquals(keyA, keyB)
        assertEquals(32, keyA.size)
    }

    @Test
    fun testHmacIsKeyedAndDeterministic() {
        val key = ByteArray(32) { 0x55 }
        val data = "iTantra protocol v2".toByteArray(Charsets.UTF_8)

        val h1 = MessageSecurityManager.computeHmac(data, key)
        val h2 = MessageSecurityManager.computeHmac(data, key)
        val otherKey = ByteArray(32) { 0x66 }

        assertArrayEquals(h1, h2)
        assertFalse(MessageSecurityManager.computeHmac(data, otherKey).contentEquals(h1))
    }
}
