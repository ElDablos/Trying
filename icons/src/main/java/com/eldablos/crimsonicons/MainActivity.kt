package com.eldablos.crimsonicons

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import org.xmlpull.v1.XmlPullParser

/** Shows how many of the phone's apps this pack covers, and lets the user copy the rest. */
class MainActivity : Activity() {
    private var missingList = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val covered = themedComponents()
        val apps = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { App(it.loadLabel(packageManager).toString(), "${it.activityInfo.packageName}/${it.activityInfo.name}") }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
        val missing = apps.filter { it.component !in covered }

        findViewById<TextView>(R.id.coverage_text).text =
            "${apps.size - missing.size} of your ${apps.size} apps have a Crimson icon. " +
                "The rest get their usual icon in a matching dark frame."
        findViewById<TextView>(R.id.missing_text).text =
            if (missing.isEmpty()) "Every app is covered." else missing.joinToString("\n") { it.label }
        missingList = missing.joinToString("\n") { "${it.label}: ${it.component}" }

        findViewById<Button>(R.id.copy_button).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Apps without a Crimson icon", missingList))
            Toast.makeText(this, "Copied ${missing.size} apps", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.open_goodlock_button).setOnClickListener {
            val goodLock = packageManager.getLaunchIntentForPackage(GOOD_LOCK)
            if (goodLock != null) {
                startActivity(goodLock)
            } else {
                // Not installed: open its Galaxy Store page.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("samsungapps://ProductDetail/$GOOD_LOCK")))
                } catch (e: Exception) {
                    Toast.makeText(this, "Install Good Lock from the Galaxy Store", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** The "package/activity" names in res/xml/appfilter.xml. */
    private fun themedComponents(): Set<String> {
        val found = mutableSetOf<String>()
        resources.getXml(R.xml.appfilter).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "item") continue
                parser.getAttributeValue(null, "component")
                    ?.removePrefix("ComponentInfo{")?.removeSuffix("}")
                    ?.let { found += it }
            }
        }
        return found
    }

    private data class App(val label: String, val component: String)

    companion object {
        private const val GOOD_LOCK = "com.samsung.android.goodlock"
    }
}
