package com.pocket.brain

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.RemoteInput
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** Banking and payment content is dropped here, before it can leave the phone. */
object Banking {
    private val names = Regex(
        "(?i)\\b(hdfc\\w*|icici\\w*|sbi\\w*|axis\\s?bank|axisbk|kotak\\w*|yes\\s?bank|pnb\\w*|idfc\\w*|indusind|canara|" +
            "bank\\s?of\\s?\\w+|paytm|phonepe|gpay|google\\s?pay|bhim|cred|razorpay)\\b",
    )
    private val money = Regex(
        "(?i)\\b(debited|credited|a/c|acct|avl\\.?\\s?bal|available\\s?balance|upi\\s?(ref|id|txn)|net\\s?banking|" +
            "credit\\s?card|debit\\s?card|card\\s?ending|bank\\s?statement|emi\\s?due)\\b",
    )

    fun matches(person: String, text: String) = names.containsMatchIn(person) || money.containsMatchIn(text)
}

/** Tidies dictated text for the app it's going into. If the laptop can't be reached, the raw words are used. */
object Polish {
    fun run(api: Api, text: String, pkg: String): String = try {
        JSONObject(api.call("POST", "/polish?app=" + encode(pkg), JSONObject().put("text", text).toString(), 25_000)).getString("text")
    } catch (_: Exception) {
        text
    }
}

/** Reply buttons from live notifications, so an approved reply goes back into the right chat. */
object ReplyBook {
    private val actions = ConcurrentHashMap<String, Notification.Action>()

    fun remember(person: String, n: Notification) {
        val a = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true } ?: return
        if (actions.size > 100) actions.clear()
        actions[person.lowercase()] = a
    }

    fun send(ctx: Context, person: String, text: String): Boolean {
        val a = actions[person.lowercase()] ?: return false
        val inputs = a.remoteInputs ?: return false
        val intent = Intent()
        val values = Bundle()
        inputs.forEach { values.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, values)
        a.actionIntent.send(ctx, 0, intent)
        return true
    }
}

/** Sends calendar, contacts, calls and location to the laptop, only for permissions you've granted. */
object PhoneSync {
    private const val EVERY_MS = 10 * 60_000L
    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun maybePush(ctx: Context, api: Api) {
        val prefs = ctx.getSharedPreferences("brain", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_loc", 0) >= 120_000) {
            prefs.edit().putLong("last_loc", now).apply()
            freshLocation(ctx, api)
        }
        if (now - prefs.getLong("last_sync", 0) < EVERY_MS) return
        val body = JSONObject()
        val perms = listOf(
            Manifest.permission.READ_CALENDAR, Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (has(ctx, perms[0])) body.put("calendar", calendar(ctx, now))
        if (has(ctx, perms[1])) body.put("contacts", contacts(ctx))
        if (has(ctx, perms[2])) body.put("calls", calls(ctx))
        if (has(ctx, perms[3])) location(ctx)?.let { body.put("location", it) }
        if (body.length() == 0) return
        api.call("POST", "/sync", body.toString(), 60_000)
        if (perms.all { has(ctx, it) }) prefs.edit().putLong("last_sync", now).apply()
    }

    private fun calendar(ctx: Context, now: Long): JSONArray {
        val day = 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - day)
            ContentUris.appendId(it, now + 30 * day)
        }.build()
        val out = JSONArray()
        val fmt = stamp
        ctx.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
            ),
            null, null, CalendarContract.Instances.BEGIN,
        )?.use {
            while (it.moveToNext() && out.length() < 300) {
                out.put(
                    JSONObject().put("id", "${it.getLong(0)}-${it.getLong(2)}")
                        .put("title", it.getString(1) ?: "(no title)")
                        .put("starts", fmt.format(Date(it.getLong(2))))
                        .put("ends", fmt.format(Date(it.getLong(3))))
                        .put("location", it.getString(4) ?: ""),
                )
            }
        }
        return out
    }

    private fun contacts(ctx: Context): JSONArray {
        val out = JSONArray()
        val c = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        ctx.contentResolver.query(c, cols, null, null, null)?.use {
            while (it.moveToNext() && out.length() < 3000) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                out.put(JSONObject().put("name", name).put("number", number))
            }
        }
        return out
    }

    private fun calls(ctx: Context): JSONArray {
        val out = JSONArray()
        val cols = arrayOf(CallLog.Calls.DATE, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE)
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, null, null, CallLog.Calls.DATE + " DESC")?.use {
            while (it.moveToNext() && out.length() < 50) {
                val kind = when (it.getInt(3)) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    else -> "other"
                }
                out.put(
                    JSONObject().put("ts", it.getLong(0) / 1000.0).put("name", it.getString(1) ?: "")
                        .put("number", it.getString(2) ?: "").put("kind", kind),
                )
            }
        }
        return out
    }

    /** A current fix every two minutes, for reminders that fire when you reach a place. */
    @Suppress("MissingPermission")
    private fun freshLocation(ctx: Context, api: Api) {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = ctx.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { lm.hasProvider(it) && lm.isProviderEnabled(it) } ?: return
        lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc ->
            if (loc == null) return@getCurrentLocation
            val body = JSONObject().put(
                "location",
                JSONObject().put("lat", loc.latitude).put("lon", loc.longitude).put("at", stamp.format(Date(loc.time))),
            )
            Thread {
                try {
                    api.call("POST", "/sync", body.toString(), 30_000)
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    @Suppress("MissingPermission")
    private fun location(ctx: Context): JSONObject? {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val best = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } ?: return null
        return JSONObject().put("lat", best.latitude).put("lon", best.longitude)
            .put("at", stamp.format(Date(best.time)))
    }
}

/** Carries out what you approved. Runs only from your tap in the app. Returns an error message, or null. */
object Actions {
    fun run(a: Activity, kind: String, p: JSONObject, text: String?): String? = try {
        when (kind) {
            "reply" -> reply(a, p, text ?: p.optString("text"))
            "event" -> addEvent(a, p)
            "call" -> open(a, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.getString("number")))))
            "navigate" -> open(a, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(p.getString("destination")))))
            "alarm" -> open(
                a,
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, p.getInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, p.getInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, p.optString("label")),
            )
            "open" -> open(a, Intent(Intent.ACTION_VIEW, Uri.parse(p.getString("url"))))
            "phone_task" -> task(a, p)
            else -> "I don't know how to do that yet."
        }
    } catch (e: Exception) {
        "That didn't work: ${e.message}"
    }

    private fun task(a: Activity, p: JSONObject): String? {
        val pkg = Control.find(a, p.getString("app"))
            ?: return "I'm not allowed to use ${p.getString("app")}. Add it in Settings, under Apps Chacha can use."
        val launch = a.packageManager.getLaunchIntentForPackage(pkg) ?: return "That app isn't installed."
        if (ScreenService.active == null) return "Turn on Chacha under Settings, Accessibility first."
        a.startActivity(launch)
        ScreenService.start(p.getString("goal"))
        return null
    }

    private fun open(a: Activity, i: Intent): String? {
        a.startActivity(i)
        return null
    }

    /** Sends through the notification's own reply button, or opens the WhatsApp chat ready to send. */
    private fun reply(a: Activity, p: JSONObject, text: String): String? {
        if (ReplyBook.send(a, p.getString("person"), text)) return null
        val number = p.optString("number").filter { it.isDigit() }
        if (number.isEmpty()) return "That chat's notification is gone and I have no number for them."
        return open(a, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=" + Uri.encode(text))))
    }

    private fun addEvent(a: Activity, p: JSONObject): String? {
        if (a.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "Please allow calendar access first."
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val id = a.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=700",
            null, CalendarContract.Calendars.IS_PRIMARY + " DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return "I couldn't find a calendar to add it to."
        a.contentResolver.insert(
            CalendarContract.Events.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, id)
                put(CalendarContract.Events.TITLE, p.getString("title"))
                put(CalendarContract.Events.DTSTART, fmt.parse(p.getString("start"))!!.time)
                put(CalendarContract.Events.DTEND, fmt.parse(p.getString("end"))!!.time)
                put(CalendarContract.Events.EVENT_LOCATION, p.optString("location"))
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            },
        ) ?: return "The calendar refused that event."
        return null
    }
}
