package com.example.arabicvideotranslator.translate

import com.example.arabicvideotranslator.core.FatalProviderException
import com.example.arabicvideotranslator.core.RetryableProviderException
import com.example.arabicvideotranslator.core.TranslationProvider

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * ترجمة مجانية على الجهاز بعد تنزيل نموذج اللغة. لا API key ولا رسوم لكل طلب.
 * يحتاج اتصال إنترنت لتنزيل نموذج اللغة أول مرة، ثم تتم الترجمة محلياً.
 */
class OnDeviceTranslationProvider(context: Context, private val sourceTag: String) : TranslationProvider {
    private val appContext = context.applicationContext

    override fun translate(texts: List<String>, sourceLang: String?, targetLang: String): List<String> {
        val sourceCode = TranslateLanguage.fromLanguageTag((sourceLang ?: sourceTag).trim())
            ?: throw FatalProviderException("رمز لغة المصدر غير مدعوم. أدخل رمزاً مثل en أو fr أو es.")
        val targetCode = TranslateLanguage.fromLanguageTag(targetLang)
            ?: throw FatalProviderException("لغة الهدف غير مدعومة.")
        if (sourceCode == targetCode) return texts

        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(sourceCode).setTargetLanguage(targetCode).build()
        )
        try {
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            return texts.map { text -> Tasks.await(translator.translate(text)) }
        } catch (e: Exception) {
            throw RetryableProviderException(
                "تعذّر تنزيل نموذج الترجمة أو الاتصال بخدمات Google Play. تحقق من الإنترنت والمساحة ثم أعد المحاولة.", e
            )
        } finally {
            translator.close()
        }
    }
}
