package pl.marcin.openclaw.chat

/** Prevents a late/stale history response from repainting or rolling back live UI state. */
object ConversationHistory {
    fun shouldReplace(local: List<ChatMessage>, incoming: List<ChatMessage>): Boolean {
        if (local.isEmpty()) return incoming.isNotEmpty()
        if (incoming.size <= local.size) return false
        return true
    }
}
