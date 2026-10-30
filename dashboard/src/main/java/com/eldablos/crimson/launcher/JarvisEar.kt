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
    private var wake: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null
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
                // Only the wake words (and "[unk]" for everything else), so it's light and hard to fool.
                val rec = Recognizer(m, 16000f, "[\"hey jarvis\", \"okay jarvis\", \"ok jarvis\", \"jarvis\", \"[unk]\"]")
                rec.setWords(true)
                main.post {
                    if (!canWake() || wake != null) {
                        rec.close()
                        return@post
                    }
                    try {
                        wake = SpeechService(rec, 16000f).also { it.startListening(wakeListener) }
                    } catch (e: Exception) {
                        wake = null
                    }
                }
            } catch (e: Exception) {
                // The model isn't usable: the settings will offer to download it again.
            }
        }.start()
    }

    private fun stopWake() {
        wake?.let {
            it.stop()
            it.shutdown()
        }
        wake = null
    }

    private val wakeListener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) = heardWake(hypothesis)
        override fun onFinalResult(hypothesis: String?) = heardWake(hypothesis)
        override fun onError(exception: Exception?) {
            stopWake()
            main.postDelayed({ startWake() }, 3000)
        }
        override fun onTimeout() {}
    }

    /** "Hey Jarvis" (or "OK Jarvis"), said clearly enough: two words, both confidently heard. */
    private fun heardWake(json: String?) {
        if (json == null || session) return
        try {
            val o = JSONObject(json)
            val text = o.optString("text")
            if (!Regex("\\b(hey|okay|ok) jarvis\\b").containsMatchIn(text)) return
            val words = o.optJSONArray("result") ?: return
            var sum = 0.0
            for (i in 0 until words.length()) sum += words.getJSONObject(i).optDouble("conf", 0.0)
            if (words.length() == 0 || sum / words.length() < WAKE_CONFIDENCE) return
            main.post { wakeUp() }
        } catch (e: Exception) {
            // Not a result we can read.
        }
    }

    private fun wakeUp() {
        if (session) return
        stopWake()
        session = true
        lingering = false
        JarvisSound.play(this, JarvisSound.Cue.LISTEN)
        showOverlay()
        listen(followUp = false)
    }

    // --- A conversation over any app ---

    private fun showOverlay() {
        val o = overlay ?: JarvisOverlay(this).also {
            it.onDismiss = { end(goHome = false) }
            overlay = it
        }
        o.state = JarvisOverlay.State.LISTENING
        o.heard("")
        o.show()
    }

    /** Hears one command; a [followUp] that hears nothing just ends the conversation. */
    private fun listen(followUp: Boolean) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return end(goHome = false)
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
                if (followUp) end(goHome = lingering) else {
                    overlay?.said("Didn't catch that, sir.")
                    main.postDelayed({ end(goHome = false) }, 1500)
                }
            }
            override fun onResults(results: Bundle?) {
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                if (all.isEmpty()) return onError(SpeechRecognizer.ERROR_NO_MATCH)
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
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // A follow-up gives up quickly if you've nothing more to say.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (followUp) 1500L else 2500L)
            .putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(listOf("Jarvis", "Claude", "Tapo", "Air+", "brighter", "dimmer", "next colour", "warmer", "cooler") + AppCatalog.apps().map { it.label }.take(150))))
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
            val reply = brain().answer(text)
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
        val after = {
            if (!done) {
                done = true
                if (reply.afterSpeech) reply.then?.invoke()
                when {
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
            }
            override fun onEnd(finished: Boolean) {
                main.removeCallbacks(safety)
                after()
            }
        })
    }

    /** From his hands, still in the app: say how it went, then a few seconds for "next colour" or "brighter" before going back. */
    private fun linger(say: String) {
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

    private fun toLauncher(say: String) {
        startActivity(Intent(this, LauncherActivity::class.java).putExtra(JarvisProactive.EXTRA_SAY, say).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        end(goHome = false)
    }

    /** The conversation's over: the overlay goes, the wake word comes back (or the service stops if it was only here for this). */
    private fun end(goHome: Boolean) {
        recognizer?.destroy()
        recognizer = null
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
        private const val WAKE_CONFIDENCE = .62
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
