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
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).let { l ->
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l < 0 || scale <= 0) -1 else l * 100 / scale
            }
            val wasLow = low
            low = !now && level in 0..LOW_BATTERY
            batteryLevel = level
            if (now != charging || low != wasLow) {
                charging = now
                if (canWake()) startWake() else if (!session) stopWake()
                // The notification says so: resting on a low battery, or listening again.
                showState()
            }
        }
    }

    /** The battery's low and not charging: the wake word rests until it's charged (a tap still works). */
    private var low = false
    private var batteryLevel = -1

    /** The ongoing notification, saying what he's doing: listening, or resting and why. */
    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, JarvisEar::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, LauncherActivity::class.java).putExtra(JarvisProactive.EXTRA_INSIGHT, "ear"), PendingIntent.FLAG_IMMUTABLE)
        val (title, text) = when {
            low -> "◇ Jarvis is resting" to "Battery at $batteryLevel%: \"Hey Jarvis\" is paused until you charge. Tap the orb to talk to him."
            chargingOnly(this) && !charging -> "◇ Jarvis is resting" to "\"Hey Jarvis\" listens only while charging (you set that). Tap the orb to talk."
            else -> "◆ Jarvis is listening" to "Say \"Hey Jarvis\" from any app. Nothing leaves the phone."
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "TURN OFF", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun showState() {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION, notification())
        } catch (e: Exception) {
            // Notifications not allowed: nothing to update.
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SysLog.init(this)
        instance = this
        // 1.68: the old, stricter bar (it could climb until he stopped hearing you) starts afresh.
        prefs(this).let { p -> if (!p.getBoolean("wake_v2", false)) p.edit().remove("wake_bar").putBoolean("wake_v2", true).apply() }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Jarvis", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while Jarvis listens for \"Hey Jarvis\""
            setShowBadge(false)
        })
        val n = notification()
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

    private fun canWake() = wakeEnabled(this) && modelReady(this) && !session && paused == 0 && (!chargingOnly(this) || charging) && !low &&
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
        if (json == null) return
        try {
            val o = JSONObject(json)
            val text = o.optString("text").replace("[unk]", "").replace(Regex("\\s+"), " ").trim()
            val words = o.optJSONArray("result")
            val list = if (words == null) emptyList() else (0 until words.length()).map { words.getJSONObject(it) }
            // Training: everything heard goes to the trainer, nothing wakes him.
            trainer?.let { t ->
                if (text.isNotEmpty()) main.post { t(text, list) }
                return
            }
            if (session) return
            val names = wakeNames(this)
            // The name: "jarvis", or how it comes out in your voice (learnt in training: "jervis", "travis"…).
            val i = list.indexOfLast { it.optString("word") in names }
            if (i < 0) return
            val name = list[i]
            val first = list.getOrNull(i - 1)
            val bar = wakeBar(this)
            val hey = first != null && first.optString("word") in setOf("hey", "ok", "okay", "hi", "hello")
            val nameConf = name.optDouble("conf", 0.0)
            val pace = if (first != null) name.optDouble("end") - first.optDouble("start") else 0.0
            val reason = when {
                // Said with "hey" (or "ok"): the usual way.
                hey && first!!.optDouble("conf", 0.0) >= bar - .15 && nameConf >= bar && pace in .25..2.2 -> null
                // The name on its own, said clearly (only the real name, never a sound-alike).
                !hey && name.optString("word") == "jarvis" && nameConf >= maxOf(bar + .12, .88) && list.size <= 2 -> null
                !hey && name.optString("word") != "jarvis" -> "sound-alike without hey"
                hey && pace !in .25..2.2 -> "wrong pace"
                else -> "unsure"
            }
            val confs = list.joinToString(" ") { "${it.optString("word")}:${"%.2f".format(java.util.Locale.ROOT, it.optDouble("conf", 0.0))}" }
            logWake(this, if (reason == null) "WOKE" else "ignored ($reason)", "$confs · bar ${"%.2f".format(java.util.Locale.ROOT, bar)}")
            if (reason == null) main.post { wakeUp() }
        } catch (e: Exception) {
            // Not a result we can read.
        }
    }

    // --- Learning your voice ---

    /** While training, what the wake-word listener hears goes here instead of waking him. */
    private var trainer: ((String, List<JSONObject>) -> Unit)? = null

    /**
     * "Train my voice": you say "Hey Jarvis" three times; he listens with the wake-word model and learns how it
     * comes out in your voice (the word it hears for "Jarvis", and how sure it is), then sets himself to that.
     * [step] is told each try's result; [done] gets the summary.
     */
    private fun train(step: (Int, String) -> Unit, done: (String) -> Unit) {
        if (!modelReady(this)) return done("I need the Hey Jarvis download first, sir: it's in my settings.")
        val heard = ArrayList<List<JSONObject>>()
        stopWake()
        paused = 0
        session = false
        val timeout = Runnable {
            trainer = null
            done(if (heard.isEmpty()) "I didn't hear anything, sir. Try again somewhere quieter." else summarise(heard))
            startWake()
        }
        trainer = { text, list ->
            // Only tries that sound like "hey ___" count.
            if (list.size in 1..3) {
                heard += list
                step(heard.size, text)
                if (heard.size >= 3) {
                    main.removeCallbacks(timeout)
                    trainer = null
                    done(summarise(heard))
                }
            }
        }
        main.postDelayed(timeout, 45_000)
        startWake()
    }

    /** What three tries taught him: the word your "Jarvis" comes out as, and a bar he'll reliably clear. */
    private fun summarise(tries: List<List<JSONObject>>): String {
        val nameWords = tries.mapNotNull { t -> t.lastOrNull { it.optString("word") !in setOf("hey", "ok", "okay", "hi", "hello", "the", "a") } }
        if (nameWords.isEmpty()) return "I couldn't make out the name, sir. Try once more, a little slower."
        val learnt = nameWords.map { it.optString("word") }.filter { it.isNotBlank() && it != "[unk]" }.toSet()
        val lowest = nameWords.minOf { it.optDouble("conf", 0.0) }
        val bar = (lowest - .08).coerceIn(.5, .86)
        val p = prefs(this)
        p.edit().putStringSet("wake_names", (learnt + "jarvis")).putFloat("wake_bar", bar.toFloat()).putFloat("wake_base", bar.toFloat()).apply()
        logWake(this, "TRAINED", "names ${learnt.joinToString()} · bar ${"%.2f".format(java.util.Locale.ROOT, bar)}")
        startWake()
        val extra = (learnt - "jarvis")
        return "Got it, sir. " + (if (extra.isNotEmpty()) "In your voice \"Jarvis\" sometimes comes out as \"${extra.joinToString("\" or \"")}\", so I'll answer to that too after \"hey\". " else "") +
            "I've tuned myself to how you say it. Try \"Hey Jarvis\" from anywhere."
    }

    /** Nothing said after the wake word: likely a false start. The bar goes up a little, and it's logged. */
    private fun nothingSaid() {
        if (!wokeByWord) return
        wokeByWord = false
        // Never so strict he stops hearing you.
        setWakeBar(this, (wakeBar(this) + .02).coerceAtMost(.86))
        logWake(this, "nothing said after", "bar now ${"%.2f".format(java.util.Locale.ROOT, wakeBar(this))}")
    }

    /** A real call: the bar eases back down towards where it started. */
    private fun calledForReal() {
        if (!wokeByWord) return
        wokeByWord = false
        setWakeBar(this, (wakeBar(this) - .02).coerceAtLeast(wakeBase(this)))
    }

    private fun wakeUp() {
        if (session) return
        waved = false
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
            it.onStop = {
                Speech.stop()
                stopHush()
            }
            it.onTalk = {
                Speech.stop()
                stopWaiting()
                JarvisSound.play(this, JarvisSound.Cue.LISTEN)
                listen(followUp = false)
            }
            it.onRepeat = {
                lastSpoken?.let { line ->
                    overlay?.said(line)
                    overlay?.state = JarvisOverlay.State.SPEAKING
                    Speech.speak(this, listOf("again" to Speech.speakable(line)), object : Speech.Listener {
                        override fun onPart(id: String) {}
                        override fun onEnd(finished: Boolean) { overlay?.state = JarvisOverlay.State.STANDBY }
                    })
                }
            }
            it.onDismiss = {
                if (waiting) waved = true
                end(goHome = false)
            }
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
        /** A late answer over another app: said on the strip if he's still there, else in the panel. */
        override fun later(reply: Jarvis.Reply) {
            if (reply.failed) SysLog.warn("Jarvis", reply.say.take(160))
            main.post {
                JarvisChat.add(this@JarvisEar, false, reply.say, JarvisChat.EAR)
                // You waved him off while he was thinking: it's kept in the panel, not said.
                if (waved) {
                    waved = false
                    return@post
                }
                // Always on screen while he answers: if the strip had gone, it comes back for this.
                if (!session) {
                    stopWake()
                    session = true
                    lingering = false
                    showOverlay()
                }
                stopWaiting()
                recognizer?.destroy()
                recognizer = null
                stopCommand()
                respond(reply)
            }
        }

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

        /** Picking a file needs the screen: carried on in the launcher. */
        override fun pick(what: String) = toLauncher(lastText)
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
        if (reply.silent && reply.more) {
            // Checking with a brain first: the strip stays, thinking, until the answer's in.
            if (reply.say.isNotBlank()) overlay?.said(reply.say)
            overlay?.state = JarvisOverlay.State.THINKING
            waitForAnswer()
            return
        }
        // A look-up's result is shown in the panel, not read; over an app there's no panel, so he says it.
        if (reply.silent && (reply.then != null || reply.say.isBlank())) {
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
                    // The real answer's on its way (a brain, the web): he stays, thinking, until it's here.
                    reply.more -> {
                        overlay?.state = JarvisOverlay.State.THINKING
                        waitForAnswer()
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
        lastSpoken = Jarvis.spokenFor(reply)
        Speech.speak(this, listOf("ear" to Speech.speakable(Jarvis.spokenFor(reply))), object : Speech.Listener {
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

    // --- Waiting for an answer that takes a moment ---

    /** What he said last, for REPEAT on the strip. */
    private var lastSpoken: String? = null

    private var waiting = false
    /** Dismissed while he was thinking: the answer goes quietly to the panel. */
    private var waved = false
    private val tooLong = Runnable {
        if (!waiting || !session) return@Runnable
        waiting = false
        val say = "Still working on it, sir. I'll come back to you with the answer."
        overlay?.said(say)
        overlay?.state = JarvisOverlay.State.SPEAKING
        Speech.speak(this, listOf("ear" to say), object : Speech.Listener {
            override fun onPart(id: String) {}
            override fun onEnd(finished: Boolean) = end(goHome = false)
        })
    }

    private fun waitForAnswer() {
        waiting = true
        main.removeCallbacks(tooLong)
        main.postDelayed(tooLong, WAIT_MS)
    }

    private fun stopWaiting() {
        waiting = false
        main.removeCallbacks(tooLong)
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
        stopWaiting()
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
        /** Where the wake word's confidence bar starts (training sets your own; false starts nudge it up a little). */
        private const val WAKE_CONFIDENCE = .62

        /** At or below this (and not charging), "Hey Jarvis" rests to save the battery. */
        private const val LOW_BATTERY = 8

        /** The wake words, and sound-alikes for everything that isn't them to land on. */
        private val GRAMMAR = listOf(
            "hey jarvis", "ok jarvis", "okay jarvis", "jarvis",
            "hey", "okay", "hi", "hello", "yes", "no", "what", "they", "say", "play", "stay", "day", "away", "may", "the", "a",
            "harvest", "nervous", "service", "purpose", "office", "travis", "davis", "jervis", "james", "jealous", "marvel",
            "carvings", "garage", "charges", "jars", "jobs", "chavez", "harvey", "starving", "parcels", "hey there", "hey guys",
            "[unk]",
        ).joinToString(", ", "[", "]") { "\"$it\"" }

        fun wakeBase(context: Context) = prefs(context).getFloat("wake_base", WAKE_CONFIDENCE.toFloat()).toDouble()
        fun wakeBar(context: Context) = prefs(context).getFloat("wake_bar", wakeBase(context).toFloat()).toDouble().coerceIn(minOf(wakeBase(context), WAKE_CONFIDENCE), .86)

        /** The words he answers to after "hey": "jarvis", plus how it comes out in your voice (from training). */
        fun wakeNames(context: Context): Set<String> = prefs(context).getStringSet("wake_names", null)?.plus("jarvis") ?: setOf("jarvis")

        /** "Train my voice": three tries of "Hey Jarvis", from the panel. False if he isn't running. */
        fun trainVoice(step: (Int, String) -> Unit, done: (String) -> Unit): Boolean {
            val ear = instance ?: return false
            ear.main.post { ear.train(step, done) }
            return true
        }
        private fun setWakeBar(context: Context, v: Double) = prefs(context).edit().putFloat("wake_bar", v.toFloat()).apply()

        /** The last few times he heard "jarvis": woken, or ignored and why. For "wake log". */
        fun wakeLog(context: Context): String? = prefs(context).getString("wake_log", null)

        private fun logWake(context: Context, what: String, detail: String) {
            val time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm:ss"))
            val lines = (wakeLog(context).orEmpty().lines().filter { it.isNotBlank() } + "$time  $what  ·  $detail").takeLast(25)
            prefs(context).edit().putString("wake_log", lines.joinToString("\n")).apply()
        }
        private const val HOLD_MS = 3 * 60_000L
        /** How long the strip waits, thinking, for a brain or a look-up before leaving the answer for the panel. */
        private const val WAIT_MS = 3 * 60_000L
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
