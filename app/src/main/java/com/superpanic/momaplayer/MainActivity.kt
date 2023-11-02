package com.superpanic.momaplayer

import android.annotation.SuppressLint
import android.content.ContentValues.TAG
import android.content.Context
import android.graphics.Matrix
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.effect.MatrixTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList


@UnstableApi class MainActivity : AppCompatActivity() {
    private lateinit var playbackStateListener: Player.Listener
    private var player: ExoPlayer? = null

    private var playWhenReady = true
    private var currentItem = 0
    private var playbackPosition = 0L
    private lateinit var video_view : PlayerView
    private lateinit var text_view : TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        video_view = findViewById(R.id.video_view)
        text_view = findViewById(R.id.text_view)
        playbackStateListener = playbackStateListener(text_view)
    }

    public override fun onStart() {
        super.onStart()
        initializePlayer()
        mirrorVideo()
        hideVideoControllers()
    }

    public override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    public override fun onPause() {
        super.onPause()
        releasePlayer()
    }

    public override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun initializePlayer() {
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setMaxVideoSizeSd())
        }
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .build()
            .also {exoPlayer ->
                video_view.player = exoPlayer
                val mediaItem = MediaItem.fromUri(
                    RawResourceDataSource.buildRawResourceUri(R.raw.sledgehammer)
                )
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.playWhenReady = playWhenReady
                exoPlayer.seekTo(currentItem, playbackPosition)
                exoPlayer.addListener(playbackStateListener)
                exoPlayer.prepare()
            }
    }

    private fun mirrorVideo() {
        player!!.setVideoEffects(
            ImmutableList.of<Effect>(
                MatrixTransformation { presentationTimeUs ->
                    val transformationMatrix = Matrix()
                    transformationMatrix.postScale(-1f,1f)
                    transformationMatrix
                } as MatrixTransformation))
    }

    private fun hideVideoControllers() {
        video_view.useController = false
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            playbackPosition = exoPlayer.currentPosition
            currentItem = exoPlayer.currentMediaItemIndex
            playWhenReady = exoPlayer.playWhenReady
            exoPlayer.removeListener(playbackStateListener)
            exoPlayer.release()
        }
        player = null
    }

    @SuppressLint("InlinedApi")
    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, video_view).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(event.repeatCount > 0) return true // block all repeated key presses
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                    text_view.text = "Volume up!"
                    return true // stops the event
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                text_view.text = "Volume down!"
                return true // stops the event
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                text_view.text = "Play/Pause pressed!"
                return true // stops the event
            }
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                text_view.text = "Headset hook pressed!"
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    fun getResourceFiles(context: Context): List<String> {
        val fieldList = R.raw::class.java.fields
        val resourceNames = mutableListOf<String>()

        for (field in fieldList) {
            try {
                val resourceId = field.getInt(field)
                val resourceName = context.resources.getResourceEntryName(resourceId)
                resourceNames.add(resourceName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return resourceNames
    }

}

private fun playbackStateListener(text_view: TextView) = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
        val stateString: String = when (playbackState) {
            ExoPlayer.STATE_IDLE -> "ExoPlayer.STATE_IDLE"
            ExoPlayer.STATE_BUFFERING -> "ExoPlayer.STATE_BUFFERING"
            ExoPlayer.STATE_READY -> "ExoPlayer.STATE_READY"
            ExoPlayer.STATE_ENDED -> "ExoPlayer.STATE_ENDED"
            else -> "UNKNOWN_STATE"
        }
        Log.d(TAG, "changed state to @stateString")
        //text_view.text = stateString
    }
}

