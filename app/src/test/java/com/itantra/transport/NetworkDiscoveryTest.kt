package com.itantra.transport

import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the lightweight DTN network discovery + routing table.
 */
class NetworkDiscoveryTest {

    @Test
    fun testHelloDiscoversNeighbor() {
        val dm = NetworkDiscoveryManager(myNodeId = "ITN-AAAA11")
        val hello = TextPacket(
            senderId = "ITN-B91C",
            recipientId = "*",
            type = PacketType.NODE_HELLO,
            language = "en",
            text = "Relay-01|RESCUE|STT/TTS/RELAY"
        )
        dm.onDiscoveryPacket(hello)

        val neighbor = dm.neighbors["ITN-B91C"]
        assertNotNull("Neighbor must be recorded", neighbor)
        assertEquals("RESCUE", neighbor!!.role)
        assertTrue(neighbor.lastSeenMs > 0)
    }

    @Test
    fun testAddRefreshRoute() {
        val dm = NetworkDiscoveryManager("ITN-AAAA11")
        dm.addOrRefreshRoute("ITN-M18A", "INDIVIDUAL", "ITN-R3", 2)

        val route = dm.routes["ITN-M18A"]
        assertNotNull(route)
        assertEquals("ITN-R3", route!!.nextHopId)
        assertEquals(2, route.hopCount)
        assertEquals("INDIVIDUAL", route.destinationMode)
    }

    @Test
    fun testBestNextHop() {
        val dm = NetworkDiscoveryManager("ITN-AAAA11")
        dm.addOrRefreshRoute("ITN-M18A", "INDIVIDUAL", "ITN-R3", 2)
        assertEquals("ITN-R3", dm.bestNextHop("ITN-M18A"))
        assertNull(dm.bestNextHop("UNKNOWN"))
    }

    @Test
    fun testDeliveryFailureRemovesRoute() {
        val dm = NetworkDiscoveryManager("ITN-AAAA11")
        dm.addOrRefreshRoute("ITN-M18A", "INDIVIDUAL", "ITN-R3", 2)
        dm.markDeliveryFailure("ITN-M18A")
        assertNull(dm.bestNextHop("ITN-M18A"))
    }

    @Test
    fun testHelloIgnoresSelf() {
        val dm = NetworkDiscoveryManager("ITN-AAAA11")
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
    fun testRouteResponseParsing() {
        val dm = NetworkDiscoveryManager("ITN-AAAA11")
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

        val route = dm.routes["ITN-B91C"]
        assertNotNull("Route from ROUTE_RESPONSE must be installed", route)
        assertEquals("ITN-R9", route?.nextHopId)
        assertEquals(3, route?.hopCount)
        assertTrue(discovered)    }

    @Test
    fun testBuildHelloCarriesRole() {
        val dm = NetworkDiscoveryManager("ITN-AAAA11")
        val hello = dm.buildHello("MEDICAL", "Med-01")
        assertEquals(PacketType.NODE_HELLO, hello.type)
        assertEquals("ITN-AAAA11", hello.senderId)
        assertTrue(hello.text.contains("MEDICAL"))
        assertTrue(hello.text.contains("Med-01"))
    }
}
