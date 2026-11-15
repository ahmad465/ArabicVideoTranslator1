package com.example.arabicvideotranslator.core

/** مقطع ترجمة واحد بتوقيته بالمللي ثانية. */
data class Cue(val startMs: Long, val endMs: Long, val text: String) {
    init { require(endMs >= startMs) { "endMs < startMs" } }
    val durationMs: Long get() = endMs - startMs
}

/** قارئ SRT وWebVTT. يتجاهل NOTE/STYLE/REGION والوسوم، ويحوّل التوقيتات إلى مللي ثانية. */
object SubtitleParser {
    private val timing = Regex(
        """^\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})\s*-->\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})(?:\s+.*)?$"""
    )
    private val tags = Regex("<[^>]*>")
    private val ssaTags = Regex("""\{\\[^}]*}""")

    fun parse(input: String): List<Cue> {
        val text = input.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = text.split(Regex("\n{2,}"))
        val cues = ArrayList<Cue>()
        for (block in blocks) {
            val lines = block.split('\n').map { it.trimEnd() }.dropWhile { it.isBlank() }
            if (lines.isEmpty()) continue
            val first = lines[0].trim()
            if (first.startsWith("WEBVTT") && lines.none { it.contains("-->") }) continue
            if (first.startsWith("NOTE") || first == "STYLE" || first == "REGION") continue
            val tIdx = lines.indexOfFirst { it.contains("-->") }
            if (tIdx < 0 || tIdx > 1) continue
            val m = timing.matchEntire(lines[tIdx]) ?: continue
            val g = m.groupValues
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])
            if (end < start) continue
            val body = lines.drop(tIdx + 1)
                .map { clean(it) }.filter { it.isNotEmpty() }.joinToString("\n")
            if (body.isEmpty()) continue
            cues += Cue(start, end, body)
        }
        return cues.sortedBy { it.startMs }
    }

    fun toPlainText(input: String): String = parse(input).joinToString("\n") { it.text }

    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        val hours = if (h.isEmpty()) 0L else h.toLong()
        val millis = frac.padEnd(3, '0').take(3).toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }

    private fun clean(line: String): String =
        line.replace(tags, "").replace(ssaTags, "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&lrm;", "").replace("&rlm;", "").replace("&amp;", "&")
            .trim()
}
