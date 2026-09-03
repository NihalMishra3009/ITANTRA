package com.itantra.location

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for offline location with privacy (never fabricates coordinates).
 */
class LocationManagerTest {

    @Test
    fun testUnknownByDefault() {
        val lm = LocationManager("ITN-AAAA11")
        assertNull(lm.selfLocation)
        assertNull(lm.getLocation("ITN-UNKNOWN-NODE"))
        assertTrue("No locations known by default", lm.getKnownLocations().isEmpty())
    }

    @Test
    fun testUpdateSelfAndRetrieve() {
        val lm = LocationManager("ITN-AAAA11")
        lm.updateSelf(28.6139, 77.2090, 5f, "GNSS", 0.95f)
        val loc = lm.getLocation("ITN-AAAA11")
        assertNotNull(loc)
        assertEquals(LocationStatus.EXACT, loc!!.status)
        assertEquals("GNSS", loc.source)
        assertTrue(loc.isFresh)
    }

    @Test
    fun testApproximateIsCoarse() {
        val lm = LocationManager("ITN-AAAA11")
        lm.updateSelfApproximate(28.61, 77.20, 3f)
        val loc = lm.getLocation("ITN-AAAA11")!!
        // Accuracy is floored to privacy radius (coarse by design)
        assertTrue(loc.accuracyMeters >= 50f)
        assertEquals(LocationStatus.APPROXIMATE, loc.status)
        assertEquals("RELAY_ANCHOR", loc.source)
    }

    @Test
    fun testExpiryProducesUnknown() {
        val lm = LocationManager("ITN-AAAA11", defaultExpiryMs = 100)
        lm.updateSelf(1.0, 2.0, 5f, "GNSS", 0.9f)
        Thread.sleep(150)
        assertNull("Expired location must be UNKNOWN (removed)", lm.getLocation("ITN-AAAA11"))
    }

    @Test
    fun testRoundTripLocationUpdatePrivatelyCoarsened() {
        val lm = LocationManager("ITN-AAAA11")
        lm.updateSelf(28.6139, 77.2090, 5f, "GNSS", 0.95f)
        val update = lm.buildLocationUpdate()
        assertNotNull(update)

        // Remote node parses the coarse advertisement
        val remote = LocationManager("ITN-B91C")
        val parsed = remote.parseLocationUpdate("ITN-AAAA11", update!!.text)
        assertNotNull(parsed)
        assertEquals(LocationStatus.APPROXIMATE, parsed?.status)
        assertTrue("Accuracy must be coarse on the wire", (parsed?.accuracyMeters ?: 0f) >= 50f)
    }

    @Test
    fun testImportRemote() {
        val lm = LocationManager("ITN-AAAA11")
        lm.updateSelf(1.0, 2.0, 5f, "GNSS", 0.9f)
        val update = lm.buildLocationUpdate()!!
        val remote = LocationManager("ITN-B91C")
        val parsed = remote.parseLocationUpdate("ITN-AAAA11", update.text)!!

        remote.importRemote(parsed)
        assertEquals("ITN-AAAA11", remote.getLocation("ITN-AAAA11")?.nodeId)
    }

    @Test
    fun testClearSelf() {
        val lm = LocationManager("ITN-AAAA11")
        lm.updateSelf(1.0, 2.0, 5f, "GNSS", 0.9f)
        lm.clearSelf()
        assertNull(lm.selfLocation)
    }
}
