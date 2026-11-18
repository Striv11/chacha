package com.pocket.brain

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Items wait here until the laptop is reachable, so nothing is lost while it is off. */
class Queue(ctx: Context) : SQLiteOpenHelper(ctx, "queue.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE q(id INTEGER PRIMARY KEY, body TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, a: Int, b: Int) {}

    fun add(item: JSONObject) {
        writableDatabase.insert("q", null, ContentValues().apply { put("body", item.toString()) })
    }

    fun size(): Int = readableDatabase.rawQuery("SELECT count(*) FROM q", null).use {
        it.moveToFirst()
        it.getInt(0)
    }

    /** Sends up to 50 items; they are deleted only after the laptop confirms. */
    fun flush(api: Api) {
        repeat(20) {
            if (!sendBatch(api)) return
        }
    }

    private fun sendBatch(api: Api): Boolean {
        val ids = ArrayList<Long>()
        val batch = JSONArray()
        readableDatabase.rawQuery("SELECT id, body FROM q ORDER BY id LIMIT 50", null).use {
            while (it.moveToNext()) {
                ids.add(it.getLong(0))
                batch.put(JSONObject(it.getString(1)))
            }
        }
        if (ids.isEmpty()) return false
        api.call("POST", "/ingest", batch.toString())
        writableDatabase.execSQL("DELETE FROM q WHERE id <= ${ids.last()}")
        return true
    }
}

class Api(ctx: Context) {
    private val p = ctx.getSharedPreferences("brain", Context.MODE_PRIVATE)

    var url: String
        get() = p.getString("url", "") ?: ""
        set(v) { p.edit().putString("url", v.trim().trimEnd('/')).apply() }
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) { p.edit().putString("token", v.trim()).apply() }
    var floating: Boolean
        get() = p.getBoolean("float", true)
        set(v) { p.edit().putBoolean("float", v).apply() }
    var lastSeen: Int
        get() = p.getInt("last", 0)
        set(v) { p.edit().putInt("last", v).apply() }

    fun upload(path: String, bytes: ByteArray): String {
        val c = URL(url + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8_000
            c.readTimeout = 180_000
            c.doOutput = true
            c.setRequestProperty("x-token", token)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    fun call(method: String, path: String, body: String? = null, readMs: Int = 30_000): String {
        val c = URL(url + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8_000
            c.readTimeout = readMs
            c.setRequestProperty("x-token", token)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}
