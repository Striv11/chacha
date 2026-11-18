package com.pocket.brain

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Turns speech into text on the phone, so only text ever leaves it.
 * If this phone can't do that, it falls back to sending the clip to the laptop.
 * All calls must be made on the main thread.
 */
class Voice(private val ctx: Context, private val api: Api) {
    private val prefs = ctx.getSharedPreferences("brain", Context.MODE_PRIVATE)
    private var recognizer: SpeechRecognizer? = null
    private var rec: MediaRecorder? = null
    private val file = File(ctx.cacheDir, "clip.m4a")

    private var listening = false
    private var waiting: ((String?, String?) -> Unit)? = null
    private var ended = false
    private var finalText: String? = null
    private var finalError: String? = null
    private var rms = 0f

    /** True once the recogniser has noticed someone speaking. */
    var heard = false
        private set

    /** Called when the phone decides you've stopped talking. */
    var onFinished: (() -> Unit)? = null

    /** Called with the words so far, while you are still speaking. */
    var onPartial: ((String) -> Unit)? = null

    val recording: Boolean get() = listening || rec != null

    val onPhone: Boolean
        get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) && System.currentTimeMillis() > prefs.getLong("phone_speech_retry", 0) && !prefs.getBoolean("voice_laptop", false)

    fun level(): Float {
        if (listening) return ((rms - 1f) / 9f).coerceIn(0f, 1f)
        return try {
            (rec?.maxAmplitude ?: 0) / 32767f
        } catch (_: RuntimeException) {
            0f
        }
    }

    fun start() {
        heard = false
        if (onPhone) startOnPhone() else startRecording()
    }

    private fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

    private fun startOnPhone() {
        ended = false
        finalText = null
        finalError = null
        waiting = null
        rms = 0f
        val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                Log.i(TAG, "phone recogniser ready")
            }
            override fun onBeginningOfSpeech() {
                heard = true
            }
            override fun onRmsChanged(v: Float) {
                rms = v
            }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {
                onFinished?.invoke()
            }
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(r: Bundle?) {
                first(r)?.let { onPartial?.invoke(it) }
            }
            override fun onResults(r: Bundle?) {
                Log.i(TAG, "phone recogniser finished")
                complete(first(r) ?: "", null)
            }
            override fun onError(code: Int) {
                Log.i(TAG, "phone recogniser error $code")
                complete(null, describe(code))
            }
        })
        sr.startListening(speechIntent())
        recognizer = sr
        listening = true
    }

    private fun first(r: Bundle?) =
        r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap to try again."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
            languageMissing()
            "Getting voice ready on this phone. Try again in a minute."
        }
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chacha needs the microphone."
        else -> "Voice didn't work. Try again?"
    }

    /** Asks Android for the language pack, and uses the laptop until the phone is ready. */
    private fun languageMissing() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                recognizer?.triggerModelDownload(speechIntent())
            } catch (_: Exception) {
            }
        }
        prefs.edit().putLong("phone_speech_retry", System.currentTimeMillis() + 10 * 60_000).apply() // the pack may still be downloading
    }

    private fun complete(text: String?, error: String?) {
        if (ended) return
        ended = true
        finalText = text
        finalError = error
        release()
        val done = waiting
        if (done != null) {
            waiting = null
            listening = false
            done(text, error)
        } else {
            onFinished?.invoke()
        }
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    /** Stops listening and calls back with the text, or an error message. */
    fun stop(polish: Boolean = true, done: (text: String?, error: String?) -> Unit) {
        if (listening) {
            if (ended) {
                listening = false
                done(finalText, finalError)
            } else {
                waiting = done
                recognizer?.stopListening()
            }
            return
        }
        stopRecording(polish, done)
    }

    fun cancel() {
        ended = true
        waiting = null
        listening = false
        recognizer?.cancel()
        release()
        rec?.let {
            try {
                it.stop()
            } catch (_: RuntimeException) {
            }
            it.release()
        }
        rec = null
        file.delete()
    }

    // Fallback only: used when this phone has no speech recogniser of its own.

    private fun startRecording() {
        Log.i(TAG, "using laptop transcription")
        rec = MediaRecorder(ctx).apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(64000)
            setOutputFile(file.path)
            prepare()
            start()
        }
    }

    private fun stopRecording(polish: Boolean, done: (String?, String?) -> Unit) {
        val r = rec ?: return
        rec = null
        try {
            r.stop()
        } catch (_: RuntimeException) {
            r.release()
            file.delete()
            done(null, "Too short. Hold while you speak.")
            return
        }
        r.release()
        thread {
            try {
                val json = JSONObject(api.upload("/transcribe?polish=$polish", file.readBytes()))
                done(json.getString("text"), null)
            } catch (e: Exception) {
                done(null, "Could not reach the laptop: ${e.message}")
            } finally {
                file.delete()
            }
        }
    }

    private companion object {
        const val TAG = "PlimVoice"
    }
}

fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
