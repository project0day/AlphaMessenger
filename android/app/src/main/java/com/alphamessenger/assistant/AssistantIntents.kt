package com.alphamessenger.assistant

/**
 * What the user's last message asks for, read from its words (in the app's
 * languages): which tools the model is offered for it, and the one it must call
 * when the request leaves no doubt. Fewer tools to pick from, fewer wrong picks.
 */
object AssistantIntents {
    class Intent(
        /** The tools to offer; null = all (nothing recognised: an open question). */
        val tools: Set<String>?,
        /** The tool the reply must call, or null. */
        val force: String?,
        /** A theme or a background was asked for (the reply is checked for a call). */
        val look: Boolean,
    )

    // Word starts only: "фон" is inside "телефон", "тему" inside "систему", "обои" inside "обоих".
    private const val W = "(?<!\\p{L})"
    private const val E = "(?!\\p{L})"
    private val THEME = Regex("${W}(тем[уыаеой]$E|тема$E|theme|tema|thème)")
    private val BACKGROUND = Regex("${W}(узор|обо(и|ев|ями|ях)$E|фон(ы|а|ом|е)?$E|pattern|wallpaper|background|fondo|fond d|motif)")
    private val ACT = Regex("сдела|созд|придума|нарису|поменя|давай|ещ[её]|друг|нов|make|create|design|draw|another|new|change|haz|crea|otro|nuevo|fais|crée|autre|nouv")
    private val GO = Regex("давай|делай|do it|vas-y|^\\s*(да|го|ок|ага|ok|yes|go|sure|vale|sí|oui)[\\s!.]*$")
    private val RELAY = Regex("${W}(реле|relay|связ|подключ|connect|сеть|network|tor$E|тор$E|прокси|proxy)")
    private val WEB = Regex("найди|поищ|поиск|загугл|интернет|сайт|ссылк|новост|погод|курс |search|google|web|site|link|news|weather|http|busca|cherche")
    private val IMAGE = Regex("картин|изображ|фото|рисунок|image|picture|photo|imagen|dessin")
    private val VOICE = Regex("голос|озвуч|скажи вслух|voice|aloud|speak|voz|voix")

    private val LOOK_TOOLS = setOf("propose_theme", "propose_pattern", "find_icons", "propose_wallpaper", "generate_image")
    private val ALWAYS = setOf("remember", "forget")

    fun of(turns: List<ChatTurn>): Intent {
        val i = turns.indexOfLast { it.role == "user" }
        if (i < 0) return Intent(null, null, false)
        var text = turns[i].text.lowercase()
        // "Go on" carries what the assistant itself was about to do.
        if (text.trim().length <= 24 && GO.containsMatchIn(text)) {
            text += " " + turns.subList(0, i).lastOrNull { it.role == "assistant" }?.text?.lowercase().orEmpty() + " сделай"
        }
        val tools = LinkedHashSet<String>()
        val themeAsked = THEME.containsMatchIn(text)
        val look = (themeAsked || BACKGROUND.containsMatchIn(text)) && ACT.containsMatchIn(text)
        if (look || themeAsked || BACKGROUND.containsMatchIn(text)) tools += LOOK_TOOLS
        if (RELAY.containsMatchIn(text)) tools += setOf("check_relays", "propose_relay_changes")
        if (WEB.containsMatchIn(text)) tools += setOf("web_search", "read_page", "open_url")
        if (IMAGE.containsMatchIn(text)) tools += setOf("generate_image", "propose_wallpaper")
        if (VOICE.containsMatchIn(text)) tools += "speak"
        val force = when {
            !look -> null
            themeAsked -> "propose_theme"
            else -> "propose_pattern"
        }
        return Intent(if (tools.isEmpty()) null else tools + ALWAYS, force, look)
    }
}
