package com.itantra.security

import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end transport security for iTantra.
 *
 * Provides modern AEAD (AES-256-GCM) with ephemeral-session key agreement
 * (ECDH P-256) and HKDF-SHA256 key derivation. No hard-coded secrets; a
 * session key is established per peer via a handshake and then used for
 * authenticated encryption (confidentiality + integrity + replay protection
 * via unique nonce + associated data).
 *
 * ECDH P-256 + AES-256-GCM + HKDF-SHA256 are used because they are available
 * through the standard Java Crypto API on Android API 24+ without third-party
 * providers. X25519 (XDH) is not reliably available below API 28, so P-256 is
 * chosen for the widest device compatibility.
 */
object MessageSecurityManager {

    private const val TAG = "MessageSecurityManager"

    private const val AEAD = "AES/GCM/NoPadding"
    private const val KEY_AGREEMENT = "ECDH"
    private const val CURVE = "EC"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val KEY_BYTES = 32 // AES-256

    private val secureRandom = SecureRandom()

    // Current derived session key material (raw 32-byte key)
    private var sessionKeyBytes: ByteArray? = null

    // --- Session key management ---------------------------------------------

    @Synchronized
    fun setSessionKey(key: ByteArray) {
        require(key.size == KEY_BYTES) { "Session key must be $KEY_BYTES bytes" }
        sessionKeyBytes = key
        Log.i(TAG, "Session key established (${KEY_BYTES * 8}-bit)")
    }

    @Synchronized
    fun clearSessionKey() {
        sessionKeyBytes = null
        Log.i(TAG, "Session key cleared")
    }

    @Synchronized
    fun hasSessionKey(): Boolean = sessionKeyBytes != null

    /** Expose the active session key (or null) for wire authentication. */
    @Synchronized
    fun currentSessionKeyOrNull(): ByteArray? = sessionKeyBytes?.copyOf()

    // --- Ephemeral key agreement (ECDH P-256) -------------------------------

    /**
     * Generate this device's ephemeral key pair for a handshake.
     * @return [PublicKey, PrivateKey] in wire-friendly PublicKey export.
     */
    fun generateKeyPair(): Pair<ByteArray, PrivateKey> {
        val gen = KeyPairGenerator.getInstance(CURVE)
        gen.initialize(256)
        val pair: KeyPair = gen.generateKeyPair()
        return pair.public.encoded to pair.private
    }

    /**
     * Derive shared secret between our ephemeral private key and a peer's
     * public key, then expand it via HKDF-SHA256 into a 32-byte session key.
     * @return raw 32-byte session key (feeds [setSessionKey]).
     */
    fun deriveSharedSessionKey(
        privateKey: PrivateKey,
        peerPublicKeyEncoded: ByteArray,
        salt: ByteArray = "iTANTRA_PS26173_HKDF".toByteArray(StandardCharsets.UTF_8)
    ): ByteArray {
        val keyFactory = KeyFactory.getInstance(CURVE)
        val peerPublic: PublicKey = keyFactory.generatePublic(X509EncodedKeySpec(peerPublicKeyEncoded))
        val agreement = KeyAgreement.getInstance(KEY_AGREEMENT)
        agreement.init(privateKey)
        agreement.doPhase(peerPublic, true)
        val sharedSecret = agreement.generateSecret()
        val ikm = sharedSecret
        // HKDF-SHA256 expand
        val hkdfKey = hkdfExtract(salt, ikm)
        return hkdfExpand(hkdfKey, "itTantra-session-key".toByteArray(StandardCharsets.UTF_8), KEY_BYTES)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var t = ByteArray(0)
        val okm = java.io.ByteArrayOutputStream()
        var count = 1
        while (okm.size() < length) {
            mac.update(t)
            mac.update(info)
            mac.update(count.toByte())
            t = mac.doFinal()
            okm.write(t)
            count++
        }
        return okm.toByteArray().copyOf(length)
    }

    // --- AEAD payload encryption (AES-256-GCM) ------------------------------

    /**
     * Encrypt plaintext into base64 string: [nonce(12) || ciphertext || tag].
     * Associated data binds the message so context (e.g. msg id / session epoch)
     * cannot be silently swapped.
     */
    @Synchronized
    fun encryptPayload(plainText: String, secretKey: ByteArray? = null, aad: ByteArray = ByteArray(0)): String {
        if (plainText.isEmpty()) return ""
        val key = keyBytes(secretKey)
        return try {
            val nonce = ByteArray(NONCE_BYTES)
            secureRandom.nextBytes(nonce)

            val cipher = Cipher.getInstance(AEAD)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

            val combined = ByteArray(NONCE_BYTES + ciphertext.size)
            System.arraycopy(nonce, 0, combined, 0, NONCE_BYTES)
            System.arraycopy(ciphertext, 0, combined, NONCE_BYTES, ciphertext.size)
            Base64Codec.encode(combined)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            throw SecurityException("Payload encryption failed", e)
        }
    }

    /**
     * Decrypt payload ciphertext. Throws on tamper / authentication failure —
     * no silent plaintext fallback.
     */
    @Synchronized
    fun decryptPayload(cipherText: String, secretKey: ByteArray? = null, aad: ByteArray = ByteArray(0)): String {
        if (cipherText.isEmpty()) return ""
        val key = keyBytes(secretKey)
        return try {
            val combined = Base64Codec.decode(cipherText)
            if (combined == null || combined.size <= NONCE_BYTES + GCM_TAG_BITS / 8) {
                throw SecurityException("Ciphertext too short")
            }
            val nonce = combined.copyOfRange(0, NONCE_BYTES)
            val ciphertext = combined.copyOfRange(NONCE_BYTES, combined.size)

            val cipher = Cipher.getInstance(AEAD)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(ciphertext)
            String(plain, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed (possible tamper)", e)
            throw SecurityException("Payload decryption failed", e)
        }
    }

    /**
     * HMAC-SHA256 over arbitrary canonical bytes — used for the packet
     * integrity checksum (bound to session key material).
     */
    @Synchronized
    fun computeHmac(data: ByteArray, secretKey: ByteArray? = null): ByteArray {
        val key = keyBytes(secretKey)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun keyBytes(secretKey: ByteArray?): ByteArray {
        if (secretKey != null) {
            require(secretKey.size == KEY_BYTES) { "Key must be $KEY_BYTES bytes" }
            return secretKey
        }
        return sessionKeyBytes
            ?: throw IllegalStateException("No session key set — call setSessionKey() or pass explicit key")
    }
}
