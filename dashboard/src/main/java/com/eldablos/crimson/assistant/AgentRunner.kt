package com.eldablos.crimson.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.eldablos.crimson.R
import com.eldablos.crimson.core.DocText
import com.eldablos.crimson.core.SysLog
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jarvis at work, apart from the chat screen: a request runs here (kept alive by [AgentService], a foreground
 * service with a "Working on it" notification and Stop), so it carries on when the app's minimised or swiped
 * away. Every step, card and the answer are saved to the chat as they happen; the screen, while it's open,
 * attaches as [ui] and draws them. Finished with the screen closed: a notification with the answer.
 * A yes/no question waits for you (a notification brings you to it).
 */
object AgentRunner : Agent.Listener, Tools.Host {
    /** What the chat screen does with each event (on the main thread's behalf it posts them itself). */
    interface Ui {
        fun onStep(id: Int, item: JSONObject)
        fun onStepDone(id: Int, item: JSONObject)
        fun onItem(item: JSONObject)
        fun onPartial(text: String)
        fun onFinished(item: JSONObject, reply: Agent.Reply)
        fun onConfirm(question: String)
        fun onPermission(permissions: Array<out String>)
    }

    @Volatile var ui: Ui? = null
    @Volatile var busy = false
        private set
    @Volatile var asked: String? = null
        private set
    /** The words streaming in now (not saved until a step starts or it finishes). */
    @Volatile var partialText: String = ""
        private set
    /** A yes/no question waiting for an answer. */
    @Volatile var question: String? = null
        private set

    private lateinit var app: Context
    private var agent: Agent? = null
    private val ids = AtomicInteger(1)
    private val steps = java.util.concurrent.ConcurrentHashMap<Int, JSONObject>()
    private val files = ArrayList<DocText.Picked>()
    private var latch: CountDownLatch? = null
    private var answer = false
    private val waitingPermissions = LinkedHashSet<String>()

    /** Starts [ask] (with [attached] files, and [all] files of the conversation for the tools). False if one's running. */
    @Synchronized
    fun start(context: Context, ask: String, attached: List<DocText.Picked>, all: List<DocText.Picked>): Boolean {
        if (busy) return false
        app = context.applicationContext
        busy = true
        asked = ask
        partialText = ""
        steps.clear()
        synchronized(files) { files.clear(); files.addAll(all) }
        try { app.startForegroundService(Intent(app, AgentService::class.java)) } catch (e: Exception) { SysLog.warn("Assistant", "No background service: ${e.javaClass.simpleName}") }
        Thread {
            val reply = try {
                val a = Agent(app, Tools(app, this), this)
                agent = a
                a.send(ask, attached)
            } catch (e: Throwable) {
                SysLog.error("Assistant", "${e.javaClass.simpleName}: ${e.message.orEmpty().take(140)}")
                Agent.Reply(if (e is OutOfMemoryError) "That was too big for the phone's memory. Try a smaller file." else "Something went wrong inside the app (${e.javaClass.simpleName}). It's in the log in settings.", "", failed = true)
            }
            finish(reply, ask)
        }.start()
        return true
    }

    fun stop() {
        agent?.stop()
        latch?.let { answer = false; it.countDown() }
    }

    private fun record(type: String, text: String = "", extra: (JSONObject) -> Unit = {}): JSONObject {
        val item = JSONObject().put("t", type).put("text", text).put("at", System.currentTimeMillis())
        extra(item)
        Chat.add(item)
        return item
    }

    /** Words that streamed in before a step or card: kept in the chat as they were. */
    private fun keepPartial() {
        val t = partialText.trim()
        partialText = ""
        if (t.isNotEmpty()) { val item = record("ai", t); ui?.onItem(item) }
    }

    private fun finish(reply: Agent.Reply, asked: String) {
        partialText = ""
        steps.keys.toList().forEach { stepDone(it, false, "") }
        val tools = reply.tools.joinToString(",")
        val item = if (reply.failed) record("note", reply.text) { if (tools.isNotEmpty()) it.put("tools", tools) }
        else record("ai", reply.text) { it.put("via", reply.via).put("asked", asked); if (tools.isNotEmpty()) it.put("tools", tools) }
        runCatching { Chat.save(app) }
        busy = false
        agent = null
        val screen = ui
        if (screen != null) screen.onFinished(item, reply)
        else notifyDone(reply)
        runCatching { app.stopService(Intent(app, AgentService::class.java)) }
    }

    // --- Agent.Listener ---

    override fun step(label: String): Int {
        keepPartial()
        val id = ids.getAndIncrement()
        val item = record("step", label) { it.put("sid", id).put("running", true) }
        steps[id] = item
        ui?.onStep(id, item)
        AgentService.update(app, label)
        return id
    }

    override fun stepDone(id: Int, ok: Boolean, summary: String) {
        val item = steps.remove(id) ?: return
        item.put("done", summary).put("ok", ok).remove("running")
        ui?.onStepDone(id, item)
    }

    override fun card(card: Card) {
        keepPartial()
        val item = record("card") { it.put("card", card.json()) }
        ui?.onItem(item)
    }

    override fun partial(text: String) {
        partialText = text
        ui?.onPartial(text)
    }

    // --- Tools.Host ---

    override fun attachments(): List<DocText.Picked> = synchronized(files) { files.toList() }

    override fun requestPermission(vararg permissions: String) {
        val screen = ui
        if (screen != null) screen.onPermission(permissions) else synchronized(waitingPermissions) { waitingPermissions += permissions }
    }

    /** Permissions asked for while the screen was closed: the screen asks for them when it opens. */
    fun takeWaitingPermissions(): Array<String> = synchronized(waitingPermissions) { waitingPermissions.toTypedArray().also { waitingPermissions.clear() } }

    override fun confirm(question: String): Boolean {
        keepPartial()
        val l = CountDownLatch(1)
        latch = l
        answer = false
        this.question = question
        val screen = ui
        if (screen != null) screen.onConfirm(question) else notifyQuestion(question)
        // Up to ten minutes for an answer (it may come from the notification bringing you back).
        l.await(10, TimeUnit.MINUTES)
        this.question = null
        latch = null
        cancelNotification(QUESTION)
        return answer
    }

    /** Your answer to the waiting question. */
    fun answer(yes: Boolean) {
        answer = yes
        latch?.countDown()
    }

    // --- Notifications when the screen's closed ---

    private const val CHANNEL = "jarvis_done"
    private const val DONE = 6101
    private const val QUESTION = 6102

    private fun channel() {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis's answers", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When Jarvis finishes something you asked while the app was closed"
        })
    }

    private fun openChat(code: Int) = PendingIntent.getActivity(app, code, Intent(app, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(AssistantActivity.EXTRA_RESUME, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notifyDone(reply: Agent.Reply) {
        try {
            channel()
            val text = reply.text.replace("**", "").trim()
            val n = Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(if (reply.failed) "${Agent.assistantName(app)} couldn't finish" else "${Agent.assistantName(app)} · done")
                .setContentText(text.take(120)).setStyle(Notification.BigTextStyle().bigText(text.take(600)))
                .setAutoCancel(true).setContentIntent(openChat(DONE)).build()
            app.getSystemService(NotificationManager::class.java)?.notify(DONE, n)
        } catch (_: Exception) {}
    }

    private fun notifyQuestion(q: String) {
        try {
            channel()
            val n = Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("${Agent.assistantName(app)} needs a yes or no")
                .setContentText(q).setStyle(Notification.BigTextStyle().bigText(q))
                .setAutoCancel(true).setContentIntent(openChat(QUESTION)).build()
            app.getSystemService(NotificationManager::class.java)?.notify(QUESTION, n)
        } catch (_: Exception) {}
    }

    fun cancelNotification(id: Int = DONE) { runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(id) } }
}

/** Keeps the app alive while Jarvis works, with a notification saying what he's doing and a Stop button. */
class AgentService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AgentRunner.stop()
            return START_NOT_STICKY
        }
        show(this, "Thinking…", start = true)
        if (!AgentRunner.busy) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "jarvis_working"
        private const val ID = 6100
        private const val ACTION_STOP = "com.eldablos.crimson.assistant.STOP"

        private fun build(c: Context, what: String): Notification {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL) == null) nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis working", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Jarvis finishes something in the background"
            })
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val stop = PendingIntent.getService(c, 6103, Intent(c, AgentService::class.java).setAction(ACTION_STOP), flags)
            val open = PendingIntent.getActivity(c, 6104, Intent(c, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AssistantActivity.EXTRA_RESUME, true), flags)
            return Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("${Agent.assistantName(c)} is working on it")
                .setContentText(what).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
        }

        private fun show(s: Service, what: String, start: Boolean) {
            val n = build(s, what)
            try {
                s.startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (e: Exception) {
                SysLog.warn("Assistant", "Couldn't keep working in the background: ${e.javaClass.simpleName}")
            }
        }

        /** Says what Jarvis is doing now, on the notification. */
        fun update(c: Context, what: String) {
            if (!AgentRunner.busy) return
            runCatching { c.getSystemService(NotificationManager::class.java)?.notify(ID, build(c, what)) }
        }
    }
}
