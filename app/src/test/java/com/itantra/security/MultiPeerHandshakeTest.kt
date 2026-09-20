package com.itantra.security

import org.junit.Assert.*
import org.junit.Test

/**
 * A phone that auto-connects to several neighbours broadcasts ONE SESSION_START and gets a
 * reply from each. Every neighbour must end up with the same secret as the initiator.
 */
class MultiPeerHandshakeTest {

    private fun responderKey(initiatorPubB64: String): Pair<String, ByteArray> {
        val (pub, priv) = MessageSecurityManager.createEphemeralKeyPairBase64()
        return pub to MessageSecurityManager.deriveSharedSessionKey(priv, Base64Codec.decode(initiatorPubB64))
    }

    @Test
    fun secondAndThirdPeerRepliesDeriveTheSameKeyAsTheirResponder() {
        PeerSessionManager.clearAll()
        val aPub = PeerSessionManager.initiateHandshake("*")

        val (bPub, bKey) = responderKey(aPub)
        val (cPub, cKey) = responderKey(aPub)
        val (dPub, dKey) = responderKey(aPub)

        // Replies arrive one after another; the broadcast key must survive the first.
        assertNull(PeerSessionManager.handleHandshake("ITN-B", bPub)!!.replyPublicKeyB64)
        assertNull("2nd peer must be treated as a reply, not a new initiation",
            PeerSessionManager.handleHandshake("ITN-C", cPub)!!.replyPublicKeyB64)
        assertNull(PeerSessionManager.handleHandshake("ITN-D", dPub)!!.replyPublicKeyB64)

        assertArrayEquals(bKey, PeerSessionManager.getSessionKey("ITN-B"))
        assertArrayEquals(cKey, PeerSessionManager.getSessionKey("ITN-C"))
        assertArrayEquals(dKey, PeerSessionManager.getSessionKey("ITN-D"))
        assertFalse("distinct peers must not share a secret",
            PeerSessionManager.getSessionKey("ITN-B")!!.contentEquals(PeerSessionManager.getSessionKey("ITN-C")!!))
    }

    @Test
    fun reconnectAfterTheWindowStillDerivesTheSameKeyOnBothSides() {
        // Regression: a stale private key left under the peer id shadowed the fresh broadcast key
        // on the second handshake, so the phones derived different secrets after a reconnect.
        PeerSessionManager.clearAll()
        PeerSessionManager.expireBroadcastWindowForTest()

        // First session with ITN-B.
        val pub1 = PeerSessionManager.initiateHandshake("*")
        val (bPub1, bKey1) = responderKey(pub1)
        PeerSessionManager.handleHandshake("ITN-B", bPub1)
        assertArrayEquals(bKey1, PeerSessionManager.getSessionKey("ITN-B"))

        // The link drops; much later it re-forms and a NEW handshake starts.
        PeerSessionManager.expireBroadcastWindowForTest()
        val pub2 = PeerSessionManager.initiateHandshake("*")
        assertNotEquals("a new session must use a new key", pub1, pub2)
        val (bPub2, bKey2) = responderKey(pub2)
        PeerSessionManager.handleHandshake("ITN-B", bPub2)
        assertArrayEquals("both ends must agree after the reconnect", bKey2, PeerSessionManager.getSessionKey("ITN-B"))
        assertFalse(bKey1.contentEquals(bKey2))
    }

    @Test
    fun aSecondBroadcastInsideTheWindowReusesTheKeyInFlight() {
        PeerSessionManager.clearAll()
        val first = PeerSessionManager.initiateHandshake("*")
        val second = PeerSessionManager.initiateHandshake("*")
        assertEquals("a neighbour joining mid-handshake must not invalidate earlier replies", first, second)
    }
}
