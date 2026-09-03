package com.itantra.transport

import com.itantra.protocol.AddressMode
import com.itantra.protocol.PacketType
import com.itantra.protocol.TextPacket
import org.junit.Assert.*
import org.junit.Test

class DeliveryTrackerTest {

    private fun packet(id: String, recipient: String, mode: AddressMode = AddressMode.INDIVIDUAL): TextPacket =
        TextPacket(
            messageId = id,
            senderId = "ITN-AAAA11",
            recipientId = recipient,
            addressMode = mode,
            language = "hi",
            text = "मदद चाहिए"
        )

    @Test
    fun testTrackAndLifecycle() {
        val tracker = DeliveryTracker()
        val p = packet("m1", "ITN-B91C")

        tracker.track(p, DeliveryStatus.QUEUED)
        assertEquals(DeliveryStatus.QUEUED, tracker.getStatus("m1")?.status)

        tracker.update("m1", DeliveryStatus.STORED)
        tracker.update("m1", DeliveryStatus.FORWARDING, hopCount = 1)
        tracker.update("m1", DeliveryStatus.DELIVERED, hopCount = 2)
        tracker.update("m1", DeliveryStatus.ACKNOWLEDGED)

        val s = tracker.getStatus("m1")
        assertEquals(DeliveryStatus.ACKNOWLEDGED, s?.status)
        assertEquals("ITN-B91C", s?.recipientId)
        assertEquals(2, s?.hopCount)
    }

    @Test
    fun testEmergencyFlagged() {
        val tracker = DeliveryTracker()
        val p = TextPacket(
            messageId = "em1",
            senderId = "ITN-AAAA11",
            recipientId = "*",
            addressMode = AddressMode.GROUP,
            type = PacketType.EMERGENCY,
            language = "hi",
            text = "SOS"
        )
        tracker.track(p, DeliveryStatus.QUEUED)
        assertTrue(tracker.getStatus("em1")?.isEmergency == true)
        assertEquals("GROUP", tracker.getStatus("em1")?.recipientMode)
    }

    @Test
    fun testQueryByRecipient() {
        val tracker = DeliveryTracker()
        tracker.track(packet("a", "ITN-R3"), DeliveryStatus.QUEUED)
        tracker.track(packet("b", "ITN-R5"), DeliveryStatus.QUEUED)

        val forR3 = tracker.messageIdForRecipient("ITN-R3")
        assertEquals(1, forR3.size)
        assertEquals("a", forR3[0].messageId)
    }

    @Test
    fun testStatusChangeCallback() {
        val tracker = DeliveryTracker()
        var last: DeliveryStatus? = null
        tracker.onStatusChange = { last = it.status }
        tracker.track(packet("x", "*"), DeliveryStatus.CREATED)
        tracker.update("x", DeliveryStatus.PLAYING)
        assertEquals(DeliveryStatus.PLAYING, last)
    }
}
