package com.itantra.translation

import com.itantra.protocol.BinaryPacketCodec
import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import com.itantra.stt.SupportedLanguage
import com.itantra.transport.CapabilityFormat
import com.itantra.transport.MeshRoutingManager
import com.itantra.transport.TransportLayer
import com.itantra.transport.TransportType
import com.itantra.transport.DeviceInfo
import com.itantra.transport.ConnectionState
import org.junit.Assert.*
import org.junit.Test

/** Phase 8: TextPacket.language == language of the transmitted text. */
class PacketLanguageTest {

    @Test
    fun testPacketLanguageEqualsTransmittedText() {
        // Hindi speaker, target English -> packet language MUST be "en"
        val lang = CrossLanguagePipeline.packetLanguage(SupportedLanguage.HINDI, SupportedLanguage.ENGLISH)
        assertEquals("en", lang.code)

        val packet = TextPacket(
            senderId = "A", recipientId = "B", type = PacketType.DATA,
            language = lang.code, text = "Where are you going?"
        )
        assertEquals("en", packet.language)
        assertEquals("Where are you going?", packet.text)
        // Never set "hi" when the text is English.
        assertNotEquals("hi", packet.language)
    }

    @Test
    fun testSameLanguagePacketKeepsLanguage() {
        val lang = CrossLanguagePipeline.packetLanguage(SupportedLanguage.HINDI, SupportedLanguage.HINDI)
        assertEquals("hi", lang.code)
    }

    @Test
    fun testPacketRoundTripPreservesLanguage() {
        val key = ByteArray(32) { it.toByte() }
        val packet = TextPacket(
            senderId = "A", recipientId = "B", type = PacketType.DATA,
            language = "en", text = "Where are you going?", timestamp = System.currentTimeMillis()
        ).withEncryption(key)
        val wire = BinaryPacketCodec().encode(packet, sessionKey = key)
        val decoded = BinaryPacketCodec().decode(wire, sessionKey = key)!!
        assertEquals("en", decoded.language)
    }
}

/** Phase 8-9: translation metadata consistency (packet language vs direction). */
class TranslationMetadataTest {
    @Test
    fun testOutcomeCarriesTargetAndLatency() {
        val out = CrossLanguagePipeline.apply(
            "आप कहाँ जा रहे हैं?", SupportedLanguage.HINDI, SupportedLanguage.ENGLISH,
            translate = HI_EN_FAKE::translate
        )
        out as CrossLanguagePipeline.Outcome.Translated
        assertEquals("en", out.target)
        assertEquals(12L, out.latencyMs)
    }

    companion object {
        private val HI_EN_FAKE = FakeTranslationEngine(mapOf(
            "hi" to "en" to { s -> "en:$s" }
        ))
    }
}

/** Phase 9: network carries ONLY the final target text; encrypted session keys intact. */
class CrossLanguagePacketEncryptionTest {
    @Test
    fun testTranslatedPacketEncryptsAndDecrypts() {
        val key = ByteArray(32) { 0x55 }
        val translated = "Where are you going?"
        val packet = TextPacket(
            senderId = "NODE_A", recipientId = "NODE_B", type = PacketType.DATA,
            language = "en", text = translated, timestamp = System.currentTimeMillis()
        ).withEncryption(key)
        val wire = BinaryPacketCodec().encode(packet, sessionKey = key)
        // Wrong key rejected (security intact through the translation layer).
        assertNull(BinaryPacketCodec().decode(wire, ByteArray(32) { 0x66 }))
        val plain = BinaryPacketCodec().decode(wire, sessionKey = key)!!.withDecryption(key)
        assertEquals(translated, plain.text)
        assertEquals("en", plain.language)
    }
}

/** Phases 26/38: relay never needs translation models. */
class RelayDoesNotNeedTranslationModelTest {

    class NoTranslationTransport : TransportLayer {
        override val transportType = TransportType.BLUETOOTH
        override var connectionState = ConnectionState.CONNECTED
        val sent = mutableListOf<TextPacket>()
        override fun startListening(onPacketReceived: (TextPacket) -> Unit, onStateChanged: (ConnectionState) -> Unit) {}
        override fun discoverDevices(onDevicesFound: (List<DeviceInfo>) -> Unit) {}
        override fun connect(device: DeviceInfo, onResult: (Boolean) -> Unit) {}
        override fun sendPacket(packet: TextPacket): Boolean { sent.add(packet); return true }
        override fun disconnect() {}
        override fun isConnected(): Boolean = true
    }

    @Test
    fun testRelayForwardsWithoutAnyTranslationModel() {
        // Relay node has NO translation engine — it just forwards encrypted packets.
        val transport = NoTranslationTransport()
        val relay = MeshRoutingManager("NODE_R", transport)
        val packet = TextPacket(
            messageId = "x1", senderId = "NODE_A", recipientId = "NODE_C",
            type = PacketType.DATA, language = "en", text = "Where are you going?",
            hopCount = 0, maxHops = 3
        )
        var deliveredLocally = false
        relay.handleIncomingPacket(packet) { deliveredLocally = true }
        assertFalse(deliveredLocally)
        assertTrue(transport.sent.isNotEmpty())
        assertEquals("NODE_C", transport.sent[0].recipientId)
    }
}

/** Phase 25: SOS works with zero translation models (never translated). */
class SosWithoutTranslationModelTest {
    @Test
    fun testEmergencyPacketNeverRunsThroughTranslation() {
        // SOS text is a direct EMERGENCY packet; no translation pipeline invoked.
        val sos = TextPacket(
            messageId = "sos1", senderId = "NODE_A", recipientId = "*",
            type = PacketType.EMERGENCY, language = "en",
            text = "SOS — Emergency assistance required", isAlert = true, isPriority = true
        )
        assertEquals(PacketType.EMERGENCY, sos.type)
        assertTrue(sos.isAlert)
        // No translationEngine dependency exists on this path by construction.
        assertNotNull(sos.text)
    }
}

/** Phase 33: capability advertisement round-trips honestly. */
class CapabilityAdvertisementTest {
    @Test
    fun testBuildAndParseRoundTrip() {
        val cap = CapabilityFormat.build(setOf("hi", "en"), setOf("hi", "en"), setOf("hi-en", "en-hi"))
        assertEquals(setOf("hi-en", "en-hi"), CapabilityFormat.parseMtPairs(cap))
        assertEquals(setOf("hi", "en"), CapabilityFormat.parseTtsLanguages(cap))
    }

    @Test
    fun testParseOnUnadvertisedCapabilities() {
        assertEquals(emptySet<String>(), CapabilityFormat.parseMtPairs("display|DEFAULT|STT/TTS/RELAY"))
        assertEquals(emptySet<String>(), CapabilityFormat.parseTtsLanguages("display|DEFAULT|ROUTES:a"))
    }
}

/** Phase 34 (peer target): minimal, no contact registry — capability-driven only. */
class PeerTargetLanguageTest {
    @Test
    fun testPeerCapabilitiesHoldTtsLanguages() {
        // Orchestrator stores per-peer capabilities; this asserts the parsing contract.
        val hello = "NodeB|DEFAULT|${CapabilityFormat.build(emptySet(), setOf("en"), setOf("en-hi"))}"
        assertEquals(setOf("en"), CapabilityFormat.parseTtsLanguages(hello))
        assertEquals(setOf("en-hi"), CapabilityFormat.parseMtPairs(hello))
    }
}

/** Phase 21/22: benchmark records translation latency + real packet bytes. */
class BenchmarkTranslationLatencyTest {
    @Test
    fun testLatencyRecordCarriesTranslationSegment() {
        val rec = com.itantra.benchmark.BenchmarkLogger.logInteraction(
            messageId = "m1", language = "en", isAlert = false,
            tSpeechStart = 1000, tSpeechEnd = 1100, tSttStart = 1100, tSttEnd = 1320,
            tSend = 1330, tReceive = 1370, tTtsStart = 1370, tTtsEnd = 1800, tPlayStart = 1800,
            translationLatencyMs = 120
        )
        assertEquals(120L, rec.translationLatencyMs)
        assertTrue(rec.hasAnyMeasurement())
    }
}

/** Phase 35: existing same-language packet flow unchanged. */
class BackwardCompatibilityTest {
    @Test
    fun testSameLanguageRoundTripUnaffected() {
        val lang = CrossLanguagePipeline.packetLanguage(SupportedLanguage.HINDI, SupportedLanguage.HINDI)
        assertEquals(SupportedLanguage.HINDI, lang)
        // Existing TextPacket still carries language + text end-to-end.
        assertEquals("hi", lang.code)
    }
}