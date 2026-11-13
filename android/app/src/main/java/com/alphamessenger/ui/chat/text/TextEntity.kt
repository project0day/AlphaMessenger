package com.alphamessenger.ui.chat.text

/**
 * TextEntity — the single model of message-text entities.
 *
 * An entity is a [start, end) range in the source text plus a type and
 * payload. This is the ONLY model TextRenderer understands.
 *
 * AlphaMessenger architecture rule:
 *
 *   Bubble → MessageText → TextRenderer → TextEntity
 *
 * Bubble knows nothing about links/emails/phones. TextParser turns raw text
 * into a list of TextEntity; TextRenderer decides how each entity looks and
 * what happens on tap.
 *
 * Active now: [Url], [Email], [Phone], [AlphaInvite].
 * Skeleton (not rendered, but present in the model and pipeline so the
 * architecture stays intact later): [Mention], [Hashtag], [Markdown], [Spoiler].
 */
sealed class TextEntity {
    /** Range start in the source text (inclusive). */
    abstract val start: Int

    /** Range end in the source text (exclusive). */
    abstract val end: Int

    /** Whether the entity is clickable in normal mode (tap → action). */
    open val clickable: Boolean get() = false

    /** Regular web link. [url] is normalized (www. → https://). */
    data class Url(
        override val start: Int,
        override val end: Int,
        val url: String,
    ) : TextEntity() {
        override val clickable: Boolean get() = true
    }

    /** E-mail address. [address] has no mailto: prefix. */
    data class Email(
        override val start: Int,
        override val end: Int,
        val address: String,
    ) : TextEntity() {
        override val clickable: Boolean get() = true
    }

    /** Phone number. [number] as written, [normalized] digits/+ only. */
    data class Phone(
        override val start: Int,
        override val end: Int,
        val number: String,
        val normalized: String,
    ) : TextEntity() {
        override val clickable: Boolean get() = true
    }

    /** Alpha group invite: alpha://group/invite/<token>. */
    data class AlphaInvite(
        override val start: Int,
        override val end: Int,
        val link: String,
    ) : TextEntity() {
        override val clickable: Boolean get() = true
    }

    // --- Skeleton of future entities ---
    // NOT rendered or parsed now. Present in the model so adding support later
    // does not change the public TextRenderer contract.

    /** @-mention of a member: shown highlighted. */
    data class Mention(
        override val start: Int,
        override val end: Int,
        val handle: String,
    ) : TextEntity()

    /** #hashtag: a tap searches this chat for it. */
    data class Hashtag(
        override val start: Int,
        override val end: Int,
        val tag: String,
    ) : TextEntity() {
        override val clickable: Boolean get() = true
    }

    /** Inline markup (skeleton): bold/italic/code/strike etc. */
    data class Markdown(
        override val start: Int,
        override val end: Int,
        val kind: MarkdownKind,
    ) : TextEntity()

    /** Text hidden until tapped (skeleton). */
    data class Spoiler(
        override val start: Int,
        override val end: Int,
    ) : TextEntity()
}

/** Inline markup kinds (skeleton). */
enum class MarkdownKind { BOLD, ITALIC, STRIKETHROUGH, CODE, UNDERLINE, CODE_BLOCK }
