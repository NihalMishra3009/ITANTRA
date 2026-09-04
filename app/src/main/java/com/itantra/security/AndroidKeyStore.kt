package com.itantra.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed key storage for iTantra node identity.
 *
 * The persistent private key is stored in the Android Keystore as a
 * non-exportable EC key (API 23+). This prevents extraction via
 * SharedPreferences and satisfies "private key must not be stored as
 * plaintext".
 *
 * Public key and Node ID remain in SharedPreferences (not secret).
 *
 * Compatibility: on devices where Keystore EC keys are unavailable, falls
 * back to the previous SharedPreferences storage so existing identities
 * keep working (migration-safe).
 */
object AndroidKeyStore {

    private const val TAG = "AndroidKeyStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val EC_KEY_ALIAS = "itantra_node_identity_ec"
    private const val PREFS = "itantra_node_key_prefs"
    private const val KEY_PUB = "pub_key_b64"

    /**
     * Ensure an EC keypair exists in the Android Keystore for this node.
     * Returns the public key as base64 (stable across restarts).
     *
     * If Keystore is unavailable, returns null (caller falls back to
     * SharedPreferences keypair in NodeIdentity).
     */
    fun provisionOrLoadPublicKey(context: Context): String? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE)
            keyStore.load(null)

            val existing: java.security.Key? = keyStore.getKey(EC_KEY_ALIAS, null)
            if (existing != null) {
                val pub = keyStore.getCertificate(EC_KEY_ALIAS)?.publicKey
                    ?: return null
                return Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
            }

            // Generate a fresh non-exportable EC P-256 keypair in the Keystore
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                KEYSTORE
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    EC_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(
                        java.security.spec.ECGenParameterSpec("secp256r1")
                    )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setKeySize(256)
                    .build()
            )
            val pair = generator.generateKeyPair()
            Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Keystore EC key unavailable, will fall back: ${e.message}")
            null
        }
    }

    /**
     * Load the Keystore private key (non-exportable). Returns null if the
     * Keystore key does not exist or is unavailable.
     */
    fun getPrivateKey(): PrivateKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE)
            keyStore.load(null)
            keyStore.getKey(EC_KEY_ALIAS, null) as? PrivateKey
        } catch (e: Exception) {
            Log.w(TAG, "Cannot load Keystore private key: ${e.message}")
            null
        }
    }

    /**
     * Sign a message with the Keystore identity key (future P2P trust/signing).
     * Returns base64 signature or null.
     */
    fun sign(plainText: String): String? {
        return try {
            val key = getPrivateKey() ?: return null
            val signature = java.security.Signature.getInstance("SHA256withECDSA")
            signature.initSign(key)
            signature.update(plainText.toByteArray())
            val sigBytes = signature.sign()
            Base64.encodeToString(sigBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Signing failed: ${e.message}")
            null
        }
    }

    /**
     * Delete the Keystore key (for testing/reset).
     */
    fun reset(context: Context) {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(EC_KEY_ALIAS)
            Log.i(TAG, "Keystore identity key deleted")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reset Keystore key: ${e.message}")
        }
    }
}
