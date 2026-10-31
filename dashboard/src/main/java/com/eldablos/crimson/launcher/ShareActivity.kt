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
                // Copied now, while this screen holds permission to read it; off the main thread, since a file
                // from Drive or Gmail may still be downloading. Its name and type travel with it.
                val type = intent.type
                Thread {
                    val picked = DocText.copy(this, uri, type)
                    runOnUiThread {
                        if (picked != null) startActivity(go.putExtra(LauncherActivity.EXTRA_FILE, picked.file.path)
                            .putExtra(LauncherActivity.EXTRA_FILE_NAME, picked.name).putExtra(LauncherActivity.EXTRA_FILE_TYPE, picked.mime))
                        finish()
                    }
                }.start()
                return
            }
            !text.isNullOrBlank() -> startActivity(go.putExtra(JarvisProactive.EXTRA_SAY, "summarise this: ${text.take(4000)}"))
        }
        finish()
    }
}
