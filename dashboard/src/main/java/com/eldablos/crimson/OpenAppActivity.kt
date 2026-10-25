package com.eldablos.crimson

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * Invisible trampoline for widget taps. For the App Strip and the Arcade it opens the app that was tapped
 * (or, for the Arcade's Random button, a random game, or the usage access settings). For the
 * weather tap it opens the app the user picked, or else tries to find one; if nothing opens, it sends
 * the user to the setup screen to pick the app.
 */
class OpenAppActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_ARCADE)) {
            ARCADE_RANDOM -> {
                // A game picked fresh on every tap.
                Arcade.games(this).randomOrNull()?.let { openStripApp(it.component) }
                finish()
                return
            }
            ARCADE_USAGE_ACCESS -> {
                Toast.makeText(this, "Turn on Crimson Widget to show your most played games", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
                return
            }
        }
        intent.getStringExtra(EXTRA_COMPONENT)?.let { flat ->
            ComponentName.unflattenFromString(flat)?.let { openStripApp(it) }
            finish()
            return
        }
        val label = intent.getStringExtra(EXTRA_LABEL) ?: run { finish(); return }
        val packages = intent.getStringArrayExtra(EXTRA_PACKAGES)?.toList() ?: emptyList()

        val opened = candidates(label, packages).any { target ->
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open $target", e)
                false
            }
        }
        if (!opened) {
            Toast.makeText(this, "Pick your $label app, then tap the widget again", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PICK_WEATHER_APP, true))
        }
        finish()
    }

    private fun openStripApp(component: ComponentName) {
        try {
            startActivity(launcherIntent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open $component", e)
            Toast.makeText(this, "That app isn't installed any more", Toast.LENGTH_SHORT).show()
        }
    }

    private fun candidates(label: String, packages: List<String>): List<Intent> {
        val pm = packageManager
        return buildList {
            DashboardStore.weatherApp(this@OpenAppActivity)?.let { saved ->
                ComponentName.unflattenFromString(saved)?.let { add(launcherIntent(it)) }
            }
            launcherApps(this@OpenAppActivity)
                .filter { appLabel(pm, it).equals(label, ignoreCase = true) }
                .forEach { add(launcherIntent(it.component())) }
            packages.forEach { pkg -> pm.getLaunchIntentForPackage(pkg)?.let { add(it) } }
        }
    }

    companion object {
        private const val TAG = "CrimsonOpenApp"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_COMPONENT = "component"
        private const val EXTRA_ARCADE = "arcade"
        private const val ARCADE_RANDOM = "random"
        private const val ARCADE_USAGE_ACCESS = "usage_access"

        fun intent(context: Context, label: String, packages: List<String>): Intent =
            Intent(context, OpenAppActivity::class.java)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_PACKAGES, packages.toTypedArray())

        /** The App Strip's tap target; each icon fills in its app with [fillIn]. */
        fun template(context: Context): Intent =
            Intent(context, OpenAppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun fillIn(component: ComponentName): Intent = Intent().putExtra(EXTRA_COMPONENT, component.flattenToString())

        /** The Arcade's Random button: opens a game picked at the moment of the tap. */
        fun fillInRandomGame(): Intent = Intent().putExtra(EXTRA_ARCADE, ARCADE_RANDOM)

        /** The Arcade's prompt to turn on usage access, for its most played games. */
        fun fillInUsageAccess(): Intent = Intent().putExtra(EXTRA_ARCADE, ARCADE_USAGE_ACCESS)

        /** Every app with a home-screen icon, other than this one. */
        fun launcherApps(context: Context): List<ResolveInfo> =
            context.packageManager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != context.packageName }

        /** The name under the icon: the activity's label, falling back to the app's. */
        fun appLabel(pm: PackageManager, info: ResolveInfo): String =
            info.loadLabel(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: info.activityInfo.applicationInfo.loadLabel(pm).toString().trim()

        fun ResolveInfo.component() = ComponentName(activityInfo.packageName, activityInfo.name)

        fun launcherIntent(component: ComponentName): Intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}
