package cz.meshcore.meshward.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for MeshCore serial V3 framing used by the USB and TCP transports. */
class SerialV3Test {

    private val toHost = '>'.code.toByte()

    private fun deviceFrame(payload: ByteArray): ByteArray =
        byteArrayOf(toHost, (payload.size and 0xFF).toByte(), ((payload.size shr 8) and 0xFF).toByte()) + payload

    @Test fun frameToDevicePrependsMarkerAndLength() {
        val out = SerialV3.frameToDevice(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf('<'.code.toByte(), 3, 0, 1, 2, 3), out)
    }

    @Test fun deframerReadsOneFrame() {
        val d = SerialV3.Deframer()
        val frames = d.feed(deviceFrame(byteArrayOf(0x10, 0x20, 0x30)))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30), frames[0])
    }

    @Test fun deframerReassemblesAcrossChunks() {
        val d = SerialV3.Deframer()
        val full = deviceFrame(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        assertTrue(d.feed(full.copyOfRange(0, 2)).isEmpty()) // marker + 1 length byte only
        assertTrue(d.feed(full.copyOfRange(2, 4)).isEmpty()) // rest of header + 1 payload byte
        val frames = d.feed(full.copyOfRange(4, full.size))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()), frames[0])
    }

    @Test fun deframerSkipsLeadingNoiseAndReadsTwoFrames() {
        val d = SerialV3.Deframer()
        val stream = "boot banner\r\n".toByteArray() +
            deviceFrame(byteArrayOf(1)) +
            deviceFrame(byteArrayOf(2, 3))
        val frames = d.feed(stream)
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(1), frames[0])
        assertArrayEquals(byteArrayOf(2, 3), frames[1])
    }

    @Test fun frameRoundTripsThroughDeframerWithHostMarker() {
        // A device that echoed our framing with the host marker would still need the host marker to
        // be read; here we just confirm payload integrity for a realistic packet size.
        val payload = ByteArray(200) { (it and 0xFF).toByte() }
        val frames = SerialV3.Deframer().feed(deviceFrame(payload))
        assertArrayEquals(payload, frames.single())
    }
}
