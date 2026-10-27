package com.eldablos.crimson

import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser

/**
 * App icons for the App Strip, taken from the Crimson Icons pack when it's installed so the strip
 * matches the home screen. Apps the pack doesn't cover keep their own icon.
 */
class IconPack private constructor(
    private val res: Resources?,
    private val byComponent: Map<String, String>,
    private val byPackage: Map<String, String>,
) {
    fun icon(context: Context, component: ComponentName, sizePx: Int): Bitmap? {
        val name = byComponent[component.flattenToString()] ?: byPackage[component.packageName]
        val packed = name?.let { drawable(it) }
        val drawable = packed ?: try {
            context.packageManager.getActivityIcon(component)
        } catch (e: Exception) {
            return null
        }
        return render(drawable, sizePx)
    }

    /** Whether [component]'s icon comes from the pack (rather than the app's own). */
    fun packed(component: ComponentName): Boolean =
        (byComponent[component.flattenToString()] ?: byPackage[component.packageName])?.let { drawable(it) } != null

    private fun drawable(name: String): Drawable? {
        val res = res ?: return null
        val id = res.getIdentifier(name, "drawable", PACKAGE)
        return if (id == 0) null else try {
            res.getDrawable(id, null)
        } catch (e: Resources.NotFoundException) {
            null
        }
    }

    companion object {
        private const val TAG = "CrimsonIconPack"
        const val PACKAGE = "com.eldablos.crimsonicons"

        /** Reads the pack's appfilter. Returns an empty pack if Crimson Icons isn't installed. */
        fun load(context: Context): IconPack {
            val res = try {
                context.packageManager.getResourcesForApplication(PACKAGE)
            } catch (e: Exception) {
                return IconPack(null, emptyMap(), emptyMap())
            }
            val byComponent = HashMap<String, String>()
            val byPackage = HashMap<String, String>()
            try {
                val id = res.getIdentifier("appfilter", "xml", PACKAGE)
                if (id != 0) {
                    val parser = res.getXml(id)
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType != XmlPullParser.START_TAG || parser.name != "item") continue
                        val drawable = parser.getAttributeValue(null, "drawable") ?: continue
                        // ComponentInfo{package/class}
                        val component = parser.getAttributeValue(null, "component")
                            ?.removePrefix("ComponentInfo{")?.removeSuffix("}")
                            ?.let { ComponentName.unflattenFromString(it) } ?: continue
                        byComponent[component.flattenToString()] = drawable
                        byPackage.putIfAbsent(component.packageName, drawable)
                    }
                    parser.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the Crimson Icons appfilter", e)
            }
            return IconPack(res, byComponent, byPackage)
        }

        private fun render(drawable: Drawable, sizePx: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(bitmap))
            return bitmap
        }
    }
}
