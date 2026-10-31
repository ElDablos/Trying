package com.eldablos.crimson.launcher

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.Toast
import java.util.Locale

/**
 * The launcher's voice, shared by the briefing and Jarvis: one speech engine (Google's, for its natural
 * voices, when it's installed), a calm British male voice unless you pick another, and text turned into
 * what a person would say. A script is a list of parts (id to text) read in turn; [speak] can start
 * part-way through, which is how a stopped briefing carries on where it left off.
 */
object Speech {
    private const val GOOGLE_TTS = "com.google.android.tts"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var tts: TextToSpeech? = null
    private var appContext: Context? = null
    private var ready = false
    private val waiting = ArrayList<(TextToSpeech) -> Unit>()

    /** Who's speaking now: told each part as it starts, and when the script ends or stops. */
    private var listener: Listener? = null

    interface Listener {
        fun onPart(id: String)
        fun onEnd(finished: Boolean)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("deck", Context.MODE_PRIVATE)

    /** Starts the engine if needed, then runs [then] with it, on the main thread. */
    fun with(context: Context, then: (TextToSpeech) -> Unit) {
        val t = tts
        if (t != null && ready) return then(t)
        waiting += then
        if (t != null) return
        val app = context.applicationContext
        appContext = app
        // The engine you picked in his settings (Google's, Samsung's, a neural one like VoxSherpa), else Google's.
        val google = prefs(app).getString("engine", null)?.takeIf { installed(app, it) } ?: try {
            app.packageManager.getPackageInfo(GOOGLE_TTS, 0)
            GOOGLE_TTS
        } catch (e: Exception) {
            null
        }
        val init = TextToSpeech.OnInitListener { status ->
            main.post {
                val engine = tts
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    ready = true
                    // On the media volume, through the speaker or your earbuds, over whatever else is playing.
                    // (Some phones give "assistant" sound its own, much quieter level, which is why he isn't one.)
                    engine.setAudioAttributes(ATTRIBUTES)
                    pickVoice(app, engine)
                    waiting.toList().forEach { it(engine) }
                } else {
                    tts = null
                    Toast.makeText(app, "Text to speech isn't available", Toast.LENGTH_SHORT).show()
                }
                waiting.clear()
            }
        }
        tts = if (google != null) TextToSpeech(app, init, google) else TextToSpeech(app, init)
    }

    /**
     * Reads [parts] from [from] on, telling [who] as each starts and when it's done. A new script takes
     * over from one already playing (whose listener hears it ended, unfinished).
     */
    fun speak(context: Context, parts: List<Pair<String, String>>, who: Listener, from: Int = 0) {
        if (parts.isEmpty() || from > parts.lastIndex) return
        val previous = listener
        listener = who
        if (previous != null && previous !== who) previous.onEnd(false)
        with(context) { t ->
            focus(context, true)
            // His manner: calm and unhurried, a touch lower than the voice's own (see [STYLES]).
            val style = style(context)
            t.setSpeechRate(style.rate)
            t.setPitch(style.pitch)
            val last = parts.last().first
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    if (id != null && !id.startsWith("pause:")) main.post { if (listener === who) who.onPart(id) }
                }

                override fun onDone(id: String?) {
                    if (id == last) main.post { focus(context, false); if (listener === who) { listener = null; who.onEnd(true) } }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    main.post { focus(context, false); if (listener === who) { listener = null; who.onEnd(false) } }
                }
            })
            parts.drop(from).forEachIndexed { i, (id, text) ->
                t.speak(speakable(text), if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
                // A breath between parts, a little longer after the opening.
                if (from + i < parts.lastIndex) t.playSilentUtterance(if (from + i < 2) 380 else 300, TextToSpeech.QUEUE_ADD, "pause:${from + i}")
            }
        }
    }

    /** Stops whatever's being said; its listener hears it ended, unfinished. */
    // Audio focus while he talks: music and videos in other apps go quiet for him, then come back.
    private var focusRequest: android.media.AudioFocusRequest? = null

    private val ATTRIBUTES: android.media.AudioAttributes by lazy {
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }

    /** The media volume he raised to be heard, and what it was before: put back when he's done, unless you changed it. */
    private var raisedTo = -1
    private var raisedFrom = -1

    private fun focus(context: Context, on: Boolean) {
        val am = context.applicationContext.getSystemService(android.media.AudioManager::class.java) ?: return
        val stream = android.media.AudioManager.STREAM_MUSIC
        if (on) {
            if (focusRequest != null) return
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(ATTRIBUTES).build()
            am.requestAudioFocus(req)
            focusRequest = req
            // Never a whisper: if the media volume's right down, he brings it up to a level you can hear, for himself only.
            if (loud(context)) {
                val max = am.getStreamMaxVolume(stream)
                val now = am.getStreamVolume(stream)
                val floor = (max * MIN_LOUDNESS).toInt().coerceAtLeast(1)
                if (now < floor && !am.isStreamMute(stream)) {
                    try {
                        am.setStreamVolume(stream, floor, 0)
                        raisedFrom = now
                        raisedTo = floor
                    } catch (e: Exception) {
                        // Do not disturb, or not allowed: he plays at what it is.
                    }
                }
            }
        } else {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
            if (raisedTo >= 0) {
                try {
                    if (am.getStreamVolume(stream) == raisedTo) am.setStreamVolume(stream, raisedFrom, 0)
                } catch (e: Exception) {
                    // Left as it is.
                }
                raisedTo = -1
                raisedFrom = -1
            }
        }
    }

    /** At least this share of the full media volume while he speaks (on unless you turn it off). */
    private const val MIN_LOUDNESS = .5
    fun loud(context: Context) = prefs(context).getBoolean("loud", true)
    fun setLoud(context: Context, on: Boolean) = prefs(context).edit().putBoolean("loud", on).apply()

    fun stop() {
        appContext?.let { focus(it, false) }
        tts?.stop()
        listener?.let { listener = null; it.onEnd(false) }
    }

    val speaking: Boolean get() = listener != null

    // --- Voices ---

    /** English voices worth offering: the Jarvis-like British male voices first, then by country and quality. */
    fun voices(t: TextToSpeech): List<Voice> {
        val country = Locale.getDefault().country
        return try {
            t.voices.orEmpty()
                .filter { it.locale.language == "en" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(
                    compareByDescending<Voice> { it.locale.country == "GB" && male(it) }
                        .thenByDescending { it.locale.country == country }
                        .thenByDescending { it.quality }
                        .thenByDescending { it.isNetworkConnectionRequired }
                        .thenBy { it.name },
                )
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Google's British male voices are "gbd" and "rjs"; others say so in their features. */
    private fun male(v: Voice): Boolean =
        listOf("-gbd-", "-rjs-", "gbd", "rjs").any { v.name.contains(it, ignoreCase = true) } ||
            v.features.orEmpty().any { it.equals("male", true) || it.contains("gender=male", true) }

    /** The voice you chose, or the most Jarvis-like there is. */
    private fun pickVoice(context: Context, t: TextToSpeech) {
        val saved = prefs(context).getString("voice", null)
        val list = voices(t)
        val v = list.firstOrNull { it.name == saved } ?: list.firstOrNull()
        if (v != null) t.voice = v else t.language = Locale.UK
    }

    /** "UK · Voice D, male · natural, online". */
    fun voiceLabel(v: Voice): String {
        val country = when (v.locale.country) { "GB" -> "UK"; "US" -> "US"; "AU" -> "Australia"; "IN" -> "India"; "IE" -> "Ireland"; "ZA" -> "South Africa"; else -> v.locale.displayCountry }
        val id = v.name.substringAfter("-x-", "").substringBefore('-')
        // Kokoro's names ("am_michael", "bm_george"): the name, and its accent and sex from the prefix.
        Regex("^([ab])([mf])_([a-z]+)", RegexOption.IGNORE_CASE).find(v.name)?.let { m ->
            val who = m.groupValues[3].replaceFirstChar { it.uppercaseChar() }
            return "${if (m.groupValues[1].equals("b", true)) "UK" else "US"} · $who, ${if (m.groupValues[2].equals("m", true)) "male" else "female"} · neural, on the phone"
        }
        val who = (if (id.length >= 3) "Voice ${id.last().uppercaseChar()}" else v.name) + if (male(v)) ", male" else ""
        val kind = when {
            v.isNetworkConnectionRequired -> "natural, online"
            v.quality >= Voice.QUALITY_VERY_HIGH -> "natural, on the phone"
            else -> "on the phone"
        }
        return "$country · $who · $kind"
    }

    /** Pick the voice, hearing each as you choose. [context] must be an activity, for the dialog. */
    /** How he speaks: pace and pitch on top of the voice itself. */
    class Style(val label: String, val rate: Float, val pitch: Float)
    val STYLES = listOf(
        Style("JARVIS", .97f, .86f),   // measured, lower: the butler
        Style("CALM", .96f, .94f),     // as he was
        Style("NATURAL", 1f, 1f),
        Style("BRISK", 1.12f, .92f),
    )
    fun style(context: Context): Style = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("set:voice_style", "JARVIS")
        .let { n -> STYLES.firstOrNull { it.label == n } ?: STYLES[0] }
    fun setStyle(context: Context, label: String) = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("set:voice_style", label).apply()

    private fun installed(context: Context, pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Switches to another voice engine (Google's, Samsung's, VoxSherpa…): the old one's let go, the new one starts. */
    private fun useEngine(context: Context, pkg: String, then: (TextToSpeech) -> Unit) {
        prefs(context).edit().putString("engine", pkg).remove("voice").apply()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ready = false
        with(context, then)
    }

    /**
     * Pick the voice, hearing each as you choose: first the engine when there's more than one (Google's,
     * Samsung's, a neural one such as VoxSherpa with Kokoro's voices), then its voices. An engine whose
     * voices it doesn't list speaks in the voice you chose inside it (VoxSherpa's Michael, say).
     */
    fun chooseVoice(context: Context, name: String) {
        with(context) { t ->
            val engines = t.engines.orEmpty()
            if (engines.size > 1) {
                val current = prefs(context).getString("engine", null) ?: t.defaultEngine
                AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("◆ VOICE ENGINE")
                    .setItems(engines.map { (if (it.name == current) "● " else "○ ") + it.label }.toTypedArray()) { _, i ->
                        val pick = engines[i].name
                        if (pick == current && tts != null) voiceList(context, name, t) else useEngine(context, pick) { nt -> voiceList(context, name, nt) }
                    }
                    .show()
            } else voiceList(context, name, t)
        }
    }

    private fun voiceList(context: Context, name: String, t: TextToSpeech) {
        run {
            val list = voices(t).take(24)
            if (list.size <= 1) {
                // An engine that keeps its own voice (VoxSherpa's Michael): just hear it.
                if (list.size == 1) {
                    prefs(context).edit().putString("voice", list[0].name).apply()
                    t.voice = list[0]
                }
                speak(context, listOf("x:sample" to "Good evening $name. This is how I'll sound from now on."), object : Listener {
                    override fun onPart(id: String) {}
                    override fun onEnd(finished: Boolean) {}
                })
                Toast.makeText(context, "Using this engine's own voice. Change it inside its app.", Toast.LENGTH_LONG).show()
                return@run
            }
            AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("◆ JARVIS'S VOICE")
                .setItems(list.map { voiceLabel(it) }.toTypedArray()) { _, i ->
                    prefs(context).edit().putString("voice", list[i].name).apply()
                    t.voice = list[i]
                    speak(context, listOf("x:sample" to "Good evening $name. This is how I'll sound from now on. Your next shift is tomorrow at 06:00."), object : Listener {
                        override fun onPart(id: String) {}
                        override fun onEnd(finished: Boolean) {}
                    })
                }
                .show()
        }
    }

    // --- Saying things like a person ---

    /** Times, durations and symbols as they're said: "06:00" → "6 a.m.", "7h 11m" → "about 7 hours". */
    fun speakable(text: String): String {
        var t = text
        t = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").replace(t) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            when {
                h == 0 && min == 0 -> "midnight"
                h == 12 && min == 0 -> "midday"
                else -> {
                    val h12 = if (h % 12 == 0) 12 else h % 12
                    (if (min == 0) "$h12" else "$h12:%02d".format(min)) + if (h < 12) " a.m." else " p.m."
                }
            }
        }
        t = Regex("\\b(\\d+)d (\\d+)h\\b").replace(t) { m -> spokenHours(m.groupValues[1].toInt() * 24 + m.groupValues[2].toInt(), 0) }
        t = Regex("\\b(\\d+)h (\\d+)m\\b").replace(t) { m -> spokenHours(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        t = Regex("\\b(\\d+)h\\b").replace(t) { m -> spokenHours(m.groupValues[1].toInt(), 0) }
        t = Regex("\\b(\\d+)m\\b").replace(t) { m -> m.groupValues[1].toInt().let { if (it == 1) "a minute" else "$it minutes" } }
        return t.replace("°", " degrees").replace("%", " percent").replace(" · ", ", ").replace("·", ",")
            .replace("▲", "up").replace("▼", "down").replace("→", "to").replace("–", " to ")
    }

    /** "an hour and 20 minutes", "2 and a half hours", "about 7 hours". */
    private fun spokenHours(h: Int, m: Int): String = when {
        h == 0 -> if (m == 1) "a minute" else "$m minutes"
        h >= 3 -> "about ${if (m >= 30) h + 1 else h} hours"
        m == 0 -> if (h == 1) "an hour" else "$h hours"
        m in 25..35 -> if (h == 1) "an hour and a half" else "$h and a half hours"
        else -> (if (h == 1) "an hour" else "$h hours") + " and $m minutes"
    }
}
