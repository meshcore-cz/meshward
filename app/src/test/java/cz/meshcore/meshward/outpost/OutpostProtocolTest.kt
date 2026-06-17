package cz.meshcore.meshward.outpost

import cz.meshcore.meshward.outpost.protocol.CloseReason
import cz.meshcore.meshward.outpost.protocol.ClosePayload
import cz.meshcore.meshward.outpost.protocol.ExchangeCategory
import cz.meshcore.meshward.outpost.protocol.ExchangePayload
import cz.meshcore.meshward.outpost.protocol.OutpostCrypto
import cz.meshcore.meshward.outpost.protocol.OutpostObject
import cz.meshcore.meshward.outpost.protocol.OutpostObjectBuilder
import cz.meshcore.meshward.outpost.protocol.OutpostObjectType
import cz.meshcore.meshward.outpost.protocol.OutpostProfile
import cz.meshcore.meshward.outpost.protocol.OutpostProtocol
import cz.meshcore.meshward.outpost.protocol.OutpostTtl
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutpostProtocolTest {

    private val seed = ByteArray(32) { (it + 1).toByte() }
    private val pubKey: ByteArray = ByteArray(Ed25519.PUBLIC_KEY_SIZE).also { Ed25519.generatePublicKey(seed, 0, it, 0) }
    private val channelKey = ByteArray(16) { (0x40 + it).toByte() }

    private fun exchangeCreate(
        category: ExchangeCategory = ExchangeCategory.OFFER,
        currency: String = "CZK",
        price: Long = 500,
        description: String = "Heltec V3, good condition, Praha",
        revision: Int = 0,
        type: OutpostObjectType = OutpostObjectType.CREATE,
    ): OutpostObject {
        val payload = ExchangePayload(category, currency, price, description).encode()
        val builder = OutpostObjectBuilder(
            type = type,
            profile = OutpostProfile.EXCHANGE,
            boardId = OutpostCrypto.boardId(channelKey),
            authorRef = OutpostCrypto.authorRef(pubKey),
            listingId = 0xDEADBEEFL,
            revision = revision,
            createdAt = 1_700_000_000,
            ttl = OutpostTtl.D7,
            payload = payload,
        )
        return OutpostCrypto.sign(builder, seed)
    }

    @Test fun signedRoundTripVerifies() {
        val obj = exchangeCreate()
        assertTrue(obj.signedBytes.size in OutpostProtocol.MIN_OBJECT_SIZE..OutpostProtocol.MAX_OBJECT_SIZE)
        val decoded = OutpostObject.decode(obj.signedBytes)
        assertNotNull(decoded)
        assertTrue(OutpostCrypto.verify(decoded!!, pubKey))
        assertEquals(OutpostObjectType.CREATE, decoded.type)
        assertEquals(OutpostProfile.EXCHANGE, decoded.profile)
        assertEquals(0, decoded.revision)
        assertEquals(1_700_000_000L + OutpostTtl.D7.seconds, decoded.expiresAt)
    }

    @Test fun exchangePayloadRoundTrips() {
        val obj = exchangeCreate(category = ExchangeCategory.WANTED, currency = "", price = 0, description = "868 MHz antenna near Plzeň")
        val ex = obj.exchange()
        assertNotNull(ex)
        assertEquals(ExchangeCategory.WANTED, ex!!.category)
        assertEquals("", ex.currency)
        assertEquals(0L, ex.price)
        assertEquals("868 MHz antenna near Plzeň", ex.description)
    }

    @Test fun authorRefIsFirstTenKeyBytes() {
        val obj = exchangeCreate()
        assertArrayEquals(pubKey.copyOfRange(0, 10), obj.authorRef)
    }

    @Test fun tamperedDescriptionFailsVerification() {
        val obj = exchangeCreate()
        val bytes = obj.signedBytes.copyOf()
        bytes[OutpostProtocol.HEADER_SIZE + 9] = (bytes[OutpostProtocol.HEADER_SIZE + 9] + 1).toByte()
        val decoded = OutpostObject.decode(bytes)
        // Structure may still parse, but the signature must no longer verify.
        assertTrue(decoded == null || !OutpostCrypto.verify(decoded, pubKey))
    }

    @Test fun wrongKeyFailsVerification() {
        val obj = exchangeCreate()
        val otherSeed = ByteArray(32) { (it + 9).toByte() }
        val otherPub = ByteArray(Ed25519.PUBLIC_KEY_SIZE).also { Ed25519.generatePublicKey(otherSeed, 0, it, 0) }
        assertFalse(OutpostCrypto.verify(obj, otherPub))
    }

    @Test fun boardIdIsDeterministicAndEightBytes() {
        val a = OutpostCrypto.boardId(channelKey)
        val b = OutpostCrypto.boardId(channelKey)
        assertEquals(8, a.size)
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(OutpostCrypto.boardId(ByteArray(16))))
    }

    @Test fun closeRoundTrips() {
        val payload = ClosePayload(CloseReason.SOLD, "gone, thanks").encode()
        val builder = OutpostObjectBuilder(
            type = OutpostObjectType.CLOSE, profile = OutpostProfile.EXCHANGE,
            boardId = OutpostCrypto.boardId(channelKey), authorRef = OutpostCrypto.authorRef(pubKey),
            listingId = 0xDEADBEEFL, revision = 1, createdAt = 1_700_000_100, ttl = OutpostTtl.D30,
            payload = payload,
        )
        val obj = OutpostCrypto.sign(builder, seed)
        val decoded = OutpostObject.decode(obj.signedBytes)!!
        assertTrue(OutpostCrypto.verify(decoded, pubKey))
        val close = decoded.close()
        assertNotNull(close)
        assertEquals(CloseReason.SOLD, close!!.reason)
        assertEquals("gone, thanks", close.note)
    }

    @Test fun createWithNonZeroRevisionRejected() {
        // A CREATE must be revision 0 (§10.1). Build the bytes directly (signing would refuse) and
        // confirm decode rejects the structurally invalid object.
        val builder = OutpostObjectBuilder(
            type = OutpostObjectType.CREATE, profile = OutpostProfile.EXCHANGE,
            boardId = OutpostCrypto.boardId(channelKey), authorRef = OutpostCrypto.authorRef(pubKey),
            listingId = 1, revision = 1, createdAt = 1_700_000_000, ttl = OutpostTtl.D7,
            payload = ExchangePayload(ExchangeCategory.OFFER, "CZK", 1, "x").encode(),
        )
        val bytes = builder.unsignedBytes() + ByteArray(OutpostProtocol.SIGNATURE_SIZE)
        assertNull(OutpostObject.decode(bytes))
    }

    @Test fun objectIdMatchesDigestPrefix() {
        val obj = exchangeCreate()
        assertArrayEquals(
            OutpostCrypto.fullDigest(obj.signedBytes).copyOfRange(0, 8),
            OutpostCrypto.objectId(obj.signedBytes),
        )
    }

    @Test fun syncRequestRoundTrips() {
        val boardId = OutpostCrypto.boardId(channelKey)
        val frame = cz.meshcore.meshward.outpost.protocol.OutpostSync.encodeRequest(boardId, sinceSeconds = 1_699_000_000, maxObjects = 20)
        assertTrue(cz.meshcore.meshward.outpost.protocol.OutpostSync.isControlFrame(frame))
        // A persistent object is NOT a control frame (its first byte is 0x11/0x12/0x13).
        assertFalse(cz.meshcore.meshward.outpost.protocol.OutpostSync.isControlFrame(exchangeCreate().signedBytes))
        val req = cz.meshcore.meshward.outpost.protocol.OutpostSync.decodeRequest(frame)
        assertNotNull(req)
        assertArrayEquals(boardId, req!!.boardId)
        assertEquals(1_699_000_000L, req.sinceSeconds)
        assertEquals(20, req.maxObjects)
    }
}
