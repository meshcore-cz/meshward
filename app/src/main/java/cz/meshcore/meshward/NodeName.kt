package cz.meshcore.meshward

import cz.meshcore.sidepath.protocol.defaultNodeNameFromHex

/**
 * The deterministic bootstrap name derived from a node's public key hex. Delegates to the
 * canonical generator in `:sidepath-protocol` so it matches
 * Go/ESP32. This is only a fallback — the node's real (possibly user-chosen) name now travels on
 * the wire in ANNOUNCE/NODE_INFO and is preferred wherever it's known. Returns "" for a partial key.
 */
fun nameFromPubKey(pubKeyHex: String): String = defaultNodeNameFromHex(pubKeyHex)
