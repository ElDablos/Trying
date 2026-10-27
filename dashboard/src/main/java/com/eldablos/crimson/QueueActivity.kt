package com.eldablos.crimson

import android.app.Activity
import android.graphics.BitmapFactory
import android.media.session.MediaSession
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/**
 * The pop-up behind the widget's queue button: what's up next in the player (tap a song to jump to it),
 * with shuffle and repeat.
 */
class QueueActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)
        // The launcher's theme, on this screen too.
        WidgetTheme.apply(window.decorView)
        findViewById<View>(R.id.queue_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.queue_open_app).setOnClickListener {
            val player = NowPlaying.activeController(this)?.packageName ?: NowPlaying.SPOTIFY
            packageManager.getLaunchIntentForPackage(player)?.let { startActivity(it) }
            finish()
        }
        findViewById<View>(R.id.queue_shuffle).setOnClickListener {
            NowPlaying.control(this, NowPlaying.Control.SHUFFLE)
            renderModes()
            // The player reorders the queue a moment later; show the new order.
            window.decorView.postDelayed({ loadQueue() }, RELOAD_DELAY_MS)
        }
        findViewById<View>(R.id.queue_repeat).setOnClickListener {
            NowPlaying.control(this, NowPlaying.Control.REPEAT)
            renderModes()
        }
        renderModes()
        loadQueue()
    }

    /** Lights the shuffle and repeat tiles crimson when they're on. */
    private fun renderModes() {
        val on = WidgetTheme.text(this, R.color.crimson)
        val off = WidgetTheme.text(this, R.color.dust)
        val shuffle = NowPlaying.shuffle(this)
        findViewById<ImageView>(R.id.queue_shuffle_icon).setColorFilter(if (shuffle) on else off)
        findViewById<TextView>(R.id.queue_shuffle_label).setTextColor(if (shuffle) on else off)
        val repeat = NowPlaying.repeat(this)
        findViewById<ImageView>(R.id.queue_repeat_icon).apply {
            setImageResource(if (repeat == NowPlaying.Repeat.ONE) R.drawable.ic_music_repeat_one else R.drawable.ic_music_repeat)
            setColorFilter(if (repeat == NowPlaying.Repeat.OFF) off else on)
        }
        findViewById<TextView>(R.id.queue_repeat_label).apply {
            text = if (repeat == NowPlaying.Repeat.ONE) "Repeat one" else "Repeat"
            setTextColor(if (repeat == NowPlaying.Repeat.OFF) off else on)
        }
    }

    private fun loadQueue() {
        val controller = NowPlaying.activeController(this)
        val queue = controller?.queue.orEmpty()
        val list = findViewById<ListView>(R.id.queue_list)
        val empty = findViewById<View>(R.id.queue_empty)

        findViewById<TextView>(R.id.queue_subtitle).text = controller?.queueTitle?.toString()
            ?: NowPlaying.read(this)?.let { "Playing ${it.title}" } ?: ""
        if (queue.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
        list.visibility = View.VISIBLE
        empty.visibility = View.GONE
        // Start the list at the song that's playing, if the player marks it.
        val current = controller?.playbackState?.activeQueueItemId
        list.adapter = QueueAdapter(queue, current)
        queue.indexOfFirst { it.queueId == current }.takeIf { it > 0 }?.let { list.setSelection(it) }
        list.setOnItemClickListener { _, _, position, _ ->
            controller?.transportControls?.skipToQueueItem(queue[position].queueId)
            finish()
        }
    }

    private inner class QueueAdapter(private val items: List<MediaSession.QueueItem>, private val current: Long?) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].queueId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
            val item = items[position]
            val d = item.description
            val playing = item.queueId == current
            view.findViewById<TextView>(R.id.queue_item_title).apply {
                text = d.title
                setTextColor(WidgetTheme.text(context, if (playing) R.color.crimson else R.color.bone))
            }
            view.findViewById<TextView>(R.id.queue_item_artist).text = d.subtitle ?: ""
            val art = view.findViewById<ImageView>(R.id.queue_item_art)
            val bitmap = d.iconBitmap ?: d.iconUri?.let { uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) art.setImageBitmap(bitmap) else art.setImageResource(R.drawable.music_art_placeholder)
            return view
        }
    }

    companion object {
        private const val RELOAD_DELAY_MS = 700L
    }
}
