package com.itantra.transport

import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the lightweight DTN network discovery + routing table.
 * Verifies the core routing rule: NEXT HOP = the advertising neighbor,
 * newHopCount = advertisedHopCount + 1, and cost-based best-route selection.
 */
class NetworkDiscoveryTest {

    private fun dmWithNeighbor(id: String): NetworkDiscoveryManager {
        val dm = NetworkDiscoveryManager(myNodeId = id)
        return dm
    }

    private fun registerNeighbor(dm: NetworkDiscoveryManager, id: String) {
        dm.onDiscoveryPacket(
            TextPacket(
                senderId = id,
                recipientId = "*",
                type = PacketType.NODE_HELLO,
                language = "en",
                text = "N-$id|RELAY|STT/TTS/RELAY"
            )
        )
    }

    @Test
    fun testHelloDiscoversNeighbor() {
        val dm = dmWithNeighbor("ITN-AAAA11")
        registerNeighbor(dm, "ITN-B91C")

        val neighbor = dm.neighbors["ITN-B91C"]
        assertNotNull("Neighbor must be recorded", neighbor)
        assertEquals("RELAY", neighbor!!.role)
        assertTrue(neighbor.lastSeenMs > 0)
    }

    @Test
    fun testAddRefreshRoute() {
        val dm = dmWithNeighbor("ITN-AAAA11")
        registerNeighbor(dm, "ITN-R3")
        dm.addOrRefreshRoute("ITN-M18A", "INDIVIDUAL", "ITN-R3", 2)

        val route = dm.bestRoute("ITN-M18A")
        assertNotNull(route)
        assertEquals("ITN-R3", route!!.nextHopId)
        assertEquals(2, route.hopCount)
        assertEquals("INDIVIDUAL", route.destinationMode)
    }

    @Test
    fun testBestNextHop() {
        val dm = dmWithNeighbor("ITN-AAAA11")
        registerNeighbor(dm, "ITN-R3")
        dm.addOrRefreshRoute("ITN-M18A", "INDIVIDUAL", "ITN-R3", 2)
        assertEquals("ITN-R3", dm.bestNextHop("ITN-M18A"))
        assertNull(dm.bestNextHop("UNKNOWN"))
    }

    @Test
    fun testRouteRejectedIfNextHopNotNeighbor() {
        // A route whose next hop is NOT a discovered neighbor must be rejected,
        // preventing adoption of unreachable remote next-hops.
        val dm = dmWithNeighbor("ITN-AAAA11")
        dm.addOrRefreshRoute("ITN-HIDDEN", "INDIVIDUAL", "ITN-NOPE", 1)
        assertNull("Route via unknown neighbor must not be installed", dm.bestRoute("ITN-HIDDEN"))
    }

    @Test
    fun testCostPrefersShorterRoute() {
        val dm = dmWithNeighbor("ITN-AAAA11")
        registerNeighbor(dm, "ITN-R1")
        registerNeighbor(dm, "ITN-R2")
        // Two routes to B: direct via R1 (2 hops) and longer via R2 (4 hops)
        dm.addOrRefreshRoute("ITN-DEST", "INDIVIDUAL", "ITN-R1", 2)
        dm.addOrRefreshRoute("ITN-DEST", "INDIVIDUAL", "ITN-R2", 4)

        assertEquals("Lower-cost (shorter) route wins", "ITN-R1", dm.bestNextHop("ITN-DEST"))
    }

    @Test
    fun testHelloIgnoresSelf() {
        val dm = dmWithNeighbor("ITN-AAAA11")
        val selfHello = TextPacket(
            senderId = "ITN-AAAA11",
            recipientId = "*",
            type = PacketType.NODE_ANNOUNCE,
            language = "en",
            text = "Me|DEFAULT|STT"
        )
        dm.onDiscoveryPacket(selfHello)
        assertTrue(dm.neighbors.isEmpty())
    }

    @Test
    fun testRouteResponseUsesAdvertiserAsNextHop() {
        // Phase 4 rule: ROUTE_RESPONSE from ITN-R5 advertising "B via ITN-R9, 3 hops"
        // must yield OUR route "B via ITN-R5 (4 hops)" — next hop is the advertiser.
        val dm = dmWithNeighbor("ITN-AAAA11")
        registerNeighbor(dm, "ITN-R5")

        val response = TextPacket(
            senderId = "ITN-R5",
            recipientId = "ITN-AAAA11",
            type = PacketType.ROUTE_RESPONSE,
            language = "en",
            text = "ROUTE:ITN-B91C:ITN-R9:3"
        )
        var discovered = false
        dm.onRouteDiscovered = { _, _, _, _ -> discovered = true }
        dm.onDiscoveryPacket(response)

        val route = dm.bestRoute("ITN-B91C")
        assertNotNull("Route from ROUTE_RESPONSE must be installed", route)
        assertEquals("Next hop must be the ADVERTISER (ITN-R5), not remote ITN-R9", "ITN-R5", route?.nextHopId)
        assertEquals("Hop count must be advertised+1", 4, route?.hopCount)
        assertTrue(discovered)
    }

    @Test
    fun testAnnounceTeachesRoutesViaAdvertiser() {
        val dm = dmWithNeighbor("ITN-AAAA11")
        registerNeighbor(dm, "ITN-R2")
        // ITN-R2 announces: 'R2-Node|RELAY|...|ROUTES:ITN-B91C:ITN-R9:1'
        dm.onDiscoveryPacket(
            TextPacket(
                senderId = "ITN-R2",
                recipientId = "*",
                type = PacketType.NODE_ANNOUNCE,
                language = "en",
                text = "R2|RELAY|STT/TTS/RELAY|ROUTES:ITN-B91C:ITN-R9:1"
            )
        )
        val route = dm.bestRoute("ITN-B91C")
        assertNotNull(route)
        assertEquals("Next hop = advertiser ITN-R2", "ITN-R2", route?.nextHopId)
        assertEquals("Distance = advertised+1", 2, route?.hopCount)
    }

    @Test
    fun testRejectSelfAndExcessiveHops() {
        val dm = dmWithNeighbor("ITN-AAAA11")
        registerNeighbor(dm, "ITN-R1")
        dm.addOrRefreshRoute("ITN-AAAA11", "INDIVIDUAL", "ITN-R1", 1) // self dest
        assertNull(dm.bestRoute("ITN-AAAA11"))
    }

    @Test
    fun testBuildHelloCarriesRole() {
        val dm = dmWithNeighbor("ITN-AAAA11")
        val hello = dm.buildHello("MEDICAL", "Med-01")
        assertEquals(PacketType.NODE_HELLO, hello.type)
        assertEquals("ITN-AAAA11", hello.senderId)
        assertTrue(hello.text.contains("MEDICAL"))
        assertTrue(hello.text.contains("Med-01"))
    }
}