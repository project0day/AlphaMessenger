package com.alphamessenger.ui.chat.text

/**
 * TextParser — the single pipeline for parsing message text.
 *
 *   raw text → TextParser.parse() → List<TextEntity> → TextRenderer
 *
 * ONE pass over the registered matchers; no scattered regexes across the
 * code. Adding a new entity (mention/hashtag/markdown/spoiler) means adding
 * ONE matcher to [matchers], without touching the renderer.
 *
 * Rules:
 *  - Pure function (no Android/Compose dependencies) — easy to test.
 *  - Entities never overlap: on conflict the earlier start wins; on a tie
 *    the longer range wins (a URL absorbs the email inside it).
 *  - Matcher order = type priority, all else equal (invite > url > email >
 *    phone): invites and URLs must not be cut short by a phone/email matcher.
 */
object TextParser {

    // Alpha links, and a Nostr emoji set's address (opens the Nostr emoji import).
    private val INVITE_RE =
        Regex("""alpha://(?:group/invite|group|channel|stickers|theme)/[A-Za-z0-9_\-]+|nostr:naddr1[02-9ac-hj-np-z]+""")

    private val URL_RE =
        Regex("""(https?://|www\.)[^\s<>"']+""", RegexOption.IGNORE_CASE)

    // #tag: letters/digits/_ in any script, not glued to a word before it
    // (so "a#b" or a URL's "#fragment" stays plain).
    private val HASHTAG_RE =
        Regex("""(?<![\p{L}\p{N}_&/#])#[\p{L}\p{N}_]{1,64}""")

    // @mention: not glued to a word or an address before it (so mail stays mail).
    private val MENTION_RE =
        Regex("""(?<![\p{L}\p{N}_.@])@[\p{L}\p{N}_.]{1,40}""")

    private val EMAIL_RE =
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    // Phone: optional +, digits with separators, at least 7 significant
    // digits. Not greedy about spaces — so it does not swallow the rest of
    // the sentence.
    private val PHONE_RE =
        Regex("""\+?[0-9][0-9()\-. ]{5,}[0-9]""")


    /** Punctuation characters that are not part of a URL/invite tail. */
    private val TRAILING_URL_CHARS = charArrayOf('.', ',', ')', ']', '!', '?', ';', ':')

    /** Parse [text] into a sorted, non-overlapping list of entities. */
    fun parse(text: String): List<TextEntity> {
        if (text.isEmpty()) return emptyList()

        val found = ArrayList<TextEntity>(8)

        // Priority 1: Alpha Invite (a special case of a link — matched first
        // so the URL matcher does not eat it as a plain link).
        for (m in INVITE_RE.findAll(text)) {
            found += TextEntity.AlphaInvite(m.range.first, m.range.last + 1, m.value)
        }

        // Priority 2: URL.
        for (m in URL_RE.findAll(text)) {
            val (s, e, raw) = trimRange(text, m.range.first, m.range.last + 1, TRAILING_URL_CHARS)
            if (e <= s) continue
            val normalized = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
            found += TextEntity.Url(s, e, normalized)
        }

        // Priority 3: Email.
        for (m in EMAIL_RE.findAll(text)) {
            found += TextEntity.Email(m.range.first, m.range.last + 1, m.value)
        }

        // Priority 4: Phone (only with ≥7 digits, to avoid catching dates/numbers).
        for (m in PHONE_RE.findAll(text)) {
            val raw = m.value.trim()
            val digits = raw.count { it.isDigit() }
            if (digits < 7) continue
            val lead = text.substring(m.range.first, m.range.first + (m.value.length - m.value.trimStart().length))
            val s = m.range.first + lead.length
            val e = s + raw.length
            val normalized = raw.filter { it.isDigit() || it == '+' }
            found += TextEntity.Phone(s, e, raw, normalized)
        }

        // Priority 5: #hashtag (never inside a link: the overlap rule keeps the longer one).
        for (m in HASHTAG_RE.findAll(text)) {
            if (m.value.drop(1).all { it.isDigit() }) continue // "#1" is a number, not a tag
            found += TextEntity.Hashtag(m.range.first, m.range.last + 1, m.value.drop(1))
        }

        // Priority 6: @mention (an email's "@" is already taken by the email).
        for (m in MENTION_RE.findAll(text)) {
            val (s, e, raw) = trimRange(text, m.range.first, m.range.last + 1, TRAILING_URL_CHARS)
            if (e - s < 2) continue
            found += TextEntity.Mention(s, e, raw.drop(1))
        }

        return resolveOverlaps(found)
    }

    /** First web URL in the text (normalized), or null. For LinkPreview. */
    fun firstUrl(text: String): String? =
        (parse(text).firstOrNull { it is TextEntity.Url } as? TextEntity.Url)?.url

    /** First alpha-invite in the text, or null. */
    fun firstInvite(text: String): String? =
        (parse(text).firstOrNull { it is TextEntity.AlphaInvite } as? TextEntity.AlphaInvite)?.link

    /** First email in the text, or null. */
    fun firstEmail(text: String): String? =
        (parse(text).firstOrNull { it is TextEntity.Email } as? TextEntity.Email)?.address

    /** First phone number in the text (normalized), or null. */
    fun firstPhone(text: String): String? =
        (parse(text).firstOrNull { it is TextEntity.Phone } as? TextEntity.Phone)?.normalized

    // --- Internals ---

    /** Trim trailing punctuation off a range; returns (start, end, value). */
    private fun trimRange(text: String, start: Int, end: Int, trailing: CharArray): Triple<Int, Int, String> {
        var e = end
        while (e > start && text[e - 1] in trailing) e--
        return Triple(start, e, text.substring(start, e))
    }

    /**
     * Resolve overlaps: sort by (start, -length), then greedily keep the
     * entities that do not intersect the accepted ones. Guarantees a valid
     * AnnotatedString with no overlapping ranges.
     */
    private fun resolveOverlaps(input: List<TextEntity>): List<TextEntity> {
        val sorted = input.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
        val out = ArrayList<TextEntity>(sorted.size)
        var lastEnd = -1
        for (e in sorted) {
            if (e.start >= lastEnd) {
                out += e
                lastEnd = e.end
            }
        }
        return out
    }
}
