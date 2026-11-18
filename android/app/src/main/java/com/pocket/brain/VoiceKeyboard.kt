package com.pocket.brain

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A keyboard with one job: hold to talk, and cleaned-up text is typed into any app. */
class VoiceKeyboard : InputMethodService() {
    private lateinit var voice: Voice
    private lateinit var status: TextView
    private lateinit var mic: Button
    private val ui = Handler(Looper.getMainLooper())
    private var blocked = false
    private var target = ""

    override fun onCreate() {
        super.onCreate()
        voice = Voice(this, Api(this))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        status = TextView(this).apply {
            text = "Hold to talk"
            gravity = Gravity.CENTER
            setTextColor(C.MUTED)
            setPadding(0, dp(10), 0, dp(10))
        }
        mic = Button(this).apply {
            text = "Hold to talk"
            textSize = 20f
            isAllCaps = false
            setTextColor(C.TEXT)
            background = rounded(C.PLUM, 28)
            stateListAnimator = null
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> begin()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end()
                }
                true
            }
        }
        val other = smallKey("Normal keyboard") { leave() }
        val del = smallKey("Delete") { currentInputConnection?.deleteSurroundingText(1, 0) }
        val row = LinearLayout(this).apply {
            setPadding(0, dp(8), 0, 0)
            addView(other, LinearLayout.LayoutParams(0, dp(44), 2f).apply { marginEnd = dp(8) })
            addView(del, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.INK)
            setPadding(dp(12), dp(2), dp(12), dp(12))
            addView(status)
            addView(mic, LinearLayout.LayoutParams(-1, dp(120)))
            addView(row)
        }
    }

    private fun smallKey(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(C.TEXT)
        background = rounded(C.SURFACE, 14)
        stateListAnimator = null
        setOnClickListener { click() }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        target = info.packageName ?: ""
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        blocked = v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        status.text = if (blocked) "Voice is off in password fields" else "Hold to talk"
    }

    private fun begin() {
        if (blocked || voice.recording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Open Chacha once and allow the microphone"
            return
        }
        try {
            voice.start()
            mic.background = rounded(C.PEACH, 28)
            status.text = "Listening..."
        } catch (e: Exception) {
            voice.cancel()
            status.text = "Microphone busy"
        }
    }

    private fun end() {
        mic.background = rounded(C.PLUM, 28)
        if (!voice.recording) return
        status.text = "Thinking..."
        voice.stop { text, error ->
            ui.post {
                if (text != null) {
                    if (text.isNotBlank()) {
                        val api = Api(this)
                        status.text = "Tidying..."
                        kotlin.concurrent.thread {
                            val clean = Polish.run(api, text, target)
                            ui.post {
                                currentInputConnection?.commitText("$clean ", 1)
                                status.text = "Hold to talk"
                            }
                        }
                        return@post
                    }
                    status.text = if (text.isBlank()) "Didn't catch that" else "Hold to talk"
                } else {
                    status.text = error
                }
            }
        }
    }

    private fun leave() {
        if (Build.VERSION.SDK_INT >= 28) {
            switchToPreviousInputMethod()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        voice.cancel()
        super.onFinishInputView(finishingInput)
    }
}
