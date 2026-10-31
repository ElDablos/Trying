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
        val google = try {
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
                    // An assistant's voice: it plays over other apps at the media volume, and ducks their sound.
                    engine.setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
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
    // Audio focus while he talks: music and videos in other apps duck under him, then come back.
    private var focusRequest: android.media.AudioFocusRequest? = null

    private fun focus(context: Context, on: Boolean) {
        val am = context.applicationContext.getSystemService(android.media.AudioManager::class.java) ?: return
        if (on) {
            if (focusRequest != null) return
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            am.requestAudioFocus(req)
            focusRequest = req
        } else {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

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

    fun chooseVoice(context: Context, name: String) {
        with(context) { t ->
            val list = voices(t).take(16)
            if (list.isEmpty()) {
                Toast.makeText(context, "No English voices installed", Toast.LENGTH_SHORT).show()
                return@with
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
