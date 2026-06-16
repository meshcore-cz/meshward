package cz.meshcore.meshward.notify

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import cz.meshcore.meshward.AvatarStyle
import cz.meshcore.meshward.AVATAR_STYLE_KEY
import cz.meshcore.meshward.IdentityStore
import cz.meshcore.meshward.dataStore
import cz.meshcore.meshward.hexToBytes
import cz.meshcore.meshward.nameFromPubKey
import cz.meshcore.meshward.shortHex
import cz.meshcore.meshward.toHex
import cz.meshcore.meshward.data.ChatDatabase
import cz.meshcore.meshward.data.NotifMode
import cz.meshcore.meshward.data.channelPeerId
import cz.meshcore.sidepath.chat.ChatChannel
import cz.meshcore.sidepath.chat.ChatKind
import cz.meshcore.sidepath.protocol.Identity
import cz.meshcore.sidepath.service.IncomingMessageBridge
import cz.meshcore.sidepath.service.ReceivedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Posts chat notifications for messages that arrive while the app UI is gone (task-killed) but the
 * foreground mesh service is still running. Registered once at [cz.meshcore.meshward.ChatApp] startup
 * and invoked by the service via [IncomingMessageBridge] for every received message.
 *
 * It is a *fallback*: while a [cz.meshcore.meshward.ChatViewModel] is alive ([MeshwardNotifier.uiAlive]
 * = true) it stands down, because the richer ViewModel path posts those notifications. When the UI is
 * dead it resolves the sender name, channel and mute mode straight from the active identity's Room DB
 * (no live topology) and posts through the same [MeshwardNotifier], so killed-state notifications look
 * the same as foreground ones.
 */
class HeadlessMessageNotifier(private val app: Context) : IncomingMessageBridge.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Bounded set of recently-handled message keys, so the same message arriving over several mesh
    // paths only notifies once (mirrors the ViewModel's `processed` dedup, independently).
    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > 256
    }

    override fun onIncoming(msg: ReceivedMessage) {
        // The live ViewModel handles notifications when present; only act when the UI is gone.
        if (MeshwardNotifier.uiAlive) return
        if (msg.isAck || msg.isTyping || msg.traceResponse != null || msg.bridgedDatagramId != null) return
        if (msg.chatKind != ChatKind.DIRECT_TEXT && msg.chatKind != ChatKind.CHANNEL_TEXT) return
        scope.launch { runCatching { handle(msg) } }
    }

    private suspend fun handle(msg: ReceivedMessage) {
        val identity = IdentityStore(app).active()
        val dao = ChatDatabase.get(app, identity.dbName).chatDao()

        val prefs = app.dataStore.data.first()
        val style = AvatarStyle.fromValue(prefs[AVATAR_STYLE_KEY])
        val myPubKeyHex = identity.seedHex.takeIf { it.length == 64 }
            ?.let { runCatching { Identity.fromSeed(it.hexToBytes()).publicKey.toHex() }.getOrNull() }.orEmpty()
        val myNodeHex = myPubKeyHex.take(20)
        val myName = prefs[stringPreferencesKey("${identity.id}_name")].orEmpty()
            .ifBlank { nameFromPubKey(myPubKeyHex) }

        when (msg.chatKind) {
            ChatKind.DIRECT_TEXT -> {
                val peer = msg.fromNodeId.toHex()
                val text = msg.text?.takeIf { it.isNotBlank() } ?: return
                if (!markSeen("d:${msg.datagramId.toHex()}:${text.hashCode()}")) return
                val contact = dao.contactByNode(peer)
                val pub = contact?.pubKeyHex.orEmpty()
                val name = contact?.localName?.takeIf { it.isNotBlank() }
                    ?: nameFromPubKey(pub).ifBlank { shortHex(peer) }
                post(dao, style, myName, peer, isChannel = false, title = "",
                    senderName = name, senderHex = peer, senderPub = pub, text = text, timeMs = msg.timestampMs)
            }

            ChatKind.CHANNEL_TEXT -> {
                val cp = msg.channelPayload ?: return
                val hash = ChatChannel.payloadChannelHash(cp) ?: return
                if (!markSeen("c:${cp.toHex()}")) return
                for (ch in dao.channelsByHash(hash)) {
                    val decoded = ChatChannel.decodePayload(ch.pskHex.hexToBytes(), cp) ?: continue
                    val author = decoded.senderLabel.ifBlank { "Someone" }
                    // Native channel messages carry the originating node (→ avatar key); bridged
                    // MeshCore ones are name-only.
                    val authorHex = if (msg.fromMeshCore) "" else msg.fromNodeId.toHex()
                    if (authorHex.isNotEmpty() && authorHex == myNodeHex) return // our own message
                    val pub = authorHex.takeIf { it.isNotEmpty() }?.let { dao.contactByNode(it)?.pubKeyHex }.orEmpty()
                    post(dao, style, myName, channelPeerId(ch.pskHex), isChannel = true, title = ch.name,
                        senderName = author, senderHex = authorHex, senderPub = pub,
                        text = decoded.text, timeMs = msg.timestampMs)
                    return
                }
            }
        }
    }

    private suspend fun post(
        dao: cz.meshcore.meshward.data.ChatDao,
        style: AvatarStyle,
        myName: String,
        peerHex: String,
        isChannel: Boolean,
        title: String,
        senderName: String,
        senderHex: String,
        senderPub: String,
        text: String,
        timeMs: Long,
    ) {
        if (text.isBlank()) return
        if (MeshwardNotifier.isConversationActive(peerHex)) return
        val mode = NotifMode.fromValue(dao.prefFor(peerHex)?.notifMode)
        when (mode) {
            NotifMode.NONE -> return
            NotifMode.MENTIONS -> if (isChannel && !(myName.isNotBlank() && text.contains("@$myName", ignoreCase = true))) return
            NotifMode.ALL -> {}
        }
        val name = senderName.ifBlank { "Someone" }
        val avatar = AvatarBitmap.get(
            style, senderHex.ifBlank { name }, name, senderPub.takeIf { it.length == 64 },
            dark = AvatarBitmap.isSystemDark(app),
        )
        MeshwardNotifier.notifyMessage(
            context = app,
            peerHex = peerHex,
            conversationTitle = title,
            isGroup = isChannel,
            senderName = name,
            senderKey = senderHex.ifBlank { name },
            senderAvatar = avatar,
            text = text,
            timeMs = if (timeMs > 0) timeMs else System.currentTimeMillis(),
            selfName = myName,
        )
    }

    @Synchronized
    private fun markSeen(key: String): Boolean = seen.put(key, true) == null
}
