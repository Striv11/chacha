package com.pocket.brain

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var api: Api
    private lateinit var voice: Voice
    private lateinit var avatar: ImageView
    private lateinit var breath: ObjectAnimator
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var banners: LinearLayout
    private lateinit var chat: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var tasks: LinearLayout
    private lateinit var tasksScroll: ScrollView
    private lateinit var composer: LinearLayout
    private lateinit var input: EditText
    private lateinit var micButton: ImageButton

    private val ui = Handler(Looper.getMainLooper())
    private var thinkingRow: View? = null
    private var thinkingTick: Runnable? = null
    private var tab = 1
    private var brainMode = "offline"

    private val inputHint = "Message Chacha"

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        bootstrapEmulator()
        if (!getSharedPreferences("brain", MODE_PRIVATE).getBoolean("intro_done", false)) {
            startActivity(Intent(this, IntroActivity::class.java))
            finish()
            return
        }
        api = Api(this)
        voice = Voice(this, api)

        window.statusBarColor = C.INK
        window.navigationBarColor = C.INK
        setContentView(buildScreen())
        startBreathing()
        renderHistory()
        selectTab(1)
    }

    /** Lets the local debug emulator be configured without putting secrets in the APK. */
    private fun bootstrapEmulator() {
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debug || !intent.getBooleanExtra("chacha_emulator_setup", false)) return
        val url = intent.getStringExtra("chacha_url")?.trim().orEmpty()
        val token = intent.getStringExtra("chacha_token")?.trim().orEmpty()
        if (url.isEmpty() || token.isEmpty()) return
        Api(this).apply {
            this.url = url
            this.token = token
        }
        getSharedPreferences("brain", MODE_PRIVATE).edit().putBoolean("intro_done", true).apply()
        Control.choose(
            this,
            setOf(
                "com.android.settings",
                "com.android.chrome",
                "com.google.android.apps.maps",
                "com.google.android.youtube",
            ),
        )
    }

    override fun onResume() {
        super.onResume()
        if (thinkingRow == null) renderHistory()
        FloatService.setHidden(true)
        FloatService.startIfAllowed(this)
        ScreenService.stop()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        FloatService.setHidden(false)
    }

    override fun onDestroy() {
        if (::breath.isInitialized) {
            breath.cancel()
            voice.cancel()
        }
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- layout ----

    private fun buildScreen(): View {
        banners = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(chat)
        }
        tasks = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        tasksScroll = ScrollView(this).apply { addView(tasks) }
        val body = FrameLayout(this).apply {
            addView(chatScroll)
            addView(tasksScroll)
        }
        composer = buildComposer()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.INK)
            addView(buildHeader())
            addView(banners)
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(composer)
        }
    }

    private fun buildHeader(): View {
        avatar = ImageView(this).apply {
            setImageResource(R.drawable.ic_plim)
        }
        val title = TextView(this).apply {
            text = "Chacha"
            textSize = 19f
            setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        statusDot = View(this).apply { background = rounded(C.MUTED, 4) }
        statusText = TextView(this).apply {
            text = "Waking up"
            textSize = 13f
            setTextColor(C.MUTED)
        }
        val status = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
            addView(statusText)
        }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(status)
        }
        val settings = ImageButton(this).apply {
            setImageResource(R.drawable.ic_tune)
            imageTintList = ColorStateList.valueOf(C.MUTED)
            background = null
            contentDescription = "Settings"
            setOnClickListener { showSettings() }
        }
        val todo = ImageButton(this).apply {
            setImageResource(R.drawable.ic_tasks)
            imageTintList = ColorStateList.valueOf(C.MUTED)
            background = null
            contentDescription = "To-dos"
            setOnClickListener { selectTab(if (tab == 1) 2 else 1) }
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(8), dp(10))
            addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
            addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            addView(todo, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }

    private fun selectTab(index: Int) {
        tab = index
        chatScroll.visibility = if (index == 1) View.VISIBLE else View.GONE
        tasksScroll.visibility = if (index == 2) View.VISIBLE else View.GONE
        composer.visibility = if (index == 1) View.VISIBLE else View.GONE
        if (index == 2) loadTasks()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildComposer(): LinearLayout {
        input = EditText(this).apply {
            hint = inputHint
            textSize = 16f
            maxLines = 4
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            background = rounded(C.SURFACE, 22)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        micButton = roundButton(R.drawable.ic_mic, C.SURFACE, "Hold to talk").apply {
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> startTalking()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopTalking()
                }
                true
            }
        }
        val send = roundButton(R.drawable.ic_send, C.PLUM, "Send").apply { setOnClickListener { ask() } }
        return LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(6), dp(12), dp(12))
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(micButton, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) })
            addView(send, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) })
        }
    }

    private fun roundButton(icon: Int, color: Int, label: String) = ImageButton(this).apply {
        setImageResource(icon)
        background = rounded(color, 23)
        contentDescription = label
    }

    private fun startBreathing() {
        breath = ObjectAnimator.ofPropertyValuesHolder(
            avatar,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
        ).apply {
            duration = 2400
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    // ---- chat ----

    private fun renderHistory() {
        chat.removeAllViews()
        val log = ChatLog.all(this)
        if (log.length() == 0) {
            chat.addView(emptyState())
            return
        }
        for (i in 0 until log.length()) {
            val m = log.getJSONObject(i)
            chat.addView(bubble(m.getString("t"), m.getString("r") == "me"))
        }
        scrollDown()
    }

    private fun emptyState(): View = LinearLayout(this).apply {
        tag = "empty"
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(-1, -1)
        addView(TextView(context).apply {
            text = "What's on your mind?"
            textSize = 26f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(C.TEXT)
        })
        addView(
            TextView(context).apply {
                text = "Ask about your messages, people or day."
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(C.MUTED)
            },
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(24) },
        )
        for (q in listOf("Who's waiting on a reply from me?", "What should I focus on today?", "What can you do?")) {
            addView(
                TextView(context).apply {
                    text = q
                    textSize = 15f
                    setTextColor(C.TEXT)
                    background = rounded(C.SURFACE, 16)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setOnClickListener { ask(q) }
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
            )
        }
    }

    private fun bubble(text: String, mine: Boolean): View {
        val t = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(C.TEXT)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            maxWidth = (resources.displayMetrics.widthPixels * if (mine) 0.78 else 0.92).toInt()
            if (mine) {
                background = rounded(C.RAISED, 20)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            } else {
                setPadding(dp(2), dp(8), dp(2), dp(8))
            }
        }
        return LinearLayout(this).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
            addView(t)
        }
    }

    private fun addMessage(role: String, text: String) {
        chat.findViewWithTag<View>("empty")?.let { chat.removeView(it) }
        ChatLog.add(this, role, text)
        chat.addView(bubble(text, role == "me"))
        scrollDown()
    }

    private fun scrollDown() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun note(text: String) {
        chat.findViewWithTag<View>("empty")?.let { chat.removeView(it) }
        chat.addView(
            TextView(this).apply {
                this.text = text
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(C.MUTED)
                setPadding(0, dp(6), 0, dp(6))
            },
        )
        scrollDown()
    }

    private fun showThinking() {
        val label = TextView(this).apply {
            textSize = 15f
            setTextColor(C.MUTED)
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        val row = LinearLayout(this).apply {
            setPadding(0, dp(4), 0, dp(4))
            addView(label)
        }
        chat.addView(row)
        thinkingRow = row
        breath.duration = 700
        val started = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val secs = ((System.currentTimeMillis() - started) / 1000).toInt()
                label.text = "Thinking" + ".".repeat(secs % 3 + 1) + "  ${secs}s"
                ui.postDelayed(this, 1000)
            }
        }
        thinkingTick = tick
        tick.run()
        scrollDown()
    }

    private fun hideThinking() {
        thinkingTick?.let { ui.removeCallbacks(it) }
        thinkingTick = null
        thinkingRow?.let { chat.removeView(it) }
        thinkingRow = null
        breath.duration = 2400
    }

    private fun ask(preset: String? = null) {
        val q = (preset ?: input.text.toString()).trim()
        if (q.isEmpty()) return
        if (thinkingRow != null) {
            note("Chacha is still working on your last message.")
            return
        }
        if (preset == null) input.setText("")
        addMessage("me", q)
        showThinking()
        thread {
            val reply = try {
                val r = JSONObject(api.call("POST", "/ask", JSONObject().put("text", q).toString(), 900_000))
                listOf(r.optString("question"), r.optString("result"))
                    .firstOrNull { it.isNotBlank() && it != "null" }
                    ?: "I couldn't finish that one. Could you try again?"
            } catch (_: Exception) {
                "I can't reach your laptop. Check that it's on and Tailscale is running."
            }
            runOnUiThread {
                hideThinking()
                addMessage("plim", reply)
                refresh()
            }
        }
    }

    // ---- voice ----

    private fun startTalking() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            note("Please allow the microphone in Settings.")
            return
        }
        try {
            voice.start()
            input.hint = "Listening..."
            micButton.background = rounded(C.PEACH, 23)
        } catch (_: Exception) {
            voice.cancel()
            note("The microphone is busy.")
        }
    }

    private fun stopTalking() {
        micButton.background = rounded(C.SURFACE, 23)
        if (!voice.recording) return
        input.hint = "Writing down what you said..."
        voice.stop { text, error ->
            runOnUiThread {
                input.hint = inputHint
                if (text != null) input.setText(text) else note(error ?: "Voice didn't work. Try again?")
            }
        }
    }

    // ---- status and banners ----

    private fun trackingOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun refresh() {
        renderBanners(emptyList())
        if (api.url.isEmpty()) {
            setStatus("Not set up yet", C.MUTED)
            return
        }
        thread {
            try {
                val list = JSONArray(api.call("GET", "/agents"))
                val waiting = (0 until list.length()).map { list.getJSONObject(it) }
                    .filter { it.getString("status") in listOf("proposed", "waiting") && it.optString("origin") != "chat" }
                val raw = JSONArray(api.call("GET", "/actions"))
                val todo = (0 until raw.length()).map { raw.getJSONObject(it) }
                val mode = JSONObject(api.call("GET", "/settings")).optString("brain", "offline")
                runOnUiThread {
                    brainMode = mode
                    setStatus(if (mode == "claude") "Awake, thinking with Claude" else "Awake, offline", C.GOOD)
                    renderBanners(waiting, todo)
                }
            } catch (_: Exception) {
                runOnUiThread { setStatus("Can't reach your laptop", C.BAD) }
            }
        }
        if (tab == 2) loadTasks()
    }

    private fun setStatus(text: String, color: Int) {
        statusText.text = text
        statusDot.background = rounded(color, 4)
    }

    private fun renderBanners(waiting: List<JSONObject>, todo: List<JSONObject> = emptyList()) {
        banners.removeAllViews()
        if (api.url.isEmpty()) {
            banners.addView(banner("Connect to your laptop", "Tap to scan the code", C.PEACH) {
                startActivity(Intent(this, IntroActivity::class.java))
            })
        }
        if (!trackingOn()) {
            banners.addView(banner("Turn on message tracking", "Chacha can't see your messages yet", C.PEACH) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        }
        if (api.floating && !Settings.canDrawOverlays(this)) {
            banners.addView(banner("Turn on the floating ring", "Allow Chacha to appear on top of other apps", C.PLUM) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
        }
        if (todo.isNotEmpty()) {
            val title = if (todo.size == 1) "1 thing ready for your OK" else "${todo.size} things ready for your OK"
            banners.addView(banner(title, todo.first().getString("summary"), C.PEACH) { showActions(todo, 0) })
        }
        if (waiting.isNotEmpty()) {
            val title = if (waiting.size == 1) "1 thing needs you" else "${waiting.size} things need you"
            banners.addView(banner(title, "Tap to answer", C.PLUM) { showPending(waiting, 0) })
        }
    }

    private fun banner(title: String, sub: String, accent: Int, click: () -> Unit): View {
        val bar = View(this).apply { background = rounded(accent, 2) }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(C.TEXT)
            })
            addView(TextView(context).apply {
                text = sub
                textSize = 13f
                setTextColor(C.MUTED)
            })
        }
        return LinearLayout(this).apply {
            background = rounded(C.SURFACE, 16)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(16), dp(2), dp(16), dp(8))
            }
            addView(bar, LinearLayout.LayoutParams(dp(4), dp(32)).apply { marginEnd = dp(12) })
            addView(texts)
            setOnClickListener { click() }
        }
    }

    // ---- things only your tap can do ----

    private fun showActions(items: List<JSONObject>, i: Int) {
        if (i >= items.size) return
        val a = items[i]
        val id = a.getInt("id")
        val kind = a.getString("kind")
        val p = JSONObject(a.getString("payload"))
        val box = if (kind == "reply" || kind == "email") EditText(this).apply { setText(p.optString("text")) } else null
        val b = AlertDialog.Builder(this).setCancelable(false).setTitle(a.getString("summary"))
        if (box != null) {
            b.setView(LinearLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(box, LinearLayout.LayoutParams(-1, -2))
            })
        } else if (kind == "event" && p.optString("location").isNotEmpty()) {
            b.setMessage(p.optString("location"))
        }
        b.setPositiveButton(if (box != null) "Send" else "Do it") { _, _ ->
            if (kind == "email") {
                post("/actions/$id/send", JSONObject().put("text", box?.text?.toString() ?: "").toString())
                showActions(items, i + 1)
                return@setPositiveButton
            }
            val error = Actions.run(this, kind, p, box?.text?.toString())
            report(id, if (error == null) "done" else "failed", error)
            if (error != null) note(error)
            showActions(items, i + 1)
        }
        b.setNegativeButton("Skip") { _, _ ->
            report(id, "rejected", null)
            showActions(items, i + 1)
        }
        b.show()
    }

    private fun report(id: Int, status: String, error: String?) {
        post("/actions/$id/result", JSONObject().put("status", status).put("note", error ?: "").toString())
    }

    // ---- to-dos ----

    private fun loadTasks() {
        if (api.url.isEmpty()) return showTasksMessage("Finish setup to see your to-dos.")
        thread {
            try {
                val list = JSONArray(api.call("GET", "/tasks"))
                val items = (0 until list.length()).map { list.getJSONObject(it) }
                runOnUiThread {
                    if (items.isEmpty()) {
                        showTasksMessage("Nothing on your list. Nice.")
                    } else {
                        tasks.removeAllViews()
                        items.forEach { tasks.addView(taskRow(it)) }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { showTasksMessage("I can't reach your laptop right now.") }
            }
        }
    }

    private fun showTasksMessage(text: String) {
        tasks.removeAllViews()
        tasks.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(C.MUTED)
            setPadding(0, dp(48), 0, 0)
        })
    }

    private fun taskRow(t: JSONObject): View {
        val id = t.getInt("id")
        val due = t.optString("due").takeIf { it.isNotBlank() && it != "null" }
        val circle = View(this).apply {
            background = rounded(C.RAISED, 13)
            contentDescription = "Mark done"
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = t.getString("title")
                textSize = 16f
                setTextColor(C.TEXT)
            })
            if (due != null) {
                addView(TextView(context).apply {
                    text = due
                    textSize = 13f
                    setTextColor(C.PEACH)
                })
            }
        }
        val row = LinearLayout(this).apply {
            background = rounded(C.SURFACE, 16)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            addView(circle, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        }
        circle.setOnClickListener {
            circle.background = rounded(C.GOOD, 13)
            circle.isEnabled = false
            thread {
                try {
                    api.call("POST", "/tasks/$id/done", "{}")
                    runOnUiThread { loadTasks() }
                } catch (_: Exception) {
                    runOnUiThread {
                        circle.background = rounded(C.RAISED, 13)
                        circle.isEnabled = true
                    }
                }
            }
        }
        return row
    }

    // ---- approvals ----

    private fun showPending(items: List<JSONObject>, i: Int) {
        if (i >= items.size) return
        val a = items[i]
        val id = a.getInt("id")
        val next = { showPending(items, i + 1) }
        val b = AlertDialog.Builder(this).setCancelable(false)
        if (a.getString("status") == "proposed") {
            b.setTitle("Should I start this?").setMessage(a.getString("goal"))
                .setPositiveButton("Yes, go") { _, _ ->
                    post("/agents/$id/approve", "{}")
                    next()
                }
                .setNegativeButton("Skip") { _, _ -> next() }
        } else {
            val box = EditText(this).apply { hint = "Your answer" }
            b.setTitle("Quick question").setMessage(a.optString("question")).setView(box)
                .setPositiveButton("Send") { _, _ ->
                    post("/agents/$id/reply", JSONObject().put("text", box.text.toString()).toString())
                    next()
                }
                .setNegativeButton("Skip") { _, _ -> next() }
        }
        b.show()
    }

    private fun post(path: String, body: String) {
        thread {
            val ok = try {
                api.call("POST", path, body)
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                selectTab(1)
                note(if (ok) "Got it." else "I can't reach your laptop right now.")
                refresh()
            }
        }
    }

    // ---- settings ----

    private fun showSettings() {
        val url = EditText(this).apply {
            hint = "http://laptop-tailscale-ip:8765"
            setText(api.url)
            isSingleLine = true
        }
        val token = EditText(this).apply {
            hint = "BRAIN_TOKEN from .env"
            setText(api.token)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val tracking = settingsRow(if (trackingOn()) "Message tracking: on" else "Message tracking: off") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val keyboard = settingsRow("Voice keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        val brain = settingsRow(brainLabel()) {}
        brain.setOnClickListener {
            if (brainMode == "claude") {
                setBrain("offline", brain)
            } else {
                AlertDialog.Builder(this).setTitle("Think with Claude?")
                    .setMessage("Your messages, calendar, contacts and chats will be sent to Anthropic whenever Chacha thinks, and it costs money per use. Banking stays out. Switch back to Offline any time.")
                    .setPositiveButton("Use Claude") { _, _ -> setBrain("claude", brain) }
                    .setNegativeButton("Stay offline", null)
                    .show()
            }
        }
        val ring = settingsRow(if (api.floating) "Floating ring: on" else "Floating ring: off") {}
        ring.setOnClickListener {
            api.floating = !api.floating
            ring.text = if (api.floating) "Floating ring: on" else "Floating ring: off"
            if (api.floating) FloatService.startIfAllowed(this) else FloatService.stop(this)
            refresh()
        }
        val control = settingsRow(if (ScreenService.active != null) "App control and sent messages: on" else "App control and sent messages: off") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val appsRow = settingsRow("Apps Chacha can use: ${Control.allowed(this).size}") {}
        appsRow.setOnClickListener { pickApps(appsRow) }
        val prefs = getSharedPreferences("brain", MODE_PRIVATE)
        fun engine() = if (prefs.getBoolean("voice_laptop", false)) "Voice text: on laptop (better Hinglish)" else "Voice text: on phone (faster)"
        val voiceRow = settingsRow(engine()) {}
        voiceRow.setOnClickListener {
            prefs.edit().putBoolean("voice_laptop", !prefs.getBoolean("voice_laptop", false)).apply()
            voiceRow.text = engine()
        }
        val widget = settingsRow("Add Chacha to home screen") {
            getSystemService(AppWidgetManager::class.java)
                .requestPinAppWidget(ComponentName(this, PlimWidget::class.java), null, null)
        }
        val clear = settingsRow("Clear this chat") {
            ChatLog.clear(this)
            renderHistory()
        }
        val queued = TextView(this).apply {
            text = "Messages waiting to send: ${Queue(context).size()}"
            setPadding(0, dp(12), 0, 0)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(url)
            addView(token)
            addView(brain)
            addView(tracking)
            addView(keyboard)
            addView(ring)
            addView(control)
            addView(appsRow)
            addView(voiceRow)
            addView(widget)
            addView(clear)
            addView(queued)
        }
        AlertDialog.Builder(this).setTitle("Settings").setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                api.url = url.text.toString()
                api.token = token.text.toString()
                refresh()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Banking and payment apps never appear in this list. */
    private fun pickApps(row: TextView) {
        val all = Control.installed(this)
        val chosen = Control.allowed(this).toMutableSet()
        val ticks = all.map { it.first in chosen }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Apps Chacha can use")
            .setMultiChoiceItems(all.map { it.second }.toTypedArray(), ticks) { _, i, on ->
                if (on) chosen.add(all[i].first) else chosen.remove(all[i].first)
            }
            .setPositiveButton("Save") { _, _ ->
                Control.choose(this, chosen)
                row.text = "Apps Chacha can use: ${Control.allowed(this).size}"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun brainLabel() = if (brainMode == "claude") "Intelligence: Claude" else "Intelligence: Offline"

    private fun setBrain(mode: String, row: TextView) {
        thread {
            val error = try {
                api.call("POST", "/settings", JSONObject().put("brain", mode).toString())
                null
            } catch (e: Exception) {
                if (e.message?.contains("409") == true) "Add a Claude key on the laptop first." else "I can't reach your laptop right now."
            }
            runOnUiThread {
                if (error == null) {
                    brainMode = mode
                    android.widget.Toast.makeText(this, if (mode == "claude") "Now thinking with Claude" else "Now offline", android.widget.Toast.LENGTH_SHORT).show()
                } else if (error.startsWith("Add a Claude key")) {
                    AlertDialog.Builder(this).setTitle("Claude isn't set up yet")
                        .setMessage("Your laptop needs a Claude API key first. Add it to the .env file on the laptop, next to ANTHROPIC_API_KEY=, then try this switch again. No restart needed.")
                        .setPositiveButton("OK", null).show()
                } else {
                    android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show()
                }
                row.text = brainLabel()
                refresh()
            }
        }
    }

    private fun settingsRow(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        setPadding(0, dp(14), 0, dp(14))
        setOnClickListener { click() }
    }
}
