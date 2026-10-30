package com.example.audio

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R

/**
 * Android Foreground MediaSessionService backed by Jetpack Media3 ExoPlayer.
 * Hardened for Android 14 / Xiaomi HyperOS 1.0.3.0:
 * - Uses an explicit monochrome vector small icon for foreground media playback notifications.
 * - Guards WAKE_MODE_LOCAL with WAKE_LOCK permission check to prevent SecurityException.
 */
class BeaverAudioPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        runCatching {
            val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply {
                    setSmallIcon(R.drawable.ic_stat_beaver_audio)
                }
            setMediaNotificationProvider(notificationProvider)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val hasWakeLock = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WAKE_LOCK
        ) == PackageManager.PERMISSION_GRANTED

        val playerBuilder = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)

        if (hasWakeLock) {
            playerBuilder.setWakeMode(C.WAKE_MODE_LOCAL)
        }

        val player = playerBuilder.build()

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            runCatching { player.release() }
            runCatching { release() }
            mediaSession = null
        }
        super.onDestroy()
    }
}
