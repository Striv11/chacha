package com.pocket.brain

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/** The ring that floats over every app. Tap to talk, drag to move. Hidden while Chacha itself is open. */
class FloatService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var orb: OrbView? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        addOrb()
        instance = this
        applyHidden()
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        orb?.let { wm.removeView(it) }
        orb = null
        super.onDestroy()
    }

    /** Android requires a notification for this; it sits silently at the very bottom of the list. */
    private fun goForeground() {
        val channel = NotificationChannel("plim_float", "Floating ring", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val n = Notification.Builder(this, "plim_float")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Chacha ring is on")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOrb() {
        val size = dp(76)
        val view = OrbView(this, lowPower = true).apply { contentDescription = "Talk to Chacha" }
        params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size
            y = resources.displayMetrics.heightPixels * 2 / 5
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) snap(view) else open(e.eventTime - e.downTime >= 500)
            }
            true
        }
        wm.addView(view, params)
        orb = view
    }

    private fun snap(view: View) {
        val w = resources.displayMetrics.widthPixels
        val target = if (params.x + params.width / 2 < w / 2) 0 else w - params.width
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 180
            addUpdateListener {
                if (view.isAttachedToWindow) {
                    params.x = it.animatedValue as Int
                    wm.updateViewLayout(view, params)
                }
            }
            start()
        }
    }

    /** A tap asks Chacha; holding the ring dictates into the box you're typing in. */
    private fun open(dictate: Boolean) {
        if (dictate) ScreenService.rememberFocus()
        startActivity(
            Intent(this, OrbActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("dictate", dictate),
        )
    }

    private fun applyHidden() {
        orb?.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    companion object {
        private const val ID = 77
        private var instance: FloatService? = null
        private var hidden = false

        /** Chacha's own screens hide the ring, so it only appears over other apps. */
        fun setHidden(value: Boolean) {
            hidden = value
            instance?.applyHidden()
        }

        fun startIfAllowed(ctx: Context) {
            if (!Api(ctx).floating || !Settings.canDrawOverlays(ctx)) return
            try {
                ctx.startForegroundService(Intent(ctx, FloatService::class.java))
            } catch (_: Exception) {
                // Android refused for now; it is tried again next time Chacha opens.
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatService::class.java))
        }
    }
}
