package com.pocket.brain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** Android keeps this running once notification access is granted. */
class CaptureService : NotificationListenerService() {
    private lateinit var queue: Queue
    private lateinit var api: Api
    private lateinit var bg: Handler
    private val recent = LinkedHashSet<String>()

    private val apps = mapOf(
        "com.whatsapp" to "whatsapp",
        "com.whatsapp.w4b" to "whatsapp_business",
        "com.google.android.gm" to "gmail",
        "com.google.android.apps.messaging" to "sms",
        "com.samsung.android.messaging" to "sms",
        "org.telegram.messenger" to "telegram",
        "com.instagram.android" to "instagram",
        "com.linkedin.android" to "linkedin",
        "com.Slack" to "slack",
        "org.thoughtcrime.securesms" to "signal",
    )

    // One-time codes are never captured.
    private val secret = Regex("(?i)\\b(otp|one[- ]time|verification code|passcode|cvv)\\b")

    private val codeLike = Regex("(?i)\\bcode\\b.{0,30}\\b\\d{4,8}\\b|\\b\\d{4,8}\\b.{0,30}\\bcode\\b")

    private val tick = object : Runnable {
        override fun run() {
            try {
                if (api.url.isNotEmpty()) {
                    queue.flush(api)
                    pull()
                    PhoneSync.maybePush(this@CaptureService, api)
                }
            } catch (_: Exception) {
                // Laptop unreachable: items stay queued, try again next tick.
            }
            bg.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        queue = Queue(this)
        api = Api(this)
        val channel = NotificationChannel("brain", "Chacha", NotificationManager.IMPORTANCE_DEFAULT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        bg = Handler(HandlerThread("sync").apply { start() }.looper)
        bg.post(tick)
        FloatService.startIfAllowed(this)
    }

    override fun onDestroy() {
        bg.removeCallbacksAndMessages(null)
        bg.looper.quitSafely()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = apps[sbn.packageName] ?: return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return
        val who = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return
        if (text.isBlank() || secret.containsMatchIn(text) || codeLike.containsMatchIn(text)) return
        if (Banking.matches(who, text)) return
        ReplyBook.remember(who, n)
        val key = "$app|$who|$text" // apps often re-post the same notification
        if (!recent.add(key)) return
        if (recent.size > 300) recent.remove(recent.first())
        queue.add(
            JSONObject().put("source", "phone").put("app", app).put("person", who)
                .put("direction", "in").put("text", text).put("ts", sbn.postTime / 1000.0)
        )
    }

    private fun pull() {
        val list = JSONArray(api.call("GET", "/notifications?since=${api.lastSeen}"))
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val body = o.getString("body")
            nm.notify(
                o.getInt("id"),
                Notification.Builder(this, "brain")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(C.PLUM)
                    .setContentTitle(o.getString("title"))
                    .setContentText(body)
                    .setStyle(Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            )
            api.lastSeen = o.getInt("id")
        }
    }
}
