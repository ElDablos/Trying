package com.eldablos.crimson.launcher

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import org.vosk.Recognizer

/**
 * The microphone for the wake word: the phone's own, even with earbuds in. A headset's mic sits in your
 * ear and hears chewing, rustling and your own breathing, which a wake-word listener can mistake for
 * "Hey Jarvis"; the phone's mic hears the room, the way you'd call him. Feeds [rec] and hands each finished
 * phrase to [onResult], on its own thread. Closes [rec] when it stops.
 */
class WakeMic(private val context: Context, private val rec: Recognizer, private val onResult: (String) -> Unit, private val onError: () -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** False when the mic can't be had (no permission, or it's in use). */
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val size = maxOf(min, RATE / 5 * 2)
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
        } catch (e: Exception) {
            return false
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return false
        }
        context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { ar.setPreferredDevice(it) }
        running = true
        thread = Thread {
            val buf = ShortArray(size / 2)
            try {
                ar.startRecording()
                while (running) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n < 0) {
                        if (running) onError()
                        break
                    }
                    if (n > 0 && rec.acceptWaveForm(buf, n)) onResult(rec.result)
                }
            } catch (e: Exception) {
                if (running) onError()
            } finally {
                runCatching { ar.stop() }
                ar.release()
                runCatching { rec.close() }
            }
        }.also { it.name = "jarvis-wake"; it.start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(600)
        thread = null
    }

    companion object {
        const val RATE = 16000
    }
}
