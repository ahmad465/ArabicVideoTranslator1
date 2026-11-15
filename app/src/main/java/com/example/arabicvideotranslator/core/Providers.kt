package com.example.arabicvideotranslator.core

/** أخطاء الخدمات الخارجية. القابلة لإعادة المحاولة (شبكة، 429، 5xx) منفصلة عن القاتلة (مفتاح خاطئ، طلب غير صالح). */
sealed class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
class RetryableProviderException(message: String, cause: Throwable? = null) : ProviderException(message, cause)
class FatalProviderException(message: String, cause: Throwable? = null) : ProviderException(message, cause)

/** مزود ترجمة. يُستدعى على خيط خلفي (blocking). يجب أن يعيد قائمة بنفس الحجم والترتيب. */
interface TranslationProvider {
    @Throws(ProviderException::class)
    fun translate(texts: List<String>, sourceLang: String?, targetLang: String): List<String>
}

/**
 * مزود تفريغ كلام (STT) من ملف صوتي يملكه المستخدم أو مصرّح به.
 * واجهة فقط في هذه النسخة: لا يوجد تنفيذ حقيقي بعد، ولا يستخرج التطبيق صوت YouTube.
 */
interface SpeechToTextProvider {
    @Throws(ProviderException::class)
    fun transcribe(audioFilePath: String, languageHint: String?): List<Cue>
}
