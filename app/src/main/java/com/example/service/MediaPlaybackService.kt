package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.player.AudioPlayerManager

class MediaPlaybackService : Service() {

    private lateinit var mediaSession: MediaSessionCompat

    companion object {
        const val CHANNEL_ID = "media_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_UPDATE = "com.example.action.UPDATE_NOTIFICATION"
        const val ACTION_PREVIOUS = "com.example.action.PREVIOUS"
        const val ACTION_TOGGLE = "com.example.action.TOGGLE"
        const val ACTION_NEXT = "com.example.action.NEXT"
        const val ACTION_STOP = "com.example.action.STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_ALBUM = "extra_album"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_ALBUM_ART_URI = "extra_album_art_uri"
        const val EXTRA_GRADIENT_INDEX = "extra_gradient_index"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        fun updateNotification(
            context: Context,
            title: String,
            artist: String,
            album: String = "",
            durationMs: Int = 0,
            positionMs: Int = 0,
            albumArtUri: String?,
            gradientIndex: Int,
            isPlaying: Boolean
        ) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_ALBUM, album)
                putExtra(EXTRA_DURATION_MS, durationMs)
                putExtra(EXTRA_POSITION_MS, positionMs)
                putExtra(EXTRA_ALBUM_ART_URI, albumArtUri)
                putExtra(EXTRA_GRADIENT_INDEX, gradientIndex)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MediaPlaybackService").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    AudioPlayerManager.activeInstance?.resume()
                }

                override fun onPause() {
                    AudioPlayerManager.activeInstance?.pause()
                }

                override fun onSkipToNext() {
                    AudioPlayerManager.activeInstance?.nextTrack()
                }

                override fun onSkipToPrevious() {
                    AudioPlayerManager.activeInstance?.previousTrack()
                }

                override fun onSeekTo(pos: Long) {
                    AudioPlayerManager.activeInstance?.seekTo(pos.toInt())
                }

                override fun onStop() {
                    AudioPlayerManager.activeInstance?.pause()
                }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_PREVIOUS -> {
                AudioPlayerManager.activeInstance?.previousTrack()
            }
            ACTION_TOGGLE -> {
                AudioPlayerManager.activeInstance?.togglePlayPause()
            }
            ACTION_NEXT -> {
                AudioPlayerManager.activeInstance?.nextTrack()
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "0003 Player"
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: "Artist"
                val album = intent.getStringExtra(EXTRA_ALBUM) ?: ""
                val durationMs = intent.getIntExtra(EXTRA_DURATION_MS, 0).toLong()
                val positionMs = intent.getIntExtra(EXTRA_POSITION_MS, 0).toLong()
                val albumArtUri = intent.getStringExtra(EXTRA_ALBUM_ART_URI)
                val gradientIndex = intent.getIntExtra(EXTRA_GRADIENT_INDEX, 0)
                val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)

                updateMediaSessionStateAndMetadata(
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    positionMs = positionMs,
                    albumArtUri = albumArtUri,
                    gradientIndex = gradientIndex,
                    isPlaying = isPlaying
                )

                val notification = buildNotification(title, artist, albumArtUri, gradientIndex, isPlaying)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }

        return START_STICKY
    }

    private fun updateMediaSessionStateAndMetadata(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        positionMs: Long,
        albumArtUri: String?,
        gradientIndex: Int,
        isPlaying: Boolean
    ) {
        if (!::mediaSession.isInitialized) return

        val largeIcon = getLargeIconBitmap(this, albumArtUri, gradientIndex, title)

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (durationMs > 0) durationMs else -1L)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, largeIcon)

        mediaSession.setMetadata(metadataBuilder.build())

        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(
                state,
                if (positionMs >= 0) positionMs else 0L,
                if (isPlaying) 1.0f else 0.0f
            )
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls in notification shade"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun getLargeIconBitmap(
        context: Context,
        albumArtUri: String?,
        gradientIndex: Int,
        title: String
    ): Bitmap {
        if (!albumArtUri.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(albumArtUri)
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    if (bitmap != null) return bitmap
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback: Generate an elegant colored bitmap placeholder
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val colors = when (gradientIndex % 5) {
            0 -> intArrayOf(Color.parseColor("#4B6CB7"), Color.parseColor("#182848"))
            1 -> intArrayOf(Color.parseColor("#FF512F"), Color.parseColor("#DD2476"))
            2 -> intArrayOf(Color.parseColor("#8A2387"), Color.parseColor("#E94057"))
            3 -> intArrayOf(Color.parseColor("#11998E"), Color.parseColor("#38EF7D"))
            else -> intArrayOf(Color.parseColor("#1F1C2C"), Color.parseColor("#928DAB"))
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                colors, null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 96f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val initial = title.firstOrNull()?.toString()?.uppercase() ?: "🎵"
        val yPos = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(initial, size / 2f, yPos, textPaint)

        return bitmap
    }

    private fun buildNotification(
        title: String,
        artist: String,
        albumArtUri: String?,
        gradientIndex: Int,
        isPlaying: Boolean
    ): Notification {
        // PendingIntent to launch app when notification clicked
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Control PendingIntents
        val prevPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PREVIOUS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val togglePendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextPendingIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val largeIcon = getLargeIconBitmap(this, albumArtUri, gradientIndex, title)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(largeIcon)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 1. Previous action
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_previous,
                    "Previous",
                    prevPendingIntent
                )
            )
            // 2. Play / Pause action
            .addAction(
                NotificationCompat.Action(
                    playPauseIcon,
                    playPauseTitle,
                    togglePendingIntent
                )
            )
            // 3. Next action
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next,
                    "Next",
                    nextPendingIntent
                )
            )
            // Apply MediaStyle showing the 3 action buttons in compact view
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        return builder.build()
    }
}
