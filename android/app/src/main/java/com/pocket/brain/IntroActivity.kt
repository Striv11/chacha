package com.pocket.brain

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject
import java.net.URI
import kotlin.concurrent.thread

/** First launch: a short welcome, connect to the laptop, then one permission per screen. */
class IntroActivity : Activity() {
    private class Step(
        val title: String,
        val body: String,
        val granted: () -> Boolean,
        val help: String? = null,
        val ask: () -> Unit,
    )

    private val restricted = "If your phone says \"Restricted setting\", tap here, open the ⋮ menu at the top, and choose Allow restricted settings."

    private lateinit var api: Api
    private lateinit var root: LinearLayout
    private lateinit var steps: List<Step>
    private var index = -1
    private var waitingOnSettings = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        api = Api(this)
        steps = buildSteps()
        window.statusBarColor = C.INK
        window.navigationBarColor = C.INK
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.INK)
            setPadding(dp(28), dp(36), dp(28), dp(28))
        }
        setContentView(root)
        welcome()
    }

    override fun onResume() {
        super.onResume()
        if (!waitingOnSettings) return
        waitingOnSettings = false
        if (steps[index].granted()) nextStep() else showStep()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        nextStep()
    }

    private fun has(vararg perms: String) = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun popup(vararg perms: String) = requestPermissions(perms, 1)

    private fun openSettings(intent: Intent) {
        waitingOnSettings = true
        startActivity(intent)
    }

    private fun buildSteps(): List<Step> {
        val list = mutableListOf(
            Step("Hear you", "So you can talk to Chacha instead of typing.",
                { has(Manifest.permission.RECORD_AUDIO) }) { popup(Manifest.permission.RECORD_AUDIO) },
            Step("See your calendar", "So Chacha can warn you about clashes and add plans when you ask.",
                { has(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) }) {
                popup(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            },
            Step("Know your people", "So Chacha knows who \"Mom\" is and can tell you who called.",
                { has(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG) }) {
                popup(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)
            },
            Step("Know where you are", "So Chacha can tell you when to leave and find places nearby.",
                { has(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                popup(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            },
        )
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Step("Tap you on the shoulder", "So Chacha can remind you about the things that matter.",
                { has(Manifest.permission.POST_NOTIFICATIONS) }) { popup(Manifest.permission.POST_NOTIFICATIONS) })
        }
        list.add(Step("Keep up with your messages",
            "So Chacha knows who's waiting on you. On the next screen, find Chacha and turn it on, then come back.",
            { (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName) },
            help = restricted) { openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        list.add(Step("Float over your apps",
            "So you can reach Chacha from anywhere with one tap. On the next screen, turn it on, then come back.",
            { Settings.canDrawOverlays(this) }) {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        list.add(Step("Help inside your apps",
            "So Chacha can tap through apps for you and remember what you send. On the next screen, find Chacha and turn it on, then come back.",
            { ScreenService.active != null }, help = restricted) {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        list.add(Step("Know when you arrive",
            "So Chacha can remind you when you reach a place, like \"buy milk when I'm near the shop\". Choose Allow all the time.",
            { !has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
            popup(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        })
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        list.add(Step("Keep working in the background",
            "So your phone doesn't put Chacha to sleep and you don't miss reminders. Choose Allow.",
            { getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
            help = if (samsung) "On Samsung, also tap here, open Battery, and choose Unrestricted." else null) {
            @Suppress("BatteryLife")
            openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        return list
    }

    // ---- screens ----

    private fun welcome() {
        val promise = listOf(
            "Your information goes to your own laptop.",
            "Banking apps are never read.",
            "Nothing is sent without your OK.",
        )
        render(null, "Chacha", "Your personal assistant.\nIt remembers, so you don't have to.", promise, "Get started", {
            if (api.url.isEmpty()) connect() else nextStep()
        })
    }

    private fun connect(problem: String? = null) {
        val body = "On your laptop, double-click \"Connect phone\". A square code appears. Point your phone at it."
        render(null, "Connect to your laptop", problem ?: body, emptyList(), "Scan the code", { scan() },
            "Do this later", { nextStep() })
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { useCode(it.rawValue ?: "") }
            .addOnFailureListener { connect("The scanner couldn't start. Check your internet connection and try again.") }
    }

    /** Only a private Tailscale address is accepted, so a stray code can't send data elsewhere. */
    private fun useCode(raw: String) {
        val code = try { JSONObject(raw) } catch (_: Exception) { null }
        val url = code?.optString("url").orEmpty()
        val token = code?.optString("token").orEmpty()
        if (token.isEmpty() || !isTailscale(url)) {
            connect("That isn't the Chacha code. Double-click \"Connect phone\" on your laptop and scan the code it shows.")
            return
        }
        render(null, "Connecting", "One moment.", emptyList(), null, {})
        api.url = url
        api.token = token
        thread {
            val ok = try {
                api.call("GET", "/settings")
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    nextStep()
                } else {
                    api.url = ""
                    api.token = ""
                    connect("Your phone can't reach your laptop. Make sure the laptop is on and Tailscale is on for both, then try again.")
                }
            }
        }
    }

    private fun isTailscale(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        val parts = (uri.host ?: return false).split(".").map { it.toIntOrNull() ?: return false }
        return uri.scheme == "http" && parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
    }

    private fun nextStep() {
        do index++ while (index < steps.size && steps[index].granted())
        if (index >= steps.size) finished() else showStep()
    }

    private fun showStep() {
        val step = steps[index]
        render("${index + 1} of ${steps.size}", step.title, step.body, emptyList(), "Allow", step.ask, "Not now", { nextStep() }, step.help)
    }

    private fun finished() {
        val tips = listOf("Tap the ring to ask anything.", "Hold the ring to type with your voice.")
        render(null, "You're all set", "Chacha is ready when you are.", tips, "Start", {
            getSharedPreferences("brain", MODE_PRIVATE).edit().putBoolean("intro_done", true).apply()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        })
    }

    private fun render(
        count: String?, title: String, body: String, lines: List<String>,
        primary: String?, onPrimary: () -> Unit,
        secondary: String? = null, onSecondary: () -> Unit = {},
        help: String? = null,
    ) {
        root.removeAllViews()
        root.addView(text(count ?: "", 13f, C.MUTED).apply { contentDescription = count })
        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_plim) },
                LinearLayout.LayoutParams(dp(84), dp(84)).apply { bottomMargin = dp(32) })
            addView(text(title, 30f, C.TEXT).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
            addView(text(body, 17f, C.MUTED).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(24) })
            lines.forEach { addView(text(it, 15f, C.TEXT).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) }) }
            if (help != null) {
                addView(text(help, 13f, C.MUTED).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    setOnClickListener {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                })
            }
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(centre, LinearLayout.LayoutParams(-1, -2))
            })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (primary != null) {
            root.addView(text(primary, 17f, C.INK).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = rounded(C.TEXT, 28)
                setOnClickListener { onPrimary() }
            }.also { it.setPadding(0, dp(17), 0, dp(17)) }, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(text(secondary ?: "", 15f, C.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
            if (secondary != null) setOnClickListener { onSecondary() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.alpha = 0f
        root.translationY = dp(12).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(320).start()
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
