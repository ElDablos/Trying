package com.eldablos.crimson.launcher

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.util.concurrent.Executors

/**
 * Jarvis's brain: Google's Gemma, running on the phone (MediaPipe's LLM inference), for whatever his own
 * rules don't cover. Three jobs:
 * - **Working out what you meant**: a request his rules don't know is matched to one of his own commands.
 *   He does it, and remembers the phrasing ([JarvisMemory.learnMeaning]), so next time his rules know it
 *   straight away, without the brain. He learns from it the more you use him.
 * - **Answering** anything else, knowing who you are (your facts and routine).
 * - **Summarising** what a web look-up found ([WebLookup]).
 * The model is a file you download once (Gemma 3n E4B, about 4.4 GB) and load from the phone's storage.
 * Nothing you ask leaves the phone; one question at a time, on its own thread.
 */
object JarvisBrain {
    /** Where to get the model: Google's Gemma for phones (accept the licence, then download the .task file). */
    const val DOWNLOAD_PAGE = "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview"
    const val MODEL_NAME = "Gemma 3n E4B"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-brain").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var llm: LlmInference? = null
    @Volatile var loading = false
        private set

    fun modelFile(context: Context) = File(context.filesDir, "brain/model.task")

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
    }

    private fun unload() {
        val l = llm
        llm = null
        if (l != null) worker.execute { runCatching { l.close() } }
    }

    /** The model, loaded on the brain's thread (a few seconds the first time). */
    private fun load(context: Context): LlmInference {
        llm?.let { return it }
        loading = true
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile(context).absolutePath)
                .setMaxTokens(1024)
                .build()
            return LlmInference.createFromOptions(context.applicationContext, options).also { llm = it }
        } finally {
            loading = false
        }
    }

    /** Asks the model [prompt] (already in Gemma's turn format); [done] on the main thread with its reply, or null. */
    private fun ask(context: Context, prompt: String, done: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val out = try {
                load(app).generateResponse(prompt)?.trim()
            } catch (e: Throwable) {
                null
            }
            main.post { done(out?.takeIf { it.isNotBlank() }) }
        }
    }

    /** Warms the model up in the background, so the first real question isn't slow. */
    fun warm(context: Context) {
        if (!ready(context) || llm != null || loading) return
        val app = context.applicationContext
        worker.execute { runCatching { load(app) } }
    }

    private fun turn(system: String, user: String) = "<start_of_turn>user\n$system\n\n$user<end_of_turn>\n<start_of_turn>model\n"

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
        ask(context, turn(system, "Request: $said")) { out ->
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
        ask(context, turn(system, question), done)
    }

    // --- Summarising a look-up ---

    /** What [sources] (web pages' text) say about [question], in two or three sentences; null if they don't say. */
    fun summarise(context: Context, question: String, sources: List<WebLookup.Page>, done: (String?) -> Unit) {
        val system = "Answer the question using only the sources below, in two or three plain sentences, without markdown. " +
            "If the sources don't answer it, reply exactly NOTHING.\n\n" +
            sources.mapIndexed { i, p -> "Source ${i + 1} (${p.title}):\n${p.text.take(2500)}" }.joinToString("\n\n")
        ask(context, turn(system, "Question: $question")) { out -> done(out?.takeIf { !it.trim().equals("NOTHING", true) }) }
    }
}
