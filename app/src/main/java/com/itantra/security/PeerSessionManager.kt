package com.itantra.security

import android.util.Log
import java.security.PrivateKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-peer ECDH session keys for iTantra.
 *
 * Each directly connected peer (identified by node ID) has its own 32-byte
 * AES-256 session key derived via ECDH P-256 + HKDF-SHA256. This ensures:
 *
 *  1. Relays can forward encrypted packets WITHOUT decrypting the payload.
 *  2. Compromising one peer's key does not expose traffic to other peers.
 *  3. Each link (A↔R1, R1↔B) has independent confidentiality.
 *
 * Architecture:
 *  - End-to-end encryption: sender encrypts with destination's public key.
 *  - Hop-level authentication: each link authenticates via its own session key.
 *  - Routing metadata (senderId, recipientId, hopCount) travels in plaintext.
 *
 * For the SIH demo, we use hop-level encryption where each hop decrypts and
 * re-encrypts. This is simpler and allows relays to inspect routing metadata.
 */
object PeerSessionManager {

    private const val TAG = "PeerSessionManager"

    /** Per-peer session keys: nodeId -> 32-byte session key */
    private val sessionKeys = ConcurrentHashMap<String, ByteArray>()

    /** Per-peer ephemeral private keys for ongoing handshakes */
    private val pendingHandshakes = ConcurrentHashMap<String, PrivateKey>()

    /**
     * A phone that auto-connects to several neighbours sends ONE broadcast SESSION_START
     * ("*") and then receives a reply from each of them. The ephemeral key behind that
     * broadcast must therefore stay usable for every reply, not just the first: consuming
     * it on the first reply made every later neighbour derive a different secret, so its
     * packets failed authentication and were silently dropped. The key is kept for this
     * window (and reused if another broadcast starts inside it), then forgotten.
     */
    private const val BROADCAST_HANDSHAKE_WINDOW_MS = 30_000L
    private var broadcastPub: String? = null
    private var broadcastStartedAt = 0L

    /**
     * Store a session key for a specific peer.
     * Called after ECDH key agreement completes.
     */
    fun setSessionKey(peerNodeId: String, key: ByteArray) {
        require(key.size == 32) { "Session key must be 32 bytes" }
        sessionKeys[peerNodeId] = key
        Log.i(TAG, "Session key set for peer $peerNodeId")
    }

    /**
     * Get the session key for a specific peer.
     * Returns null if no session key exists for that peer.
     */
    fun getSessionKey(peerNodeId: String): ByteArray? = sessionKeys[peerNodeId]?.copyOf()

    /**
     * Check if a session key exists for a peer.
     */
    fun hasSessionKey(peerNodeId: String): Boolean = sessionKeys.containsKey(peerNodeId)

    /**
     * Remove session key for a peer (e.g. on disconnect).
     */
    fun clearSessionKey(peerNodeId: String) {
        sessionKeys.remove(peerNodeId)
        pendingHandshakes.remove(peerNodeId)
        Log.i(TAG, "Session key cleared for peer $peerNodeId")
    }

    /**
     * Clear all session keys (e.g. on app reset).
     */
    fun clearAll() {
        sessionKeys.clear()
        pendingHandshakes.clear()
        Log.i(TAG, "All session keys cleared")
    }

    /**
     * Initiate an ECDH handshake with a peer. Returns the ephemeral public key
     * to send in a SESSION_START packet.
     */
    /** Test seam: pretend the broadcast-handshake window has elapsed. */
    @Synchronized
    internal fun expireBroadcastWindowForTest() { broadcastStartedAt = 0L }

    @Synchronized
    fun initiateHandshake(peerNodeId: String): String {
        val now = System.currentTimeMillis()
        if (peerNodeId == "*") {
            val pub = broadcastPub
            if (pub != null && pendingHandshakes.containsKey("*") &&
                now - broadcastStartedAt < BROADCAST_HANDSHAKE_WINDOW_MS
            ) {
                return pub // a broadcast handshake is already in flight: reuse its key
            }
        }
        val (pubB64, priv) = MessageSecurityManager.createEphemeralKeyPairBase64()
        pendingHandshakes[peerNodeId] = priv
        if (peerNodeId == "*") {
            broadcastPub = pubB64
            broadcastStartedAt = now
        }
        return pubB64
    }

    /**
     * Handle a SESSION_START from a peer.
     *
     * @return a [HandshakeResult]: the derived session key (already stored for
     *         this peer) plus, if this node is the responder, the public key it
     *         must send back to complete the handshake (replyPublicKeyB64).
     *         Returns null on failure.
     */
    data class HandshakeResult(
        val sessionKey: ByteArray,
        val replyPublicKeyB64: String? // non-null only when we are the responder
    )

    fun handleHandshake(peerNodeId: String, peerPubKeyB64: String): HandshakeResult? {
        return try {
            val peerPub = Base64Codec.decode(peerPubKeyB64)
            // Broadcast key first: it is always the freshest. A per-peer entry can only be stale.
            val inWindowNow = System.currentTimeMillis() - broadcastStartedAt < BROADCAST_HANDSHAKE_WINDOW_MS
            var pendingPriv = if (inWindowNow) pendingHandshakes["*"] else null
            val perPeer = pendingHandshakes.remove(peerNodeId)
            if (pendingPriv == null) pendingPriv = perPeer
            if (pendingPriv == null) {
                // The initiator broadcasts SESSION_START with peerNodeId="*" before
                // it knows the peer's actual node ID. When the peer replies, the
                // pending private key is stored under "*" — fall back to it so we
                // REUSE our original ephemeral private key. Otherwise we'd generate
                // a fresh keypair here and derive a DIFFERENT shared secret than the
                // responder, causing HMAC/AEAD mismatches (packets silently dropped).
                val inWindow = System.currentTimeMillis() - broadcastStartedAt < BROADCAST_HANDSHAKE_WINDOW_MS
                val broadcastPending = if (inWindow) pendingHandshakes["*"] else pendingHandshakes.remove("*")
                if (broadcastPending != null) {
                    // NOTE: no copy is kept under the peer id. That copy used to survive the
                    // handshake and, on the NEXT handshake with the same peer, shadowed the fresh
                    // key: the two phones then derived different secrets and every packet failed
                    // authentication after a reconnect.
                    pendingPriv = broadcastPending
                }
            }
            if (pendingPriv != null) {
                // We initiated this handshake: derive the shared key, no reply needed.
                val shared = MessageSecurityManager.deriveSharedSessionKey(pendingPriv, peerPub)
                setSessionKey(peerNodeId, shared)
                HandshakeResult(shared, replyPublicKeyB64 = null)
            } else {
                // We are the responder (no pending outbound key): generate our own
                // ephemeral key, derive the shared key, return our public key for reply.
                val (ourPubB64, ourPriv) = MessageSecurityManager.createEphemeralKeyPairBase64()
                val shared = MessageSecurityManager.deriveSharedSessionKey(ourPriv, peerPub)
                setSessionKey(peerNodeId, shared)
                HandshakeResult(shared, replyPublicKeyB64 = ourPubB64)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed with peer $peerNodeId", e)
            null
        }
    }

    /**
     * Encrypt a payload for a specific peer using that peer's session key.
     */
    fun encryptForPeer(peerNodeId: String, plainText: String, aad: ByteArray = ByteArray(0)): String {
        val key = sessionKeys[peerNodeId]
            ?: throw SecurityException("No session key for peer $peerNodeId")
        return MessageSecurityManager.encryptPayload(plainText, key, aad)
    }

    /**
     * Decrypt a payload from a specific peer using that peer's session key.
     */
    fun decryptFromPeer(peerNodeId: String, cipherText: String, aad: ByteArray = ByteArray(0)): String {
        val key = sessionKeys[peerNodeId]
            ?: throw SecurityException("No session key for peer $peerNodeId")
        return MessageSecurityManager.decryptPayload(cipherText, key, aad)
    }

    /**
     * Compute HMAC for a peer's session key (for packet authentication).
     */
    fun computeHmacForPeer(peerNodeId: String, data: ByteArray): ByteArray {
        val key = sessionKeys[peerNodeId]
            ?: throw SecurityException("No session key for peer $peerNodeId")
        return MessageSecurityManager.computeHmac(data, key)
    }

    /**
     * Get the number of active sessions.
     */
    fun activeSessionCount(): Int = sessionKeys.size

    /**
     * Get all peer IDs with active sessions.
     */
    fun activePeerIds(): Set<String> = sessionKeys.keys.toSet()
}
