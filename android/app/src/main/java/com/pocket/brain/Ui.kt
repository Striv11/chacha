package com.pocket.brain

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import org.json.JSONArray
import org.json.JSONObject

/** Chacha's colours, shared by the app and the voice keyboard. */
object C {
    val INK = Color.parseColor("#08080D")
    val SURFACE = Color.parseColor("#15151D")
    val RAISED = Color.parseColor("#262632")
    val PLUM = Color.parseColor("#5B7CFF")
    val PEACH = Color.parseColor("#4DE8FF")
    val TEXT = Color.parseColor("#F2F3F7")
    val MUTED = Color.parseColor("#8B8D9C")
    val GOOD = Color.parseColor("#6EE7A8")
    val BAD = Color.parseColor("#FF8A80")
}

/** The last 60 messages, kept only on this phone. Shared by the chat screen and the orb. */
object ChatLog {
    private fun store(ctx: Context) = ctx.applicationContext.getSharedPreferences("plim_chat", Context.MODE_PRIVATE)

    fun all(ctx: Context): JSONArray = try {
        JSONArray(store(ctx).getString("log", "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    @Synchronized
    fun add(ctx: Context, role: String, text: String) {
        val log = all(ctx).put(JSONObject().put("r", role).put("t", text))
        while (log.length() > 60) log.remove(0)
        store(ctx).edit().putString("log", log.toString()).commit()
    }

    fun clear(ctx: Context) {
        store(ctx).edit().remove("log").commit()
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radius: Int = 20) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
}
