package com.example.util

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

class OfflineJapaneseTtsManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    var isReady: Boolean = false
        private set

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.JAPAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                val fallback = tts?.setLanguage(Locale.JAPANESE)
                isReady = fallback != TextToSpeech.LANG_MISSING_DATA && fallback != TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                isReady = true
            }
        }
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    /**
     * Speaks the original Japanese sentence with network timeout set to 0 (offline-only mode).
     */
    fun speakJapanese(text: String, onDone: (() -> Unit)? = null) {
        val clean = text.trim()
        if (clean.isBlank()) return

        if (!isReady) {
            // Attempt language set in case engine initialized late
            val res = tts?.setLanguage(Locale.JAPAN)
            if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                isReady = true
            } else {
                val res2 = tts?.setLanguage(Locale.JAPANESE)
                if (res2 != TextToSpeech.LANG_MISSING_DATA && res2 != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isReady = true
                }
            }
        }

        val params = Bundle().apply {
            putString("networkTimeout", "0")
            putInt("networkTimeout", 0)
        }

        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, "manga_offline_tts_${System.currentTimeMillis()}")
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
    }
}
