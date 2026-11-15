package com.example.arabicvideotranslator.core

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** استخراج معرّف فيديو YouTube (11 محرفاً) من الروابط الشائعة. منطق نقي بلا اعتماد على Android. */
object YouTubeUrl {
    private val idRegex = Regex("^[A-Za-z0-9_-]{11}$")
    private val allowedHosts = setOf(
        "youtube.com", "m.youtube.com", "music.youtube.com", "youtube-nocookie.com"
    )
    private val pathPrefixes = setOf("embed", "shorts", "live", "v")

    fun isValidId(id: String): Boolean = idRegex.matches(id)

    fun extractVideoId(raw: String?): String? {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return null
        if (isValidId(input)) return input
        val withScheme = if (input.contains("://")) input else "https://$input"
        val uri = try { URI(withScheme) } catch (_: Exception) { return null }
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        val host = (uri.host ?: return null).lowercase(Locale.ROOT).removePrefix("www.")
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        val id: String? = when {
            host == "youtu.be" -> segments.firstOrNull()
            host in allowedHosts -> when {
                segments.firstOrNull() == "watch" -> queryParam(uri.rawQuery, "v")
                segments.size >= 2 && segments[0] in pathPrefixes -> segments[1]
                else -> null
            }
            else -> null
        }
        return id?.takeIf { isValidId(it) }
    }

    private fun queryParam(rawQuery: String?, name: String): String? {
        if (rawQuery.isNullOrEmpty()) return null
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            if (key == name) {
                val value = if (idx >= 0) pair.substring(idx + 1) else ""
                return try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { null }
            }
        }
        return null
    }
}
