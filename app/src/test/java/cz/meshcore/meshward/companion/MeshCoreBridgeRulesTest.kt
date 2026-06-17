package cz.meshcore.meshward.companion

import cz.meshcore.sidepath.meshcore.MeshCoreEnvelope
import cz.meshcore.sidepath.meshcore.MeshCoreType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the ported bridge rules. Only the pure pieces are exercised here — anything that decodes
 * a packet or computes a content hash goes through the native meshpkt `.so`, which can't load on the
 * host JVM, so those are validated on-device / against the Go reference.
 */
class MeshCoreBridgeRulesTest {

    private fun env(
        type: String,
        route: String = "FLOOD",
        payload: ByteArray = ByteArray(0),
        hops: List<ByteArray> = emptyList(),
    ) = MeshCoreEnvelope(
        route = route, routeCode = 0, type = type, typeCode = 0, version = 1,
        pathHashSize = 1, hopCount = hops.size, hops = hops, payload = payload,
        isTransport = route.contains("TRANSPORT"), transportCodes = null,
    )

    @Test fun undecodableIsSkipped() {
        assertEquals(ForwardMode.SKIP, MeshCoreBridgeRules.classifyEnvelope(null).mode)
    }

    @Test fun advertAlwaysFloodsEvenWhenDirect() {
        val c = MeshCoreBridgeRules.classifyEnvelope(env(MeshCoreType.ADVERT, route = "DIRECT"))
        assertEquals(ForwardMode.FLOOD, c.mode)
        assertTrue(c.isAdvert)
    }

    @Test fun textMsgIsDirectByPayloadDestHash() {
        val c = MeshCoreBridgeRules.classifyEnvelope(
            env(MeshCoreType.TXT_MSG, route = "FLOOD", payload = byteArrayOf(0x42, 0x01, 0x02)),
        )
        assertEquals(ForwardMode.DIRECT, c.mode)
        assertArrayEquals(byteArrayOf(0x42), c.targetHash)
        assertFalse(c.isAdvert)
    }

    @Test fun floodRouteFloods() {
        assertEquals(ForwardMode.FLOOD, MeshCoreBridgeRules.classifyEnvelope(env(MeshCoreType.GRP_TXT, "FLOOD")).mode)
        assertEquals(ForwardMode.FLOOD, MeshCoreBridgeRules.classifyEnvelope(env(MeshCoreType.GRP_TXT, "TRANSPORT_FLOOD")).mode)
    }

    @Test fun directRouteUsesFirstHopWhenNoDestHash() {
        val c = MeshCoreBridgeRules.classifyEnvelope(env(MeshCoreType.GRP_DATA, "DIRECT", hops = listOf(byteArrayOf(0xAB.toByte()))))
        assertEquals(ForwardMode.DIRECT, c.mode)
        assertArrayEquals(byteArrayOf(0xAB.toByte()), c.targetHash)
    }

    @Test fun directWithNoTargetSkips() {
        assertEquals(ForwardMode.SKIP, MeshCoreBridgeRules.classifyEnvelope(env(MeshCoreType.GRP_DATA, "DIRECT")).mode)
    }

    @Test fun directAckWithNoTargetFloods() {
        // ACKs carry no sender hash; fan them through as flood so recipients match by pending CRC.
        assertEquals(ForwardMode.FLOOD, MeshCoreBridgeRules.classifyEnvelope(env(MeshCoreType.ACK, "DIRECT")).mode)
    }

    @Test fun unknownRouteSkips() {
        assertEquals(ForwardMode.SKIP, MeshCoreBridgeRules.classifyEnvelope(env(MeshCoreType.RAW_CUSTOM, "WEIRD")).mode)
    }

    @Test fun dedupSuppressesRepeatThenAllowsAfterTtl() {
        val d = BridgeDedup(ttlMs = 50)
        val raw = byteArrayOf(1, 2, 3, 4)
        assertTrue(d.shouldInjectRaw(raw))   // first time
        assertFalse(d.shouldInjectRaw(raw))  // within TTL
        Thread.sleep(70)
        assertTrue(d.shouldInjectRaw(raw))   // TTL elapsed
    }

    @Test fun dedupBridgeOutIsPerDatagramId() {
        val d = BridgeDedup(ttlMs = 1000)
        assertTrue(d.shouldBridgeOut("aabb"))
        assertFalse(d.shouldBridgeOut("aabb"))
        assertTrue(d.shouldBridgeOut("ccdd"))
    }
}
