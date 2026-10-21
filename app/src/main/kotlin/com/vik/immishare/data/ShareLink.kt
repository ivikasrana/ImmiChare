package com.vik.immishare.data

import java.net.URI

data class ShareLink(val base: String, val key: String) {
    val host: String get() = runCatching { URI(base).host }.getOrNull() ?: base
    val url: String get() = "$base/share/$key"

    companion object {
        private val candidate = Regex("""(?i)\bhttps?://[^\s<>"']+""")
        private val shareSegment = Regex("""^(.*?)/share/([A-Za-z0-9_-]+)/?$""")

        fun find(text: String?): ShareLink? {
            if (text.isNullOrBlank()) return null
            return candidate.findAll(text).firstNotNullOfOrNull { parse(it.value.trimEnd('.', ',', ')', ']', ';', '!', '?')) }
        }

        fun parse(raw: String): ShareLink? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            val match = shareSegment.find(uri.rawPath ?: return null) ?: return null
            val prefix = match.groupValues[1].trimEnd('/')
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return ShareLink("$scheme://${hostPart.lowercase()}$port$prefix", match.groupValues[2])
        }
    }
}

fun ShareLink.looksLocal(): Boolean {
    val h = host.trim('[', ']').lowercase()
    if (h == "localhost" || !h.contains('.') && !h.contains(':')) return true
    if (listOf(".local", ".lan", ".home", ".internal", ".home.arpa").any { h.endsWith(it) }) return true
    if (h.contains(':')) return h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80") || h == "::1"
    val p = h.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size != 4) return false
    return p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
        (p[0] == 169 && p[1] == 254) || (p[0] == 100 && p[1] in 64..127)
}
