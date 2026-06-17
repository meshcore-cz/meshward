package cz.meshcore.meshward.outpost.protocol

/**
 * Outpost synchronization control frames (§16 / §19). Version 1 here implements the one frame a
 * Meshward peer needs to recover missed history without a server: a compact SYNC_REQUEST. A peer that
 * follows the named board answers by re-sending its newest verified objects for that board; because
 * objects are self-verifying and content-addressed, the requester simply ingests and deduplicates
 * them. This is the bounded, "good enough for two phones" reconciliation of §16, deferring full
 * inventory/Bloom exchange to a later version.
 *
 * SYNC_REQUEST layout (14 bytes):
 * ```
 * 0  1  frame type (0x14)
 * 1  8  board_id
 * 9  4  since_time (LE u32; 0 = no lower bound)
 * 13 1  max_objects (1..32)
 * ```
 */
object OutpostSync {
    const val FRAME_SYNC_REQUEST = 0x14
    const val MAX_OBJECTS_CAP = 32

    /** True if [frame] is an Outpost sync control frame rather than a persistent object. */
    fun isControlFrame(frame: ByteArray): Boolean =
        frame.isNotEmpty() && (frame[0].toInt() and 0xFF) == FRAME_SYNC_REQUEST

    fun encodeRequest(boardId: ByteArray, sinceSeconds: Long, maxObjects: Int): ByteArray {
        require(boardId.size == OutpostProtocol.BOARD_ID_SIZE) { "board_id must be 8 bytes" }
        val max = maxObjects.coerceIn(1, MAX_OBJECTS_CAP)
        val out = ByteArray(14)
        out[0] = FRAME_SYNC_REQUEST.toByte()
        System.arraycopy(boardId, 0, out, 1, 8)
        for (i in 0 until 4) out[9 + i] = ((sinceSeconds ushr (8 * i)) and 0xFF).toByte()
        out[13] = max.toByte()
        return out
    }

    data class SyncRequest(val boardId: ByteArray, val sinceSeconds: Long, val maxObjects: Int)

    fun decodeRequest(frame: ByteArray): SyncRequest? {
        if (frame.size != 14 || (frame[0].toInt() and 0xFF) != FRAME_SYNC_REQUEST) return null
        val boardId = frame.copyOfRange(1, 9)
        var since = 0L
        for (i in 0 until 4) since = since or ((frame[9 + i].toLong() and 0xFF) shl (8 * i))
        val max = (frame[13].toInt() and 0xFF).coerceIn(1, MAX_OBJECTS_CAP)
        return SyncRequest(boardId, since, max)
    }
}
