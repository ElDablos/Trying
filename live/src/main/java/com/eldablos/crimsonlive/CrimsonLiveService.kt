package com.eldablos.crimsonlive

import android.media.MediaPlayer
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder

/**
 * Plays the pre-rendered Crimson Live loop (res/raw/crimson_live.mp4) as the wallpaper.
 * The video is decoded by the phone's hardware, and paused whenever the wallpaper isn't visible.
 */
class CrimsonLiveService : WallpaperService() {
    override fun onCreateEngine(): Engine = LoopEngine()

    private inner class LoopEngine : Engine() {
        private var player: MediaPlayer? = null

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            player = try {
                MediaPlayer().apply {
                    setDataSource(this@CrimsonLiveService, Uri.parse("android.resource://$packageName/${R.raw.crimson_live}"))
                    setSurface(holder.surface)
                    isLooping = true
                    setVolume(0f, 0f)
                    setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    prepare()
                    if (isVisible) start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the wallpaper video", e)
                null
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            val p = player ?: return
            if (visible && !p.isPlaying) p.start() else if (!visible && p.isPlaying) p.pause()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            player?.release()
            player = null
            super.onSurfaceDestroyed(holder)
        }
    }

    companion object {
        private const val TAG = "CrimsonLive"
    }
}
