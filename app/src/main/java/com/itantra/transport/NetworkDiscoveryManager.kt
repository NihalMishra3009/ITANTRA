package com.itantra.transport

import android.util.Log
import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Routing table entry. Multiple entries may exist for the same destination
 * (one per candidate next hop); cost-based selection picks the best.
 */
data class RouteEntry(
    val destinationId: String,   // node ID, group name, or zone
    val destinationMode: String, // INDIVIDUAL / GROUP / ZONE
    var nextHopId: String,       // THE NEIGHBOR that advertised this route (directly reachable)
    var advertisedById: String,  // who told us about this route
    var hopCount: Int,           // distance to destination THROUGH nextHopId
    var lastSeenMs: Long,
    var routeConfidence: Float,  // 0..1
    var linkQuality: Float,      // 0..1 (of the next-hop link)
    var deliverySuccess: Float,  // 0..1 smoothed delivery success for this route
    var failureCount: Int,
    var expiryMs: Long,
    var cost: Double             // computed route cost (lowest = best)
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
 * routing table with cost-based next-hop selection.
 *
 * KEY ROUTING RULE: for every advertised route, the NEXT HOP is the NEIGHBOR
 * THAT ADVERTISED IT (`packet.senderId`), and the new hop count is
 * `advertisedHopCount + 1`. A node never installs a route whose next hop is a
 * destination it cannot reach directly.
 *
 * cost = hopWeight*hopCount + stalePenalty + linkPenalty + failurePenalty
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
        private const val MAX_HOP_COUNT = 12

        // Cost weights
        private const val HOP_WEIGHT = 10.0
        private const val STALE_MS = 60000L
        private const val STALE_WEIGHT = 2.0
        private const val LINK_WEIGHT = 5.0
        private const val FAIL_WEIGHT = 8.0
    }

    val neighbors = ConcurrentHashMap<String, Neighbor>()
    val routes = ConcurrentHashMap<String, MutableList<RouteEntry>>()

    private val deliveryFailures = ConcurrentHashMap<String, Int>()

    // --- Neighbor ingress ---------------------------------------------------

    /** Process an inbound NODE_HELLO / NODE_ANNOUNCE / route / location packet. */
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
                // NODE_ANNOUNCE carries a route snapshot from the advertiser.
                if (packet.type == PacketType.NODE_ANNOUNCE) {
                    importAdvertisedRoutes(packet)
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

    /**
     * Install/refresh a route to `destId` via neighbor `viaNeighbor` at distance
     * `hops`. NEXT HOP is always the advertising neighbor; we never adopt a
     * remote next-hop we cannot reach.
     */
    fun addOrRefreshRoute(destId: String, mode: String, viaNeighbor: String, hops: Int) {
        if (destId.isEmpty() || destId == myNodeId) return          // reject self / empty
        if (hops > MAX_HOP_COUNT) return                            // reject excessive hops
        if (!neighbors.containsKey(viaNeighbor) && viaNeighbor != myNodeId) {
            // Cannot install a route through a neighbor we have not discovered.
            Log.w(TAG, "Ignoring route to $destId via unknown neighbor $viaNeighbor")
            return
        }
        val now = System.currentTimeMillis()
        val routeList = routes.getOrPut(destId) { mutableListOf() }

        val cost = computeCost(now, hops, linkQualityOf(viaNeighbor), deliveryFailures[destId] ?: 0)
        val existing = routeList.firstOrNull { it.nextHopId == viaNeighbor }
        if (existing == null) {
            routeList.add(
                RouteEntry(
                    destinationId = destId,
                    destinationMode = mode,
                    nextHopId = viaNeighbor,
                    advertisedById = viaNeighbor,
                    hopCount = hops,
                    lastSeenMs = now,
                    routeConfidence = computeConfidence(now, hops, 0),
                    linkQuality = linkQualityOf(viaNeighbor),
                    deliverySuccess = 1.0f,
                    failureCount = 0,
                    expiryMs = now + ROUTE_DEFAULT_TTL_MS,
                    cost = cost
                )
            )
            Log.i(TAG, "Route installed: $destId via $viaNeighbor (${hops} hops, cost=${"%.1f".format(cost)})")
        } else {
            // Refresh if newer/lower cost. Chain-wise equivalent route retains.
            if (hops <= existing.hopCount) {
                val prev = existing
                routeList[routeList.indexOf(existing)] = existing.copy(
                    hopCount = hops,
                    lastSeenMs = now,
                    advertisedById = viaNeighbor,
                    routeConfidence = computeConfidence(now, hops, 0),
                    cost = cost,
                    expiryMs = now + ROUTE_DEFAULT_TTL_MS
                )
                Log.i(TAG, "Route refreshed to $destId via $viaNeighbor (${hops} hops)")
            }
        }
    }

    /**
     * Best next-hop for a destination using cost-based selection across all
     * candidate routes. Returns the next-hop node ID, or null if no valid route.
     */
    fun bestNextHop(destId: String): String? {
        val route = bestRoute(destId) ?: return null
        return route.nextHopId
    }

    /** Best route (lowest cost, non-expired) to a destination. Deterministic tie-break for debugging. */
    fun bestRoute(destId: String): RouteEntry? {
        val now = System.currentTimeMillis()
        val list = routes[destId] ?: return null
        // Remove expired; recompute cost (stale penalty grows with time).
        list.removeIf { it.expiryMs < now }
        if (list.isEmpty()) {
            routes.remove(destId)
            return null
        }
        val candidates = list.sortedWith(
            compareBy<RouteEntry> { computeCost(now, it.hopCount, it.linkQuality, it.failureCount) }
                .thenBy { it.nextHopId } // deterministic tie-break
        )
        return candidates.firstOrNull()
    }

    fun markDeliveryFailure(destId: String, nextHop: String? = null) {
        deliveryFailures.merge(destId, 1, Int::plus)
        if (nextHop != null) {
            routes[destId]?.removeIf { it.nextHopId == nextHop }
        }
        // Decay failure penalty over time.
        if (deliveryFailures.size > 200) {
            deliveryFailures.entries.removeIf { System.currentTimeMillis() - it.value * 60000L > 0L }
        }
    }

    fun markDeliverySuccess(destId: String, nextHop: String?) {
        deliveryFailures[destId]?.let {
            if (it > 0) deliveryFailures[destId] = it - 1
        }
    }

    /** True if we have a direct, live link to `nodeId`. */
    fun isNeighbor(nodeId: String): Boolean = neighbors.containsKey(nodeId)

    private fun handleRouteRequest(packet: TextPacket) {
        val requestedDest = packet.text.removePrefix("ROUTE:")
        val route = bestRoute(requestedDest)
        if (route != null) {
            val response = TextPacket(
                senderId = myNodeId,
                recipientId = packet.senderId,
                type = PacketType.ROUTE_RESPONSE,
                language = "en",
                text = "ROUTE:$requestedDest:${route.nextHopId}:${route.hopCount}",
                maxHops = 4
            )
            onRouteResponseReady?.invoke(response)
        }
    }

    /**
     * Handle a ROUTE_RESPONSE. `packet.senderId` is the advertiser; the payload
     * is 'ROUTE:<dest>:<remoteNextHop>:<hops>'. Per the core rule, our next hop
     * to <dest> is packet.senderId and the total distance is hops+1. The
     * advertiser's own nextHop is NOT usable by us.
     */
    private fun handleRouteResponse(packet: TextPacket) {
        val parts = packet.text.removePrefix("ROUTE:").split(":")
        if (parts.size >= 3) {
            val dest = parts[0]
            val remoteHops = parts.getOrNull(2)?.toIntOrNull() ?: 1
            val totalHops = remoteHops + 1
            addOrRefreshRoute(dest, "INDIVIDUAL", packet.senderId, totalHops)
            onRouteDiscovered?.invoke(packet.senderId, dest, packet.senderId, totalHops)
        }
    }

    /**
     * Handle a ROUTE_UPDATE. Payload 'ROUTEUP:<dest>:<remoteNextHop>:<hops>'.
     * Our next hop is the advertiser; distance = hops+1.
     */
    private fun handleRouteUpdate(packet: TextPacket) {
        val parts = packet.text.removePrefix("ROUTEUP:").split(":")
        if (parts.size >= 3) {
            val dest = parts[0]
            val remoteHops = parts.getOrNull(2)?.toIntOrNull() ?: 1
            addOrRefreshRoute(dest, "INDIVIDUAL", packet.senderId, remoteHops + 1)
        }
    }

    /**
     * Parse the route snapshot embedded in a NODE_ANNOUNCE and teach routes to
     * remote destinations, keyed by the ADVERTISER as our next hop.
     */
    private fun importAdvertisedRoutes(packet: TextPacket) {
        val routesMarker = packet.text.substringAfter("|ROUTES:", missingDelimiterValue = "")
        if (routesMarker.isEmpty()) return
        // routesMarker: "dest:nextHop:hops;dest2:nextHop2:hops2;..."
        for (routeStr in routesMarker.split(";")) {
            val parts = routeStr.split(":")
            if (parts.size >= 3) {
                val dest = parts[0]
                val remoteHops = parts[2].toIntOrNull() ?: 1
                addOrRefreshRoute(dest, "INDIVIDUAL", packet.senderId, remoteHops + 1)
            }
        }
    }

    private fun computeCost(now: Long, hops: Int, linkQuality: Float, failures: Int): Double {
        val staleMs = (now - lastRouteSeen()).coerceAtLeast(0L)
        val stalePenalty = if (staleMs > STALE_MS) STALE_WEIGHT else 0.0
        val linkPenalty = LINK_WEIGHT * (1.0 - linkQuality.coerceIn(0.0f, 1.0f))
        val failPenalty = FAIL_WEIGHT * failures.coerceAtMost(4)
        return HOP_WEIGHT * hops + stalePenalty + linkPenalty + failPenalty
    }

    private var lastRouteSeenMs: Long = System.currentTimeMillis()

    private fun lastRouteSeen(): Long = lastRouteSeenMs.also { lastRouteSeenMs = System.currentTimeMillis() }

    private fun linkQualityOf(nodeId: String): Float = neighbors[nodeId]?.linkQuality ?: 0.5f

    private fun computeConfidence(now: Long, hops: Int, failures: Int): Float {
        val recency = 0.4f
        val hopPenalty = 0.1f * hops
        val failPenalty = 0.2f * failures.coerceAtMost(4)
        return (recency - hopPenalty - failPenalty).coerceIn(0.05f, 0.95f)
    }

    /** Build the periodic NODE_ANNOUNCE that also carries a snapshot of my routes. */
    fun buildAnnounce(role: String, displayName: String): TextPacket {
        val routeSnapshot = bestRoutesSnapshot().take(8)
            .joinToString(";") { "${it.destinationId}:${it.nextHopId}:${it.hopCount}" }
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

    /** Best route per destination (for announcement and UI). */
    fun bestRoutesSnapshot(): List<RouteEntry> {
        return routes.values.mapNotNull { bestRoute(it.firstOrNull()?.destinationId ?: return@mapNotNull null) }
    }

    fun getAllRoutes(): List<RouteEntry> = routes.values.flatten()

    // --- Callback hooks (wired by the transport/orchestrator) --------------

    var onRouteResponseReady: ((TextPacket) -> Unit)? = null
    var onRouteDiscovered: ((viaNodeId: String, dest: String, nextHop: String, hops: Int) -> Unit)? = null

    // --- Small parsers ------------------------------------------------------

    private fun parseRole(packetText: String): String {
        val parts = packetText.split("|")
        return parts.getOrNull(1) ?: "DEFAULT"
    }

    private fun parseDisplayName(packetText: String): String? {
        val parts = packetText.split("|")
        return parts.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    fun pruneStale(now: Long = System.currentTimeMillis()) {
        routes.entries.removeIf { entry ->
            entry.value.removeIf { it.expiryMs < now }
            entry.value.isEmpty()
        }
        neighbors.entries.removeIf { now - it.value.lastSeenMs > HELLO_TTL_MS * 5 }
    }
}
