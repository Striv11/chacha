package com.pocket.brain

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** The only apps Chacha may see or drive: the ones you picked. Banking and payment apps can never be picked. */
object Control {
    val apps = mapOf(
        "whatsapp" to "com.whatsapp", "whatsapp_business" to "com.whatsapp.w4b", "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android", "swiggy" to "in.swiggy.android", "zomato" to "com.application.zomato",
        "uber" to "com.ubercab", "ola" to "com.olacabs.customer", "amazon" to "in.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android", "blinkit" to "com.grofers.customerapp", "zepto" to "com.zeptoconsumerapp",
        "bookmyshow" to "com.bt.bms", "makemytrip" to "com.makemytrip", "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music", "youtube" to "com.google.android.youtube",
    )
    val messaging = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.instagram.android")
    private val gated = Regex(
        "(?i)\\b(send|pay|payment|place\\s?order|order\\s?now|buy|purchase|checkout|check\\s?out|book\\s?now|book\\s?ride|" +
            "request|confirm|delete|remove|call|sign\\s?in|log\\s?in|post|share|upi|wallet)\\b",
    )

    fun gated(label: String) = gated.containsMatchIn(label)

    private val money = Regex("(?i)bank|pay|upi|wallet|money|financ|loan|invest|trade|broker|zerodha|groww|upstox|mutual|credit|securities")

    fun banned(pkg: String, label: String) =
        Banking.matches(label, "") || money.containsMatchIn(pkg) || money.containsMatchIn(label)

    fun label(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        ""
    }

    fun allowed(ctx: Context): Set<String> {
        val chosen = ctx.getSharedPreferences("brain", Context.MODE_PRIVATE).getStringSet("apps", null) ?: apps.values.toSet()
        return chosen.filterNot { banned(it, label(ctx, it)) }.toSet()
    }

    fun choose(ctx: Context, pkgs: Set<String>) {
        ctx.getSharedPreferences("brain", Context.MODE_PRIVATE).edit().putStringSet("apps", pkgs).apply()
        ScreenService.active?.limitToAllowed()
    }

    /** Apps on the phone that can be picked, sorted by name. */
    fun installed(ctx: Context): List<Pair<String, String>> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return ctx.packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(ctx.packageManager).toString() }
            .distinctBy { it.first }
            .filter { it.first != ctx.packageName && !banned(it.first, it.second) }
            .sortedBy { it.second.lowercase() }
    }

    /** Turns a spoken name like "swiggy" or "Myntra" into an allowed app, or null. */
    fun find(ctx: Context, name: String): String? {
        val ok = allowed(ctx)
        val n = name.trim().lowercase()
        apps[n]?.let { if (it in ok) return it }
        return ok.firstOrNull { label(ctx, it).lowercase() == n } ?: ok.firstOrNull { label(ctx, it).lowercase().contains(n) }
    }

    fun running(ctx: Context): Boolean = ScreenService.active != null
}

/** Sees the messages you send, and carries out app tasks you approved. Stops before anything final. */
class ScreenService : AccessibilityService() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var api: Api
    private lateinit var queue: Queue
    private var draft = ""
    private var goal: String? = null
    private var steps = 0
    private var history = JSONArray()
    private var nodes = listOf<Pair<AccessibilityNodeInfo, String>>()

    private val code = Regex("(?i)\\b(otp|one[- ]time|verification code|passcode|cvv)\\b")

    override fun onServiceConnected() {
        api = Api(this)
        queue = Queue(this)
        active = this
        limitToAllowed()
    }

    /** Android itself only shows Chacha the apps you allowed; every other app, banking included, stays invisible. */
    fun limitToAllowed() {
        val info = serviceInfo ?: return
        info.packageNames = (Control.allowed(this) + Control.messaging).toTypedArray()
        serviceInfo = info
    }

    /** Some apps draw buttons that aren't marked clickable, so tap the spot on screen instead. */
    private fun tapAt(node: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    override fun onUnbind(i: Intent?): Boolean {
        active = null
        return super.onUnbind(i)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in Control.messaging) return
        when (e.eventType) {
            // Apps clear the box just before reporting the Send tap, so an empty box must not wipe the draft.
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> e.text.joinToString("").let { if (it.isNotBlank()) draft = it }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val what = (e.contentDescription ?: e.text.joinToString("")).toString().trim()
                if (what.equals("send", ignoreCase = true)) capture(pkg)
            }
        }
    }

    private fun capture(pkg: String) {
        val text = draft.trim()
        draft = ""
        if (text.isEmpty() || code.containsMatchIn(text)) return
        val who = rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId("$pkg:id/conversation_contact_name")
            ?.firstOrNull()?.text?.toString() ?: ""
        if (Banking.matches(who, text)) return
        val app = Control.apps.entries.firstOrNull { it.value == pkg }?.key ?: pkg
        queue.add(
            JSONObject().put("source", "phone").put("app", app).put("person", who)
                .put("direction", "out").put("text", text).put("ts", System.currentTimeMillis() / 1000.0),
        )
    }

    private fun begin(task: String) {
        goal = task
        steps = 0
        history = JSONArray()
        ui.removeCallbacksAndMessages(null)
        ui.postDelayed({ step() }, 3000)
    }

    private fun halt() {
        goal = null
        ui.removeCallbacksAndMessages(null)
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Pair<AccessibilityNodeInfo, String>>) {
        if (n == null || out.size >= 80) return
        val label = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
        if (n.isVisibleToUser && (label.isNotEmpty() || n.isEditable)) out.add(n to label.ifEmpty { "(text box)" })
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    private fun step() {
        val task = goal ?: return
        val root = rootInActiveWindow ?: return finish("I couldn't read the screen.")
        val pkg = root.packageName?.toString() ?: ""
        if (pkg !in Control.allowed(this)) return finish("I stopped because that app isn't one I'm allowed to use.")
        val found = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        collect(root, found)
        nodes = found
        val body = JSONObject().put("goal", task).put("history", history)
            .put("nodes", JSONArray(found.mapIndexed { i, p -> "$i: ${p.second}" }))
        thread {
            val reply = try {
                JSONObject(api.call("POST", "/screen/step", body.toString(), 180_000))
            } catch (_: Exception) {
                JSONObject().put("action", "done").put("note", "I lost the connection to your laptop.")
            }
            ui.post { apply(reply) }
        }
    }

    private fun apply(r: JSONObject) {
        if (goal == null) return
        val action = r.optString("action")
        val note = r.optString("note")
        val target = nodes.getOrNull(r.optInt("index", -1))
        android.util.Log.i("Chacha", "step: $action ${r.optInt("index", -1)} ${target?.second ?: ""}")
        when (action) {
            "tap" -> {
                val (node, label) = target ?: return finish("I got confused on that screen.")
                if (Control.gated(label)) return finish("Stopped at \"$label\". That one's your tap.")
                var t: AccessibilityNodeInfo? = node
                while (t != null && !t.isClickable) t = t.parent
                val pressed = t?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true || tapAt(node)
                if (!pressed) return finish("I couldn't press that.")
            }
            "type" -> {
                val node = target?.first ?: return finish("I got confused on that screen.")
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, r.optString("text"))
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "scroll" -> {
                val n = nodes.firstOrNull { it.first.isScrollable }?.first
                var parent = nodes.firstOrNull()?.first
                while (n == null && parent != null && !parent.isScrollable) parent = parent.parent
                (n ?: parent)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            else -> return finish(note.ifEmpty { "Done." })
        }
        history.put("$action ${target?.second ?: ""}".trim())
        if (++steps >= 25) return finish("I took too many steps, so I stopped.")
        ui.postDelayed({ step() }, 1800)
    }

    private fun finish(note: String) {
        val task = goal ?: return
        halt()
        thread {
            try {
                api.call("POST", "/screen/done", JSONObject().put("goal", task).put("note", note).toString())
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        var active: ScreenService? = null
            private set

        /** Returns false if Accessibility hasn't been switched on for Chacha. */
        fun start(goal: String): Boolean {
            val s = active ?: return false
            s.begin(goal)
            return true
        }

        private var focused: AccessibilityNodeInfo? = null

        /** The text box the user is typing in: the focused one, else the first editable box on screen. */
        private fun findBox(): AccessibilityNodeInfo? {
            val s = active ?: return null
            val ok = { n: AccessibilityNodeInfo -> n.isEditable && !n.isPassword && n.packageName?.toString() in Control.allowed(s) }
            val roots = s.windows.mapNotNull { it.root }.filter { it.packageName?.toString() != s.packageName } +
                listOfNotNull(s.rootInActiveWindow)
            roots.forEach { r -> r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf(ok)?.let { return it } }
            fun walk(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
                if (n == null || depth > 40) return null
                if (ok(n)) return n
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)?.let { return it }
                return null
            }
            roots.forEach { r -> walk(r, 0)?.let { return it } }
            return null
        }

        fun rememberFocus() {
            focused = findBox()
        }

        fun targetPackage(): String = focused?.packageName?.toString() ?: ""

        fun insertLater(ctx: Context, text: String) {
            val remembered = focused
            focused = null
            Handler(Looper.getMainLooper()).postDelayed({
                val node = remembered?.takeIf { it.refresh() } ?: findBox()
                val s = active
                android.util.Log.d("ChachaDictate", "service=${s != null} remembered=${remembered != null} node=${node?.packageName}/${node?.className} " +
                    "windows=${s?.windows?.map { it.root?.packageName }} activeRoot=${s?.rootInActiveWindow?.packageName} allowed=${s?.let { Control.allowed(it) }}")
                val before = node?.text?.toString()?.takeUnless { node.isShowingHintText }.orEmpty()
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, (before + " " + text).trim())
                }
                if (node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) != true) {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Chacha", text))
                    android.widget.Toast.makeText(ctx, "Copied. Long-press to paste.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, 400)
        }

        fun stop() {
            active?.halt()
        }
    }
}
