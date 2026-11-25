package com.merged.voice

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Offline, continuous speech recogniser built on Vosk. It listens until stop() is called and
 * hands every finished phrase to [onResult]; live words go to [onPartial].
 * Nothing leaves the phone and the Google speech service is not used.
 */
class VoiceEngine(
    context: Context,
    private val onResult: (List<String>) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFail: (String) -> Unit
) {
    private val app = context.applicationContext
    private var service: SpeechService? = null
    private var active = false
    private var loading = false
    @Volatile private var paused = false

    companion object {
        @Volatile
        private var model: Model? = null
    }

    fun start(): Boolean {
        if (active) return true
        active = true
        val m = model
        if (m != null) {
            begin(m)
            return true
        }
        if (loading) return true
        loading = true
        onPartial("Loading voice model (first time only)…")
        StorageService.unpack(
            app, "model-en-us", "model",
            { loaded: Model ->
                loading = false
                model = loaded
                if (active) begin(loaded)
            },
            { e: java.io.IOException ->
                loading = false
                active = false
                onFail("Voice model is missing or could not be loaded (${e.message}). Rebuild the app with internet so it can download the model.")
            }
        )
        return true
    }

    /** Pause while the app is speaking so it does not hear itself. */
    fun setPaused(p: Boolean) {
        paused = p
        try { service?.setPause(p) } catch (e: Exception) { /* ignore */ }
    }

    fun stop() {
        active = false
        paused = false
        val s = service
        service = null
        if (s != null) {
            try { s.stop() } catch (e: Exception) { /* ignore */ }
            try { s.shutdown() } catch (e: Exception) { /* ignore */ }
        }
    }

    /** Final results: the best guess first, then the alternatives (the app picks the one that is a command). */
    private fun finalTexts(json: String?): List<String> =
        try {
            val o = JSONObject(json ?: "{}")
            val arr = o.optJSONArray("alternatives")
            val out = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i)?.optString("text", "")?.trim() ?: ""
                    if (t.isNotEmpty()) out.add(t)
                }
            }
            if (out.isEmpty()) {
                val t = o.optString("text", "").trim()
                if (t.isNotEmpty()) out.add(t)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }

    private fun textOf(json: String?, key: String): String =
        try {
            JSONObject(json ?: "{}").optString(key, "").trim()
        } catch (e: Exception) {
            ""
        }

    private fun begin(m: Model) {
        if (!active) return
        try {
            val old = service
            service = null
            if (old != null) {
                try { old.stop() } catch (e: Exception) { /* ignore */ }
                try { old.shutdown() } catch (e: Exception) { /* ignore */ }
            }
            val rec = Recognizer(m, 16000.0f)
            try { rec.setMaxAlternatives(3) } catch (e: Throwable) { /* older library: single answer */ }
            val s = SpeechService(rec, 16000.0f)
            service = s
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (!active || paused) return
                    val t = textOf(hypothesis, "partial")
                    if (t.isNotEmpty()) onPartial(t)
                }

                override fun onResult(hypothesis: String?) {
                    if (!active || paused) return
                    val l = finalTexts(hypothesis)
                    if (l.isNotEmpty()) onResult(l)
                }

                override fun onFinalResult(hypothesis: String?) {
                    if (!active || paused) return
                    val l = finalTexts(hypothesis)
                    if (l.isNotEmpty()) onResult(l)
                }

                override fun onError(exception: Exception?) {
                    if (!active) return
                    stop()
                    onFail("Microphone problem: ${exception?.message ?: "unknown"}")
                }

                override fun onTimeout() {}
            })
            if (paused) { try { s.setPause(true) } catch (e: Exception) { /* ignore */ } }
        } catch (e: Exception) {
            stop()
            onFail("Could not start the microphone: ${e.message}")
        }
    }
}
