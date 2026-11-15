package com.example.arabicvideotranslator.core

/** قارئ JSON صغير (RFC 8259) يعيد Map/List/String/Double/Boolean/null. يرمي IllegalArgumentException عند الخطأ. */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        require(p.pos == text.length) { "محتوى زائد بعد JSON عند ${p.pos}" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0
        fun skipWs() { while (pos < s.length && s[pos] in " \t\n\r") pos++ }
        fun value(): Any? {
            require(pos < s.length) { "نهاية مفاجئة" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("محرف غير متوقع '$c' عند $pos")
            }
        }
        fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "قيمة غير صالحة عند $pos" }
            pos += word.length; return v
        }
        fun num(): Double {
            val st = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(st, pos).toDoubleOrNull() ?: throw IllegalArgumentException("رقم غير صالح عند $st")
        }
        fun str(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "نص غير منتهٍ" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "هروب غير منتهٍ" }
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "هروب \\u ناقص" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                            }
                            else -> throw IllegalArgumentException("هروب غير صالح \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            pos++; val out = ArrayList<Any?>(); skipWs()
            if (s.getOrNull(pos) == ']') { pos++; return out }
            while (true) {
                skipWs(); out += value(); skipWs()
                when (s.getOrNull(pos++)) { ',' -> continue; ']' -> return out; else -> throw IllegalArgumentException("مصفوفة غير صالحة عند $pos") }
            }
        }
        fun obj(): Map<String, Any?> {
            pos++; val out = LinkedHashMap<String, Any?>(); skipWs()
            if (s.getOrNull(pos) == '}') { pos++; return out }
            while (true) {
                skipWs()
                require(s.getOrNull(pos) == '"') { "مفتاح متوقع عند $pos" }
                val k = str(); skipWs()
                require(s.getOrNull(pos++) == ':') { "':' متوقعة عند $pos" }
                skipWs(); out[k] = value(); skipWs()
                when (s.getOrNull(pos++)) { ',' -> continue; '}' -> return out; else -> throw IllegalArgumentException("كائن غير صالح عند $pos") }
            }
        }
    }
}
