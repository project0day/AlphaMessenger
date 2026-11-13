package com.alphamessenger.ui.chat.preview

/**
 * LinkPreviewModel — the data contract for a link preview.
 *
 * The full set of fields LinkPreviewRenderer can display. The data source
 * (currently sender metadata in media_ref; later an OpenGraph fetch through
 * the data layer) is known to neither Bubble nor the renderer.
 *
 *   data source → LinkPreviewModel → LinkPreviewState → LinkPreviewRenderer
 *
 * All fields except [url] are optional — the renderer builds a tidy fallback
 * from whatever is there (down to a bare domain + icon).
 */
data class LinkPreviewModel(
    /** Canonical preview URL. */
    val url: String,
    /** Page title (og:title / <title>). */
    val title: String = "",
    /** Short description (og:description). */
    val description: String = "",
    /** Preview image (og:image), absolute URL. */
    val image: String = "",
    /** Site icon (favicon), absolute URL. */
    val favicon: String = "",
    /** Human-readable domain/site name (og:site_name or host). */
    val domain: String = "",
    /** The full picture as an encrypted blob ref (the [image] is its small copy). */
    val imageRef: String = "",
    /** A video: a ▶ over the picture. */
    val video: Boolean = false,
) {
    companion object {
        /** Derive the domain from the URL when [domain] is blank. */
        fun domainOf(url: String): String = runCatching {
            java.net.URL(url).host?.removePrefix("www.") ?: ""
        }.getOrDefault("")
    }
}

/**
 * Preview state (modeled after LinkPreviewState): the renderer reacts
 * to the state, not to the presence/absence of fields. Currently [Loaded] and
 * [None] are used; [Loading]/[Error] are reserved for a future fetch.
 */
sealed class LinkPreviewState {
    /** No preview (no link in the text, or data unavailable). */
    object None : LinkPreviewState()

    /** Data is loading (skeleton for a future OpenGraph fetch). */
    data class Loading(val url: String) : LinkPreviewState()

    /** Preview ready to display. */
    data class Loaded(val model: LinkPreviewModel) : LinkPreviewState()

    /** Failed to fetch data (skeleton; the renderer shows a fallback built from [url]). */
    data class Error(val url: String) : LinkPreviewState()
}
