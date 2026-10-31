package com.eldablos.crimson.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * "Share to Jarvis" from any app (Files, Gmail, Drive, WhatsApp…): the PDF, Word or Excel file, text file or
 * picture goes to his chat, where he reads it and asks what to do. Text shared on its own is said to him.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val go = Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        when {
            uri != null -> {
                // A copy is made at once, while this app still holds permission to read it.
                val picked = DocText.copy(this, uri)
                if (picked != null) {
                    startActivity(go.putExtra(LauncherActivity.EXTRA_FILE, Uri.fromFile(picked.file)))
                }
            }
            !text.isNullOrBlank() -> startActivity(go.putExtra(JarvisProactive.EXTRA_SAY, "summarise this: ${text.take(4000)}"))
        }
        finish()
    }
}
