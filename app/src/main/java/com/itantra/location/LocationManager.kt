package com.itantra.location

/**
 * Location quality/confidence, used instead of fabricating coordinates.
 * A node's location is UNKNOWN unless a real source provides it.
 */
enum class LocationStatus {
    UNKNOWN,       // no location data available
    APPROXIMATE,   // coarse accuracy (e.g. relay anchor, Wifi/BLE RSSI)
    LAST_KNOWN,    // previously-acquired location, now stale
    ESTIMATED,     // inferred from nearby anchors/proximity
    EXACT          // fresh high-accuracy fix (e.g. GNSS)
}

data class NodeLocation(
    val nodeId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    val source: String,          // "GNSS", "WIFI_RTT", "BLE_RSSI", "RELAY_ANCHOR", "LAST_KNOWN"
    val status: LocationStatus,
    val confidence: Float,       // 0..1
    val expiryMs: Long
) {
    val isFresh: Boolean get() = !isExpired()
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now - timestampMs > expiryMs
}

/**
 * Offline network-aware location with privacy.
 *
 * - Does NOT depend solely on GPS.
 * - Does NOT fabricate coordinates when location is unavailable — reports UNKNOWN.
 * - Advertised locations are coarse, accuracy-bounded, and expiry-limited to
 *   avoid permanent movement history.
 */
class LocationManager(
    private val myNodeId: String,
    private val privacyRadiusMeters: Float = 50f,
    private val defaultExpiryMs: Long = 600_000L  // 10 min
) {

    private val knownLocations = mutableMapOf<String, NodeLocation>()
    var selfLocation: NodeLocation? = null

    /**
     * Record this node's own fresh location from a real source (GNSS/RTT/etc).
     * Coordinates are only trusted if they come from a genuine hardware/API source.
     */
    fun updateSelf(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        source: String,
        confidence: Float
    ) {
        selfLocation = NodeLocation(
            nodeId = myNodeId,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            timestampMs = System.currentTimeMillis(),
            source = source,
            status = LocationStatus.EXACT,
            confidence = confidence,
            expiryMs = defaultExpiryMs
        )
        knownLocations[myNodeId] = selfLocation!!
    }

    /** Mark location unknown (privacy: expires -> UNKNOWN). */
    fun clearSelf() {
        selfLocation = null
    }

    /**
     * Set an approximate location for this node (e.g. a relay anchor, Wifi-RTT
     * reading). Accuracy is coarse and expiry-limited by design.
     */
    fun updateSelfApproximate(latitude: Double, longitude: Double, accuracyMeters: Float) {
        selfLocation = NodeLocation(
            nodeId = myNodeId,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = maxOf(accuracyMeters, privacyRadiusMeters),
            timestampMs = System.currentTimeMillis(),
            source = "RELAY_ANCHOR",
            status = LocationStatus.APPROXIMATE,
            confidence = 0.6f,
            expiryMs = defaultExpiryMs
        )
        knownLocations[myNodeId] = selfLocation!!
    }

    /** Import a location advertisement received from another node. */
    fun importRemote(location: NodeLocation) {
        if (location.isExpired()) return
        knownLocations[location.nodeId] = location
    }

    fun getLocation(nodeId: String): NodeLocation? {
        val loc = knownLocations[nodeId] ?: return null
        if (loc.isExpired()) {
            knownLocations.remove(nodeId)
            return null
        }
        return loc
    }

    fun getKnownLocations(): List<NodeLocation> = knownLocations.values.toList()

    /**
     * Build a privacy-preserving LOCATION_UPDATE advertisement.
     * Always coarse + expiry-limited; never exposes raw precision to the network.
     */
    fun buildLocationUpdate(): com.itantra.protocol.TextPacket? {
        val loc = selfLocation ?: return null
        // Round to ~privacyRadius to avoid broadcasting precise coordinates.
        val coarseLat = roundTo(loc.latitude, privacyRadiusMeters)
        val coarseLon = roundTo(loc.longitude, privacyRadiusMeters)
        return com.itantra.protocol.TextPacket(
            senderId = myNodeId,
            recipientId = "*",
            type = com.itantra.protocol.PacketType.LOCATION_UPDATE,
            language = "en",
            text = "LOC:$coarseLat,$coarseLon:${loc.accuracyMeters}:${loc.source}:${loc.expiryMs}",
            ttlMs = 120_000L,
            maxHops = 3
        )
    }

    /** Parse a LOCATION_UPDATE advertisement into a coarse NodeLocation. */
    fun parseLocationUpdate(senderId: String, text: String): NodeLocation? {
        return try {
            val body = text.removePrefix("LOC:")
            val parts = body.split(":")
            if (parts.size < 4) return null
            val coords = parts[0].split(",")
            val lat = coords[0].toDouble()
            val lon = coords[1].toDouble()
            val accuracy = parts[1].toFloat().coerceAtLeast(privacyRadiusMeters)
            val source = parts[2]
            val expiry = parts.getOrNull(3)?.toLongOrNull() ?: defaultExpiryMs
            NodeLocation(
                nodeId = senderId,
                latitude = lat,
                longitude = lon,
                accuracyMeters = accuracy,
                timestampMs = System.currentTimeMillis(),
                source = source,
                status = LocationStatus.APPROXIMATE,
                confidence = 0.6f,
                expiryMs = expiry
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Round a coordinate to a coarse grid cell approximating the privacy radius. */
    private fun roundTo(value: Double, meters: Float): Double {
        // ~111,320 m per degree lat; use nearest ~50m cell.
        val degrees = (meters / 111320.0)
        return Math.round(value / degrees) * degrees
    }
}
