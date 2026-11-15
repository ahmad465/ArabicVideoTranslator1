package com.example.arabicvideotranslator.core

/** نتيجة ترجمة المقاطع: الناجح يحمل النص المترجم، والفاشل يبقى null مع سبب. */
data class TranslationResult(
    val cues: List<Cue>,
    val translated: List<Cue?>,
    val failures: Map<Int, String>
) {
    val isComplete: Boolean get() = failures.isEmpty()
    val successCount: Int get() = translated.count { it != null }
}

class TranslationPipeline(
    private val provider: TranslationProvider,
    private val batchSize: Int = 20,
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 500,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    init { require(batchSize > 0 && maxRetries >= 0) }

    /**
     * يترجم على دفعات. دفعة فاشلة بخطأ قابل للإعادة تُعاد حتى maxRetries بتأخير أُسّي؛
     * الخطأ القاتل يوقف كل العمل ويُرمى (لا فائدة من المتابعة بمفتاح خاطئ).
     * عند فشل دفعة بعد استنفاد المحاولات تُسجَّل مقاطعها كفاشلة ويستمر الباقي.
     */
    @Throws(FatalProviderException::class)
    fun translate(
        cues: List<Cue>, sourceLang: String?, targetLang: String = "ar",
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): TranslationResult {
        val out = arrayOfNulls<Cue>(cues.size)
        val failures = LinkedHashMap<Int, String>()
        var done = 0
        for (start in cues.indices step batchSize) {
            val end = minOf(start + batchSize, cues.size)
            val texts = (start until end).map { cues[it].text }
            try {
                val result = withRetry { provider.translate(texts, sourceLang, targetLang) }
                if (result.size != texts.size) throw FatalProviderException(
                    "عدد النتائج (${result.size}) لا يطابق عدد المقاطع (${texts.size})")
                for (i in texts.indices) out[start + i] = cues[start + i].copy(text = result[i])
            } catch (e: RetryableProviderException) {
                for (i in start until end) failures[i] = e.message ?: "فشل مؤقت"
            }
            done = end
            onProgress(done, cues.size)
        }
        return TranslationResult(cues, out.toList(), failures)
    }

    private fun <T> withRetry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try { return block() } catch (e: RetryableProviderException) {
                if (attempt >= maxRetries) throw e
                sleeper(baseDelayMs shl attempt)
                attempt++
            }
        }
    }
}
