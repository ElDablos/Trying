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

/** The pop-up behind the widget's queue button: what's up next in the player, tap a song to jump to it. */
class QueueActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)
        findViewById<View>(R.id.queue_close).setOnClickListener { finish() }

        val controller = NowPlaying.activeController(this)
        val queue = controller?.queue.orEmpty()
        val list = findViewById<ListView>(R.id.queue_list)
        val empty = findViewById<View>(R.id.queue_empty)
        val openApp = findViewById<Button>(R.id.queue_open_app)
        val playerPackage = controller?.packageName ?: NowPlaying.SPOTIFY
        openApp.setOnClickListener {
            packageManager.getLaunchIntentForPackage(playerPackage)?.let { startActivity(it) }
            finish()
        }

        findViewById<TextView>(R.id.queue_subtitle).text = controller?.queueTitle?.toString()
            ?: NowPlaying.read(this)?.let { "Playing ${it.title}" } ?: ""
        if (queue.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
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
                setTextColor(getColor(if (playing) R.color.crimson else R.color.bone))
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
}
