package com.alphamessenger.ui.browser

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Filter-list matcher for EasyList / AdGuard / hosts lists.
 *  - `^` is a separator: one char that is not alnum and not `-._%~`, or the end of the URL;
 *  - `||host` matches the host and its subdomains; `|…|` anchors; `*` wildcards;
 *  - `$third-party`, `domain=`, `important`; option classes a request interceptor
 *    cannot honour make the rule skipped rather than misfire;
 *  - `##` / `#@#` element hiding with per-domain exceptions;
 *  - network rules are indexed by their longest literal token, so a request is
 *    checked against a handful of rules even with 100k-rule lists.
 */
class AdBlockEngine {

    class NetRule(
        val segs: List<Seg>,
        val anchoredStartDomain: Boolean,
        val anchoredStart: Boolean,
        val anchoredEnd: Boolean,
        val trailingCaret: Boolean,
        val thirdParty: Boolean?,
        val important: Boolean,
        val isAllow: Boolean,
        val domainAllow: Set<String>?,
        val domainDeny: Set<String>?,
    ) {
        class Seg(val text: String, val sepBefore: String)
    }

    private class ElemRule(val domainAllow: List<String>, val domainDeny: List<String>, val selector: String)

    private val lock = ReentrantReadWriteLock()
    private val blockIndex = HashMap<String, MutableList<NetRule>>()
    private val allowIndex = HashMap<String, MutableList<NetRule>>()
    private val elemHide = ArrayList<ElemRule>()
    private val elemUnhide = ArrayList<Pair<List<String>, String>>()
    private var ruleCount = 0

    companion object {
        private val SEP_NOT = setOf('-', '.', '_', '%', '~')
        private val PROCS = listOf(":has-text", ":has(", ":matches", ":-abp-", ":style(", ":remove(", "[-abp-properties=")
        private val SKIP_OPTS = setOf("document", "popup", "generichide", "genericblock", "elemhide", "webrtc", "badfilter")
        private val HOSTS_LINE = Regex("""^(\d{1,3}\.){3}\d{1,3}\s+[\w.\-]+\s*$""")
        private val SPACES = Regex("""\s+""")

        fun isSepChar(c: Char): Boolean = !c.isLetterOrDigit() && c !in SEP_NOT

        /** Longest literal run (3+ chars): the index key, split the same way as request URLs. */
        fun keywordOf(pattern: String): String? {
            var best: String? = null
            val sb = StringBuilder()
            fun flush() {
                if (sb.length >= 3 && (best == null || sb.length > best!!.length)) best = sb.toString()
                sb.setLength(0)
            }
            for (c in pattern) if (c.isLetterOrDigit()) sb.append(c) else flush()
            flush()
            return best
        }

        fun hostOf(url: String): String = try {
            val sep = url.indexOf("://")
            if (sep < 0) "" else {
                var rest = url.substring(sep + 3)
                if (rest.contains('@')) rest = rest.substringAfter('@')
                rest.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
            }
        } catch (_: Exception) { "" }

        fun hostMatchesDomain(host: String, domain: String): Boolean {
            if (domain.startsWith("||")) { val d = domain.substring(2); return host == d || host.endsWith(".$d") }
            if (domain.startsWith(".")) return host.endsWith(domain)
            return host == domain || host.endsWith(".$domain")
        }
    }

    fun size(): Int = lock.read { ruleCount }

    fun clear() = lock.write {
        blockIndex.clear(); allowIndex.clear(); elemHide.clear(); elemUnhide.clear(); ruleCount = 0
    }

    /** One filter-list line; true if a rule was added. */
    fun addLine(line: String): Boolean {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("!") || l.startsWith("[")) return false

        if (HOSTS_LINE.containsMatchIn(l)) {
            val host = l.split(SPACES).getOrNull(1) ?: return false
            if (host.contains('.') && host.none { !it.isLetterOrDigit() && it != '.' && it != '-' }) return addLine("||$host^")
        }

        for (marker in listOf("#@#", "#?#", "##")) {
            val idx = l.indexOf(marker)
            if (idx < 0) continue
            if (marker == "#?#") return false
            val selector = l.substring(idx + marker.length).trim()
            if (selector.isEmpty() || selector.length > 400 || PROCS.any { selector.contains(it) }) return false
            val allow = ArrayList<String>()
            val deny = ArrayList<String>()
            for (d in l.substring(0, idx).split(',')) {
                val dd = d.trim()
                if (dd.isEmpty()) continue
                if (dd.startsWith("~")) deny.add(dd.removePrefix("~")) else allow.add(dd)
            }
            lock.write {
                if (marker == "#@#") elemUnhide.add(allow to selector) else elemHide.add(ElemRule(allow, deny, selector))
                ruleCount++
            }
            return true
        }

        var body = l
        val isAllow = body.startsWith("@@")
        if (isAllow) body = body.substring(2)

        var thirdParty: Boolean? = null
        var important = false
        var domainAllow: MutableSet<String>? = null
        var domainDeny: MutableSet<String>? = null
        val dollar = body.lastIndexOf('$')
        if (dollar > 0) {
            val opts = body.substring(dollar + 1)
            body = body.substring(0, dollar)
            for (o in opts.split(',')) {
                val opt = o.trim().lowercase()
                when {
                    opt == "third-party" || opt == "3p" -> thirdParty = true
                    opt == "~third-party" || opt == "1p" -> thirdParty = false
                    opt == "important" -> important = true
                    opt == "badfilter" -> return false
                    opt.startsWith("domain=") -> for (d in opt.substring(7).split('|')) {
                        val dd = d.trim()
                        if (dd.startsWith("~")) (domainDeny ?: mutableSetOf<String>().also { domainDeny = it }).add(dd.removePrefix("~"))
                        else if (dd.isNotEmpty()) (domainAllow ?: mutableSetOf<String>().also { domainAllow = it }).add(dd)
                    }
                    opt in SKIP_OPTS || opt.startsWith("csp") || opt.startsWith("removeparam") ||
                        opt.startsWith("denyallow") || opt.startsWith("replace") ||
                        opt.startsWith("dnsrewrite") || opt.startsWith("redirect-rule") -> return false
                    opt.startsWith("~") -> return false
                }
            }
        }
        if (body.isEmpty()) return false

        var pattern = body.trim().lowercase()
        var anchoredStartDomain = false
        var anchoredStart = false
        var anchoredEnd = false
        var trailingCaret = false
        if (pattern.startsWith("||")) { anchoredStartDomain = true; pattern = pattern.substring(2) }
        else if (pattern.startsWith("|")) { anchoredStart = true; pattern = pattern.substring(1) }
        if (pattern.endsWith("|")) { anchoredEnd = true; pattern = pattern.dropLast(1) }
        else if (pattern.endsWith("^")) { trailingCaret = true; pattern = pattern.dropLast(1) }
        if (pattern.isEmpty() || pattern.length > 600) return false
        if (pattern.length > 2 && pattern.startsWith("/") && pattern.endsWith("/")) return false
        if (pattern.any { it == '\\' || it == '(' || it == ')' || it == '[' || it == ']' || it == '{' || it == '}' || it == '+' }) {
            val cut = pattern.split('\\', '(', ')', '[', ']', '{', '}', '+').firstOrNull() ?: ""
            if (cut.length < 4) return false
            pattern = cut
            anchoredEnd = false; trailingCaret = false
        }

        val segs = ArrayList<NetRule.Seg>(4)
        val cur = StringBuilder()
        var sep = ""
        for (c in pattern) {
            when (c) {
                '*' -> { segs.add(NetRule.Seg(cur.toString(), sep)); cur.setLength(0); sep = "*" }
                '^' -> { segs.add(NetRule.Seg(cur.toString(), sep)); cur.setLength(0); sep = "^" }
                else -> cur.append(c)
            }
        }
        segs.add(NetRule.Seg(cur.toString(), sep))

        val kw = keywordOf(pattern) ?: return false
        val rule = NetRule(segs, anchoredStartDomain, anchoredStart, anchoredEnd, trailingCaret,
            thirdParty, important, isAllow, domainAllow?.toSet(), domainDeny?.toSet())
        lock.write {
            (if (isAllow) allowIndex else blockIndex).getOrPut(kw) { mutableListOf() }.add(rule)
            ruleCount++
        }
        return true
    }

    fun addList(text: String) { for (line in text.lineSequence()) addLine(line) }

    private fun matchRule(r: NetRule, urlLower: String, requestHost: String, pageHost: String): Boolean {
        if (r.thirdParty != null) {
            val isThird = requestHost.isNotEmpty() && pageHost.isNotEmpty() && !hostMatchesDomain(requestHost, pageHost.removePrefix("www."))
            if (isThird != r.thirdParty) return false
        }
        if (r.domainAllow != null || r.domainDeny != null) {
            if (r.domainDeny?.any { hostMatchesDomain(pageHost, it) } == true) return false
            if (r.domainAllow != null && r.domainAllow.none { hostMatchesDomain(pageHost, it) }) return false
        }
        var idx = 0
        var first = true
        for (seg in r.segs) {
            val text = seg.text
            if (text.isEmpty()) continue
            val found = urlLower.indexOf(text, idx)
            if (found < 0) return false
            if (first) {
                if (r.anchoredStart && found != 0) return false
                if (r.anchoredStartDomain) {
                    val dom = text.substringBefore('/')
                    if (requestHost != dom && !requestHost.endsWith(".$dom")) return false
                }
                first = false
            }
            if (seg.sepBefore == "^" && (found == 0 || !isSepChar(urlLower[found - 1]))) return false
            idx = found + text.length
        }
        if (r.anchoredEnd) {
            if (idx != urlLower.length) return false
        } else if (r.trailingCaret && idx < urlLower.length && !isSepChar(urlLower[idx])) return false
        return true
    }

    private fun matchesAny(index: HashMap<String, MutableList<NetRule>>, requestUrl: String, pageHost: String, requestHost: String, importantOnly: Boolean = false): Boolean {
        val urlLower = requestUrl.lowercase()
        val tokens = HashSet<String>()
        val sb = StringBuilder(urlLower.length)
        for (c in urlLower) {
            if (c.isLetterOrDigit()) sb.append(c) else {
                if (sb.length >= 3) tokens.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.length >= 3) tokens.add(sb.toString())
        for (part in requestHost.split('.')) if (part.length >= 3) tokens.add(part)
        lock.read {
            for (kw in tokens) {
                val rules = index[kw] ?: continue
                for (r in rules) {
                    if (importantOnly && !r.important) continue
                    if (matchRule(r, urlLower, requestHost, pageHost)) return true
                }
            }
        }
        return false
    }

    /** Safe from the WebView's network threads. */
    fun shouldBlock(requestUrl: String, pageUrl: String): Boolean {
        val requestHost = hostOf(requestUrl)
        if (requestHost.isEmpty()) return false
        val pageHost = hostOf(pageUrl)
        if (matchesAny(blockIndex, requestUrl, pageHost, requestHost, importantOnly = true)) return true
        if (matchesAny(allowIndex, requestUrl, pageHost, requestHost)) return false
        return matchesAny(blockIndex, requestUrl, pageHost, requestHost)
    }

    /**
     * The element-hiding stylesheet for [pageHost]. One rule per selector: in a
     * single combined rule one selector this WebView does not know drops them all.
     */
    fun elementHideCss(pageHost: String): String {
        val kept = ArrayList<String>()
        lock.read {
            val excluded = elemUnhide.filter { it.first.isEmpty() || it.first.any { d -> hostMatchesDomain(pageHost, d) } }.map { it.second }.toHashSet()
            for (e in elemHide) {
                if (e.domainDeny.isNotEmpty() && e.domainDeny.any { hostMatchesDomain(pageHost, it) }) continue
                if (e.domainAllow.isNotEmpty() && e.domainAllow.none { hostMatchesDomain(pageHost, it) }) continue
                if (e.selector !in excluded) kept.add(e.selector)
            }
        }
        if (kept.isEmpty()) return ""
        val css = StringBuilder(kept.size * 32)
        for (s in kept) css.append(s.replace("*/", "* /")).append("{display:none!important}\n")
        return css.toString()
    }
}
