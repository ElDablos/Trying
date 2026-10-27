package com.eldablos.crimson.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import com.eldablos.crimson.IconPack
import java.util.concurrent.Executors

class AppEntry(val component: ComponentName, val label: String)

/**
 * Every app the launcher can open, with icons from the Crimson Icons pack. Icons load in the
 * background; [icon] returns null until one is ready, then calls the listener so the screen redraws.
 */
object AppCatalog {
    const val ICON_PX = 144

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val icons = LruCache<ComponentName, Bitmap>(220)
    private val loading = HashSet<ComponentName>()
    private val missing = HashSet<ComponentName>()
    private var pack: IconPack? = null
    private var apps: List<AppEntry> = emptyList()
    private val byComponent = HashMap<ComponentName, AppEntry>()

    private val iconListeners = LinkedHashSet<() -> Unit>()

    /** [listener] runs on the main thread whenever an icon finishes loading. */
    fun addIconListener(listener: () -> Unit) {
        iconListeners += listener
    }

    fun removeIconListener(listener: () -> Unit) {
        iconListeners -= listener
    }

    fun apps(): List<AppEntry> = apps

    fun entry(component: ComponentName): AppEntry? = byComponent[component]

    /** The first app from [packageName], for notification icons. */
    fun forPackage(packageName: String): AppEntry? = apps.firstOrNull { it.component.packageName == packageName }

    /** Re-reads the installed apps, A–Z. */
    fun load(context: Context) {
        val launcher = context.getSystemService(LauncherApps::class.java) ?: return
        apps = launcher.getActivityList(null, Process.myUserHandle())
            .map { AppEntry(it.componentName, it.label?.toString()?.trim().orEmpty()) }
            .sortedBy { it.label.lowercase() }
        byComponent.clear()
        apps.forEach { byComponent[it.component] = it }
    }

    /** Forgets cached icons, after apps change or the icon pack updates. */
    fun clearIcons() {
        icons.evictAll()
        missing.clear()
        pack = null
    }

    fun icon(context: Context, component: ComponentName): Bitmap? {
        icons.get(component)?.let { return it }
        if (component in missing) return null
        synchronized(loading) {
            if (!loading.add(component)) return null
        }
        val app = context.applicationContext
        worker.execute {
            val iconPack = pack ?: IconPack.load(app).also { pack = it }
            val bitmap = iconPack.icon(app, component, ICON_PX)
            main.post {
                synchronized(loading) { loading.remove(component) }
                if (bitmap != null) {
                    icons.put(component, bitmap)
                    iconListeners.toList().forEach { it() }
                } else {
                    missing += component
                }
            }
        }
        return null
    }

    fun launch(context: Context, component: ComponentName, bounds: android.graphics.Rect? = null) {
        val launcher = context.getSystemService(LauncherApps::class.java) ?: return
        try {
            launcher.startMainActivity(component, Process.myUserHandle(), bounds, null)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Couldn't open that app", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun appInfo(context: Context, component: ComponentName) {
        context.getSystemService(LauncherApps::class.java)
            ?.startAppDetailsActivity(component, Process.myUserHandle(), null, null)
    }
}
