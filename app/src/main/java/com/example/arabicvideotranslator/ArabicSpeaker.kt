package com.example.arabicvideotranslator

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** غلاف لمحرك Android TextToSpeech المثبت على الجهاز (لا يرسل أي صوت أو نص إلى خدمة خارجية). */
class ArabicSpeaker(context: Context, private val onReady: (ArabicSpeaker) -> Unit) {
    enum class Status { INITIALIZING, READY, NO_ENGINE, NO_ARABIC_VOICE }

    @Volatile var status: Status = Status.INITIALIZING
        private set
    private var engine: TextToSpeech? = null
    private var counter = 0

    init {
        engine = TextToSpeech(context.applicationContext) { code ->
            val e = engine
            status = when {
                code != TextToSpeech.SUCCESS || e == null -> Status.NO_ENGINE
                else -> when (e.setLanguage(Locale.forLanguageTag("ar"))) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> Status.NO_ARABIC_VOICE
                    else -> Status.READY
                }
            }
            onReady(this)
        }
    }

    val isReady: Boolean get() = status == Status.READY

    /** ينطق النص فوراً (يقطع أي نطق جارٍ) بسرعة rate. يعيد false إن لم يكن المحرك جاهزاً. */
    fun speak(text: String, rate: Float = 1f): Boolean {
        val e = engine ?: return false
        if (!isReady || text.isBlank()) return false
        e.setSpeechRate(rate)
        return e.speak(text.take(TextToSpeech.getMaxSpeechInputLength()), TextToSpeech.QUEUE_FLUSH, null, "cue_${counter++}") ==
            TextToSpeech.SUCCESS
    }

    fun stop() { engine?.stop() }

    fun shutdown() { engine?.stop(); engine?.shutdown(); engine = null; status = Status.NO_ENGINE }
}
