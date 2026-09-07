package com.itantra.security

import com.itantra.protocol.BinaryPacketCodec
import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SecurityTest {

    @Before
    fun setUp() {
        PeerSessionManager.clearAll()
    }

    @Test
    fun testAeadEncryptDecryptRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val plain = "à¤®à¥à¤à¥‡ à¤¤à¤¤à¥à¤•à¤¾à¤² à¤¸à¤¹à¤¾à¤¯à¤¤à¤¾ à¤šà¤¾à¤¹à¤¿à¤"

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
            // expected â€” AEAD authentication must reject tampering
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
        val data = "iTantra protocol v4".toByteArray(Charsets.UTF_8)

        val h1 = MessageSecurityManager.computeHmac(data, key)
        val h2 = MessageSecurityManager.computeHmac(data, key)
        val otherKey = ByteArray(32) { 0x66 }

        assertArrayEquals(h1, h2)
        assertFalse(MessageSecurityManager.computeHmac(data, otherKey).contentEquals(h1))
    }

    @Test
    fun testTwoPeersDeriveSameKeyAndDifferentPeersDifferentKeys() {
        val (pubA, privA) = MessageSecurityManager.generateKeyPair()
        val (pubB, privB) = MessageSecurityManager.generateKeyPair()
        val (pubC, privC) = MessageSecurityManager.generateKeyPair()

        // Same two peers derive the SAME shared secret.
        val keyAB = MessageSecurityManager.deriveSharedSessionKey(privA, pubB)
        val keyBA = MessageSecurityManager.deriveSharedSessionKey(privB, pubA)
        assertArrayEquals(keyAB, keyBA)

        // Different peer pairs get different keys.
        val keyAC = MessageSecurityManager.deriveSharedSessionKey(privA, pubC)
        assertFalse(keyAB.contentEquals(keyAC))
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

    // ---- Phase 1: per-peer wire encryption + authentication ----

    private fun hop(data: ByteArray, fromKey: ByteArray, toKey: ByteArray): ByteArray? {
        val wire = BinaryPacketCodec().decode(data, fromKey) ?: return null
        // Relay decrypts with the incoming hop key, then re-encrypts for next hop.
        val plain = if (wire.isEncrypted) wire.withDecryption(fromKey) else wire
        return BinaryPacketCodec().encode(plain.withEncryption(toKey), sessionKey = toKey)
    }

    @Test
    fun testPacketEncryptedForPeerA_CannotBeDecryptedByPeerB() {
        val keyA = ByteArray(32) { 0x0A.toByte() }
        val keyB = ByteArray(32) { 0x0B.toByte() }
        val packet = TextPacket(
            messageId = "peer_isolation_1",
            senderId = "ITN-X",
            recipientId = "ITN-A",
            type = PacketType.DATA,
            language = "hi",
            text = "à¤®à¤¦à¤¦ à¤šà¤¾à¤¹à¤¿à¤",
            timestamp = System.currentTimeMillis()
        )

        val wireForA = BinaryPacketCodec().encode(packet.withEncryption(keyA), keyA)
        // B's key must NOT decode A's wire packet (HMAC mismatch -> reject).
        assertNull(BinaryPacketCodec().decode(wireForA, keyB))

        // Hop re-encryption to B works.
        val toB = hop(wireForA, keyA, keyB)
        assertNotNull(toB)
        val atB = BinaryPacketCodec().decode(toB!!, keyB)!!
        assertEquals("à¤®à¤¦à¤¦ à¤šà¤¾à¤¹à¤¿à¤", atB.withDecryption(keyB).text)
    }

    @Test
    fun testTamperedWirePacketRejected() {
        val key = ByteArray(32) { 0x42 }
        val packet = TextPacket(
            messageId = "tamper_wire",
            senderId = "ITN-A",
            recipientId = "ITN-B",
            type = PacketType.DATA,
            language = "en",
            text = "verify integrity",
            timestamp = System.currentTimeMillis()
        )
        val wire = BinaryPacketCodec().encode(packet.withEncryption(key), key)

        // Flip one byte in the middle of the wire packet.
        val corrupted = wire.copyOf()
        corrupted[wire.size / 2] = (corrupted[wire.size / 2].toInt() xor 0xFF).toByte()

        assertNull(BinaryPacketCodec().decode(corrupted, key))
    }

    @Test
    fun testWrongPeerKeyFails() {
        val keyA = ByteArray(32) { 1 }
        val keyB = ByteArray(32) { 2 }
        val packet = TextPacket(
            messageId = "wrong_key",
            senderId = "ITN-A",
            recipientId = "ITN-B",
            type = PacketType.DATA,
            language = "en",
            text = "secret",
            timestamp = System.currentTimeMillis()
        )
        val wire = BinaryPacketCodec().encode(packet.withEncryption(keyA), keyA)
        assertNull(BinaryPacketCodec().decode(wire, keyB))
    }

    @Test
    fun testUnknownPeerWithoutKeyRejected() {
        // Authenticated packet without a supplied key (unknown peer) must be rejected,
        // NOT silently accepted.
        val packet = TextPacket(
            messageId = "no_key",
            senderId = "ITN-UNKNOWN",
            recipientId = "ITN-B",
            type = PacketType.DATA,
            language = "en",
            text = "hi",
            timestamp = System.currentTimeMillis()
        )
        val wire = BinaryPacketCodec().encode(packet.withEncryption(ByteArray(32) { 7 }), ByteArray(32) { 7 })
        assertNull(BinaryPacketCodec().decode(wire, sessionKey = null))
    }

    @Test
    fun testPeerSessionReplacementAndIsolation() {
        PeerSessionManager.clearAll()
        val k1 = ByteArray(32) { 1 }
        val k2 = ByteArray(32) { 2 }
        PeerSessionManager.setSessionKey("ITN-A", k1)
        PeerSessionManager.setSessionKey("ITN-B", k2)

        assertArrayEquals(k1, PeerSessionManager.getSessionKey("ITN-A"))
        assertArrayEquals(k2, PeerSessionManager.getSessionKey("ITN-B"))

        // Replacing A's key must NOT touch B's key (handshake for A only re-keys A).
        val k1new = ByteArray(32) { 9 }
        PeerSessionManager.setSessionKey("ITN-A", k1new)
        assertArrayEquals(k1new, PeerSessionManager.getSessionKey("ITN-A"))
        assertArrayEquals(k2, PeerSessionManager.getSessionKey("ITN-B"))
    }

    @Test
    fun testOldPeerKeyCannotDecryptNewSessionPackets() {
        val oldKey = ByteArray(32) { 0x11 }
        val newKey = ByteArray(32) { 0x22 }
        val packet = TextPacket(
            messageId = "session_rotate",
            senderId = "ITN-A",
            recipientId = "ITN-B",
            type = PacketType.DATA,
            language = "en",
            text = "after re-key",
            timestamp = System.currentTimeMillis()
        )
        val newWire = BinaryPacketCodec().encode(packet.withEncryption(newKey), newKey)
        // Old (pre-handshake) key must not authenticate the new-session packet.
        assertNull(BinaryPacketCodec().decode(newWire, oldKey))
        // New key succeeds.
        val ok = BinaryPacketCodec().decode(newWire, newKey)!!
        assertEquals("after re-key", ok.withDecryption(newKey).text)
    }

    @Test
    fun testMultiPeerRelayAllKeysDistinct() {
        val keyAR1 = ByteArray(32) { 0xA1.toByte() }
        val keyR1B = ByteArray(32) { 0xB2.toByte() }
        val packet = TextPacket(
            messageId = "relay_chain",
            senderId = "ITN-A",
            recipientId = "ITN-B",
            type = PacketType.DATA,
            language = "hi",
            text = "relay me",
            timestamp = System.currentTimeMillis()
        )

        // A -> R1 hop encrypted with A-R1 key.
        val onR1 = hop(
            BinaryPacketCodec().encode(packet.withEncryption(keyAR1), keyAR1),
            keyAR1, keyR1B
        )
        assertNotNull(onR1)
        // R1 -> B hop: B sees plaintext only with R1-B key.
        val atB = BinaryPacketCodec().decode(onR1!!, keyR1B)!!
        assertEquals("relay me", atB.withDecryption(keyR1B).text)
        // A's key can no longer see the R1->B hop.
        assertNull(BinaryPacketCodec().decode(onR1!!, keyAR1))
    }

    // ---- Phase 2: replay protection ----

    @Test
    fun testReplayRejectedWithinTtl() {
        val rp = ReplayProtection()
        val now = System.currentTimeMillis()
        assertTrue(rp.isNew("ITN-A", "msg_1", now))
        // Same (sender, messageId) replayed within TTL -> rejected.
        assertFalse(rp.isNew("ITN-A", "msg_1", now))
    }

    @Test
    fun testSameIdAcrossDifferentPeersAllowed() {
        val rp = ReplayProtection()
        val now = System.currentTimeMillis()
        assertTrue(rp.isNew("ITN-A", "msg_9", now))
        // Same messageId but different sender (per-peer namespace) is NOT a replay.
        assertTrue(rp.isNew("ITN-B", "msg_9", now))
    }

    @Test
    fun testExpiredPacketRejected() {
        val rp = ReplayProtection(maxEntriesPerPeer = 16, ttlMs = 100)
        val old = System.currentTimeMillis() - 10_000 // older than 100ms TTL
        assertFalse(rp.isNew("ITN-A", "stale", old))
    }

    @Test
    fun testReplayCacheBounded() {
        val rp = ReplayProtection(maxEntriesPerPeer = 4, ttlMs = 300_000)
        val now = System.currentTimeMillis()
        // Fill past the per-peer cap.
        for (i in 0..9) assertTrue(rp.isNew("ITN-A", "m$i", now))
        // Still functional; memory is bounded by the cap (new ids accepted evict the oldest).
        assertTrue(rp.isNew("ITN-A", "m_new", now))
    }
}
