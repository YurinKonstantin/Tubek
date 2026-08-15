package ru.tubek.app.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.tubek.app.network.OkHttpClients
import ru.tubek.app.youtube.PlaybackOption
import ru.tubek.app.youtube.VideoItem
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PlayerController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val playerRef = AtomicReference<ExoPlayer?>(null)
    private var errorListener: ((PlaybackException) -> Unit)? = null
    private var stallListener: (() -> Unit)? = null
    private val bufferingSinceElapsed = AtomicLong(0L)
    private var lastStallNotifyElapsed = 0L

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentItem = MutableStateFlow<VideoItem?>(null)
    val currentItem: StateFlow<VideoItem?> = _currentItem.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    val player: ExoPlayer
        get() = playerRef.get() ?: createPlayer().also { playerRef.set(it) }

    fun setErrorListener(listener: ((PlaybackException) -> Unit)?) {
        errorListener = listener
    }

    fun setStallListener(listener: (() -> Unit)?) {
        stallListener = listener
    }

    private fun createPlayer(): ExoPlayer {
        return ExoPlayer.Builder(appContext).build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        bufferingSinceElapsed.set(0L)
                        _isBuffering.value = false
                        errorListener?.invoke(error)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        if (isPlaying) {
                            bufferingSinceElapsed.set(0L)
                            _isBuffering.value = false
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                _isBuffering.value = true
                                if (playWhenReady && bufferingSinceElapsed.get() == 0L) {
                                    bufferingSinceElapsed.set(SystemClock.elapsedRealtime())
                                }
                            }
                            Player.STATE_READY -> {
                                _isBuffering.value = false
                                bufferingSinceElapsed.set(0L)
                            }
                            Player.STATE_IDLE, Player.STATE_ENDED -> {
                                _isPlaying.value = false
                                _isBuffering.value = false
                                bufferingSinceElapsed.set(0L)
                            }
                        }
                    }
                }
            )
        }
    }

    /**
     * Долгая буферизация при желании играть — прокси/поток «умерли» без явной ошибки.
     */
    fun checkStallAndNotify(thresholdMs: Long = STALL_THRESHOLD_MS): Boolean {
        val exo = playerRef.get() ?: return false
        if (!exo.playWhenReady) return false
        if (exo.playbackState != Player.STATE_BUFFERING) return false
        val since = bufferingSinceElapsed.get()
        if (since <= 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - since
        if (elapsed < thresholdMs) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastStallNotifyElapsed < STALL_COOLDOWN_MS) return false
        lastStallNotifyElapsed = now
        bufferingSinceElapsed.set(0L)
        stallListener?.invoke()
        return true
    }

    fun play(
        item: VideoItem,
        option: PlaybackOption,
        startPositionMs: Long = 0L
    ) {
        val exo = player
        _currentItem.value = item
        bufferingSinceElapsed.set(0L)
        lastStallNotifyElapsed = 0L
        val factory = ProgressiveMediaSource.Factory(dataSourceFactory())
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.uploader)
            .setArtworkUri(item.thumbnailUrl?.let { android.net.Uri.parse(it) })
            .build()

        val mediaSource = when {
            option.isAudioOnly && !option.audioUrl.isNullOrBlank() -> {
                factory.createMediaSource(
                    MediaItem.Builder()
                        .setUri(option.audioUrl)
                        .setMediaId(item.id)
                        .setMediaMetadata(metadata)
                        .setMimeType(MimeTypes.AUDIO_UNKNOWN)
                        .build()
                )
            }

            !option.videoUrl.isNullOrBlank() && !option.audioUrl.isNullOrBlank() -> {
                val video = factory.createMediaSource(
                    MediaItem.Builder()
                        .setUri(option.videoUrl)
                        .setMediaId(item.id)
                        .setMediaMetadata(metadata)
                        .build()
                )
                val audio = factory.createMediaSource(
                    MediaItem.Builder()
                        .setUri(option.audioUrl)
                        .build()
                )
                MergingMediaSource(video, audio)
            }

            !option.videoUrl.isNullOrBlank() -> {
                factory.createMediaSource(
                    MediaItem.Builder()
                        .setUri(option.videoUrl)
                        .setMediaId(item.id)
                        .setMediaMetadata(metadata)
                        .build()
                )
            }

            !option.audioUrl.isNullOrBlank() -> {
                factory.createMediaSource(
                    MediaItem.Builder()
                        .setUri(option.audioUrl)
                        .setMediaId(item.id)
                        .setMediaMetadata(metadata)
                        .build()
                )
            }

            else -> return
        }

        exo.setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0L))
        exo.prepare()
        exo.play()
    }

    fun setLooping(enabled: Boolean) {
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun currentPositionMs(): Long = playerRef.get()?.currentPosition ?: 0L

    fun wantsToPlay(): Boolean {
        val exo = playerRef.get() ?: return false
        return exo.playWhenReady &&
            exo.playbackState != Player.STATE_ENDED &&
            exo.mediaItemCount > 0
    }

    fun switchToAudioOnly(audioUrl: String, item: VideoItem) {
        val position = currentPositionMs()
        play(
            item = item,
            option = PlaybackOption(
                label = "Только звук",
                height = 0,
                videoUrl = null,
                audioUrl = audioUrl,
                isAudioOnly = true
            ),
            startPositionMs = position
        )
    }

    fun playPause() {
        val exo = playerRef.get() ?: return
        if (exo.playWhenReady) exo.pause() else exo.play()
    }

    fun pause() {
        playerRef.get()?.pause()
    }

    fun stopAndClear() {
        playerRef.get()?.let { exo ->
            exo.stop()
            exo.clearMediaItems()
        }
        _isPlaying.value = false
        _isBuffering.value = false
        _currentItem.value = null
        bufferingSinceElapsed.set(0L)
    }

    fun release() {
        playerRef.getAndSet(null)?.release()
        _isPlaying.value = false
        _isBuffering.value = false
        _currentItem.value = null
        bufferingSinceElapsed.set(0L)
    }

    private fun dataSourceFactory(): DefaultDataSource.Factory {
        val okHttp = OkHttpDataSource.Factory(OkHttpClients.download())
            .setUserAgent(USER_AGENT)
        return DefaultDataSource.Factory(appContext, okHttp)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val STALL_THRESHOLD_MS = 12_000L
        private const val STALL_COOLDOWN_MS = 20_000L

        @Volatile
        private var instance: PlayerController? = null

        fun get(context: Context): PlayerController {
            return instance ?: synchronized(this) {
                instance ?: PlayerController(context.applicationContext).also { instance = it }
            }
        }
    }
}
