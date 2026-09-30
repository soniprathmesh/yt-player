package dev.academichub.ytplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat

/** Lets MainActivity receive button presses from the notification / lock screen. */
object PlayerBus {
    /** Called with a YouTube player command: playVideo, pauseVideo, nextVideo, previousVideo */
    @Volatile var command: ((String) -> Unit)? = null
}

/**
 * Foreground service + MediaSession.
 *  - keeps the app alive in the background
 *  - shows the media notification (play / pause / previous / next) and lock-screen controls
 */
class KeepAliveService : Service() {

    companion object {
        @Volatile var instance: KeepAliveService? = null
        private const val CHANNEL = "bg_play"
        private const val A_PLAY = "dev.academichub.ytplayer.PLAY"
        private const val A_PAUSE = "dev.academichub.ytplayer.PAUSE"
        private const val A_NEXT = "dev.academichub.ytplayer.NEXT"
        private const val A_PREV = "dev.academichub.ytplayer.PREV"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var session: MediaSession

    // what the web page tells us
    private var ytState = -1        // -1 none, 0 ended, 1 playing, 2 paused, 3 buffering
    private var title = "YT Player"
    private var hasList = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Background playback", NotificationManager.IMPORTANCE_LOW)
            )
        }

        session = MediaSession(this, "YTPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { PlayerBus.command?.invoke("playVideo") }
                override fun onPause() { PlayerBus.command?.invoke("pauseVideo") }
                override fun onSkipToNext() { PlayerBus.command?.invoke("nextVideo") }
                override fun onSkipToPrevious() { PlayerBus.command?.invoke("previousVideo") }
            })
            isActive = true
        }
        refresh(first = true)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytplayer:playback").apply {
            acquire(12 * 60 * 60 * 1000L) // auto-release after 12 hours
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_PLAY -> PlayerBus.command?.invoke("playVideo")
            A_PAUSE -> PlayerBus.command?.invoke("pauseVideo")
            A_NEXT -> PlayerBus.command?.invoke("nextVideo")
            A_PREV -> PlayerBus.command?.invoke("previousVideo")
        }
        return START_STICKY
    }

    /** Called from the web page (through MainActivity) */
    fun update(state: Int? = null, newTitle: String? = null, list: Boolean? = null) {
        if (state != null) ytState = state
        if (!newTitle.isNullOrBlank()) title = newTitle
        if (list != null) hasList = list
        refresh(first = false)
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, KeepAliveService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun action(iconRes: Int, label: String, action: String) =
        Notification.Action.Builder(Icon.createWithResource(this, iconRes), label, actionIntent(action)).build()

    private fun refresh(first: Boolean) {
        val playing = ytState == 1 || ytState == 3

        // ---- MediaSession state ----
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        if (hasList) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    when (ytState) {
                        1 -> PlaybackState.STATE_PLAYING
                        3 -> PlaybackState.STATE_BUFFERING
                        else -> PlaybackState.STATE_PAUSED
                    },
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f
                ).build()
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "YT Player")
                .build()
        )

        // ---- Notification with buttons ----
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        val compact = ArrayList<Int>()
        var index = 0
        if (hasList) {
            builder.addAction(action(android.R.drawable.ic_media_previous, "Previous", A_PREV))
            compact.add(index++)
        }
        if (playing) builder.addAction(action(android.R.drawable.ic_media_pause, "Pause", A_PAUSE))
        else builder.addAction(action(android.R.drawable.ic_media_play, "Play", A_PLAY))
        compact.add(index++)
        if (hasList) {
            builder.addAction(action(android.R.drawable.ic_media_next, "Next", A_NEXT))
            compact.add(index++)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(title)
            .setContentText(if (playing) "Playing" else "Paused")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(*compact.toIntArray())
            )
            .build()

        if (first) {
            ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            getSystemService(NotificationManager::class.java).notify(1, notification)
        }
    }

    override fun onDestroy() {
        instance = null
        session.isActive = false
        session.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
