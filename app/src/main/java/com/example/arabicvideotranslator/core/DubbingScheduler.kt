package com.example.arabicvideotranslator.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * يقرر أي مقطع يُنطق الآن بحسب موضع تشغيل الفيديو الفعلي (من YouTube IFrame API).
 * - ينطق كل مقطع مرة واحدة فقط.
 * - يتخطى المقاطع التي فات وقتها بأكثر من graceMs (بعد القفز للأمام مثلاً) بدل نطقها متأخرة.
 * - عند القفز للخلف أكثر من seekBackMs يعيد ضبط المقاطع المنطوقة.
 */
class DubbingScheduler(
    cues: List<Cue>,
    private val graceMs: Long = 400,
    private val seekBackMs: Long = 1000
) {
    private val cues = cues.sortedBy { it.startMs }
    private val handled = BooleanArray(this.cues.size)
    private var lastPos = Long.MIN_VALUE

    fun reset() { handled.fill(false); lastPos = Long.MIN_VALUE }

    /** @return المقطع الواجب نطقه الآن، أو null. استدعِه دورياً (مثلاً كل 200–250ms). */
    fun poll(positionMs: Long): Cue? {
        if (lastPos != Long.MIN_VALUE && positionMs < lastPos - seekBackMs) {
            // قفز للخلف: أعد فتح المقاطع التي تبدأ بعد الموضع الجديد
            for (i in cues.indices) if (cues[i].startMs > positionMs) handled[i] = false
        }
        lastPos = positionMs
        var pick: Cue? = null
        for (i in cues.indices) {
            val c = cues[i]
            if (handled[i]) continue
            if (c.startMs > positionMs) break
            handled[i] = true
            if (positionMs <= c.endMs + graceMs) pick = c // آخر مقطع حان وقته لا يزال صالحاً
        }
        return pick
    }
}

/** تقدير سرعة نطق مناسبة كي يتسع النص العربي في مدة المقطع. */
object SpeechFit {
    const val BASE_WORDS_PER_MINUTE = 150.0 // تقدير تقريبي لمحرك TTS عند السرعة 1.0
    const val MIN_RATE = 0.8f
    const val MAX_RATE = 2.0f

    data class Fit(val rate: Float, val fits: Boolean)

    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    fun fit(text: String, availableMs: Long): Fit {
        val words = wordCount(text)
        if (words == 0) return Fit(1f, true)
        val normalMs = words / BASE_WORDS_PER_MINUTE * 60_000.0
        if (availableMs <= 0) return Fit(MAX_RATE, false)
        val needed = normalMs / availableMs
        val rate = max(MIN_RATE.toDouble(), min(MAX_RATE.toDouble(), needed)).toFloat()
        return Fit(rate, needed <= MAX_RATE + 1e-9)
    }

    @Suppress("unused")
    fun estimatedMs(text: String, rate: Float): Long =
        ceil(wordCount(text) / BASE_WORDS_PER_MINUTE * 60_000.0 / rate).toLong()
}
