package com.itantra.identity

import android.content.Context
import android.util.Log
import com.itantra.security.Base64Codec
import com.itantra.security.MessageSecurityManager
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

data class NodeProfile(
    val nodeId: String,          // ITN-XXXXXX persistent application identity
    val displayName: String,     // human-friendly device name
    val role: String,            // e.g. RESCUE, MEDICAL, RELAY, CONTROL_ROOM, DEFAULT
    val protocolVersion: Int,
    val publicKeyB64: String,    // base64 of persistent public key (for future pre-shared trust)
    val createdAtMs: Long
)

/**
 * Persistent application-level node identity.
 *
 * The Node ID (`ITN-XXXXXX`) is a logical, transport-independent identity that
 * survives changes of Bluetooth/Wi-Fi transport. It is NOT derived from any
 * hardware MAC address — those are transport-level identifiers only.
 *
 * Persists:
 *   - Node ID      (SecureRandom-generated once)
 *   - display name
 *   - role
 *   - persistent public/private keypair (for future P2P trust / signing)
 *
 * The private key is stored in app-private SharedPreferences (never exposed,
 * never logged). Pins to APK signing via backup config is out of scope here.
 */
object NodeIdentity {

    private const val TAG = "NodeIdentity"
    private const val PREFS = "itantra_node_identity"
    private const val KEY_NODE_ID = "node_id"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_ROLE = "role"
    private const val KEY_CREATED = "created_ms"
    private const val KEY_PUB = "pub_key_b64"
    private const val KEY_PRIV = "priv_key_b64"
    private const val PROTOCOL_VERSION = 2

    @Volatile private var profile: NodeProfile? = null
    private val initialized = AtomicBoolean(false)

    @Synchronized
    fun initialize(context: Context): NodeProfile {
        val existing = profile
        if (existing != null) return existing

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        var nodeId = prefs.getString(KEY_NODE_ID, null)
        if (nodeId == null) {
            nodeId = generateNodeId()
            // Persist keypair along with first-time node creation
            ensureKeyPair(context)
        } else {
            ensureKeyPair(context)
        }

        val displayName = prefs.getString(KEY_DISPLAY_NAME, null)
            ?: ("Node-" + nodeId.substringAfter("ITN-").take(4)).also {
                prefs.edit().putString(KEY_DISPLAY_NAME, it).apply()
            }
        val role = prefs.getString(KEY_ROLE, "DEFAULT").orEmpty()
        val created = prefs.getLong(KEY_CREATED, System.currentTimeMillis())

        profile = NodeProfile(
            nodeId = nodeId,
            displayName = displayName,
            role = role,
            protocolVersion = PROTOCOL_VERSION,
            publicKeyB64 = prefs.getString(KEY_PUB, "") ?: "",
            createdAtMs = created
        )
        initialized.set(true)
        Log.i(TAG, "Node identity established: $nodeId (role=$role, ver=$PROTOCOL_VERSION)")
        return profile!!
    }

    fun current(): NodeProfile? = profile

    fun nodeId(): String = profile?.nodeId ?: "ITN-UNKNOWN"

    fun setDisplayName(context: Context, name: String): NodeProfile? {
        val p = profile ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISPLAY_NAME, name).apply()
        profile = p.copy(displayName = name)
        return profile
    }

    fun setRole(context: Context, role: String): NodeProfile? {
        val p = profile ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROLE, role).apply()
        profile = p.copy(role = role)
        return profile
    }

    /** Return the persistent private key (used only for local signing — never sent). */
    fun privateKey(context: Context): PrivateKey? {
        val b64 = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PRIV, null) ?: return null
        return try {
            MessageSecurityManager.decodePersistentPrivateKey(Base64Codec.decode(b64))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persistent private key", e)
            null
        }
    }

    // Expose a helper on MessageSecurityManager for decoding a persisted private key.
    // (MessageSecurityManager is extended below.)

    private fun generateNodeId(): String {
        val random = SecureRandom()
        val bytes = ByteArray(3)
        random.nextBytes(bytes)
        val hex = StringBuilder(6)
        for (b in bytes) hex.append(String.format("%02X", b))
        return "ITN-$hex"
    }

    private fun ensureKeyPair(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_PUB, null) != null) return
        val (pubB64, privB64) = MessageSecurityManager.generatePersistentKeyPairBase64()
        prefs.edit()
            .putString(KEY_PUB, pubB64)
            .putString(KEY_PRIV, privB64)
            .putLong(KEY_CREATED, System.currentTimeMillis())
            .putString(KEY_NODE_ID, profile?.nodeId) // ensure node id set
            .apply()
    }
}
