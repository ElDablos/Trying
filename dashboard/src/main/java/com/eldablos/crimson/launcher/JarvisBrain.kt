package com.eldablos.crimson.launcher

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Jarvis's brain: Google's Gemma 4, running on the phone with Google's LiteRT-LM (on the GPU, the CPU if it
 * must), for whatever his own rules don't cover. Three jobs:
 * - **Working out what you meant**: a request his rules don't know is matched to one of his own commands.
 *   He does it, and remembers the phrasing ([JarvisMemory.learnMeaning]), so next time his rules know it
 *   straight away, without the brain. He learns from it the more you use him.
 * - **Answering** anything else, knowing who you are (your facts and routine).
 * - **Summarising** what a web look-up found ([WebLookup]).
 * The model is a file you download once (Gemma 4 E4B, a .litertlm of a few GB) and load from the phone's
 * storage; he checks now and then for a newer Gemma ([newer]). Nothing you ask leaves the phone; one
 * question at a time, on its own thread.
 */
object JarvisBrain {
    /** Where to get the model: Google's Gemma 4 for phones (accept the licence, then download the .litertlm file). */
    const val DOWNLOAD_PAGE = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm"
    const val MODEL_NAME = "Gemma 4 E4B"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-brain").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: Engine? = null
    @Volatile var loading = false
        private set

    fun modelFile(context: Context) = File(context.filesDir, "brain/model.litertlm")

    private fun prefs(context: Context) = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /** Which model's in ("gemma-4-E4B-it"), from the file you loaded. */
    fun installedName(context: Context): String? = prefs(context).getString("brain_name", null)

    /** Downloaded and in place. */
    fun installed(context: Context) = modelFile(context).let { it.exists() && it.length() > 100_000_000L }

    fun enabled(context: Context) = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("set:brain", true)
    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("set:brain", on).apply()
        if (!on) unload()
    }

    /** Installed, switched on: questions can go to it (it loads on first use). */
    fun ready(context: Context) = enabled(context) && installed(context)

    /**
     * Copies the model you downloaded (picked from the phone's files) into his own storage, with progress
     * in percent; [done] true when it's in. Big (gigabytes): off the main thread.
     */
    fun install(context: Context, uri: Uri, progress: (Int) -> Unit, done: (Boolean, String?) -> Unit) {
        val app = context.applicationContext
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        Thread {
            val dest = modelFile(app)
            val tmp = File(dest.parentFile, "model.part")
            val ok = try {
                dest.parentFile?.mkdirs()
                unload()
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
                            if (pct != last) {
                                last = pct
                                main.post { progress(pct) }
                            }
                        }
                    }
                }
                if (tmp.length() < 100_000_000L) throw IllegalStateException("That file's too small to be the model.")
                dest.delete()
                tmp.renameTo(dest)
                prefs(app).edit().putString("brain_name", name?.substringBeforeLast('.')?.removeSuffix("-int4")?.removeSuffix("_int4") ?: MODEL_NAME).apply()
                true
            } catch (e: Exception) {
                tmp.delete()
                main.post { done(false, e.message) }
                return@Thread
            }
            main.post { done(ok, null) }
        }.start()
    }

    fun remove(context: Context) {
        unload()
        modelFile(context).delete()
        prefs(context).edit().remove("brain_name").apply()
    }

    private fun unload() {
        val e = engine
        engine = null
        if (e != null) worker.execute { runCatching { e.close() } }
    }

    /** The engine with the model loaded, on the brain's thread (up to ten seconds the first time): the GPU, else the CPU. */
    private fun load(context: Context): Engine {
        engine?.let { return it }
        loading = true
        try {
            val path = modelFile(context).absolutePath
            val cache = context.cacheDir.path
            val e = try {
                Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cache)).also { it.initialize() }
            } catch (t: Throwable) {
                Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cache)).also { it.initialize() }
            }
            return e.also { engine = it }
        } finally {
            loading = false
        }
    }

    /** Asks the model [user], with [system] as its instruction; [done] on the main thread with its reply, or null. */
    private fun ask(context: Context, system: String, user: String, done: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val out = try {
                load(app).createConversation(ConversationConfig(systemInstruction = Contents.of(system))).use { c ->
                    c.sendMessage(user).toString().trim()
                }
            } catch (e: Throwable) {
                null
            }
            main.post { done(out?.takeIf { it.isNotBlank() }) }
        }
    }

    /** Warms the model up in the background, so the first real question isn't slow. */
    fun warm(context: Context) {
        if (!ready(context) || engine != null || loading) return
        val app = context.applicationContext
        worker.execute { runCatching { load(app) } }
    }

    // --- Keeping up to date ---

    /**
     * A newer Gemma for phones than the one you've got, if Google's put one out (looked up on Hugging Face's
     * litert-community, at most once a day): its name and page, or null. Off the main thread.
     */
    fun newer(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val cached = p.getString("brain_newer", null)
        if (now - p.getLong("brain_checked", 0L) < 24 * 3600_000L) return cached?.split('|')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        val found = try {
            val json = (java.net.URL("https://huggingface.co/api/models?author=litert-community&search=gemma&limit=100").openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 6000
                readTimeout = 8000
                inputStream.use { String(it.readBytes()) }
            }
            val ids = org.json.JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") } }
            // Phone models with a version number: "litert-community/gemma-4-E4B-it-litert-lm" → 4.
            fun version(id: String) = Regex("gemma-?(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1)?.toDoubleOrNull()
            val mine = version(installedName(context) ?: MODEL_NAME.replace(" ", "-")) ?: 0.0
            ids.filter { Regex("E4B|E2B", RegexOption.IGNORE_CASE).containsMatchIn(it) && Regex("litert", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .mapNotNull { id -> version(id)?.let { v -> id to v } }
                .filter { (_, v) -> v > mine }
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenByDescending { it.first.contains("E4B", true) })
                .firstOrNull()?.first
        } catch (e: Exception) {
            return cached?.split('|')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }
        val result = found?.let { it.substringAfter('/') to "https://huggingface.co/$it" }
        p.edit().putLong("brain_checked", now).putString("brain_newer", result?.let { "${it.first}|${it.second}" }).apply()
        return result
    }

    // --- Working out what you meant ---

    /**
     * Which of his own commands [said] means, written as he'd understand it ("set an alarm for 6:30", "what's
     * my rota next week"), filled in with your names, times and numbers; null when it isn't one of his.
     */
    fun interpret(context: Context, said: String, examples: List<String>, done: (String?) -> Unit) {
        val system = "You map a request to an assistant on a phone onto one of its commands. Here are examples of the commands it understands:\n" +
            examples.joinToString("\n") { "- $it" } +
            "\n\nReply with ONE command in the same style as the examples, with the request's names, times, places and numbers filled in. " +
            "If the request isn't asking for one of these kinds of commands (a general question, chat, something it can't do), reply exactly NONE. " +
            "Reply with the command only: no quotes, no explanation."
        ask(context, system, "Request: $said") { out ->
            val line = out?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("-")?.trim()?.trim('"', '\'', '.', '`')
            done(line?.takeIf { it.isNotEmpty() && !it.equals("NONE", true) && it.length < 160 })
        }
    }

    // --- Answering ---

    /** A short answer to [question], knowing [about] you (facts, routine), as Jarvis: brief, British, helpful. */
    fun answer(context: Context, question: String, about: String, done: (String?) -> Unit) {
        val system = "You are Jarvis, a calm, witty British butler-style assistant on Vee's phone. Answer in at most four short sentences, " +
            "plainly, without markdown or lists. If you aren't sure of a fact, or it could have changed recently (news, prices, opening times, " +
            "releases, scores), say so briefly instead of guessing. What you know about Vee:\n$about"
        ask(context, system, question, done)
    }

    // --- Summarising a look-up ---

    /** What [sources] (web pages' text) say about [question], in two or three sentences; null if they don't say. */
    fun summarise(context: Context, question: String, sources: List<WebLookup.Page>, done: (String?) -> Unit) {
        val system = "Answer the question using only the sources below, in two or three plain sentences, without markdown. " +
            "If the sources don't answer it, reply exactly NOTHING.\n\n" +
            sources.mapIndexed { i, p -> "Source ${i + 1} (${p.title}):\n${p.text.take(2500)}" }.joinToString("\n\n")
        ask(context, system, "Question: $question") { out -> done(out?.takeIf { !it.trim().equals("NOTHING", true) }) }
    }
}
