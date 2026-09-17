package com.safphere.launcher.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.safphere.launcher.data.Prefs
import java.util.Locale

/** 全局TTS：中文朗读关键操作与AI回答（设备无中文语音库时静默跳过） */
object Speaker {

    @Volatile
    private var tts: TextToSpeech? = null

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                }
            }
        }
    }

    fun speak(text: String) {
        if (!Prefs.speakEnabled) return
        val engine = tts ?: return
        val result = runCatching {
            engine.setSpeechRate(0.9f)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "safphere_utterance")
        }
        if (result.isFailure) {
            // 语音引擎不可用，静默
        }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}
