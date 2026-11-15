package com.example.arabicvideotranslator.core

import java.io.IOException
import java.net.URLEncoder

/**
 * محوّل Google Cloud Translation (Basic, v2) عبر REST. يحتاج مفتاح API يوفّره المستخدم (لا يُخزَّن في المشروع).
 * ملاحظة: مُختبَر هنا ضد خادم محلي وهمي فقط، لم يُختبر ضد الخدمة الحقيقية.
 */
class GoogleTranslateProvider(
    private val apiKey: String,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val endpoint: String = "https://translation.googleapis.com/language/translate/v2"
) : TranslationProvider {

    override fun translate(texts: List<String>, sourceLang: String?, targetLang: String): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (apiKey.isBlank()) throw FatalProviderException("مفتاح API فارغ")
        val form = StringBuilder()
        texts.forEach { form.append("q=").append(enc(it)).append('&') }
        form.append("target=").append(enc(targetLang)).append("&format=text")
        if (!sourceLang.isNullOrBlank()) form.append("&source=").append(enc(sourceLang))

        val resp = try {
            transport.post(
                endpoint,
                mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-goog-api-key" to apiKey
                ),
                form.toString()
            )
        } catch (e: IOException) {
            throw RetryableProviderException("خطأ شبكة: ${e.message}", e)
        }

        when {
            resp.status in 200..299 -> Unit
            resp.status == 429 || resp.status >= 500 ->
                throw RetryableProviderException("الخدمة غير متاحة مؤقتاً (HTTP ${resp.status})")
            resp.status == 401 || resp.status == 403 ->
                throw FatalProviderException("رفض المصادقة أو الحصة (HTTP ${resp.status}). تحقق من المفتاح.")
            else -> throw FatalProviderException("طلب مرفوض (HTTP ${resp.status}): ${errorMessage(resp.body)}")
        }
        return parseTranslations(resp.body, texts.size)
    }

    private fun parseTranslations(body: String, expected: Int): List<String> {
        val list = try {
            val root = MiniJson.parse(body) as? Map<*, *>
            val data = root?.get("data") as? Map<*, *>
            val tr = data?.get("translations") as? List<*>
            tr?.map { (it as? Map<*, *>)?.get("translatedText") as? String }
        } catch (e: IllegalArgumentException) {
            throw FatalProviderException("استجابة غير صالحة: ${e.message}", e)
        }
        if (list == null || list.any { it == null }) throw FatalProviderException("بنية استجابة غير متوقعة")
        if (list.size != expected) throw FatalProviderException("عدد الترجمات ${list.size} بدل $expected")
        return list.map { unescapeHtml(it!!) }
    }

    private fun errorMessage(body: String): String = try {
        val err = (MiniJson.parse(body) as? Map<*, *>)?.get("error") as? Map<*, *>
        (err?.get("message") as? String) ?: "غير معروف"
    } catch (_: Exception) { "غير معروف" }

    private fun unescapeHtml(s: String) = s.replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
