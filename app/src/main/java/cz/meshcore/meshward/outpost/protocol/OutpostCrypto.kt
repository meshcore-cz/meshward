package cz.meshcore.meshward.outpost.protocol

import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The cryptographic core of Outpost: board ids (§7), object ids (§4), and author signatures (§6).
 *
 * Outpost reuses the node's existing MeshCore/Sidepath Ed25519 identity as its signing identity —
 * the 32-byte public key is the verification key and the first 10 bytes are the `author_ref`. This
 * is the "deterministic derivation from the same seed" binding suggested in README §6.1, so no extra
 * key management is needed and any contact whose public key we already know is verifiable.
 */
object OutpostCrypto {

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    /**
     * Derives the 8-byte board id from a channel key (§7):
     * `SHA256("OUTPOST-BOARD-V1" || 0x00 || channel_key)[0:8]`. The raw key is used, never the
     * human name, so identically named private boards stay distinct and the id reveals nothing.
     */
    fun boardId(channelKey: ByteArray): ByteArray =
        sha256("OUTPOST-BOARD-V1".toByteArray(Charsets.US_ASCII), byteArrayOf(0x00), channelKey)
            .copyOfRange(0, OutpostProtocol.BOARD_ID_SIZE)

    /** The 10-byte author reference: the first 10 bytes of the author's 32-byte public key (§4). */
    fun authorRef(publicKey: ByteArray): ByteArray {
        require(publicKey.size == 32) { "public key must be 32 bytes" }
        return publicKey.copyOfRange(0, OutpostProtocol.AUTHOR_REF_SIZE)
    }

    /** Object id used for dedup / inventory: `SHA256(signed_object)[0:8]` (§4). */
    fun objectId(signedObject: ByteArray): ByteArray =
        sha256(signedObject).copyOfRange(0, OutpostProtocol.OBJECT_ID_SIZE)

    /** Full SHA-256 digest of the signed object (stored internally for conflict ordering §13.2). */
    fun fullDigest(signedObject: ByteArray): ByteArray = sha256(signedObject)

    /**
     * Signs a built object with the author's 32-byte Ed25519 [seed] (the MeshCore identity seed) and
     * returns the finished, decoded [OutpostObject]. The signature covers
     * `SIGNATURE_DOMAIN || unsigned_object` (§6.2).
     */
    fun sign(builder: OutpostObjectBuilder, seed: ByteArray): OutpostObject {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val unsigned = builder.unsignedBytes()
        val msg = signatureMessage(unsigned)
        val sig = ByteArray(Ed25519.SIGNATURE_SIZE)
        Ed25519.sign(seed, 0, msg, 0, msg.size, sig, 0)
        val signed = unsigned + sig
        return OutpostObject.decode(signed) ?: error("self-built object failed to decode")
    }

    /**
     * Verifies [obj]'s signature against the author's full [publicKey] (§6.3). The caller must have
     * already confirmed `publicKey[0:10] == obj.authorRef` and that exactly one known key matches.
     */
    fun verify(obj: OutpostObject, publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        if (!authorRef(publicKey).contentEquals(obj.authorRef)) return false
        val msg = signatureMessage(obj.unsignedBytes)
        val sig = obj.signature
        if (sig.size != Ed25519.SIGNATURE_SIZE) return false
        return runCatching { Ed25519.verify(sig, 0, publicKey, 0, msg, 0, msg.size) }.getOrDefault(false)
    }

    private fun signatureMessage(unsignedObject: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(OutpostProtocol.SIGNATURE_DOMAIN.size + unsignedObject.size)
        out.write(OutpostProtocol.SIGNATURE_DOMAIN)
        out.write(unsignedObject)
        return out.toByteArray()
    }
}
