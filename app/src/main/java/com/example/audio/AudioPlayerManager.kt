package com.example.audio

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.R
import com.example.data.model.AudioPlayerUiState
import com.example.data.model.AudioTrackItem
import com.example.data.model.BeachFileItem
import com.example.data.model.RepeatPlayMode
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Coordinates the built-in Audio Player UI state with [BeaverAudioPlaybackService]
 * via Jetpack Media3 [MediaController], with an automatic in-process [ExoPlayer] fallback
 * if Xiaomi HyperOS / Android 14 background service restrictions block MediaSessionService binding.
 *
 * Crash & lag fixes for Android 14 HyperOS 1.0.3.0:
 * - Never sends embedded `artworkBytes` inside `MediaItem` across Binder IPC (prevents `TransactionTooLargeException`).
 * - Builds playback queues instantaneously without running `MediaMetadataRetriever` across every file upfront;
 *   extracts rich ID3 tags & downscaled cover art asynchronously only for the active track.
 */
class AudioPlayerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connectToService: Boolean = true
) {

    private val _state = MutableStateFlow(AudioPlayerUiState())
    val state: StateFlow<AudioPlayerUiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var fallbackLocalPlayer: ExoPlayer? = null
    private var usingFallbackPlayer: Boolean = false

    private var progressTickerJob: Job? = null
    private var activeMetadataJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var pendingCommandAfterConnect: ((Player) -> Unit)? = null

    private val activePlayer: Player?
        get() = controller?.takeIf { it.isConnected } ?: fallbackLocalPlayer

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncFromPlayer()
            if (isPlaying) startProgressTicker() else stopProgressTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncFromPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncFromPlayer()
            enrichCurrentTrackMetadataAsync()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            syncFromPlayer()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            syncFromPlayer()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Skip unreadable/corrupted file cleanly instead of crashing playback
            val p = activePlayer ?: return
            if (p.hasNextMediaItem()) {
                runCatching {
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
            } else {
                _state.update { it.copy(isPlaying = false) }
            }
        }
    }

    private fun switchToLocalFallbackPlayer(): Player {
        usingFallbackPlayer = true
        val existing = fallbackLocalPlayer
        if (existing != null) return existing

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val local = ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        local.addListener(playerListener)
        local.volume = _state.value.volume
        applyRepeatAndShuffleToPlayer(local, _state.value.repeatMode, _state.value.shuffleEnabled)
        fallbackLocalPlayer = local
        return local
    }

    private fun ensurePlayerReady(onReady: ((Player) -> Unit)? = null) {
        if (!connectToService) return
        val existing = activePlayer
        if (existing != null) {
            onReady?.invoke(existing)
            return
        }
        if (usingFallbackPlayer) {
            scope.launch(Dispatchers.Main) {
                val p = switchToLocalFallbackPlayer()
                onReady?.invoke(p)
            }
            return
        }

        if (onReady != null) {
            pendingCommandAfterConnect = onReady
        }
        if (controllerFuture != null) return

        // Safety watchdog: if HyperOS blocks foreground/media service binding, fall back to in-process ExoPlayer
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch(Dispatchers.Main) {
            delay(1800L)
            if (controller == null && fallbackLocalPlayer == null) {
                runCatching {
                    controllerFuture?.let { MediaController.releaseFuture(it) }
                }
                controllerFuture = null
                val local = switchToLocalFallbackPlayer()
                val cmd = pendingCommandAfterConnect
                pendingCommandAfterConnect = null
                cmd?.invoke(local)
                syncFromPlayer()
            }
        }

        val started = runCatching {
            val token = SessionToken(context, ComponentName(context, BeaverAudioPlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    connectTimeoutJob?.cancel()
                    val connectedMc = runCatching { future.get() }.getOrNull()
                    if (connectedMc != null) {
                        controller = connectedMc
                        connectedMc.addListener(playerListener)
                        connectedMc.volume = _state.value.volume
                        applyRepeatAndShuffleToPlayer(
                            connectedMc,
                            _state.value.repeatMode,
                            _state.value.shuffleEnabled
                        )
                        val cmd = pendingCommandAfterConnect
                        pendingCommandAfterConnect = null
                        runCatching { cmd?.invoke(connectedMc) }
                        syncFromPlayer()
                    } else {
                        controllerFuture = null
                        val local = switchToLocalFallbackPlayer()
                        val cmd = pendingCommandAfterConnect
                        pendingCommandAfterConnect = null
                        runCatching { cmd?.invoke(local) }
                        syncFromPlayer()
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
            true
        }.getOrDefault(false)

        if (!started) {
            connectTimeoutJob?.cancel()
            scope.launch(Dispatchers.Main) {
                val local = switchToLocalFallbackPlayer()
                val cmd = pendingCommandAfterConnect
                pendingCommandAfterConnect = null
                runCatching { cmd?.invoke(local) }
                syncFromPlayer()
            }
        }
    }

    /**
     * Starts playing [targetFile] immediately and populates the playback queue with [contextAudioFiles]
     * without blocking on metadata extraction for every file in the directory.
     */
    fun playFileWithContextQueue(
        targetFile: BeachFileItem,
        contextAudioFiles: List<BeachFileItem>
    ) {
        val orderedFiles = if (contextAudioFiles.any { it.key == targetFile.key }) {
            contextAudioFiles
        } else {
            listOf(targetFile) + contextAudioFiles
        }
        val tracks = orderedFiles.map { buildLightweightTrack(it) }
        val startIdx = tracks.indexOfFirst { it.fileKey == targetFile.key }.coerceAtLeast(0)
        val chosenTrack = tracks.getOrNull(startIdx) ?: return

        _state.update {
            it.copy(
                isActive = true,
                isPlaying = true,
                queue = tracks,
                currentIndex = startIdx,
                currentTrack = chosenTrack,
                positionMs = 0L,
                durationMs = chosenTrack.durationMs
            )
        }

        enrichCurrentTrackMetadataAsync()

        if (connectToService) {
            ensurePlayerReady { player ->
                val mediaItems = tracks.map { it.toSafeBinderMediaItem() }
                player.setMediaItems(mediaItems, startIdx, 0L)
                player.prepare()
                player.play()
            }
        }
    }

    /**
     * Appends [audioFiles] to the active playback queue (or starts the queue if idle).
     */
    fun enqueueFiles(audioFiles: List<BeachFileItem>) {
        if (audioFiles.isEmpty()) return
        val newTracks = audioFiles.map { buildLightweightTrack(it) }
        val current = _state.value
        if (!current.isActive || current.queue.isEmpty()) {
            val first = newTracks.first()
            _state.update {
                it.copy(
                    isActive = true,
                    isPlaying = true,
                    queue = newTracks,
                    currentIndex = 0,
                    currentTrack = first,
                    positionMs = 0L,
                    durationMs = first.durationMs
                )
            }
            enrichCurrentTrackMetadataAsync()
            if (connectToService) {
                ensurePlayerReady { player ->
                    player.setMediaItems(newTracks.map { it.toSafeBinderMediaItem() }, 0, 0L)
                    player.prepare()
                    player.play()
                }
            }
        } else {
            val updatedQueue = current.queue + newTracks
            _state.update { it.copy(queue = updatedQueue) }
            if (connectToService) {
                ensurePlayerReady { player ->
                    player.addMediaItems(newTracks.map { it.toSafeBinderMediaItem() })
                }
            }
        }
    }

    fun togglePlayPause() {
        val current = _state.value
        if (!current.isActive) return
        val nextPlaying = !current.isPlaying
        _state.update { it.copy(isPlaying = nextPlaying) }
        activePlayer?.let { p ->
            if (p.isPlaying) {
                p.pause()
            } else {
                if (p.playbackState == Player.STATE_ENDED) {
                    p.seekTo(p.currentMediaItemIndex, 0L)
                }
                p.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        _state.update { it.copy(positionMs = clamped) }
        activePlayer?.seekTo(clamped)
    }

    fun skipToNext() {
        val cur = _state.value
        if (cur.queue.isEmpty()) return
        val p = activePlayer
        if (p != null) {
            if (p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
            } else if (cur.repeatMode == RepeatPlayMode.ALL && cur.queue.isNotEmpty()) {
                p.seekTo(0, 0L)
            }
            p.play()
        } else {
            val nextIdx = if (cur.currentIndex + 1 < cur.queue.size) {
                cur.currentIndex + 1
            } else if (cur.repeatMode == RepeatPlayMode.ALL) {
                0
            } else {
                cur.currentIndex
            }
            val nextTrack = cur.queue.getOrNull(nextIdx)
            _state.update {
                it.copy(
                    currentIndex = nextIdx,
                    currentTrack = nextTrack,
                    positionMs = 0L,
                    durationMs = nextTrack?.durationMs ?: 0L
                )
            }
            enrichCurrentTrackMetadataAsync()
        }
    }

    fun skipToPrevious() {
        val cur = _state.value
        if (cur.queue.isEmpty()) return
        val p = activePlayer
        if (p != null) {
            if (p.currentPosition > 3500L) {
                p.seekTo(0L)
            } else if (p.hasPreviousMediaItem()) {
                p.seekToPreviousMediaItem()
            } else {
                p.seekTo(0, 0L)
            }
            p.play()
        } else {
            val prevIdx = (cur.currentIndex - 1).coerceAtLeast(0)
            val prevTrack = cur.queue.getOrNull(prevIdx)
            _state.update {
                it.copy(
                    currentIndex = prevIdx,
                    currentTrack = prevTrack,
                    positionMs = 0L,
                    durationMs = prevTrack?.durationMs ?: 0L
                )
            }
            enrichCurrentTrackMetadataAsync()
        }
    }

    fun playQueueIndex(index: Int) {
        val cur = _state.value
        if (index !in cur.queue.indices) return
        val track = cur.queue[index]
        _state.update {
            it.copy(
                currentIndex = index,
                currentTrack = track,
                isPlaying = true,
                positionMs = 0L,
                durationMs = track.durationMs
            )
        }
        enrichCurrentTrackMetadataAsync()
        activePlayer?.let { p ->
            p.seekTo(index, 0L)
            p.play()
        }
    }

    fun removeFromQueue(index: Int) {
        val cur = _state.value
        if (index !in cur.queue.indices) return
        val updatedQueue = cur.queue.toMutableList().apply { removeAt(index) }
        if (updatedQueue.isEmpty()) {
            stopAndClosePlayer()
            return
        }
        val newIndex = when {
            index < cur.currentIndex -> (cur.currentIndex - 1).coerceAtLeast(0)
            index == cur.currentIndex -> index.coerceAtMost(updatedQueue.lastIndex)
            else -> cur.currentIndex
        }
        val newTrack = updatedQueue.getOrNull(newIndex)
        _state.update {
            it.copy(
                queue = updatedQueue,
                currentIndex = newIndex,
                currentTrack = newTrack,
                durationMs = newTrack?.durationMs ?: 0L
            )
        }
        activePlayer?.removeMediaItem(index)
        enrichCurrentTrackMetadataAsync()
    }

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            RepeatPlayMode.OFF -> RepeatPlayMode.ALL
            RepeatPlayMode.ALL -> RepeatPlayMode.ONE
            RepeatPlayMode.ONE -> RepeatPlayMode.OFF
        }
        _state.update { it.copy(repeatMode = next) }
        activePlayer?.let { applyRepeatAndShuffleToPlayer(it, next, _state.value.shuffleEnabled) }
    }

    fun toggleShuffle() {
        val next = !_state.value.shuffleEnabled
        _state.update { it.copy(shuffleEnabled = next) }
        activePlayer?.let { applyRepeatAndShuffleToPlayer(it, _state.value.repeatMode, next) }
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _state.update { it.copy(volume = clamped) }
        activePlayer?.volume = clamped
    }

    fun setMiniCollapsed(collapsed: Boolean) {
        _state.update { it.copy(isMiniCollapsed = collapsed) }
    }

    fun setExpandedSheetOpen(open: Boolean) {
        _state.update { it.copy(isExpandedSheetOpen = open) }
    }

    fun stopAndClosePlayer() {
        stopProgressTicker()
        activeMetadataJob?.cancel()
        activePlayer?.let { p ->
            runCatching {
                p.stop()
                p.clearMediaItems()
            }
        }
        _state.update {
            AudioPlayerUiState(volume = it.volume, repeatMode = it.repeatMode, shuffleEnabled = it.shuffleEnabled)
        }
    }

    private fun applyRepeatAndShuffleToPlayer(
        player: Player,
        repeatMode: RepeatPlayMode,
        shuffle: Boolean
    ) {
        player.repeatMode = when (repeatMode) {
            RepeatPlayMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatPlayMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatPlayMode.ONE -> Player.REPEAT_MODE_ONE
        }
        player.shuffleModeEnabled = shuffle
    }

    private fun syncFromPlayer() {
        val p = activePlayer ?: return
        val curQueue = _state.value.queue
        val idx = p.currentMediaItemIndex.takeIf { it in curQueue.indices } ?: _state.value.currentIndex
        val track = curQueue.getOrNull(idx) ?: _state.value.currentTrack
        val dur = p.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: track?.durationMs ?: 0L
        val pos = p.currentPosition.coerceAtLeast(0L)
        _state.update {
            it.copy(
                isActive = curQueue.isNotEmpty() || p.mediaItemCount > 0,
                isPlaying = p.isPlaying,
                currentIndex = idx,
                currentTrack = track,
                positionMs = pos,
                durationMs = dur
            )
        }
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val p = activePlayer
                if (p != null && p.isPlaying) {
                    val dur = p.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: _state.value.durationMs
                    _state.update {
                        it.copy(
                            positionMs = p.currentPosition.coerceAtLeast(0L),
                            durationMs = dur
                        )
                    }
                }
                delay(250L)
            }
        }
    }

    private fun stopProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = null
    }

    private fun buildLightweightTrack(item: BeachFileItem): AudioTrackItem {
        val uriString = if (item.isSafDocument || item.pathOrUri.startsWith("content://")) {
            item.pathOrUri
        } else {
            Uri.fromFile(File(item.pathOrUri)).toString()
        }
        val fallbackTitle = item.name.substringBeforeLast('.').ifBlank { item.name }
        val unknownArtist = context.getString(R.string.player_unknown_artist)
        return AudioTrackItem(
            id = "${item.key}_${System.nanoTime()}",
            uriString = uriString,
            title = fallbackTitle,
            artist = unknownArtist,
            album = "",
            durationMs = item.durationMillis,
            artworkBytes = null,
            fileKey = item.key
        )
    }

    private fun enrichCurrentTrackMetadataAsync() {
        activeMetadataJob?.cancel()
        val snapshot = _state.value
        val idx = snapshot.currentIndex
        val track = snapshot.queue.getOrNull(idx) ?: return
        if (track.artworkBytes != null && track.durationMs > 0L) return

        activeMetadataJob = scope.launch {
            val enriched = withContext(Dispatchers.IO) {
                extractRichMetadataForSingleTrack(track)
            }
            _state.update { stateNow ->
                val targetIndex = stateNow.queue.indexOfFirst { it.id == track.id }
                if (targetIndex < 0) return@update stateNow
                val updatedQueue = stateNow.queue.toMutableList().apply {
                    this[targetIndex] = enriched
                }
                val isStillCurrent = stateNow.currentTrack?.id == track.id
                stateNow.copy(
                    queue = updatedQueue,
                    currentTrack = if (isStillCurrent) enriched else stateNow.currentTrack,
                    durationMs = if (isStillCurrent && stateNow.durationMs <= 0L) enriched.durationMs else stateNow.durationMs
                )
            }
        }
    }

    private fun extractRichMetadataForSingleTrack(base: AudioTrackItem): AudioTrackItem {
        val retriever = MediaMetadataRetriever()
        return try {
            val parsedUri = Uri.parse(base.uriString)
            if (parsedUri.scheme == "file") {
                val path = parsedUri.path ?: return base
                if (!File(path).exists()) return base
                retriever.setDataSource(path)
            } else {
                retriever.setDataSource(context, parsedUri)
            }
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: base.title
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: base.artist
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()
                .orEmpty()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: base.durationMs

            val compressedArt = retriever.embeddedPicture?.let { raw ->
                downscaleCoverArtBytes(raw, maxPx = 256)
            }

            base.copy(
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                artworkBytes = compressedArt
            )
        } catch (_: Throwable) {
            base
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun downscaleCoverArtBytes(bytes: ByteArray, maxPx: Int): ByteArray? {
        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            ByteArrayOutputStream().use { out ->
                decoded.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    /**
     * Never attach raw `artworkBytes` to Binder `MediaItem` — prevents Android 14 / HyperOS
     * `TransactionTooLargeException` when queuing folders of MP3/FLAC tracks.
     */
    private fun AudioTrackItem.toSafeBinderMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.parse(uriString))
            .setMediaMetadata(metadata)
            .build()
    }

    fun release() {
        stopProgressTicker()
        connectTimeoutJob?.cancel()
        activeMetadataJob?.cancel()
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        fallbackLocalPlayer?.let { p ->
            p.removeListener(playerListener)
            p.release()
        }
        fallbackLocalPlayer = null
    }
}
