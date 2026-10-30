package com.eldablos.crimson.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Jarvis's sounds: short synthesised chimes (no sound files) for listening, heard, done and a problem.
 * Played as assistant sounds, quietly, and only when switched on in his settings.
 */
object JarvisSound {
    enum class Cue(val notes: List<Pair<Double, Int>>, val level: Float) {
        /** Rising: he's listening. */
        LISTEN(listOf(660.0 to 70, 990.0 to 110), .22f),
        /** Falling: he heard you. */
        HEARD(listOf(990.0 to 60, 740.0 to 90), .18f),
        /** A soft bell: done. */
        DONE(listOf(1318.5 to 220), .16f),
        /** Low and short: something went wrong. */
        ERROR(listOf(330.0 to 90, 262.0 to 140), .2f),
    }

    private const val RATE = 44_100
    private val cache = HashMap<Cue, ShortArray>()

    fun play(context: Context, cue: Cue) {
        if (!JarvisMemory.sounds(context)) return
        Thread {
            try {
                val pcm = synchronized(cache) { cache.getOrPut(cue) { synth(cue) } }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / RATE + 60)
                track.release()
            } catch (e: Exception) {
                // No sound this time.
            }
        }.start()
    }

    /** Each note a sine with a touch of its octave, a quick attack and a soft tail, so it chimes rather than beeps. */
    fun synth(cue: Cue): ShortArray {
        val parts = cue.notes.map { (freq, ms) ->
            val n = RATE * ms / 1000
            ShortArray(n) { i ->
                val t = i.toDouble() / RATE
                val attack = minOf(1.0, i / (RATE * .006))
                val decay = exp(-3.2 * i / n)
                val v = (sin(2 * PI * freq * t) + .3 * sin(4 * PI * freq * t)) / 1.3
                (v * attack * decay * cue.level * Short.MAX_VALUE).toInt().toShort()
            }
        }
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }
}
