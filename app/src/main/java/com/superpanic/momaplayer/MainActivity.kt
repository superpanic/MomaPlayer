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
import android.media.AudioDeviceInfo
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale


const val TV1 : Int = 0
const val TV2 : Int = 1
const val TV3 : Int = 2

const val WAKE_HOUR : Int = 7
const val SLEEP_HOUR : Int = 19

const val BRIGHTNESS = 0.9f // 0.75 works fine!
const val SOUND_LEVEL = 0.35f
const val MIRROR_VIDEO = false

const val MILLIS_30_MIN : Long = 1000 * 60 * 30
const val MILLIS_20_MIN : Long = 1000 * 60 * 20
const val MILLIS_15_MIN : Long = 1000 * 60 * 15

@UnstableApi class MainActivity : AppCompatActivity() {

    companion object {
        private const val MY_PERMISSIONS_REQUEST_READ_MEDIA_VIDEO = 1
    }

    private val TAG : String = "DEBUG:MOMA"
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
        view = findViewById(R.id.view)
        view.setBackgroundColor(Color.BLACK)
        videoView = findViewById(R.id.video_view)
        textView = findViewById(R.id.text_view)
        playbackStateListener = playbackStateListener(textView)

        //setAlarms()
        setRecurringAlarm()
    }

    public override fun onStart() {
        super.onStart()

        EventBus.getDefault().register(this)
        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
        registerReceiver(headsetReceiver, filter)
        requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO) // request permission to load videos from external storage

        setBrightness(BRIGHTNESS)
        soundCheck()

        timeStamp = System.currentTimeMillis()
    }

    private fun checkForWiredHeadSet() : Boolean {
        val audioManager: AudioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val audioDevices: Array<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        var isWiredHeadphonesConnected = false
        for (device in audioDevices) {
            if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                isWiredHeadphonesConnected = true
                break
            }
        }
        if(audioManager.isWiredHeadsetOn()) isWiredHeadphonesConnected = true
        return isWiredHeadphonesConnected
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

    private fun setRecurringAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // sleep alarm
        val sleepIntent = Intent(this, MyAlarmReceiver::class.java).apply {
            action = "com.superpanic.momaplayer.SLEEP"
        }
        val sleepPendingIntent = PendingIntent.getBroadcast(this, 1, sleepIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        var t : Int
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        
        if( h >= WAKE_HOUR && h < SLEEP_HOUR ) {
            t = SLEEP_HOUR
        } else {
            t = WAKE_HOUR
        }

        val sleepCalendar = getNextCalendar(t)

        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            sleepCalendar.timeInMillis,
            MILLIS_30_MIN,
            sleepPendingIntent
        )

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateString = dateFormat.format(sleepCalendar.time)
        Log.d(TAG, "sleep: " + dateString)
    }

    public fun getCurrentTimeAsString(): String {
        val currentDate = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateString = dateFormat.format(currentDate.time)
        return dateString
    }

    public fun sleep() {
        textView.text = "Sleeping " + getCurrentTimeAsString()
        Log.d(TAG, "Go to sleep!")
        setBrightness(0f) // black
        soundOff()
        isAwake = false
        player?.pause()
    }

    public fun wakeup() {
        textView.text = "Awake " + getCurrentTimeAsString()
        Log.d(TAG, "Time to wake up!")
        setBrightness(BRIGHTNESS)
        if(checkForWiredHeadSet()) soundOn()
        isAwake = true
        player?.play()
    }

    public fun getNextEvening() : Calendar {
        val eveningCalendar = Calendar.getInstance().apply {
            // Assuming you want to set it to 10:30 PM (22:30) today
            val currentHour = get(Calendar.HOUR_OF_DAY)
            val targetHour = SLEEP_HOUR
            val targetMinute = 0
            val targetSecond = 0

            if (currentHour >= targetHour) {
                // If current time is past SLEEP_HOUR, set for the next day
                add(Calendar.DATE, 1)
            }

            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, targetSecond)
            set(Calendar.MILLISECOND, 0)
        }
        return eveningCalendar
    }

    public fun getNextMorning() : Calendar {
        val morningCalender = Calendar.getInstance().apply {
            val currentHour = get(Calendar.HOUR_OF_DAY)
            val targetHour = WAKE_HOUR
            val targetMinute = 0
            val targetSecond = 0

            if(currentHour >= targetHour) {
                add(Calendar.DATE, 1)
            }

            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, targetSecond)
            set(Calendar.MILLISECOND, 0)
        }
        return morningCalender
    }

    public fun getNextCalendar( t: Int ) : Calendar {
        val nextCalendar = Calendar.getInstance().apply {
            val currentHour = get(Calendar.HOUR_OF_DAY)
            val targetHour = t
            val targetMinute = 0
            val targetSecond = 0

            if(currentHour >= targetHour) {
                add(Calendar.DATE, 1)
            }

            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, targetSecond)
            set(Calendar.MILLISECOND, 0)
        }
        return nextCalendar
    }

    private fun getNextMinutes(m: Int, s: Int=0): Calendar {
        val nextMinute = Calendar.getInstance().apply {
            add(Calendar.MINUTE, m)
            add(Calendar.SECOND, s)
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

    private fun soundCheck() {
        if(checkForWiredHeadSet()) soundOn()
        else soundOff()
    }

    private fun soundOn() { // at max volume
        val audioManager: AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val desiredVolumeLevel = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * SOUND_LEVEL
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, desiredVolumeLevel.toInt(), 0)
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
                        } // advertising
                        "us_" -> {
                            ch2.add(mi)
                            d2.add(length)
                            l2=l2+length
                        } // in use videos
                        "do_" -> {
                            ch3.add(mi)
                            d3.add(length)
                            l3=l3+length
                        } // movies
                        "mu_" -> {
                            ch3.add(mi)
                            d3.add(length)
                            l3=l3+length
                        } // music videos

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
                            ch2.add(mediaItem)
                            d2.add(duration)
                            l2=l2+duration
                        }
                        "us_" -> { // in use videos
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
        soundCheck()
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
        soundCheck()
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

    fun isEvening(): Boolean {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return currentHour >= SLEEP_HOUR // Considering evening as 5 PM onwards
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onAlarmEvent(event: AlarmEvent) {
        if(isEvening()) sleep()
        else wakeup()
        setRecurringAlarm()
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
            "com.superpanic.momaplayer.SLEEP" -> {
                EventBus.getDefault().post(AlarmEvent())
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

class AlarmEvent()
