package com.itantra.transport

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class BleProtocolTest {

    @Test fun nodeHintParsesHexNodeIds() {
        assertArrayEquals(byteArrayOf(0x84.toByte(), 0x1D, 0x61), BleProtocol.nodeHint("ITN-841D61"))
        assertArrayEquals(byteArrayOf(0x93.toByte(), 0x33, 0x57), BleProtocol.nodeHint("ITN-933357"))
    }

    @Test fun nodeHintFallsBackToStableHashForOddIds() {
        val a = BleProtocol.nodeHint("weird-id")
        assertEquals(3, a.size)
        assertArrayEquals(a, BleProtocol.nodeHint("weird-id"))
    }

    @Test fun exactlyOneSideInitiatesWhenBothScan() {
        val a = BleProtocol.nodeHint("ITN-841D61")
        val b = BleProtocol.nodeHint("ITN-E375A5")
        // Just discovered: only the smaller hint connects, never both, never neither.
        assertTrue(BleProtocol.shouldInitiate(a, b, 0))
        assertFalse(BleProtocol.shouldInitiate(b, a, 0))
    }

    @Test fun largerSideConnectsAfterFallbackDelay() {
        val a = BleProtocol.nodeHint("ITN-841D61")
        val b = BleProtocol.nodeHint("ITN-E375A5")
        assertFalse(BleProtocol.shouldInitiate(b, a, BleProtocol.FALLBACK_INITIATE_MS - 1))
        assertTrue(BleProtocol.shouldInitiate(b, a, BleProtocol.FALLBACK_INITIATE_MS))
    }

    @Test fun bothPhonesKeepTheSameOfTwoDuplicateLinks() {
        val a = BleProtocol.nodeHint("ITN-349864")
        val b = BleProtocol.nodeHint("ITN-933357")
        // Link 1 opened by A (client on A, server on B); link 2 opened by B.
        val aSeesLink1 = BleProtocol.isCanonicalLink(a, b, weOpenedIt = true)
        val bSeesLink1 = BleProtocol.isCanonicalLink(b, a, weOpenedIt = false)
        val aSeesLink2 = BleProtocol.isCanonicalLink(a, b, weOpenedIt = false)
        val bSeesLink2 = BleProtocol.isCanonicalLink(b, a, weOpenedIt = true)
        assertEquals("both ends must agree on link 1", aSeesLink1, bSeesLink1)
        assertEquals("both ends must agree on link 2", aSeesLink2, bSeesLink2)
        assertNotEquals("exactly one of the two links is kept", aSeesLink1, aSeesLink2)
        assertTrue("the smaller-hint phone's own link is the one kept", aSeesLink1)
    }

    @Test fun unknownPeerHintWaitsThenConnects() {
        val self = BleProtocol.nodeHint("ITN-000001")
        assertFalse("must not race a peer whose hint is not known yet", BleProtocol.shouldInitiate(self, null, 0))
        assertTrue(BleProtocol.shouldInitiate(self, null, BleProtocol.FALLBACK_INITIATE_MS))
    }

    @Test fun frameAndAssembleRoundTripAcrossAnyChunkSize() {
        val rnd = Random(7)
        val payloads = listOf(ByteArray(1), ByteArray(19), ByteArray(20), ByteArray(509), ByteArray(3000))
            .onEach { rnd.nextBytes(it) }
        val stream = payloads.flatMap { BleProtocol.frame(it).toList() }.toByteArray()
        for (chunkSize in listOf(1, 7, 20, 100, 244, 509, 100_000)) {
            val asm = FrameAssembler()
            val got = ArrayList<ByteArray>()
            for (c in BleProtocol.chunk(stream, chunkSize)) got.addAll(asm.feed(c))
            assertEquals("chunk=$chunkSize", payloads.size, got.size)
            payloads.zip(got).forEach { (p, g) -> assertArrayEquals(p, g) }
            assertEquals(0, asm.pendingBytes())
        }
    }

    @Test fun assemblerKeepsPartialFrameUntilComplete() {
        val asm = FrameAssembler()
        val f = BleProtocol.frame(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(asm.feed(f.copyOfRange(0, 6)).isEmpty())
        assertEquals(6, asm.pendingBytes())
        val out = asm.feed(f.copyOfRange(6, f.size))
        assertEquals(1, out.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), out[0])
    }

    @Test fun corruptLengthIsDroppedNotBuffered() {
        val asm = FrameAssembler(maxFrame = 100)
        val huge = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F, 1, 2, 3)
        assertTrue(asm.feed(huge).isEmpty())
        assertTrue(asm.lastFeedCorrupt)
        assertEquals(0, asm.pendingBytes())
        // Zero-length frames are corrupt too, and the assembler recovers afterwards.
        assertTrue(asm.feed(byteArrayOf(0, 0, 0, 0)).isEmpty())
        assertTrue(asm.lastFeedCorrupt)
        val ok = asm.feed(BleProtocol.frame(byteArrayOf(9)))
        assertEquals(1, ok.size)
        assertFalse(asm.lastFeedCorrupt)
    }

    @Test fun chunkSizesAndPayload() {
        assertEquals(20, BleProtocol.payloadSize(23))
        assertEquals(509, BleProtocol.payloadSize(512))
        assertEquals(20, BleProtocol.payloadSize(5)) // never below the default
        assertTrue(BleProtocol.chunk(ByteArray(0), 20).isEmpty())
        assertEquals(3, BleProtocol.chunk(ByteArray(41), 20).size)
        assertTrue(BleProtocol.chunk(ByteArray(41), 20).all { it.size <= 20 })
    }
}
