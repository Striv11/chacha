package com.pocket.brain

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/** Pops up over whatever you're doing, listens straight away, answers, then gets out of the way. */
class OrbActivity : Activity() {
    private lateinit var api: Api
    private lateinit var voice: Voice
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var reply: TextView
    private lateinit var replyScroll: ScrollView
    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var spoke = false
    private val dictating by lazy { intent.getBooleanExtra("dictate", false) }

    private var startedAt = 0L
    private var lastSound = 0L
    private var heardSpeech = false

    private val meter = object : Runnable {
        override fun run() {
            if (!voice.recording) return
            val now = System.currentTimeMillis()
            val l = voice.level()
            orb.level = (l * 2.5f).coerceAtMost(1f)
            if (l > SPEECH_LEVEL || voice.heard) {
                heardSpeech = true
                lastSound = now
            }
            when {
                !voice.onPhone && heardSpeech && now - lastSound > 1500 -> return finishTalking()
                now - startedAt > 30_000 -> return finishTalking()
                !heardSpeech && now - startedAt > 8_000 -> {
                    voice.cancel()
                    orb.level = 0f
                    orb.mode = OrbView.Mode.IDLE
                    status.text = "I didn't hear anything. Tap me to try again."
                    return
                }
            }
            ui.postDelayed(this, 60)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        api = Api(this)
        voice = Voice(this, api).apply {
            onFinished = { finishTalking() }
            onPartial = {
                this@OrbActivity.heard.text = it
                this@OrbActivity.heard.visibility = View.VISIBLE
            }
        }
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = settle()
            @Deprecated("Required by the older callback")
            override fun onError(id: String?) = settle()
        })
        setContentView(buildScreen())
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen() else status.text = "Chacha needs the microphone."
    }

    override fun onResume() {
        super.onResume()
        FloatService.setHidden(true)
    }

    override fun onPause() {
        super.onPause()
        FloatService.setHidden(false)
        if (voice.recording) {
            voice.cancel()
            orb.mode = OrbView.Mode.IDLE
            status.text = "Tap me to talk"
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        voice.cancel()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        orb = OrbView(this).apply {
            contentDescription = "Chacha. Tap to talk or finish."
            setOnClickListener { tapped() }
        }
        status = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(C.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
        heard = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(C.MUTED)
            setPadding(dp(8), dp(6), dp(8), 0)
            visibility = View.GONE
        }
        reply = TextView(this).apply {
            textSize = 17f
            setTextColor(C.TEXT)
            setLineSpacing(0f, 1.2f)
            background = rounded(C.SURFACE, 22)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        replyScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(reply)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
            isClickable = true
            val maxReply = (resources.displayMetrics.heightPixels * 0.42).toInt()
            addView(replyScroll, LinearLayout.LayoutParams(-1, -2, 0f).apply { bottomMargin = dp(8) })
            replyScroll.viewTreeObserver.addOnGlobalLayoutListener {
                if (replyScroll.height > maxReply) {
                    replyScroll.layoutParams = replyScroll.layoutParams.apply { height = maxReply }
                }
            }
            addView(orb, LinearLayout.LayoutParams(dp(190), dp(190)))
            addView(status)
            addView(heard)
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.argb(235, 8, 8, 13))
            setOnClickListener { finish() }
            addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
    }

    private fun tapped() {
        when {
            voice.recording -> finishTalking()
            busy -> Unit
            else -> {
                tts?.stop()
                listen()
            }
        }
    }

    private fun listen() {
        try {
            voice.start()
        } catch (_: Exception) {
            voice.cancel()
            status.text = "The microphone is busy."
            return
        }
        replyScroll.visibility = View.GONE
        heard.visibility = View.GONE
        orb.mode = OrbView.Mode.LISTENING
        status.text = "Listening..."
        startedAt = System.currentTimeMillis()
        lastSound = startedAt
        heardSpeech = false
        ui.post(meter)
    }

    private fun finishTalking() {
        if (!voice.recording) return
        busy = true
        orb.level = 0f
        orb.mode = OrbView.Mode.THINKING
        status.text = "Catching that..."
        voice.stop(polish = false) { text, error ->
            ui.post {
                when {
                    text.isNullOrBlank() -> stopWith(error ?: "I didn't catch that. Tap me to try again.")
                    else -> if (dictating) dictated(text) else send(text)
                }
            }
        }
    }

    /** Puts the words into the box you were typing in, or on the clipboard if that isn't possible. */
    private fun dictated(text: String) {
        status.text = "Tidying..."
        val pkg = ScreenService.targetPackage()
        thread {
            val clean = Polish.run(api, text, pkg)
            ui.post {
                ScreenService.insertLater(applicationContext, clean)
                finish()
            }
        }
    }

    private fun stopWith(message: String) {
        busy = false
        orb.mode = OrbView.Mode.IDLE
        status.text = message
    }

    private fun send(q: String) {
        heard.text = "“$q”"
        heard.visibility = View.VISIBLE
        ChatLog.add(this, "me", q)
        val began = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val secs = (System.currentTimeMillis() - began) / 1000
                status.text = "Thinking...  ${secs}s"
                ui.postDelayed(this, 1000)
            }
        }
        tick.run()
        thread {
            val answer = try {
                val r = JSONObject(api.call("POST", "/ask", JSONObject().put("text", q).toString(), 900_000))
                listOf(r.optString("question"), r.optString("result"))
                    .firstOrNull { it.isNotBlank() && it != "null" }
                    ?: "I couldn't finish that one. Could you try again?"
            } catch (_: Exception) {
                "I can't reach your laptop. Check that it's on and Tailscale is running."
            }
            ChatLog.add(this, "plim", answer)
            ui.post {
                ui.removeCallbacks(tick)
                show(answer)
            }
        }
    }

    private fun show(answer: String) {
        busy = false
        reply.text = answer
        replyScroll.visibility = View.VISIBLE
        status.text = "Tap me to say more"
        val spoken = answer.replace(Regex("https?://\\S+"), "").replace(Regex("[*#_`]"), "").take(600)
        if (ttsReady) {
            spoke = true
            orb.mode = OrbView.Mode.SPEAKING
            tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "plim")
        } else {
            orb.mode = OrbView.Mode.IDLE
        }
    }

    private fun settle() {
        ui.post {
            if (voice.recording || busy) return@post
            orb.mode = OrbView.Mode.IDLE
            if (spoke && !isFinishing) {  // keep the conversation going; silence ends it
                spoke = false
                ui.postDelayed({ if (!voice.recording && !busy && !isFinishing) listen() }, 400)
            }
        }
    }

    private companion object {
        const val SPEECH_LEVEL = 0.06f
    }
}
