package cz.meshcore.meshward.data

import kotlinx.coroutines.flow.Flow

/**
 * The Chats list's data source: one summary row per conversation (latest message + unread count),
 * aggregated in SQL so the list never loads the whole message table. The ViewModel layers
 * title/public-key resolution and contact-only rows on top of these rows.
 */
class ChatListRepository(private val dao: MessageDao) {
    fun summaries(): Flow<List<ChatSummaryRow>> = dao.chatSummaries()
}
