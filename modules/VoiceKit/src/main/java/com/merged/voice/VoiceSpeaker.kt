package com.merged.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Spoken replies. Every answer the app gives while voice control is on is also read aloud.
 * [onSpeaking] is told when speech starts/stops so the microphone can pause (otherwise the
 * app would hear its own voice).
 */
class VoiceSpeaker(
    context: Context,
    private val onSpeaking: (Boolean) -> Unit
) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: String? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ready = true
        try { tts.language = Locale.getDefault() } catch (e: Exception) { /* keep default */ }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { main.post { onSpeaking(true) } }
            override fun onDone(utteranceId: String?) { main.post { onSpeaking(false) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { onSpeaking(false) } }
        })
        pending?.let { pending = null; speak(it) }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) { pending = text; return }
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vf" + System.nanoTime())
        } catch (e: Exception) { /* ignore */ }
    }

    fun stop() {
        pending = null
        try { tts.stop() } catch (e: Exception) { /* ignore */ }
        main.post { onSpeaking(false) }
    }

    fun shutdown() {
        try { tts.stop(); tts.shutdown() } catch (e: Exception) { /* ignore */ }
    }
}
