package com.pocket.brain

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/** Share a photo or screenshot to Chacha from any app. The answer appears in the chat. */
class ShareActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val api = Api(this)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null || api.url.isEmpty()) {
            Toast.makeText(this, if (uri == null) "That isn't a picture." else "Connect Chacha to your laptop first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val box = EditText(this).apply { hint = "What do you want to know? (optional)" }
        AlertDialog.Builder(this).setTitle("Ask Chacha about this picture")
            .setView(LinearLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(box, LinearLayout.LayoutParams(-1, -2))
            })
            .setPositiveButton("Ask") { _, _ -> send(api, uri, box.text.toString().trim()) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun send(api: Api, uri: Uri, question: String) {
        Toast.makeText(this, "Chacha is looking. The answer will be in the chat.", Toast.LENGTH_LONG).show()
        ChatLog.add(this, "me", "🖼 Picture" + if (question.isNotEmpty()) ": $question" else "")
        val app = applicationContext
        thread {
            // Read the picture before closing: access to a shared picture ends with this screen.
            val jpeg = try { shrink(uri) } catch (e: Exception) { android.util.Log.w("Chacha", "picture: $e"); null }
            runOnUiThread { finish() }
            val answer = if (jpeg == null) "I couldn't open that picture." else try {
                JSONObject(api.upload("/see?q=" + Uri.encode(question), jpeg)).getString("text")
            } catch (_: Exception) {
                "I couldn't look at that picture. Check that your laptop is on."
            }
            ChatLog.add(app, "plim", answer)
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    /** Smaller pictures are faster and still readable. */
    private fun shrink(uri: Uri): ByteArray {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 1600) d.setTargetSampleSize((longest + 1599) / 1600)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}
