package com.itantra.transport

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Pure (JVM-testable) pieces of the BLE link: identifiers, stream framing, chunking and
 * the rule that decides which of two phones opens the connection.
 *
 * A BLE GATT characteristic write carries at most (MTU - 3) bytes, so a packet frame is
 * split into chunks on the way out and re-assembled on the way in. The frame layout is
 * the same length-prefixed layout the Bluetooth-Classic and Wi-Fi Direct transports use
 * (4-byte big-endian length, then the encoded packet), so the codec and the per-hop
 * security are untouched.
 */
object BleProtocol {
    /** Service every iTantra phone advertises; scanners filter on it. */
    val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

    /** Central -> peripheral: the central WRITES packet chunks here. */
    val RX_UUID: UUID = UUID.fromString("8ce255c1-200a-11e0-ac64-0800200c9a66")

    /** Peripheral -> central: the peripheral NOTIFIES packet chunks here. */
    val TX_UUID: UUID = UUID.fromString("8ce255c2-200a-11e0-ac64-0800200c9a66")

    /** Client Characteristic Configuration Descriptor (standard). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Manufacturer-data id carrying the node hint in the advertisement. */
    const val COMPANY_ID = 0xFFFF

    /** Default ATT MTU before negotiation; usable payload is MTU - 3. */
    const val DEFAULT_MTU = 23
    const val REQUESTED_MTU = 512

    /** Same sanity bound the other transports use for one frame. */
    const val MAX_FRAME_BYTES = 1_000_000

    /** If the phone that should NOT initiate has seen a peer this long, it connects anyway. */
    const val FALLBACK_INITIATE_MS = 6_000L

    /**
     * 3-byte hint for a node id such as "ITN-841D61", small enough to ride in the
     * advertisement. Lets two phones decide who connects without a round trip.
     */
    fun nodeHint(nodeId: String): ByteArray {
        val hex = nodeId.substringAfter('-', "")
        if (hex.length >= 6 && hex.take(6).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return ByteArray(3) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
        return MessageDigest.getInstance("SHA-256").digest(nodeId.toByteArray()).copyOf(3)
    }

    private fun hintValue(h: ByteArray): Int =
        ((h[0].toInt() and 0xFF) shl 16) or ((h[1].toInt() and 0xFF) shl 8) or (h[2].toInt() and 0xFF)

    /**
     * Whether THIS phone should open the connection to a phone it just saw advertising.
     * Both phones scan and advertise, so without a rule they would each connect to the other
     * and end up with two parallel links. The phone with the smaller hint connects; the other
     * waits, but connects anyway after [FALLBACK_INITIATE_MS] in case its peer is not scanning.
     */
    fun shouldInitiate(self: ByteArray, peer: ByteArray?, peerSeenForMs: Long): Boolean {
        // A peer with no readable hint (older build, or a partial advertisement): give it a
        // chance to connect to us first, then connect anyway.
        if (peer == null || peer.size < 3) return peerSeenForMs >= FALLBACK_INITIATE_MS
        val s = hintValue(self)
        val p = hintValue(peer)
        if (s == p) return true
        return s < p || peerSeenForMs >= FALLBACK_INITIATE_MS
    }

    /**
     * When two links to the same peer exist, both phones must close the SAME one. The link
     * opened by the smaller-hint phone is the canonical one, so a link is canonical exactly
     * when "we opened it" agrees with "our hint is the smaller". Each phone evaluates this from
     * its own side and reaches the same verdict.
     */
    fun isCanonicalLink(self: ByteArray, peer: ByteArray, weOpenedIt: Boolean): Boolean {
        val s = hintValue(self)
        val p = hintValue(peer)
        if (s == p) return weOpenedIt
        return weOpenedIt == (s < p)
    }

    /** [4-byte big-endian length][payload]. */
    fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = (payload.size ushr 24).toByte()
        out[1] = (payload.size ushr 16).toByte()
        out[2] = (payload.size ushr 8).toByte()
        out[3] = payload.size.toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    /** Split [data] into pieces of at most [chunkSize] bytes (never empty for non-empty data). */
    fun chunk(data: ByteArray, chunkSize: Int): List<ByteArray> {
        require(chunkSize > 0) { "chunkSize must be positive" }
        if (data.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>((data.size + chunkSize - 1) / chunkSize)
        var i = 0
        while (i < data.size) {
            val end = minOf(i + chunkSize, data.size)
            out.add(data.copyOfRange(i, end))
            i = end
        }
        return out
    }

    /** Usable bytes per write/notification for a negotiated ATT MTU. */
    fun payloadSize(mtu: Int): Int = (mtu - 3).coerceAtLeast(DEFAULT_MTU - 3)
}

/**
 * Re-assembles length-prefixed frames from arbitrary chunk boundaries. A length outside
 * 1..[BleProtocol.MAX_FRAME_BYTES] means the stream is corrupt: the buffer is dropped and
 * [feed] reports it, so one bad write cannot wedge the link or grow memory without bound.
 */
class FrameAssembler(private val maxFrame: Int = BleProtocol.MAX_FRAME_BYTES) {
    private val buf = ByteArrayOutputStream()

    /** True after the last [feed] found a corrupt length and reset. */
    var lastFeedCorrupt: Boolean = false
        private set

    @Synchronized
    fun feed(bytes: ByteArray): List<ByteArray> {
        lastFeedCorrupt = false
        buf.write(bytes)
        val frames = ArrayList<ByteArray>()
        var data = buf.toByteArray()
        var pos = 0
        while (data.size - pos >= 4) {
            val len = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            if (len < 1 || len > maxFrame) {
                buf.reset()
                lastFeedCorrupt = true
                return frames
            }
            if (data.size - pos - 4 < len) break
            frames.add(data.copyOfRange(pos + 4, pos + 4 + len))
            pos += 4 + len
        }
        buf.reset()
        if (pos < data.size) buf.write(data, pos, data.size - pos)
        return frames
    }

    @Synchronized
    fun pendingBytes(): Int = buf.size()
}
