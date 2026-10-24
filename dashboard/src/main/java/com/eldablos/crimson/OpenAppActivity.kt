package com.eldablos.crimson

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Invisible trampoline for widget taps that need more than one try to open an app. Some Samsung apps
 * (Weather) refuse a direct launch from a widget's PendingIntent; from a foreground activity we can try
 * each way of opening them in turn, and say why if none works.
 */
class OpenAppActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: run { finish(); return }
        val packages = intent.getStringArrayExtra(EXTRA_PACKAGES)?.toList() ?: emptyList()
        val category = intent.getStringExtra(EXTRA_CATEGORY)

        val errors = mutableListOf<String>()
        val opened = candidates(label, packages, category).any { target ->
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open $target", e)
                errors += "${target.component?.flattenToShortString() ?: target.action}: ${e.javaClass.simpleName} ${e.message}"
                false
            }
        }
        if (!opened) {
            val reason = errors.firstOrNull() ?: "no app called \"$label\" was found"
            Toast.makeText(this, "Couldn't open $label: $reason", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun candidates(label: String, packages: List<String>, category: String?): List<Intent> {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcher = pm.queryIntentActivities(main, 0).filter { it.activityInfo.packageName != packageName }
        val byLabel = launcher.filter { it.loadLabel(pm).toString().trim().equals(label, ignoreCase = true) }
        val labelPackages = byLabel.map { it.activityInfo.packageName }

        return buildList {
            byLabel.forEach {
                add(Intent(main).setClassName(it.activityInfo.packageName, it.activityInfo.name))
            }
            (labelPackages + packages).distinct().forEach { pkg ->
                pm.getLaunchIntentForPackage(pkg)?.let { add(it) }
            }
            if (category != null) add(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category))
        }
    }

    companion object {
        private const val TAG = "CrimsonOpenApp"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_CATEGORY = "category"

        fun intent(context: Context, label: String, packages: List<String>, category: String?): Intent =
            Intent(context, OpenAppActivity::class.java)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_PACKAGES, packages.toTypedArray())
                .putExtra(EXTRA_CATEGORY, category)
    }
}
