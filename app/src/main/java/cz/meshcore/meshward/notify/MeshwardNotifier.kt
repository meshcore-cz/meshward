package cz.meshcore.meshward.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import cz.meshcore.meshward.ChatActivity
import cz.meshcore.meshward.R

/**
 * App-side chat notifications, built around [NotificationCompat.MessagingStyle] + [Person] so Android
 * renders them like a real messaging app (sender avatar, conversation grouping). Driven entirely from
 * [cz.meshcore.meshward.ChatViewModel] where names, channels, mute state and avatars are resolved —
 * the mesh service no longer posts its own notifications.
 *
 * One OS notification per conversation (keyed by [peerHex]); repeated unread messages accumulate into
 * the same [MessagingStyle] so they stack rather than stacking up as separate notifications. All
 * per-conversation notifications share a [GROUP_KEY] with a summary, so several active conversations
 * bundle under one group on the shade.
 */
object MeshwardNotifier {
    private const val CHANNEL_ID = "meshward_messages"
    private const val GROUP_KEY = "meshward_messages_group"
    private const val SUMMARY_ID = 1
    const val EXTRA_OPEN_PEER = "cz.meshcore.meshward.OPEN_PEER"

    /** One buffered message line within a conversation's running [MessagingStyle]. */
    private data class Line(val person: Person?, val text: String, val timeMs: Long, val avatar: Bitmap?)

    private class Convo(val title: String, val isGroup: Boolean) {
        val lines = ArrayDeque<Line>()
    }

    // Conversation buffers, keyed by peerHex. Bounded per conversation; cleared on cancel().
    private val convos = HashMap<String, Convo>()

    @Volatile
    private var activeConversationPeerHex: String? = null

    /**
     * True while the app UI (ViewModel) is alive and posting notifications itself. The headless
     * fallback ([cz.meshcore.meshward.notify.HeadlessMessageNotifier]) only posts when this is false,
     * so we never double-notify: the rich ViewModel path handles the foreground/background-stopped
     * case, the fallback handles the task-killed case.
     */
    @Volatile
    var uiAlive: Boolean = false

    fun setActiveConversation(peerHex: String?) {
        activeConversationPeerHex = peerHex?.lowercase()
    }

    fun isConversationActive(peerHex: String): Boolean =
        activeConversationPeerHex == peerHex.lowercase()

    /**
     * Records one incoming message for [peerHex] and (re)posts that conversation's notification.
     * [senderName]/[senderKey]/[senderAvatar] describe the message author; [selfName] is our own
     * display name (for the MessagingStyle "me" person). [conversationTitle] is the channel name for
     * group chats (ignored for 1:1).
     */
    @Synchronized
    fun notifyMessage(
        context: Context,
        peerHex: String,
        conversationTitle: String,
        isGroup: Boolean,
        senderName: String,
        senderKey: String,
        senderAvatar: Bitmap,
        text: String,
        timeMs: Long,
        selfName: String,
    ) {
        if (!canPost(context)) return
        createChannel(context)

        val convo = convos.getOrPut(peerHex) { Convo(conversationTitle, isGroup) }
        val sender = Person.Builder()
            .setName(senderName)
            .setKey(senderKey.ifBlank { senderName })
            .setIcon(IconCompat.createWithBitmap(senderAvatar))
            .build()
        convo.lines.addLast(Line(sender, text, timeMs, senderAvatar))
        while (convo.lines.size > MAX_LINES) convo.lines.removeFirst()

        pushShortcut(context, peerHex, conversationTitle, sender, senderAvatar)
        post(context, peerHex, convo, selfName)
        postSummary(context)
    }

    private fun post(context: Context, peerHex: String, convo: Convo, selfName: String) {
        val me = Person.Builder().setName(selfName.ifBlank { "Me" }).setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me)
        if (convo.isGroup) {
            style.isGroupConversation = true
            style.conversationTitle = convo.title
        }
        convo.lines.forEach { style.addMessage(it.text, it.timeMs, it.person) }

        val last = convo.lines.lastOrNull()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setStyle(style)
            .setShortcutId(shortcutId(peerHex))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_KEY)
            .setAutoCancel(true)
            .setContentIntent(openConversationIntent(context, peerHex))
            .setWhen(last?.timeMs ?: System.currentTimeMillis())
        last?.avatar?.let { builder.setLargeIcon(it) }

        notify(context, peerHex.hashCode(), builder.build())
    }

    /**
     * The group summary that bundles every active conversation. Required on Android 7+ for the
     * per-conversation notifications to collapse into a single group rather than floating loose.
     */
    private fun postSummary(context: Context) {
        val convoCount = convos.size
        val total = convos.values.sumOf { it.lines.size }
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText("Meshward"))
            .setContentTitle("New messages")
            .setContentText(
                if (convoCount <= 1) "$total new message${if (total == 1) "" else "s"}"
                else "$total messages in $convoCount chats",
            )
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        notify(context, SUMMARY_ID, summary)
    }

    /** Dismisses [peerHex]'s notification and clears its buffer (called on read / conversation open). */
    @Synchronized
    fun cancel(context: Context, peerHex: String) {
        val hadBuffer = convos.remove(peerHex) != null
        val manager = NotificationManagerCompat.from(context)
        // Always cancel by id, even with no in-memory buffer: after a process restart the OS
        // notification (posted by the headless fallback in a previous process) outlives our buffer.
        manager.cancel(peerHex.hashCode())
        when {
            convos.isEmpty() -> manager.cancel(SUMMARY_ID)
            hadBuffer -> postSummary(context)
        }
    }

    /** Clears all buffered conversations (e.g. on identity switch). */
    @Synchronized
    fun cancelAll(context: Context) {
        convos.clear()
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(SUMMARY_ID)
    }

    // ---- intents / shortcuts -------------------------------------------------

    private fun openConversationIntent(context: Context, peerHex: String): PendingIntent {
        val intent = Intent(context, ChatActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_PEER, peerHex)
        }
        return PendingIntent.getActivity(
            context,
            peerHex.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun shortcutId(peerHex: String) = "conversation-$peerHex"

    /** A long-lived conversation shortcut so Android 11+ renders this as a conversation notification. */
    private fun pushShortcut(
        context: Context,
        peerHex: String,
        title: String,
        person: Person,
        avatar: Bitmap,
    ) {
        runCatching {
            val intent = Intent(context, ChatActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_OPEN_PEER, peerHex)
            }
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(peerHex))
                .setShortLabel(title.ifBlank { person.name ?: "Chat" })
                .setLongLived(true)
                .setIcon(IconCompat.createWithBitmap(avatar))
                .setPerson(person)
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    // ---- plumbing ------------------------------------------------------------

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        )
            .setName("Chat messages")
            .setDescription("Notifications for received Meshward messages")
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private const val MAX_LINES = 8
}
