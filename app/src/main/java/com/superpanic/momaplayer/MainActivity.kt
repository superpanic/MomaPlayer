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
    private var currentChannel = -1
    private lateinit var video_view : PlayerView
    private lateinit var text_view : TextView

    data class Channel(
        var media: List<MediaItem> = emptyList(),
        var track: Int = 0,
        var position: Long = 0,
    )

    private val channel1 = Channel()
    private val channel2 = Channel()
    private val channel3 = Channel()

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
        loadChannels(this)
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
                    RawResourceDataSource.buildRawResourceUri(R.raw.blue_screen)
                )
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.playWhenReady = playWhenReady
                exoPlayer.seekTo(currentItem, playbackPosition)
                exoPlayer.addListener(playbackStateListener)
                exoPlayer.prepare()
            }
    }

    private fun mirrorVideo() {
        player?.setVideoEffects(
            ImmutableList.of<Effect>(
                MatrixTransformation { presentationTimeUs ->
                    val transformationMatrix = Matrix()
                    transformationMatrix.postScale(-1f, 1f)
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
                text_view.text = "TV 1"
                changeChannel(0)
                return true // stops the event
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                text_view.text = "TV 2"
                changeChannel(1)
                return true // stops the event
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                text_view.text = "TV 3"
                changeChannel(2)
                return true // stops the event
            }
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                text_view.text = "TV 3"
                changeChannel(2)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun loadChannels(context: Context) {
        val fieldList = R.raw::class.java.fields
        val ch1 = mutableListOf<MediaItem>()
        val ch2 = mutableListOf<MediaItem>()
        val ch3 = mutableListOf<MediaItem>()
        for (field in fieldList) {
            try {
                val resourceId = field.getInt(field)
                val resourceName = context.resources.getResourceEntryName(resourceId)
                val first3chars:String = resourceName.take(3)
                val mi:MediaItem = getLocalMediaItemFromString(resourceName)
                if(mi != MediaItem.EMPTY) {
                    when (first3chars) {
                        "mu_" -> ch1.add(mi) // music video
                        "do_" -> ch2.add(mi) // documentary
                        "ad_" -> ch3.add(mi) // advertising
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        channel1.media = ch1
        channel2.media = ch2
        channel3.media = ch3
    }

    private fun changeChannel(ch: Int) {
        saveCurrentChannelState()
        currentChannel = ch
        when(currentChannel) {
            0-> {
                player?.setMediaItems(channel1.media)
                player?.seekTo(channel1.track, channel1.position)
            }
            1-> {
                player?.setMediaItems(channel2.media)
                player?.seekTo(channel2.track, channel2.position)
            }
            2-> {
                player?.setMediaItems(channel3.media)
                player?.seekTo(channel3.track, channel3.position)
            }
        }
        player?.repeatMode = Player.REPEAT_MODE_ALL
    }

    private fun saveCurrentChannelState() {
        when(currentChannel) {
            0-> {
                channel1.track = player?.currentMediaItemIndex!!
                channel1.position = player?.currentPosition!!
            }
            1-> {
                channel2.track = player?.currentMediaItemIndex!!
                channel2.position = player?.currentPosition!!
            }
            2-> {
                channel3.track = player?.currentMediaItemIndex!!
                channel3.position = player?.currentPosition!!
            }
        }
    }

    private fun getLocalMediaItemFromString(file_name: String): MediaItem {
        val resourceId = this.resources.getIdentifier(
            /* name = */ file_name,
            /* defType = */ "raw",
            /* defPackage = */ this.packageName
        )

        val mediaItem: MediaItem = if (resourceId != 0) {
            MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(resourceId))
        } else {
            MediaItem.EMPTY
        }
        return mediaItem
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

