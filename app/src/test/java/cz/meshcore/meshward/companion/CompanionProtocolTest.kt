package cz.meshcore.meshward.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-vector tests for the companion codec, cross-checked against the meshcore-go reference
 * (`protocol/companion/codec_test.go`, `rf_packet_test.go`).
 */
class CompanionProtocolTest {

    @Test fun encodeSimpleCommands() {
        assertArrayEquals(byteArrayOf(20), CompanionProtocol.getBattery())
        assertArrayEquals(byteArrayOf(56, 0), CompanionProtocol.getStats(CompanionProtocol.STATS_CORE))
        assertArrayEquals(byteArrayOf(56, 1), CompanionProtocol.getStats(CompanionProtocol.STATS_RADIO))
        assertArrayEquals(byteArrayOf(56, 2), CompanionProtocol.getStats(CompanionProtocol.STATS_PACKETS))
        assertArrayEquals(byteArrayOf(19), CompanionProtocol.reboot())
        assertArrayEquals(byteArrayOf(7, 1), CompanionProtocol.sendSelfAdvert(true))
        assertArrayEquals(byteArrayOf(7, 0), CompanionProtocol.sendSelfAdvert(false))
        assertArrayEquals(byteArrayOf(22, 3), CompanionProtocol.deviceQuery())
    }

    @Test fun encodeAppStart() {
        val pkt = CompanionProtocol.appStart("meshward")
        assertEquals(1, pkt[0].toInt())
        assertEquals(3, pkt[1].toInt())
        // bytes 2..7 are reserved zeros, name follows at offset 8
        for (i in 2..7) assertEquals(0, pkt[i].toInt())
        assertEquals("meshward", String(pkt, 8, pkt.size - 8))
    }

    @Test fun encodeSendRawPacket() {
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val pkt = CompanionProtocol.sendRawPacket(payload, priority = 0)
        assertEquals(65, pkt[0].toInt())
        assertEquals(0, pkt[1].toInt())
        assertArrayEquals(payload, pkt.copyOfRange(2, pkt.size))
    }

    @Test fun encodeSetRadioParams() {
        // 869.432 MHz / 62.5 kHz / SF7 / CR5 → raw freq 869432 (kHz), raw bw 62500 (Hz)
        val pkt = CompanionProtocol.setRadioParams(freqHz = 869_432_000, bwHz = 62_500, sf = 7, cr = 5)
        assertEquals(11, pkt[0].toInt())
        assertEquals(869432L, le32At(pkt, 1))
        assertEquals(62500L, le32At(pkt, 5))
        assertEquals(7, pkt[9].toInt())
        assertEquals(5, pkt[10].toInt())
    }

    @Test fun encodeSetTxPower() {
        assertArrayEquals(byteArrayOf(12, 22), CompanionProtocol.setTxPower(22))
    }

    @Test fun decodeOkErrBattery() {
        assertTrue(CompanionProtocol.decode(byteArrayOf(0)) is CompanionMessage.Ok)
        val err = CompanionProtocol.decode(byteArrayOf(1, 7)) as CompanionMessage.Err
        assertEquals(7, err.code)
        // RESP_BATTERY_VOLTAGE (12) + le16(3700)
        val batt = CompanionProtocol.decode(byteArrayOf(12, (3700 and 0xFF).toByte(), (3700 shr 8).toByte()))
            as CompanionMessage.Battery
        assertEquals(3700, batt.millivolts)
    }

    @Test fun decodeRfPacket() {
        // 0x88, SNR=48 (=> 12.0 dB), RSSI=0xc7 (=> -57), then raw OTA bytes
        val msg = CompanionProtocol.decode(byteArrayOf(0x88.toByte(), 48, 0xc7.toByte(), 0x80.toByte(), 0x8e.toByte(), 0xa8.toByte()))
            as CompanionMessage.RfPacket
        assertEquals(12.0, msg.snr, 0.0001)
        assertEquals(-57, msg.rssi)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x8e.toByte(), 0xa8.toByte()), msg.packet)
        assertTrue(msg.isPush)
    }

    @Test fun decodeSelfInfo() {
        val key = ByteArray(32) { 0xab.toByte() }
        val body = ArrayList<Byte>()
        body.add(1)  // advType
        body.add(22) // tx
        body.add(30) // maxTx
        key.forEach { body.add(it) }
        repeat(12) { body.add(0) }   // lat(4) + lon(4) + reserved(4)
        le32(869432).forEach { body.add(it) } // freq raw (MHz*1000 = kHz)
        le32(62500).forEach { body.add(it) }  // bw raw (kHz*1000 = Hz)
        body.add(11) // sf
        body.add(5)  // cr
        "MeshCore-desk".toByteArray().forEach { body.add(it) }
        val pkt = byteArrayOf(5) + body.toByteArray()

        val si = CompanionProtocol.decode(pkt) as CompanionMessage.SelfInfo
        assertEquals("MeshCore-desk", si.name)
        assertEquals(key.joinToString("") { "%02x".format(it.toInt() and 0xFF) }, si.publicKey)
        assertEquals(869_432_000L, si.radioFreqHz) // 869.432 MHz in Hz
        assertEquals(62_500L, si.radioBwHz)        // 62.5 kHz in Hz
        assertEquals(11, si.radioSf)
        assertEquals(5, si.radioCr)
        assertEquals(22, si.txPower)
    }

    @Test fun decodeUnknownDegradesToRaw() {
        val push = CompanionProtocol.decode(byteArrayOf(0xF0.toByte(), 1, 2)) as CompanionMessage.Raw
        assertEquals(0xF0, push.code)
        assertTrue(push.isPush)
        val resp = CompanionProtocol.decode(byteArrayOf(0x7E)) as CompanionMessage.Raw
        assertTrue(!resp.isPush)
    }

    private fun le32At(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )
}
