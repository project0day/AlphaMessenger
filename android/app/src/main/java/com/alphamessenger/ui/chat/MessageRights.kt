package com.alphamessenger.ui.chat

/** How far a message may be deleted from this side. */
enum class DeleteScope { NONE, FOR_ME, FOR_EVERYONE }

/**
 * The same rule the receiving core enforces (author; channel posts — admins;
 * group and discussion messages — moderators), so the menu never offers what
 * the other side would refuse. A channel's feed belongs to its admins: a
 * subscriber has nothing to delete there.
 */
fun ChatState.deleteScope(m: MessageUi): DeleteScope = when {
    channel != null -> if (isAdmin) DeleteScope.FOR_EVERYONE else DeleteScope.NONE
    m.isOutgoing -> DeleteScope.FOR_EVERYONE
    isGroup && canModerate -> DeleteScope.FOR_EVERYONE
    else -> DeleteScope.FOR_ME
}

/** The narrowest scope over a selection. */
fun ChatState.deleteScope(ids: Collection<String>): DeleteScope =
    messages.filter { it.id in ids }.minOfOrNull { deleteScope(it) } ?: DeleteScope.NONE
