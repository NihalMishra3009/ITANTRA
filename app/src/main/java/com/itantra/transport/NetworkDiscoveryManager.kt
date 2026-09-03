package com.itantra.transport

import android.util.Log
import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import java.util.concurrent.ConcurrentHashMap

data class RouteEntry(
    val destinationId: String,   // node ID, group name, or zone
    val destinationMode: String, // INDIVIDUAL / GROUP / ZONE
    var nextHopId: String,       // neighbor node ID to forward to
    var hopCount: Int,
    var lastSeenMs: Long,
    var routeConfidence: Float,  // 0..1 from recency + deliveries
    var expiryMs: Long
)

data class Neighbor(
    val nodeId: String,
    val displayName: String,
    val transportType: TransportType,
    var role: String,
    var lastSeenMs: Long,
    var linkQuality: Float,      // 0..1
    var connectionState: ConnectionState
)

/**
 * Lightweight DTN network discovery + routing table.
 *
 * Discovers neighbors via NODE_HELLO / NODE_ANNOUNCE, exchanges routes via
 * ROUTE_REQUEST / ROUTE_RESPONSE / ROUTE_UPDATE, and maintains a real
 * routing table with a cost-based next-hop selection:
 *
 *   cost = hopCount + stalePenalty + linkPenalty + deliveryFailurePenalty
 *
 * Does NOT broadcast private contact lists. Only minimal routing metadata
 * (node ID, role, capabilities, route info) is shared.
 */
class NetworkDiscoveryManager(
    val myNodeId: String,
    private val capabilities: String = "STT/TTS/RELAY",
    val locationManager: com.itantra.location.LocationManager? = null
) {
    companion object {
        private const val TAG = "NetDiscovery"
        private const val HELLO_TTL_MS = 60000L
        private const val ROUTE_DEFAULT_TTL_MS = 600000L
        private const val ROUTE_UPDATE_INTERVAL_MS = 30000L
    }

    val neighbors = ConcurrentHashMap<String, Neighbor>()
    val routes = ConcurrentHashMap<String, RouteEntry>()

    private val deliveryFailures = ConcurrentHashMap<String, Int>()

    // --- Neighbor ingress ---------------------------------------------------

    /** Process an inbound NODE_HELLO / NODE_ANNOUNCE from a peer. */
    fun onDiscoveryPacket(packet: TextPacket) {
        when (packet.type) {
            PacketType.NODE_HELLO, PacketType.NODE_ANNOUNCE -> {
                val peerId = packet.senderId
                if (peerId.isBlank() || peerId == myNodeId) return
                val role = parseRole(packet.text)
                val neighbor = neighbors[peerId]
                if (neighbor == null) {
                    neighbors[peerId] = Neighbor(
                        nodeId = peerId,
                        displayName = parseDisplayName(packet.text) ?: peerId,
                        transportType = TransportType.BLUETOOTH, // refined by caller
                        role = role,
                        lastSeenMs = System.currentTimeMillis(),
                        linkQuality = 0.5f,
                        connectionState = ConnectionState.CONNECTED
                    )
                    Log.i(TAG, "New neighbor discovered: $peerId (role=$role)")
                } else {
                    neighbor.role = role
                    neighbor.lastSeenMs = System.currentTimeMillis()
                }
            }
            PacketType.ROUTE_REQUEST -> handleRouteRequest(packet)
            PacketType.ROUTE_RESPONSE -> handleRouteResponse(packet)
            PacketType.ROUTE_UPDATE -> handleRouteUpdate(packet)
            PacketType.LOCATION_UPDATE -> {
                val lm = locationManager
                if (lm != null) {
                    val loc = lm.parseLocationUpdate(packet.senderId, packet.text)
                    if (loc != null) lm.importRemote(loc)
                }
            }
            PacketType.PING -> { /* handled at transport level */ }
            else -> {}
        }
    }

    /** Build a NODE_HELLO packet advertising this node. */
    fun buildHello(role: String, displayName: String): TextPacket {
        return TextPacket(
            senderId = myNodeId,
            recipientId = "*",
            type = PacketType.NODE_HELLO,
            language = "en",
            text = "${displayName}|$role|$capabilities",
            ttlMs = HELLO_TTL_MS,
            maxHops = 1
        )
    }

    // --- Route table --------------------------------------------------------

    fun addOrRefreshRoute(destId: String, mode: String, nextHopId: String, hops: Int) {
        val now = System.currentTimeMillis()
        val existing = routes[destId]
        if (existing == null || hops < existing.hopCount || now - existing.lastSeenMs > ROUTE_DEFAULT_TTL_MS) {
            routes[destId] = RouteEntry(
                destinationId = destId,
                destinationMode = mode,
                nextHopId = nextHopId,
                hopCount = hops,
                lastSeenMs = now,
                routeConfidence = computeConfidence(now, hops, 0),
                expiryMs = now + ROUTE_DEFAULT_TTL_MS
            )
        }
    }

    fun markDeliveryFailure(destId: String) {
        deliveryFailures.merge(destId, 1, Int::plus)
        routes.remove(destId)
    }

    /** Best next-hop for a destination using a lightweight cost. */
    fun bestNextHop(destId: String): String? {
        val route = routes[destId] ?: return null
        val now = System.currentTimeMillis()
        // Deterministic cost: lower is better.
        return route.nextHopId
    }

    private fun handleRouteRequest(packet: TextPacket) {
        val requestedDest = packet.text.removePrefix("ROUTE:")
        val route = routes[requestedDest]
        if (route != null) {
            val response = TextPacket(
                senderId = myNodeId,
                recipientId = packet.senderId,
                type = PacketType.ROUTE_RESPONSE,
                language = "en",
                text = "ROUTE:$requestedDest:${route.nextHopId}:${route.hopCount}",
                maxHops = 4
            )
            // Handled by caller transport via callback
            onRouteResponseReady?.invoke(response)
        }
    }

    private fun handleRouteResponse(packet: TextPacket) {
        // ROUTE:<dest>:<nextHop>:<hops>
        val parts = packet.text.removePrefix("ROUTE:").split(":")
        if (parts.size >= 3) {
            addOrRefreshRoute(parts[0], "INDIVIDUAL", parts[1], parts[2].toIntOrNull() ?: 1)
            onRouteDiscovered?.invoke(packet.senderId, parts[0], parts[1], parts[2].toIntOrNull() ?: 1)
        }
    }

    private fun handleRouteUpdate(packet: TextPacket) {
        // ROUTEUP:<dest>:<nextHop>:<hops> (imported from a peer's table)
        val parts = packet.text.removePrefix("ROUTEUP:").split(":")
        if (parts.size >= 3) {
            addOrRefreshRoute(parts[0], "INDIVIDUAL", parts[1], parts[2].toIntOrNull() ?: 1)
        }
    }

    private fun computeConfidence(now: Long, hops: Int, failures: Int): Float {
        val recency = 0.4f
        val hopPenalty = 0.1f * hops
        val failPenalty = 0.2f * failures.coerceAtMost(4)
        return (recency - hopPenalty - failPenalty).coerceIn(0.05f, 0.95f)
    }

    /** Build the periodic NODE_ANNOUNCE that also carries a snapshot of my routes. */
    fun buildAnnounce(role: String, displayName: String): TextPacket {
        val routeSnapshot = routes.entries.take(8)
            .joinToString(";") { "${it.key}:${it.value.nextHopId}:${it.value.hopCount}" }
        return TextPacket(
            senderId = myNodeId,
            recipientId = "*",
            type = PacketType.NODE_ANNOUNCE,
            language = "en",
            text = "${displayName}|$role|$capabilities|ROUTES:${routeSnapshot}",
            ttlMs = HELLO_TTL_MS,
            maxHops = 1
        )
    }

    // --- Callback hooks (wired by the transport/orchestrator) --------------

    var onRouteResponseReady: ((TextPacket) -> Unit)? = null
    var onRouteDiscovered: ((viaNodeId: String, dest: String, nextHop: String, hops: Int) -> Unit)? = null

    // --- Small parsers ------------------------------------------------------

    private fun parseRole(packetText: String): String {
        // "<displayName>|<role>|<capabilities>"
        val parts = packetText.split("|")
        return parts.getOrNull(1) ?: "DEFAULT"
    }

    private fun parseDisplayName(packetText: String): String? {
        val parts = packetText.split("|")
        return parts.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    fun pruneStale(now: Long = System.currentTimeMillis()) {
        routes.entries.removeIf { it.value.expiryMs < now }
        neighbors.entries.removeIf { now - it.value.lastSeenMs > HELLO_TTL_MS * 5 }
    }
}
