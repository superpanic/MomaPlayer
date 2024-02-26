package com.superpanic.momaplayer

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Matrix
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.effect.MatrixTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Calendar

const val TV1 = 0
const val TV2 = 1
const val TV3 = 2
const val SLEEP_HOUR = 19
const val WAKEUP_HOUR = 7
const val BRIGHTNESS = 0.75f
const val SOUND_LEVEL = 0.50f
const val MIRROR_VIDEO = false

@UnstableApi class MainActivity : AppCompatActivity() {

    companion object {
        private const val MY_PERMISSIONS_REQUEST_READ_MEDIA_VIDEO = 1
    }

    private val TAG : String = "Debug:MomaPlayer"
    private lateinit var playbackStateListener : Player.Listener
    private var player : ExoPlayer? = null
    private var isAwake = true

    // used for pause, stop, resume
    private var playWhenReady : Boolean = true
    private var currentItem : Int = 0
    private var playbackPosition : Long = 0L

    // used for switching channel
    private var currentChannel : Int = -1
    private var timeStamp : Long = 0

    // layout
    private lateinit var videoView : PlayerView
    private lateinit var textView : TextView
    private lateinit var view : FrameLayout

    // channel data class
    data class Channel(
        var media: List<MediaItem> = emptyList(),
        var track: Int = 0,
        var position: Long = 0,
        var durations: List<Long> = emptyList(),
        var total_duration: Long = 0,
    )
    private val channel1 = Channel()
    private val channel2 = Channel()
    private val channel3 = Channel()
    private val channels: List<Channel> = listOf(channel1, channel2, channel3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setBrightness(BRIGHTNESS)
        soundOn() // at max volume!
        view = findViewById(R.id.view)
        view.setBackgroundColor(Color.BLACK)
        videoView = findViewById(R.id.video_view)
        textView = findViewById(R.id.text_view)
        playbackStateListener = playbackStateListener(textView)

        setAlarms()
    }

    public override fun onStart() {
        super.onStart()

        EventBus.getDefault().register(this)
        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        registerReceiver(headsetReceiver, filter)
        requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO) // request permission to load videos from external storage

        setBrightness(BRIGHTNESS)

        timeStamp = System.currentTimeMillis()
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
        EventBus.getDefault().unregister(this)
        unregisterReceiver(headsetReceiver)
        releasePlayer()
        super.onStop()
    }

    private fun setAlarms() {
        // wake alarm
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val wakeUpIntent = Intent(this, MyAlarmReceiver::class.java).apply {
            action = "com.example.myapp.WAKEUP"
        }
        val wakeUpPendingIntent = PendingIntent.getBroadcast(this, 0, wakeUpIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val wakeUpCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, WAKEUP_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, wakeUpCalendar.timeInMillis, AlarmManager.INTERVAL_DAY, wakeUpPendingIntent)

        // Sleep alarm
        val sleepIntent = Intent(this, MyAlarmReceiver::class.java).apply {
            action = "com.example.myapp.SLEEP"
        }
        val sleepPendingIntent = PendingIntent.getBroadcast(this, 1, sleepIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sleepCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, SLEEP_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, sleepCalendar.timeInMillis, AlarmManager.INTERVAL_DAY, sleepPendingIntent)
    }

    public fun slumber() {
        setBrightness(0f) // black
        soundOff()
        isAwake = false
        player?.pause()
    }

    public fun wakeup() {
        setBrightness(BRIGHTNESS)
        soundOn()
        isAwake = true
        player?.play()
    }

    private fun getNextMinute(): Calendar {
        val nextMinute = Calendar.getInstance().apply {
            add(Calendar.MINUTE, 1)
        }
        return nextMinute
    }

    private fun getNextSeconds(s: Int): Calendar {
        val nextSecs = Calendar.getInstance().apply {
            add(Calendar.SECOND, s)
        }
        return nextSecs
    }

    private fun getNextHour(): Calendar {
        val nextHour = Calendar.getInstance().apply {
            add(Calendar.HOUR, 1)
        }
        return nextHour
    }

    private fun soundOn() { // at max volume
        val audioManager: AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val desiredVolumeLevel = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (desiredVolumeLevel * SOUND_LEVEL).toInt(), 0)
    }

    private fun soundOff() { // set volume to 0
        val audioManager: AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val desiredVolumeLevel = 0
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, desiredVolumeLevel, 0)
    }

    private fun initializePlayer() {
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setMaxVideoSizeSd())
        }
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .build()
            .also {exoPlayer ->
                videoView.player = exoPlayer
                val mediaItem = MediaItem.fromUri(
                    RawResourceDataSource.buildRawResourceUri(R.raw.blue_screen)
                )
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.playWhenReady = playWhenReady
                exoPlayer.seekTo(currentItem, playbackPosition)
                exoPlayer.addListener(playbackStateListener)
                exoPlayer.prepare() }
        player?.repeatMode = Player.REPEAT_MODE_ONE
        hideVideoControllers()
        mirrorVideo()
    }

    private fun mirrorVideo() {
        if(MIRROR_VIDEO == false) {
            toaster(this, "Mirror Effect turned off!")
            return
        }
        player?.setVideoEffects(
            ImmutableList.of<Effect>(
                MatrixTransformation { presentationTimeUs ->
                    val transformationMatrix = Matrix()
                    transformationMatrix.postRotate(180f)
                    transformationMatrix.postScale(-1f, 1f)
                    transformationMatrix
                } as MatrixTransformation))
    }

    private fun hideVideoControllers() {
        videoView.useController = false
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
        WindowInsetsControllerCompat(window, videoView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onKeyDown(keyCode : Int, event : KeyEvent): Boolean {
        // 24 79 25
        if(event.repeatCount > 0) return true // block all repeated key presses
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { // KeyCode 24 Resistance 240 Ohm
                textView.text = "TV 1"
                changeChannelAndUpdateTime(TV1)
                return true // stops the event
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { // KeyCode 85, Resistance 0 Ohm
                textView.text = "TV 2"
                changeChannelAndUpdateTime(TV2)
                return true // stops the event
            }
            KeyEvent.KEYCODE_HEADSETHOOK -> { // KeyCode 79, Resistance 0 Ohm, Nothing Phone (1)
                textView.text = "TV 2"
                changeChannelAndUpdateTime(TV2)
                return true // stops the event
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { // KeyCode 25, Resistance 470 Ohm
                textView.text = "TV 3"
                changeChannelAndUpdateTime(TV3)
                return true // stops the event
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun loadChannelsFromLocalStorage(context : Context) {
        initializePlayer()

        val fieldList = R.raw::class.java.fields
        val ch1 = mutableListOf<MediaItem>()
        val ch2 = mutableListOf<MediaItem>()
        val ch3 = mutableListOf<MediaItem>()
        val d1 = mutableListOf<Long>()
        val d2 = mutableListOf<Long>()
        val d3 = mutableListOf<Long>()
        var l1 = 0L
        var l2 = 0L
        var l3 = 0L

        for (field in fieldList) {
            try {
                val resourceId = field.getInt(field)
                val resourceName = context.resources.getResourceEntryName(resourceId)
                val length: Long = getVideoDuration(resourceId)
                val first3chars:String = resourceName.take(3)
                val mi:MediaItem = getLocalMediaItemFromString(resourceName)
                if(mi != MediaItem.EMPTY) {
                    when (first3chars) {
                        "ad_" -> {
                            ch1.add(mi)
                            d1.add(length)
                            l1=l1+length
                        } // music video
                        "do_" -> {
                            ch2.add(mi)
                            d2.add(length)
                            l2=l2+length
                        } // documentary
                        "mu_" -> {
                            ch3.add(mi)
                            d3.add(length)
                            l3=l3+length
                        } // advertising
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        channel1.media = ch1
        channel1.durations = d1
        channel1.total_duration = l1
        Log.d(TAG,"channel 1 total time: "+ channel1.total_duration+" durations size: " + channel1.durations.size)
        Log.d(TAG,"durations: " + channel1.durations.toString())


        channel2.media = ch2
        channel2.durations = d2
        channel2.total_duration = l2
        Log.d(TAG,"channel 2 total time: "+ channel2.total_duration+" durations size: " + channel2.durations.size)
        Log.d(TAG,"durations: " + channel2.durations.toString())

        channel3.media = ch3
        channel3.durations = d3
        channel3.total_duration = l3
        Log.d(TAG,"channel 3 total time: "+ channel3.total_duration+" durations size: " + channel3.durations.size)
        Log.d(TAG,"durations: " + channel3.durations.toString())

    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Continue with accessing video files.
                loadChannelsFromExternalStorage(this)
                Log.d(TAG,"Permission to load external videos granted.")
            } else {
                // Permission is denied. Explain to the user that the feature is unavailable.
                toaster(this, "Permission denied. Cannot load videos.")
                toaster(this, "Delete and re-install app and try again.")
            }
        }

    private fun loadChannelsFromExternalStorage(context : Context) {
        initializePlayer()

        val ch1 = mutableListOf<MediaItem>()
        val ch2 = mutableListOf<MediaItem>()
        val ch3 = mutableListOf<MediaItem>()
        val d1 = mutableListOf<Long>()
        val d2 = mutableListOf<Long>()
        val d3 = mutableListOf<Long>()
        var l1: Long = 0L
        var l2: Long = 0L
        var l3: Long = 0L

        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION)
        if(projection.isEmpty()) {
            toaster(this,"No videos found!")
            return
        }
        val cursor = contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )

        cursor?.use {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val size = cursor.getInt(sizeColumn)
                val duration = cursor.getLong(durationColumn)
                val contentUri: Uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                Log.d(TAG, "Found video: $name, Duration: $duration, Uri: $contentUri")

                val first3chars:String = name.take(3)
                val mediaItem: MediaItem = MediaItem.fromUri(contentUri)
                if(mediaItem!=MediaItem.EMPTY) {
                    when (first3chars) {
                        "ad_" -> { // advertising
                            ch1.add(mediaItem)
                            d1.add(duration)
                            l1=l1+duration
                        }
                        "do_" -> { // documentary
                            ch2.add(mediaItem)
                            d2.add(duration)
                            l2=l2+duration
                        }
                        "mu_" -> { // music videos
                            ch3.add(mediaItem)
                            d3.add(duration)
                            l3=l3+duration
                        }
                    }
                }
            }

            channel1.media = ch1
            channel1.durations = d1
            channel1.total_duration = l1
            Log.d(TAG,"channel 1 total time: "+ channel1.total_duration+" durations size: " + channel1.durations.size)
            Log.d(TAG,"durations: " + channel1.durations.toString())


            channel2.media = ch2
            channel2.durations = d2
            channel2.total_duration = l2
            Log.d(TAG,"channel 2 total time: "+ channel2.total_duration+" durations size: " + channel2.durations.size)
            Log.d(TAG,"durations: " + channel2.durations.toString())

            channel3.media = ch3
            channel3.durations = d3
            channel3.total_duration = l3
            Log.d(TAG,"channel 3 total time: "+ channel3.total_duration+" durations size: " + channel3.durations.size)
            Log.d(TAG,"durations: " + channel3.durations.toString())
        }
    }

    private fun changeChannel(ch : Int) {
        saveCurrentChannelState()
        if (currentChannel==ch) return
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
        setBrightness(BRIGHTNESS)
    }

    private fun getTotalPlayedMillis(ch : Channel) : Long {
        val track = player?.currentMediaItemIndex
        val pos = player?.currentPosition
        var totalPlayedMS = 0L
        for (i in 0..track!!) {
            if(i==track) totalPlayedMS = totalPlayedMS + player?.currentPosition!!
            else totalPlayedMS=totalPlayedMS + ch.durations[i]
        }
        return totalPlayedMS
    }

    private fun changeChannelAndUpdateTime(ch : Int) {
        if(!isAwake) wakeup()
        saveCurrentChannelState()
        currentChannel = ch
        val cha: Channel = channels[ch]
        if(cha.media.isEmpty()) {
            toaster(this,"No video files in channel!")
            return
        } // media list is empty!
        val trackAndOffset = getTrackAndOffsetFromTotalMillis(cha)
        Log.d(TAG,
            "Changed channel to: " + ch
                    + ", track: " + trackAndOffset.first
                    + ", offset: " + trackAndOffset.second )
        player?.setMediaItems(cha.media)
        player?.seekTo(trackAndOffset.first, trackAndOffset.second)
        player?.repeatMode = Player.REPEAT_MODE_ALL
        setBrightness(BRIGHTNESS)
    }

    private fun getTrackAndOffsetFromTotalMillis(ch : Channel) : Pair<Int, Long> {
        val time_passed = System.currentTimeMillis() - timeStamp
        Log.d(TAG, "Time passed: " + time_passed)

        // offset
        var offset = time_passed % ch.total_duration
        
        // track
        var track:Int = 0
        while(offset > ch.durations[track]) {
            offset -= ch.durations[track]
            if(track+1 < ch.media.size) track++
            else track = 0
        }

        return Pair(track, offset)
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

    private fun getLocalMediaItemFromString(file_name : String): MediaItem {
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

    private fun getVideoDuration(resourceId: Int) : Long {
        val retriever = MediaMetadataRetriever()
        val rawVideoUri = "android.resource://${packageName}/${resourceId}"
        retriever.setDataSource(this, Uri.parse(rawVideoUri))
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        retriever.release()
        return duration?.toLong() ?: 0L
    }

    private fun setBrightness(brightness : Float) {
        val window = window
        val layoutParams = window.attributes
        layoutParams.screenBrightness = brightness // Range is from 0 to 1
        window.attributes = layoutParams
    }

    private fun setLowBrightness() {
        val brightness : Float = 0.5f
        val window = window
        val layoutParams = window.attributes
        layoutParams.screenBrightness = brightness
        window.attributes = layoutParams
    }

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                when (state) {
                    0 -> {
                        textView.text = "Insert headset!"
                        soundOff()
                    }
                    1 -> {
                        textView.text = "Headset connected!"
                        soundOn()
                    }
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onAlarmEvent(event: AlarmEvent) {
        when(event.type) {
            AlarmType.SLEEP -> slumber()
            AlarmType.WAKEUP -> wakeup()
        }
    }

}

private fun playbackStateListener(text_view : TextView) = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
        val stateString: String = when (playbackState) {
            ExoPlayer.STATE_IDLE -> "ExoPlayer.STATE_IDLE"
            ExoPlayer.STATE_BUFFERING -> "ExoPlayer.STATE_BUFFERING"
            ExoPlayer.STATE_READY -> "ExoPlayer.STATE_READY"
            ExoPlayer.STATE_ENDED -> "ExoPlayer.STATE_ENDED"
            else -> "UNKNOWN_STATE"
        }
        Log.d(TAG, "changed state to $stateString")
    }
}

@UnstableApi class BootUpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val startIntent = Intent(context, MainActivity::class.java)
            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(startIntent)
        }
    }
}

class MyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.example.myapp.WAKEUP" -> {
                EventBus.getDefault().post(AlarmEvent(AlarmType.WAKEUP))
                // Code to resume video playback and brighten the display
            }
            "com.example.myapp.SLEEP" -> {
                EventBus.getDefault().post(AlarmEvent(AlarmType.SLEEP))
                // Code to stop video playback and dim the display
            }
        }
    }
}

private fun toaster(context: Context, text: String) {
    val duration = Toast.LENGTH_SHORT
    val toast = Toast.makeText(context, text, duration) // in Activity
    toast.show()
}

class AlarmEvent(val type:AlarmType)
enum class AlarmType {
    WAKEUP, SLEEP
}