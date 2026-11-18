package com.pocket.brain

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A single ring of light. It drifts when idle, ripples with your voice, and spins while thinking. */
class OrbView(ctx: Context, private val lowPower: Boolean = false) : View(ctx) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode = Mode.IDLE
        set(v) {
            field = v
            invalidate()
        }

    /** Microphone loudness, 0 to 1. */
    var level = 0f

    private val spectrum = intArrayOf(
        Color.parseColor("#4DE8FF"), Color.parseColor("#5B7CFF"), Color.parseColor("#B26BFF"),
        Color.parseColor("#4DA8FF"), Color.parseColor("#4DE8FF"),
    )
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val box = RectF()
    private val spin = Matrix()
    private var sweep: SweepGradient? = null
    private var t = 0f
    private var smooth = 0f
    private var last = 0L

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sweep = SweepGradient(w / 2f, h / 2f, spectrum, null)
        ring.shader = sweep
    }

    private fun wave(cx: Float, cy: Float, r: Float, amount: Float) {
        path.rewind()
        val n = if (lowPower) 48 else 120
        for (i in 0..n) {
            val a = (i.toFloat() / n * 2 * PI).toFloat()
            val d = r * (1 + amount * (0.6f * sin(6 * a + t * 4) + 0.4f * sin(11 * a - t * 5.5f)))
            val x = cx + d * cos(a)
            val y = cy + d * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceAtMost(0.1f)
        last = now
        val speed = when (mode) {
            Mode.IDLE -> 0.5f
            Mode.LISTENING -> 1.2f
            Mode.THINKING -> 1.6f
            Mode.SPEAKING -> 1.3f
        }
        t += dt * speed
        val target = when (mode) {
            Mode.LISTENING -> level
            Mode.SPEAKING -> 0.3f + 0.25f * sin(t * 9)
            else -> 0f
        }
        smooth += (target - smooth) * 0.25f

        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.3f * (1 + 0.03f * sin(t * 2.4f) + smooth * 0.14f)
        val stroke = min(width, height) * 0.035f

        spin.setRotate(t * 120, cx, cy)
        sweep?.setLocalMatrix(spin)

        val thinking = mode == Mode.THINKING
        if (!thinking) wave(cx, cy, r, 0.004f + smooth * 0.09f)
        box.set(cx - r, cy - r, cx + r, cy + r)
        val head = t * 260
        val arc = 200 + 70 * sin(t * 3)
        for ((width, alpha) in listOf(5f to 26, 2.6f to 70, 1f to 255)) {
            ring.strokeWidth = stroke * width
            ring.alpha = alpha
            if (thinking) c.drawArc(box, head, arc, false, ring) else c.drawPath(path, ring)
        }

        core.alpha = if (mode == Mode.IDLE) 200 else 255
        c.drawCircle(cx, cy, r * (0.11f + smooth * 0.3f + 0.015f * sin(t * 3)), core)

        // The floating ring is drawn once and stays still, so it costs no power while it sits on screen.
        if (isShown && !lowPower) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changed: View, visibility: Int) {
        super.onVisibilityChanged(changed, visibility)
        if (visibility == VISIBLE) {
            last = 0
            invalidate()
        }
    }
}
