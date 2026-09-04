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

    @Test
    fun testBroadcastInitiatorDerivesSameKeyAsResponder() {
        // Regression: initiator stores its pending ephemeral private under "*"
        // (it broadcasts before knowing the peer's node id). When the peer replies
        // with its REAL node id, the initiator must REUSE that pending key via the
        // "*" fallback so both sides derive the SAME shared secret. Without the
        // fallback the initiator would mint a fresh keypair -> different secret
        // -> HMAC fail -> packets silently dropped.
        PeerSessionManager.clearAll()

        // DEVICE A (initiator) broadcasts its ephemeral public key; pending stored under "*".
        val aPub = PeerSessionManager.initiateHandshake("*")

        // DEVICE B (responder) — conceptually a SEPARATE device. We simulate B's
        // side by asserting the fallback does NOT consume A's "*" pending: B has
        // no pending, so it goes down the responder path.
        // (In the singleton test the fallback is exercised when the reply comes
        // back to A. We test that path directly below.)

        // Simulate B deriving its shared key with its own fresh ephemeral key.
        val (bPubB64, bPriv) = MessageSecurityManager.createEphemeralKeyPairBase64()
        val bKey = MessageSecurityManager.deriveSharedSessionKey(bPriv, Base64Codec.decode(aPub))
        PeerSessionManager.setSessionKey("ITN-A", bKey)

        // Now A receives B's reply (peer id = ITN-B). A had pending under "*".
        val aResult = PeerSessionManager.handleHandshake("ITN-B", bPubB64)!!
        assertNull("A is the initiator: must not reply again", aResult.replyPublicKeyB64)

        // A must have derived the SAME key as B.
        val aKey = PeerSessionManager.getSessionKey("ITN-B")!!
        assertArrayEquals("Both devices must derive the identical shared key", aKey, bKey)
        assertEquals(32, aKey.size)
    }
}
