package ru.tubek.app.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ru.tubek.app.MainActivity

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerController.get(this).player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable()
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun pendingImmutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}
