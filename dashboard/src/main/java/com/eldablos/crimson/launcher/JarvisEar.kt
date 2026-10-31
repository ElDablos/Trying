package com.eldablos.crimson.launcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Jarvis's ears outside the launcher. A foreground service (Android's green mic dot shows while it
 * listens) that waits for "Hey Jarvis" with Vosk, offline and on the phone, listening for those two
 * words and nothing else. When it hears them it lets go of the mic, puts [JarvisOverlay] over whatever
 * app you're in, hears your command with the phone's own speech recognition, and has the shared
 * [Jarvis] answer it, out loud. After he's worked an app for you (a light's colour, the heater) he
 * stays a few seconds for "next colour" or "brighter", then goes back to Jarvis.
 *
 * It also runs briefly without the wake word, just to hear those follow-ups, when you ask from the
 * panel. Only while charging, if you'd rather.
 */
class JarvisEar : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var wake: WakeMic? = null
    /** Woken by the wake word (not a tap), and nothing said yet: to tell a real call from a false one. */
    private var wokeByWord = false
    private var recognizer: SpeechRecognizer? = null

    /** The command heard on the phone itself, with Vosk, while it's locked (Google's recognizer can't use the mic then). */
    private var command: SpeechService? = null
    private var overlay: JarvisOverlay? = null
    private var session = false
    private var lingering = false
    private var paused = 0
    private var charging = false
    private var lastText = ""
    /** Something that needs the screen, asked while the phone was locked: done as soon as you unlock. */
    private var held: String? = null
    private var heldAt = 0L

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = held ?: return
            held = null
            if (System.currentTimeMillis() - heldAt > HOLD_MS) return
            // A moment for the home screen (or the app you were in) to settle, then on with it.
            main.postDelayed({
                stopWake()
                session = true
                lingering = false
                showOverlay()
                overlay?.said("Right away, sir.")
                handle(text)
            }, 600)
        }
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (now != charging) {
                charging = now
                if (chargingOnly(this@JarvisEar)) if (charging) startWake() else stopWake()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Jarvis", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while Jarvis listens for \"Hey Jarvis\""
            setShowBadge(false)
        })
        val stop = PendingIntent.getService(this, 1, Intent(this, JarvisEar::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, LauncherActivity::class.java).putExtra(JarvisProactive.EXTRA_INSIGHT, "ear"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("◆ Jarvis is listening")
            .setContentText("Say \"Hey Jarvis\" from any app. Nothing leaves the phone.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "TURN OFF", stop).build())
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            charging = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        startWake()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setWake(this, false)
            if (!session) stopSelf()
        } else {
            startWake()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(battery)
            unregisterReceiver(unlocked)
        } catch (e: Exception) {
            // Not registered.
        }
        stopWake()
        recognizer?.destroy()
        overlay?.hide()
        model?.close()
        model = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    // --- The wake word ---

    private fun canWake() = wakeEnabled(this) && modelReady(this) && !session && paused == 0 && (!chargingOnly(this) || charging) &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startWake() {
        if (wake != null || !canWake()) return
        Thread {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = model ?: Model(modelDir(this).absolutePath).also { model = it }
                // The wake words, and words that sound like them for everything else to land on, so talk, a
                // song or a rustle comes out as "harvest" or "nervous" rather than being forced into "jarvis".
                val rec = Recognizer(m, 16000f, GRAMMAR)
                rec.setWords(true)
                main.post {
                    if (!canWake() || wake != null) {
                        rec.close()
                        return@post
                    }
                    val mic = WakeMic(this, rec, { heardWake(it) }, {
                        main.post {
                            stopWake()
                            main.postDelayed({ startWake() }, 3000)
                        }
                    })
                    if (mic.start()) wake = mic else {
                        rec.close()
                        main.postDelayed({ startWake() }, 5000)
                    }
                }
            } catch (e: Exception) {
                // The model isn't usable: the settings will offer to download it again.
            }
        }.start()
    }

    private fun stopWake() {
        wake?.stop()
        wake = null
    }

    /**
     * "Hey Jarvis" (or "OK Jarvis"), said on its own and clearly: the whole phrase heard, nothing else
     * round it, said at a talking pace, and each word heard confidently (more so after false starts: see
     * [wakeBar]). Near misses go in the wake log.
     */
    private fun heardWake(json: String?) {
        if (json == null || session) return
        try {
            val o = JSONObject(json)
            val text = o.optString("text").replace("[unk]", "").replace(Regex("\\s+"), " ").trim()
            if (!text.contains("jarvis")) return
            val words = o.optJSONArray("result") ?: return
            val list = (0 until words.length()).map { words.getJSONObject(it) }
            val i = list.indexOfFirst { it.optString("word") == "jarvis" }
            val first = list.getOrNull(i - 1)
            val bar = wakeBar(this)
            val reason = when {
                text !in setOf("hey jarvis", "ok jarvis", "okay jarvis") -> "not on its own"
                first == null || first.optString("word") !in setOf("hey", "ok", "okay") -> "no hey"
                first.optDouble("conf", 0.0) < bar - .1 || list[i].optDouble("conf", 0.0) < bar -> "unsure"
                list[i].optDouble("end") - first.optDouble("start") !in .35..1.6 -> "wrong pace"
                else -> null
            }
            val confs = list.joinToString(" ") { "${it.optString("word")}:${"%.2f".format(java.util.Locale.ROOT, it.optDouble("conf", 0.0))}" }
            logWake(this, if (reason == null) "WOKE" else "ignored ($reason)", "$confs · bar ${"%.2f".format(java.util.Locale.ROOT, bar)}")
            if (reason == null) main.post { wakeUp() }
        } catch (e: Exception) {
            // Not a result we can read.
        }
    }

    /** Nothing said after the wake word: likely a false start. The bar goes up a little, and it's logged. */
    private fun nothingSaid() {
        if (!wokeByWord) return
        wokeByWord = false
        setWakeBar(this, (wakeBar(this) + .03).coerceAtMost(.92))
        logWake(this, "nothing said after", "bar now ${"%.2f".format(java.util.Locale.ROOT, wakeBar(this))}")
    }

    /** A real call: the bar eases back down towards where it started. */
    private fun calledForReal() {
        if (!wokeByWord) return
        wokeByWord = false
        setWakeBar(this, (wakeBar(this) - .01).coerceAtLeast(WAKE_CONFIDENCE))
    }

    private fun wakeUp() {
        if (session) return
        stopWake()
        session = true
        wokeByWord = true
        lingering = false
        showOverlay()
        // He answers when called, like a person would, then listens (after he's spoken, so he doesn't hear himself).
        val brief = JarvisMemory.persona(this) == JarvisMemory.Persona.BRIEF
        val hi = if (brief) "Yes?" else listOf("Yes, sir?", "At your service.", "Sir?", "I'm listening.", "Go ahead, sir.", "How can I help?").random()
        overlay?.said(hi)
        overlay?.state = JarvisOverlay.State.SPEAKING
        Speech.speak(this, listOf("hello" to hi), object : Speech.Listener {
            override fun onPart(id: String) {}
            override fun onEnd(finished: Boolean) {
                if (!session) return
                overlay?.said("")
                JarvisSound.play(this@JarvisEar, JarvisSound.Cue.LISTEN)
                listen(followUp = false)
            }
        })
    }

    // --- A conversation over any app ---

    private fun showOverlay() {
        val o = overlay ?: JarvisOverlay(this).also {
            it.onDismiss = { end(goHome = false) }
            it.onOpen = { openPanel() }
            overlay = it
        }
        o.state = JarvisOverlay.State.LISTENING
        o.heard("")
        o.show()
    }

    // --- Cutting him off ---

    private var hushing: SpeechService? = null
    private var hushOn = false

    private fun startHush(onStop: () -> Unit) {
        if (hushOn || !modelReady(this) || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        hushOn = true
        paused++
        stopWake()
        Thread {
            try {
                val m = model ?: Model(modelDir(this).absolutePath).also { model = it }
                val rec = Recognizer(m, 16000f, "[\"stop\", \"stop it\", \"stop talking\", \"jarvis\", \"hey jarvis\", \"enough\", \"quiet\", \"be quiet\", \"shut up\", \"wait\", \"hold on\", \"[unk]\"]")
                rec.setWords(true)
                main.post {
                    if (!hushOn) {
                        rec.close()
                        return@post
                    }
                    try {
                        var fired = false
                        fun check(json: String?) {
                            if (fired || !hushOn) return
                            val o = try { JSONObject(json ?: "") } catch (e: Exception) { return }
                            val text = o.optString("text").trim()
                            if (text.isEmpty() || text.contains("[unk]")) return
                            val words = o.optJSONArray("result") ?: return
                            var sum = 0.0
                            for (i in 0 until words.length()) sum += words.getJSONObject(i).optDouble("conf", 0.0)
                            if (words.length() == 0 || sum / words.length() < .7) return
                            fired = true
                            stopHush()
                            onStop()
                        }
                        hushing = SpeechService(rec, 16000f).also {
                            it.startListening(object : org.vosk.android.RecognitionListener {
                                override fun onPartialResult(hypothesis: String?) {}
                                override fun onResult(hypothesis: String?) = check(hypothesis)
                                override fun onFinalResult(hypothesis: String?) = check(hypothesis)
                                override fun onError(exception: Exception?) = stopHush()
                                override fun onTimeout() = stopHush()
                            })
                        }
                    } catch (e: Exception) {
                        stopHush()
                    }
                }
            } catch (e: Exception) {
                main.post { stopHush() }
            }
        }.start()
    }

    private fun stopHush() {
        hushing?.let {
            hushing = null
            try {
                it.stop()
                it.shutdown()
            } catch (e: Exception) {
                // Already stopped.
            }
        }
        if (hushOn) {
            hushOn = false
            paused = (paused - 1).coerceAtLeast(0)
            if (paused == 0) startWake()
        }
    }

    private fun locked() = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Hears one command; a [followUp] that hears nothing just ends the conversation. */
    private fun listen(followUp: Boolean) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return end(goHome = false)
        // Locked: Android won't give Google's recognizer the mic, so he listens himself.
        if (locked() && modelReady(this)) return listenOffline(followUp)
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        overlay?.state = JarvisOverlay.State.LISTENING
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { overlay?.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { overlay?.state = JarvisOverlay.State.THINKING }
            override fun onError(error: Int) {
                // Refused the mic or the service (locked, or busy): hear it on the phone instead.
                val refused = error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                    error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                    error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER
                if (recognizer !== r) return
                recognizer = null
                main.post { r.destroy() }
                if (refused && modelReady(this@JarvisEar)) return listenOffline(followUp)
                if (followUp) end(goHome = lingering) else {
                    nothingSaid()
                    overlay?.said("Didn't catch that, sir.")
                    main.postDelayed({ end(goHome = false) }, 1500)
                }
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== r) return
                // Let go of the recognizer (and the audio it holds) before he answers, or his voice can't be heard.
                recognizer = null
                main.post { r.destroy() }
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                if (all.isEmpty()) {
                    if (followUp) end(goHome = lingering) else {
                        nothingSaid()
                        overlay?.said("Didn't catch that, sir.")
                        main.postDelayed({ end(goHome = false) }, 1500)
                    }
                    return
                }
                JarvisSound.play(this@JarvisEar, JarvisSound.Cue.HEARD)
                handle(brain().bestHeard(all))
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { overlay?.heard("“$it”") }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !Jarvis.online(this))
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // A follow-up gives up quickly if you've nothing more to say.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (followUp) 2000L else 2500L)
            .putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList((Jarvis.HEARD_WORDS +
                JarvisMemory.vocabulary(this) + AppCatalog.apps().map { it.label }).distinct().take(200))))
    }

    private val host = object : Jarvis.Host {
        /** The briefing, settings and permissions belong to the launcher: he hands over to it there. */
        override fun briefing(control: JarvisParser.Control?, chapter: String?): Boolean {
            toLauncher(lastText)
            return true
        }

        override fun launch(component: ComponentName) {
            try {
                AppCatalog.launch(this@JarvisEar, component)
            } catch (e: Exception) {
                packageManager.getLaunchIntentForPackage(component.packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }

        override fun ask(permissions: Array<String>) = toLauncher(lastText)
    }

    private fun brain() = Jarvis.shared(this, host)

    private fun handle(text: String) {
        calledForReal()
        lastText = text
        // Locked: he can talk, but can't open or work an app. He holds it until you unlock.
        val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked && Jarvis.needsScreen(this, text)) {
            held = text
            heldAt = System.currentTimeMillis()
            val say = "Unlock the phone and I'll do it, sir."
            overlay?.said(say)
            overlay?.state = JarvisOverlay.State.SPEAKING
            Speech.speak(this, listOf("ear" to say), object : Speech.Listener {
                override fun onPart(id: String) {}
                override fun onEnd(finished: Boolean) = end(goHome = false)
            })
            return
        }
        overlay?.heard("“$text”")
        overlay?.state = JarvisOverlay.State.THINKING
        Thread {
            val reply = brain().answer(text, via = JarvisChat.EAR)
            main.post { respond(reply) }
        }.start()
    }

    private fun respond(reply: Jarvis.Reply) {
        if (reply.sheet != null) {
            toLauncher(lastText)
            return
        }
        if (reply.silent) {
            reply.then?.invoke()
            end(goHome = false)
            return
        }
        overlay?.said(reply.say)
        overlay?.state = JarvisOverlay.State.SPEAKING
        if (!reply.afterSpeech) reply.then?.invoke()
        var done = false
        var cut = false
        val after = {
            if (!done) {
                done = true
                if (reply.afterSpeech) reply.then?.invoke()
                when {
                    // You cut him off: straight to your next command.
                    cut -> {
                        overlay?.said("Go ahead, sir.")
                        listen(followUp = false)
                    }
                    reply.listen -> listen(followUp = true)
                    // His hands are off to work an app: they'll report back (and maybe stay a moment).
                    JarvisHands.busy -> overlay?.state = JarvisOverlay.State.STANDBY
                    reply.end -> main.postDelayed({ end(goHome = false) }, 900)
                    // A conversation: he listens for what's next; silence ends it.
                    JarvisMemory.keepListening(this) -> listen(followUp = true)
                    else -> main.postDelayed({ if (!JarvisHands.busy) end(goHome = false) }, 2200)
                }
            }
        }
        val safety = Runnable { after() }
        main.postDelayed(safety, 9000)
        Speech.speak(this, listOf("ear" to Speech.speakable(reply.say)), object : Speech.Listener {
            override fun onPart(id: String) {
                main.removeCallbacks(safety)
                main.postDelayed(safety, 2500L + reply.say.length * 90L)
                startHush {
                    cut = true
                    Speech.stop()
                }
            }
            override fun onEnd(finished: Boolean) {
                stopHush()
                main.removeCallbacks(safety)
                after()
            }
        })
    }

    /** From his hands, still in the app: say how it went, then a few seconds for "next colour" or "brighter" before going back. */
    private fun linger(say: String) {
        JarvisChat.add(this, false, say, JarvisChat.EAR)
        session = true
        lingering = true
        stopWake()
        showOverlay()
        overlay?.heard("")
        overlay?.said(say)
        overlay?.state = JarvisOverlay.State.SPEAKING
        Speech.speak(this, listOf("ear" to Speech.speakable(say)), object : Speech.Listener {
            override fun onPart(id: String) {}
            override fun onEnd(finished: Boolean) {
                overlay?.said("Anything else, sir? Next colour, brighter, warmer…")
                listen(followUp = true)
            }
        })
    }

    /**
     * One command, heard on the phone with Vosk's full vocabulary (the same model as the wake word):
     * a little less sharp than Google's, but it works with the phone locked and nothing leaves it.
     */
    private fun listenOffline(followUp: Boolean) {
        stopCommand()
        overlay?.state = JarvisOverlay.State.LISTENING
        Thread {
            try {
                val m = model ?: Model(modelDir(this).absolutePath).also { model = it }
                val rec = Recognizer(m, 16000f)
                main.post {
                    if (!session) {
                        rec.close()
                        return@post
                    }
                    try {
                        var done = false
                        val svc = SpeechService(rec, 16000f)
                        command = svc
                        fun heard(json: String?) {
                            if (done) return
                            val text = try { JSONObject(json ?: "").optString("text").trim() } catch (e: Exception) { "" }
                            if (text.isEmpty() || text == "jarvis") return
                            done = true
                            stopCommand()
                            JarvisSound.play(this@JarvisEar, JarvisSound.Cue.HEARD)
                            overlay?.heard("“$text”")
                            handle(text)
                        }
                        svc.startListening(object : org.vosk.android.RecognitionListener {
                            override fun onPartialResult(hypothesis: String?) {
                                val p = try { JSONObject(hypothesis ?: "").optString("partial") } catch (e: Exception) { "" }
                                if (p.isNotBlank()) overlay?.heard("“$p”")
                            }
                            override fun onResult(hypothesis: String?) = heard(hypothesis)
                            override fun onFinalResult(hypothesis: String?) = heard(hypothesis)
                            override fun onError(exception: Exception?) {
                                if (done) return
                                done = true
                                stopCommand()
                                end(goHome = false)
                            }
                            override fun onTimeout() {
                                if (done) return
                                done = true
                                stopCommand()
                                if (followUp) end(goHome = lingering) else {
                                    nothingSaid()
                                    overlay?.said("Didn't catch that, sir.")
                                    main.postDelayed({ end(goHome = false) }, 1500)
                                }
                            }
                        }, if (followUp) 6000 else 9000)
                    } catch (e: Exception) {
                        stopCommand()
                        end(goHome = false)
                    }
                }
            } catch (e: Exception) {
                main.post { end(goHome = false) }
            }
        }.start()
    }

    private fun stopCommand() {
        command?.let {
            command = null
            try {
                it.stop()
                it.shutdown()
            } catch (e: Exception) {
                // Already stopped.
            }
        }
    }

    /** From the strip: stop here and carry on in the Jarvis panel, where this conversation already is. */
    private fun openPanel() {
        Speech.stop()
        end(goHome = false)
        startActivity(Intent(this, LauncherActivity::class.java).putExtra(EXTRA_BACK, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun toLauncher(say: String) {
        startActivity(Intent(this, LauncherActivity::class.java).putExtra(JarvisProactive.EXTRA_SAY, say).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        end(goHome = false)
    }

    /** The conversation's over: the overlay goes, the wake word comes back (or the service stops if it was only here for this). */
    private fun end(goHome: Boolean) {
        recognizer?.destroy()
        recognizer = null
        stopCommand()
        overlay?.hide()
        val wasLingering = lingering
        session = false
        lingering = false
        if (goHome && wasLingering) {
            startActivity(Intent(this, LauncherActivity::class.java).putExtra(EXTRA_BACK, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        if (wakeEnabled(this)) startWake() else if (held == null) stopSelf()
    }

    companion object {
        @Volatile
        var instance: JarvisEar? = null
            private set

        const val EXTRA_BACK = "jarvis_back"
        private const val CHANNEL = "jarvis_ear"
        private const val NOTIFICATION = 7301
        private const val ACTION_STOP = "stop"
        /** Where the wake word's confidence bar starts (it rises after false starts, eases back after real calls). */
        private const val WAKE_CONFIDENCE = .8

        /** The wake words, and sound-alikes for everything that isn't them to land on. */
        private val GRAMMAR = listOf(
            "hey jarvis", "ok jarvis", "okay jarvis", "jarvis",
            "hey", "okay", "hi", "hello", "yes", "no", "what", "they", "say", "play", "stay", "day", "away", "may", "the", "a",
            "harvest", "nervous", "service", "purpose", "office", "travis", "davis", "jervis", "james", "jealous", "marvel",
            "carvings", "garage", "charges", "jars", "jobs", "chavez", "harvey", "starving", "parcels", "hey there", "hey guys",
            "[unk]",
        ).joinToString(", ", "[", "]") { "\"$it\"" }

        fun wakeBar(context: Context) = prefs(context).getFloat("wake_bar", WAKE_CONFIDENCE.toFloat()).toDouble().coerceAtLeast(WAKE_CONFIDENCE)
        private fun setWakeBar(context: Context, v: Double) = prefs(context).edit().putFloat("wake_bar", v.toFloat()).apply()

        /** The last few times he heard "jarvis": woken, or ignored and why. For "wake log". */
        fun wakeLog(context: Context): String? = prefs(context).getString("wake_log", null)

        private fun logWake(context: Context, what: String, detail: String) {
            val time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm:ss"))
            val lines = (wakeLog(context).orEmpty().lines().filter { it.isNotBlank() } + "$time  $what  ·  $detail").takeLast(25)
            prefs(context).edit().putString("wake_log", lines.joinToString("\n")).apply()
        }
        private const val HOLD_MS = 3 * 60_000L
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

        private fun prefs(context: Context) = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        fun wakeEnabled(context: Context) = prefs(context).getBoolean("set:wake", false)
        fun setWake(context: Context, on: Boolean) = prefs(context).edit().putBoolean("set:wake", on).apply()
        fun chargingOnly(context: Context) = prefs(context).getBoolean("set:wake_charging", false)
        fun setChargingOnly(context: Context, on: Boolean) = prefs(context).edit().putBoolean("set:wake_charging", on).apply()

        fun modelDir(context: Context) = File(context.filesDir, "vosk-en")
        fun modelReady(context: Context) = File(modelDir(context), "ready").exists()

        /** Starts listening (or just stands by for follow-ups); only from the foreground, which Android requires for the mic. */
        fun start(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try {
                context.startForegroundService(Intent(context, JarvisEar::class.java))
            } catch (e: Exception) {
                // Not allowed from here (in the background): it starts next time the launcher's open.
            }
        }

        fun stop(context: Context) = context.stopService(Intent(context, JarvisEar::class.java))

        /** Lets go of the mic while the panel listens, and takes it back after. */
        fun pause(on: Boolean) {
            val ear = instance ?: return
            ear.main.post {
                ear.paused = (ear.paused + if (on) 1 else -1).coerceAtLeast(0)
                if (ear.paused > 0) ear.stopWake() else ear.startWake()
            }
        }

        /**
         * While he talks: listens for "stop" (or "Jarvis", "enough", "quiet"…) on the phone, so you can cut
         * him off and go straight to your next command. [onStop] runs on the main thread. Off again with
         * [hush] false. Needs the wake word's model; without it, a tap still does the job.
         */
        fun hush(on: Boolean, onStop: () -> Unit = {}) {
            val ear = instance ?: return
            ear.main.post { if (on) ear.startHush(onStop) else ear.stopHush() }
        }

        /** From his hands, done in an app that might want more: true if he stays to listen. */
        fun linger(say: String): Boolean {
            val ear = instance ?: return false
            if (!android.provider.Settings.canDrawOverlays(ear)) return false
            ear.main.post { ear.linger(say) }
            return true
        }

        /** His hands are done and heading back to the launcher: any conversation over an app ends there. */
        fun handsReported() {
            val ear = instance ?: return
            ear.main.post {
                if (ear.session) ear.end(goHome = false) else if (!wakeEnabled(ear)) ear.stopSelf()
            }
        }

        /** Downloads and unpacks the wake-word model (about 40 MB), once. Off the main thread; [progress] in percent. */
        fun download(context: Context, progress: (Int) -> Unit, done: (Boolean) -> Unit) {
            val app = context.applicationContext
            val main = Handler(Looper.getMainLooper())
            Thread {
                val ok = try {
                    val dir = modelDir(app)
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val c = URL(MODEL_URL).openConnection() as HttpURLConnection
                    c.connectTimeout = 15_000
                    c.readTimeout = 30_000
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: 41_000_000L
                    var read = 0L
                    var last = -1
                    ZipInputStream(object : java.io.FilterInputStream(c.inputStream.buffered()) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                            if (n > 0) {
                                read += n
                                val p = (read * 100 / total).toInt().coerceIn(0, 99)
                                if (p != last) {
                                    last = p
                                    main.post { progress(p) }
                                }
                            }
                        }
                    }).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            // Drop the zip's top folder: the model's files go straight in.
                            val name = e.name.substringAfter('/', "")
                            if (name.isEmpty() || name.contains("..")) continue
                            val f = File(dir, name)
                            if (e.isDirectory) f.mkdirs() else {
                                f.parentFile?.mkdirs()
                                f.outputStream().use { zip.copyTo(it) }
                            }
                        }
                    }
                    File(dir, "ready").writeText("vosk-model-small-en-us-0.15")
                    true
                } catch (e: Exception) {
                    false
                }
                main.post { done(ok) }
            }.start()
        }
    }
}
