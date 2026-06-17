package cz.meshcore.meshward

import java.security.MessageDigest

/**
 * Inline message markup shared by the chat UI, the ViewModel, and the notifier.
 *
 *  - `#[<hash>]` — a hidden reply binding (DMs): the next message is a reply to the message whose
 *    visible text hashes to `<hash>`. Never shown to the user; stripped from display and previews.
 *  - `@[Name]`   — a mention token. Rendered as a highlighted chip inside a bubble, and flattened to
 *    `@Name` in plain-text previews / notifications.
 */

private val REPLY_TAG_RE = Regex("""#\[([0-9a-f]{6,8})]""")
private val MENTION_RE = Regex("""@\[([^]]+)]""")

/** Short, transport-independent content hash of a message's user-visible text — the reply binding id. */
fun replyHashOf(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(stripReplyTag(text).trim().toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(8)
}

/** The reply reference embedded in [text], or null. Hidden from the user. */
fun replyRefOf(text: String): String? = REPLY_TAG_RE.find(text)?.groupValues?.get(1)

/** [text] with its (leading) reply tag removed — what the user actually sees. */
fun stripReplyTag(text: String): String = REPLY_TAG_RE.replaceFirst(text, "").trimStart()

/** Prefixes a hidden reply tag binding [text] to the message whose visible text is [targetText]. */
fun withReplyTag(text: String, targetText: String): String = "#[${replyHashOf(targetText)}] $text"

/**
 * A flat, human-friendly one-liner for chat-list previews and notifications: the reply tag removed
 * and `@[Name]` mentions shown as plain `@Name`.
 */
fun messagePreview(raw: String): String =
    MENTION_RE.replace(stripReplyTag(raw)) { "@" + it.groupValues[1] }.trim()
