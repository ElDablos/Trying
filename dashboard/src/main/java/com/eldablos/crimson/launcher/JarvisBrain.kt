package com.eldablos.crimson.launcher

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Jarvis's brains: models running on the phone with Google's LiteRT-LM (on the GPU, the CPU if it must), for
 * whatever his own rules don't cover. You can give him several, and each job goes to the one best at it:
 * - **Talk** (Google's Gemma 4 E4B): his voice: answering, chatting, summing up look-ups.
 * - **Think** (Qwen3 4B Thinking): working things through: "why", plans, comparisons, tricky questions.
 * - **Quick** (Qwen3 1.7B, or any small one): working out what you meant, fast.
 * Whichever brain works out what you meant, he does it and remembers the phrasing
 * ([JarvisMemory.learnMeaning]), so next time his own rules know it without any brain: he learns from all
 * of them. With one brain, it does every job. Only one is held in memory at a time (they're gigabytes): he
 * keeps the last used and swaps when a job needs another. Nothing you ask leaves the phone.
 */
object JarvisBrain {
    enum class Role(val label: String) { TALK("TALK"), THINK("THINK"), QUICK("QUICK") }
    enum class Job { INTERPRET, ANSWER, REASON, SUMMARISE, SEE }

    /** A brain on the phone: its file, its name ("gemma-4-E4B-it"), the job it's best at. */
    class Model(val file: File, val name: String) {
        val role: Role = roleOf(name)
        val gb: Double get() = file.length() / 1e9
    }

    /** The brains worth having, and where to get them (sign in, accept the licence, download the .litertlm). */
    class Pick(val title: String, val role: Role, val page: String, val match: Regex)
    val RECOMMENDED = listOf(
        Pick("Gemma 4 E4B · Google", Role.TALK, "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm", Regex("gemma", RegexOption.IGNORE_CASE)),
        Pick("Qwen3 4B Thinking · Alibaba", Role.THINK, "https://huggingface.co/litert-community/Qwen3-4B-Thinking-2507", Regex("qwen.*4b|think", RegexOption.IGNORE_CASE)),
        Pick("Qwen3 1.7B · Alibaba", Role.QUICK, "https://huggingface.co/litert-community/Qwen3-1.7B", Regex("qwen.*(1\\.7|1_7|0\\.6|0\\.8)b", RegexOption.IGNORE_CASE)),
    )
    const val DOWNLOAD_PAGE = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm"
    const val MODEL_NAME = "Gemma 4 E4B"

    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-brain").apply { priority = Thread.NORM_PRIORITY - 1 } } }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var engine: Engine? = null
    @Volatile private var loadedPath: String? = null
    @Volatile var loading = false
        private set
    /** A brain's already loaded, so an answer comes in seconds (not after waking one). */
    val awake: Boolean get() = engine != null && !loading

    /** The brain that answered last ("Qwen3-4B-Thinking"), for the card. */
    @Volatile var lastUsed: String = MODEL_NAME
        private set

    private fun dir(context: Context) = File(context.filesDir, "brain")
    private fun prefs(context: Context) = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /** Which job a brain suits, from its name: Qwen's 4B and "thinking" ones think, small ones are quick, the rest talk. */
    fun roleOf(name: String): Role = when {
        Regex("think|qwen\\D*3\\D*4b|qwen.*-4b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> Role.THINK
        Regex("(0[._]6|0[._]8|1[._]5|1[._]7|1)b|\\be2b\\b|-e2b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> Role.QUICK
        else -> Role.TALK
    }

    /** Every brain he has, Talk first. */
    fun models(context: Context): List<Model> = (dir(context).listFiles { f -> f.name.endsWith(".litertlm") && f.length() > 100_000_000L } ?: emptyArray())
        .map { f -> Model(f, prefs(context).getString("brain_name:${f.name}", null) ?: prefs(context).getString("brain_name", null)?.takeIf { f.name == "model.litertlm" } ?: f.nameWithoutExtension) }
        .sortedBy { it.role.ordinal }

    fun installed(context: Context) = models(context).isNotEmpty()
    fun installedName(context: Context): String? = models(context).firstOrNull()?.name

    fun enabled(context: Context) = prefs(context).getBoolean("set:brain", true)
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("set:brain", on).apply()
        if (!on) unload()
    }

    /** At least one brain in, switched on: questions can go to it (it loads on first use). */
    fun ready(context: Context) = enabled(context) && installed(context)

    /**
     * Which brain does [job]: the one best at it, unless swapping would cost more than it's worth (working out
     * what you meant is quick on whichever is already loaded).
     */
    private fun pick(context: Context, job: Job): Model? {
        val all = models(context)
        if (all.isEmpty()) return null
        // Seeing: only a brain with eyes (Gemma reads pictures).
        if (job == Job.SEE) return all.firstOrNull { it.name.contains("gemma", true) }
        val order = when (job) {
            Job.INTERPRET -> listOf(Role.QUICK, Role.TALK, Role.THINK)
            Job.ANSWER -> listOf(Role.TALK, Role.THINK, Role.QUICK)
            Job.REASON -> listOf(Role.THINK, Role.TALK, Role.QUICK)
            Job.SUMMARISE, Job.SEE -> listOf(Role.TALK, Role.QUICK, Role.THINK)
        }
        val loaded = all.firstOrNull { it.file.absolutePath == loadedPath }
        if (job == Job.INTERPRET && loaded != null) return loaded
        return order.firstNotNullOfOrNull { r -> all.firstOrNull { it.role == r } } ?: all.first()
    }

    /**
     * Copies a brain you downloaded (picked from the phone's files) in next to the others, with progress in
     * percent; [done] true when it's in. The same file again replaces it. Big (gigabytes): off the main thread.
     */
    fun install(context: Context, uri: Uri, progress: (Int) -> Unit, done: (Boolean, String?) -> Unit) {
        val app = context.applicationContext
        val shown = runCatching {
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "brain-${System.currentTimeMillis()}.litertlm"
        val name = shown.substringBeforeLast('.').removeSuffix("-int4").removeSuffix("_int4")
        Thread {
            val d = dir(app)
            val dest = File(d, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".litertlm")
            val tmp = File(d, "incoming.part")
            try {
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
                            if (pct != last) {
                                last = pct
                                main.post { progress(pct) }
                            }
                        }
                    }
                }
                if (tmp.length() < 100_000_000L) throw IllegalStateException("That file's too small to be a brain.")
                dest.delete()
                tmp.renameTo(dest)
                prefs(app).edit().putString("brain_name:${dest.name}", name).apply()
            } catch (e: Exception) {
                tmp.delete()
                main.post { done(false, e.message) }
                return@Thread
            }
            main.post { done(true, name) }
        }.start()
    }

    fun remove(context: Context, model: Model) {
        if (model.file.absolutePath == loadedPath) unload()
        model.file.delete()
        prefs(context).edit().remove("brain_name:${model.file.name}").apply()
    }

    private fun unload() {
        val e = engine
        engine = null
        loadedPath = null
        if (e != null) worker.execute { runCatching { e.close() } }
    }

    /** Whether the loaded engine can see (Gemma with its vision part running). */
    @Volatile
    private var loadedEyes = false

    /** The last time he tried to read a picture, his eyes wouldn't start (not enough memory, or the GPU refused). */
    @Volatile
    var eyesFailed = false
        private set

    /**
     * The engine with [model] loaded, on the brain's thread (up to ten seconds when it swaps): the GPU, else the
     * CPU. Gemma loads with its eyes, which [needEyes] insists on (reloading if they didn't start before).
     */
    private fun load(context: Context, model: Model, needEyes: Boolean = false): Engine {
        val path = model.file.absolutePath
        engine?.let { if (loadedPath == path && (!needEyes || loadedEyes)) return it }
        engine?.let { runCatching { it.close() } }
        engine = null
        loadedPath = null
        loadedEyes = false
        loading = true
        try {
            val cache = context.cacheDir.path
            fun start(backend: Backend, vision: Backend?) = Engine(
                if (vision != null) EngineConfig(modelPath = path, backend = backend, visionBackend = vision, cacheDir = cache)
                else EngineConfig(modelPath = path, backend = backend, cacheDir = cache)).also { it.initialize() }
            // Gemma has eyes: its vision part on the GPU, else the CPU, so it can read pictures.
            val tries = if (model.name.contains("gemma", true)) listOf(
                { Backend.GPU() } to { Backend.GPU() as Backend? }, { Backend.GPU() } to { Backend.CPU() as Backend? },
                { Backend.CPU() } to { Backend.CPU() as Backend? }, { Backend.GPU() } to { null as Backend? }, { Backend.CPU() } to { null as Backend? })
            else listOf({ Backend.GPU() } to { null as Backend? }, { Backend.CPU() } to { null as Backend? })
            var last: Throwable? = null
            for ((b, v) in tries) {
                val vision = v()
                val e = try { start(b(), vision) } catch (t: Throwable) { last = t; continue }
                loadedPath = path
                loadedEyes = vision != null
                engine = e
                return e
            }
            throw last ?: IllegalStateException("no engine")
        } finally {
            loading = false
        }
    }

    /**
     * The picture made easy to read: decoded (PNG, JPEG, whatever it was), at most [MAX_SIDE] on its longest side,
     * saved as a real JPEG. A full-size screenshot is slow and can be too much for the phone's memory.
     */
    private fun prepared(context: Context, path: String): String {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return path
            var sample = 1
            while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return path
            val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            val sized = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
            val out = java.io.File(context.cacheDir, "jarvis-eyes.jpg")
            out.outputStream().use { sized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            if (sized !== bmp) sized.recycle()
            bmp.recycle()
            out.path
        } catch (e: Throwable) {
            path
        }
    }

    private const val MAX_SIDE = 1280

    /** A thinking model's working-out ("<think>…</think>") taken off: just the answer. */
    private fun tidy(out: String): String = out.replace(Regex("(?s)<think>.*?</think>"), "").substringAfterLast("</think>").trim()

    /** Asks the brain best at [job]: [user], with [system] as its instruction; [done] on the main thread with its reply, or null. */
    private fun ask(context: Context, job: Job, system: String, user: String, done: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val model = pick(app, job)
            val out = try {
                model?.let { m ->
                    load(app, m).createConversation(ConversationConfig(systemInstruction = Contents.of(system))).use { c ->
                        tidy(c.sendMessage(user).toString())
                    }.also { lastUsed = m.name }
                }
            } catch (e: Throwable) {
                null
            }
            main.post { done(out?.takeIf { it.isNotBlank() }) }
        }
    }

    /** Whether he can read pictures: a Gemma brain is in. */
    fun canSee(context: Context) = ready(context) && models(context).any { it.name.contains("gemma", true) }

    /** Looks at the picture at [imagePath] and does [instruction] with it; [done] on the main thread with the answer, or null. */
    fun see(context: Context, imagePath: String, instruction: String, done: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val model = pick(app, Job.SEE)
            eyesFailed = false
            val out = try {
                model?.let { m ->
                    val e = load(app, m, needEyes = true)
                    // Without eyes it would answer the words alone (and "describe it" becomes a description of himself).
                    if (!loadedEyes) {
                        eyesFailed = true
                        null
                    } else e.createConversation().use { c ->
                        tidy(c.sendMessage(Contents.of(Content.ImageFile(prepared(app, imagePath)), Content.Text(instruction))).toString())
                    }.also { lastUsed = m.name }
                }
            } catch (e: Throwable) {
                null
            }
            main.post { done(out?.takeIf { it.isNotBlank() }) }
        }
    }

    /**
     * A new command of yours, from how you describe it: its name, the phrase that starts it, and the steps
     * (each one of his own commands, as in [examples]); null if it can't be made from what he can do.
     */
    fun compose(context: Context, description: String, examples: List<String>, done: (Triple<String, String, List<String>>?) -> Unit) {
        val system = "You turn a description of a new voice command into steps a phone assistant can already do. Commands it understands, by example:\n" +
            examples.joinToString("\n") { "- $it" } +
            "\n\nReply in exactly this format and nothing else:\nNAME: <short name>\nSAY: <the phrase that starts it>\nSTEP: <one command in the style above>\nSTEP: <another>\n" +
            "Use only commands like the examples, filled in. If it can't be done with them, reply NONE."
        ask(context, Job.REASON, system, description) { out ->
            if (out == null || out.trim().equals("NONE", true)) return@ask done(null)
            val name = Regex("(?im)^NAME:\\s*(.+)$").find(out)?.groupValues?.get(1)?.trim()
            val say = Regex("(?im)^SAY:\\s*(.+)$").find(out)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            val steps = Regex("(?im)^STEP:\\s*(.+)$").findAll(out).map { it.groupValues[1].trim().trim('"', '\'') }.toList()
            done(if (name != null && say != null && steps.isNotEmpty()) Triple(name, say, steps) else null)
        }
    }

    /** Your notifications (app: title: text, one a line), put in a few lines: what matters first. */
    fun digest(context: Context, notifications: String, done: (String?) -> Unit) =
        ask(context, Job.SUMMARISE, "You sum up someone's phone notifications for them, as Jarvis, a British butler-style assistant. In at most five short sentences, without markdown: who wants what, what needs a reply or action, then the rest in brief. Skip adverts and system noise.", notifications, done)

    /** Warms his Talk brain in the background, so the first real question isn't slow. */
    fun warm(context: Context) {
        if (!ready(context) || engine != null || loading) return
        val app = context.applicationContext
        worker.execute { runCatching { pick(app, Job.ANSWER)?.let { load(app, it) } } }
    }

    // --- Keeping up to date ---

    /**
     * Newer brains than the ones you've got (a later Gemma, a later Qwen), looked up on Hugging Face's
     * litert-community at most once a day: name and page each. Off the main thread.
     */
    fun newer(context: Context): List<Pair<String, String>> {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        fun cached() = p.getString("brain_newer", null).orEmpty().split('\n').mapNotNull { l -> l.split('|').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        if (now - p.getLong("brain_checked", 0L) < 24 * 3600_000L) return cached()
        val mine = models(context).map { it.name }
        val found = ArrayList<Pair<String, String>>()
        try {
            for ((family, sizes) in listOf("gemma" to Regex("E4B|E2B", RegexOption.IGNORE_CASE), "qwen" to Regex("4B|1\\.7B", RegexOption.IGNORE_CASE))) {
                val json = (java.net.URL("https://huggingface.co/api/models?author=litert-community&search=$family&limit=100").openConnection() as java.net.HttpURLConnection).run {
                    connectTimeout = 6000
                    readTimeout = 8000
                    inputStream.use { String(it.readBytes()) }
                }
                val ids = org.json.JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") } }
                // "gemma-4-E4B" → 4, "Qwen3.5-4B" → 3.5.
                fun version(id: String) = Regex("$family-?(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1)?.toDoubleOrNull()
                val have = mine.filter { it.contains(family, true) }.mapNotNull { version(it) }.maxOrNull() ?: continue
                ids.filter { sizes.containsMatchIn(it) }.mapNotNull { id -> version(id)?.let { v -> id to v } }
                    .filter { (_, v) -> v > have }.maxByOrNull { it.second }
                    ?.let { (id, _) -> found += id.substringAfter('/') to "https://huggingface.co/$id" }
            }
        } catch (e: Exception) {
            return cached()
        }
        p.edit().putLong("brain_checked", now).putString("brain_newer", found.joinToString("\n") { "${it.first}|${it.second}" }).apply()
        return found
    }

    // --- Working out what you meant ---

    /**
     * Which of his own commands [said] means, written as he'd understand it ("set an alarm for 6:30", "what's
     * my rota next week"), filled in with your names, times and numbers; null when it isn't one of his.
     */
    fun interpret(context: Context, said: String, examples: List<String>, done: (String?) -> Unit) {
        // What Vee has corrected before: the way they put things, and what they meant.
        val learnt = JarvisMemory.meanings(context).takeLast(40)
        val system = "You map a request to an assistant on a phone onto one of its commands. Here are examples of the commands it understands:\n" +
            examples.joinToString("\n") { "- $it" } +
            (if (learnt.isEmpty()) "" else "\n\nThe user has corrected the assistant before; these requests meant these commands (follow the same reading for similar requests):\n" +
                learnt.joinToString("\n") { (said, meant) -> "- \"$said\" means: $meant" }) +
            "\n\nReply with ONE command in the same style as the examples, with the request's names, times, places and numbers filled in. " +
            "If the request isn't asking for one of these kinds of commands (a general question, chat, something it can't do), reply exactly NONE. " +
            "Reply with the command only: no quotes, no explanation."
        ask(context, Job.INTERPRET, system, "Request: $said") { out ->
            val line = out?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("-")?.trim()?.trim('"', '\'', '.', '`')
            done(line?.takeIf { it.isNotEmpty() && !it.equals("NONE", true) && it.length < 160 })
        }
    }

    // --- Answering ---

    private fun persona(about: String) = "You are Jarvis, a calm, witty British butler-style assistant on Vee's phone. " +
        "If you aren't sure of a fact, or it could have changed recently (news, prices, opening times, releases, scores), say so briefly instead of guessing. " +
        "What you know about Vee:\n$about"

    /** A short answer to [question], knowing [about] you (facts, routine), as Jarvis: brief, British, helpful. */
    fun answer(context: Context, question: String, about: String, done: (String?) -> Unit) =
        ask(context, Job.ANSWER, persona(about) + "\n\nAnswer conversationally, as Jarvis talking to Vee, in at most four short sentences, without markdown or lists. Use the recent conversation for context.", question, done)

    /**
     * Conversation, not a command: Vee telling him something. A natural reply as Jarvis, using what he knows
     * [about] them; at most one offer of help, on a last line "OFFER: <one of [commands]>".
     */
    fun chat(context: Context, said: String, about: String, commands: List<String>, done: (String?) -> Unit) =
        ask(context, Job.ANSWER, persona(about) + "\n\nVee is chatting with you, not giving a command. Reply the way a trusted butler who knows them well would talk: " +
            "warm, natural and a little dry, picking up on what they said and on what you know of their day (shifts, uni, habits, notes). " +
            "One to three short sentences, no markdown, no lists. You may ask one short follow-up question. " +
            "Only if there's an obviously useful thing to do, end with a separate last line \"OFFER: <command>\" where the command is written like one of these:\n" +
            commands.take(60).joinToString("\n") { "- $it" } + "\nand phrase the offer as a question in your reply.", "Vee: $said", done)

    /** How a document is written for [Documents.parse]: plain lines, headings, lists, tables. */
    fun documentRules(spreadsheet: Boolean, plan: Boolean): String =
        "Write the document Vee asked for. Output ONLY the document, in this plain format:\n" +
            "# Title (the first line)\n## Section heading\n### Sub-heading\n- a bullet point (or 1. 2. 3. for steps in order)\n" +
            "Tables: one row per line, cells separated by \" | \", the first row naming the columns; no separator lines.\n" +
            "Everything else is plain paragraphs. No bold, italics, code, emoji or links. British English. Be specific and practical. " +
            (if (spreadsheet) "This becomes a spreadsheet: put the substance in tables, one per topic, with each number in its own cell (only £ or % with numbers). "
            else "Aim for about 300 to 700 words. ") +
            (if (plan) "Use real dates starting from today, and fit it around Vee's shifts and uni where you know them. " else "")

    /**
     * A document written by a brain: [plan] by the Think brain (plans, schedules, breakdowns), otherwise the Talk
     * brain (summaries, explainers), from [material] when there is some (web pages, a conversation).
     */
    fun write(context: Context, plan: Boolean, request: String, about: String, material: String?, spreadsheet: Boolean, planDates: Boolean, done: (String?) -> Unit) =
        ask(context, if (plan) Job.REASON else Job.ANSWER, persona(about) + "\n\n" + documentRules(spreadsheet, planDates),
            "Request: $request" + (material?.let { "\n\nMaterial to work from (use it, don't copy it word for word):\n${it.take(6500)}" } ?: ""), done)

    /** Something to work through ("why…", a plan, a comparison): the Think brain, a little more room, still plain. */
    fun reason(context: Context, question: String, about: String, done: (String?) -> Unit) =
        ask(context, Job.REASON, persona(about) + "\n\nThink it through carefully, then give the answer in at most six short sentences, plainly, without markdown.", question, done)

    // --- Summarising a look-up ---

    /** What [sources] (web pages' text) say about [question], in two or three sentences; null if they don't say. */
    fun summarise(context: Context, question: String, sources: List<WebLookup.Page>, done: (String?) -> Unit) {
        val system = "Answer the question using only the sources below, in two or three plain sentences, without markdown. " +
            "If the sources don't answer it, reply exactly NOTHING.\n\n" +
            sources.mapIndexed { i, p -> "Source ${i + 1} (${p.title}):\n${p.text.take(2500)}" }.joinToString("\n\n")
        ask(context, Job.SUMMARISE, system, "Question: $question") { out -> done(out?.takeIf { !it.trim().equals("NOTHING", true) }) }
    }
}
