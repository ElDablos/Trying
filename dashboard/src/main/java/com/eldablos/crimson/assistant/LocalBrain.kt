package com.eldablos.crimson.assistant

import android.content.Context
import android.net.Uri
import com.eldablos.crimson.core.SysLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * The model on the phone (Google's Gemma, run with LiteRT-LM on the GPU, the CPU if it must): the assistant's
 * back-up for when there's no connection or Gemini's free requests have run out. Slower and simpler than
 * Gemini, but nothing leaves the phone. Loaded on first use and let go when the app's been closed a while.
 */
object LocalBrain {
    class Model(val file: File, val name: String) {
        val gb: Double get() = file.length() / 1e9
    }

    const val DOWNLOAD_PAGE = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm"

    private var engine: Engine? = null
    private var loadedPath: String? = null

    // The same folder the old Jarvis kept his brains in, so one already downloaded carries over.
    private fun dir(context: Context) = File(context.filesDir, "brain")
    private fun prefs(context: Context) = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /** The models on the phone, Gemma first (it's the one that follows instructions best). */
    fun models(context: Context): List<Model> = (dir(context).listFiles { f -> f.name.endsWith(".litertlm") && f.length() > 100_000_000L } ?: emptyArray())
        .map { f -> Model(f, prefs(context).getString("brain_name:${f.name}", null) ?: f.nameWithoutExtension) }
        .sortedBy { if (it.name.contains("gemma", true)) 0 else 1 }

    fun installed(context: Context) = models(context).isNotEmpty()

    /** Copies a model file you downloaded in next to the others, with progress in percent. Off the main thread. */
    fun install(context: Context, uri: Uri, progress: (Int) -> Unit): String? {
        val app = context.applicationContext
        val shown = runCatching {
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "model-${System.currentTimeMillis()}.litertlm"
        if (!shown.endsWith(".litertlm", true)) return "That isn't a .litertlm model file."
        val name = shown.substringBeforeLast('.')
        val d = dir(app)
        val dest = File(d, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".litertlm")
        val tmp = File(d, "incoming.part")
        return try {
            d.mkdirs()
            if (dest.absolutePath == loadedPath) unload()
            val total = app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            app.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        val pct = if (total > 0) (copied * 100 / total).toInt() else -1
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
            if (tmp.length() < 100_000_000L) throw IllegalStateException("That file's too small to be a model.")
            dest.delete()
            tmp.renameTo(dest)
            prefs(app).edit().putString("brain_name:${dest.name}", name).apply()
            null
        } catch (e: Exception) {
            tmp.delete()
            e.message ?: "Couldn't copy the file."
        }
    }

    fun remove(context: Context, model: Model) {
        if (model.file.absolutePath == loadedPath) unload()
        model.file.delete()
        prefs(context).edit().remove("brain_name:${model.file.name}").apply()
    }

    @Synchronized
    fun unload() {
        val e = engine
        engine = null
        loadedPath = null
        if (e != null) runCatching { e.close() }
    }

    @Synchronized
    private fun load(context: Context, model: Model): Engine {
        val path = model.file.absolutePath
        engine?.let { if (loadedPath == path) return it }
        unload()
        var last: Throwable? = null
        for (backend in listOf<() -> Backend>({ Backend.GPU() }, { Backend.CPU() })) {
            try {
                val e = Engine(EngineConfig(modelPath = path, backend = backend(), cacheDir = context.cacheDir.path)).also { it.initialize() }
                engine = e
                loadedPath = path
                return e
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("no engine")
    }

    /** One question, one answer, on the phone. Blocking (seconds to a minute): call off the main thread. Null if it couldn't. */
    @Synchronized
    fun ask(context: Context, system: String, user: String): String? {
        val model = models(context).firstOrNull() ?: return null
        return try {
            load(context.applicationContext, model).createConversation(ConversationConfig(systemInstruction = Contents.of(system))).use { c ->
                c.sendMessage(user).toString().replace(Regex("(?s)<think>.*?</think>"), "").trim()
            }
        } catch (e: Throwable) {
            SysLog.error("On-phone model", "${model.name}: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}")
            null
        }
    }
}
