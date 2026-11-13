package com.alphamessenger.ui.chat.media

/**
 * What the viewer pages through. The viewer never decides this itself — the
 * opener defines the set, which in turn drives the filmstrip, the counter
 * and whether paging is allowed.
 */
data class MediaCollection(
    val type: Type,
    val items: List<MediaItem>,
    val startIndex: Int = 0,
) {
    enum class Type { SINGLE, ALBUM, CHAT, SHARED_MEDIA, SEARCH }

    val size: Int get() = items.size

    /** Nothing to page: no filmstrip, no counter, no swipes. */
    val isSingle: Boolean get() = type == Type.SINGLE || items.size <= 1

    val showsFilmstrip: Boolean get() = !isSingle
    val showsCounter: Boolean get() = !isSingle
    val allowsPaging: Boolean get() = !isSingle

    companion object {
        fun single(item: MediaItem) = MediaCollection(Type.SINGLE, listOf(item), 0)

        fun album(items: List<MediaItem>, startIndex: Int) =
            MediaCollection(Type.ALBUM, items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))

        fun chat(items: List<MediaItem>, startIndex: Int) =
            MediaCollection(Type.CHAT, items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))

        fun sharedMedia(items: List<MediaItem>, startIndex: Int) =
            MediaCollection(Type.SHARED_MEDIA, items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    }
}
