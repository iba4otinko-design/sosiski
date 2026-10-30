package com.example.ui.components

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.data.model.BeachFileItem
import com.example.data.model.VideoPlayerState
import com.example.data.model.formatDurationMmSs
import com.example.data.model.formatFileSize
import kotlinx.coroutines.delay
import java.io.File

/**
 * Full-screen built-in Video Player powered by Jetpack Media3 ExoPlayer + PlayerView.
 * Supports local files & SAF content URIs, seek bar, ±10s skip, playback speed (1x..2x),
 * Fit/Zoom aspect ratio toggle, mute, prev/next video in folder or gallery, and sharing.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerModal(
    state: VideoPlayerState,
    onClose: () -> Unit,
    onShare: (BeachFileItem) -> Unit,
    onSelectIndex: (Int) -> Unit,
    onOpenExternally: ((BeachFileItem) -> Unit)? = null
) {
    val activeItem = state.currentItem ?: return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubValueMs by remember { mutableFloatStateOf(0f) }
    var isMuted by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableIntStateOf(0) }
    val speeds = remember { listOf(1.0f, 1.25f, 1.5f, 2.0f) }
    var zoomCropMode by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    // Load active video whenever currentItem changes
    LaunchedEffect(activeItem.key, activeItem.pathOrUri) {
        errorMessage = null
        positionMs = 0L
        durationMs = 0L
        val uri = if (activeItem.isSafDocument || activeItem.pathOrUri.startsWith("content://")) {
            Uri.parse(activeItem.pathOrUri)
        } else {
            Uri.fromFile(File(activeItem.pathOrUri))
        }
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = exoPlayer.duration
                if (dur > 0L && dur != C.TIME_UNSET) {
                    durationMs = dur
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMessage = error.localizedMessage ?: "Помилка декодування відео"
            }
        }
        exoPlayer.addListener(listener)

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Smooth seekbar progress updates while playing
    LaunchedEffect(exoPlayer, isPlaying, isScrubbing) {
        while (true) {
            if (!isScrubbing) {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration
                if (dur > 0L && dur != C.TIME_UNSET) {
                    durationMs = dur
                }
            }
            delay(250L)
        }
    }

    // Auto-hide controls after 4 seconds of uninterrupted playback
    LaunchedEffect(controlsVisible, isPlaying, isScrubbing) {
        if (controlsVisible && isPlaying && !isScrubbing) {
            delay(4200L)
            controlsVisible = false
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onClose)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    controlsVisible = !controlsVisible
                }
                .testTag("video_player_modal")
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        keepScreenOn = true
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        player = exoPlayer
                    }
                },
                update = { view ->
                    view.player = exoPlayer
                    view.resizeMode = if (zoomCropMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.video_player_error),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                                )
                            )
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .align(Alignment.TopCenter),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("video_player_close_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.video_player_back_cd),
                                tint = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeItem.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${formatFileSize(activeItem.sizeBytes)} • ${state.currentIndex + 1}/${state.playlist.size.coerceAtLeast(1)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                        }
                        if (onOpenExternally != null) {
                            IconButton(
                                onClick = { onOpenExternally(activeItem) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("video_player_external_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = stringResource(R.string.action_open_externally),
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(
                            onClick = { onShare(activeItem) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("video_player_share_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = stringResource(R.string.action_share),
                                tint = Color.White
                            )
                        }
                    }

                    // Center playback transport buttons
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.playlist.size > 1) {
                            IconButton(
                                onClick = {
                                    val prev = (state.currentIndex - 1).coerceAtLeast(0)
                                    if (prev != state.currentIndex) onSelectIndex(prev)
                                },
                                enabled = state.currentIndex > 0,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .background(Color.Black.copy(alpha = 0.48f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SkipPrevious,
                                    contentDescription = stringResource(R.string.player_prev_cd),
                                    tint = Color.White
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(target)
                                positionMs = target
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.Black.copy(alpha = 0.52f), CircleShape)
                                .testTag("video_player_rewind_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Replay10,
                                contentDescription = stringResource(R.string.video_player_rewind_cd),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        FilledIconButton(
                            onClick = {
                                if (exoPlayer.isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                        exoPlayer.seekTo(0L)
                                    }
                                    exoPlayer.play()
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .size(68.dp)
                                .testTag("video_player_play_pause_btn")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.player_play_pause_cd),
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val cap = if (durationMs > 0L) durationMs else Long.MAX_VALUE
                                val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(cap)
                                exoPlayer.seekTo(target)
                                positionMs = target
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.Black.copy(alpha = 0.52f), CircleShape)
                                .testTag("video_player_forward_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Forward10,
                                contentDescription = stringResource(R.string.video_player_forward_cd),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        if (state.playlist.size > 1) {
                            IconButton(
                                onClick = {
                                    val next = (state.currentIndex + 1).coerceAtMost(state.playlist.lastIndex)
                                    if (next != state.currentIndex) onSelectIndex(next)
                                },
                                enabled = state.currentIndex < state.playlist.lastIndex,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .background(Color.Black.copy(alpha = 0.48f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SkipNext,
                                    contentDescription = stringResource(R.string.player_next_cd),
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    // Bottom seek bar + speed + fit/zoom + mute
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                )
                            )
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .align(Alignment.BottomCenter)
                    ) {
                        val safeTotal = durationMs.coerceAtLeast(1L).toFloat()
                        val sliderValue = if (isScrubbing) {
                            scrubValueMs.coerceIn(0f, safeTotal)
                        } else {
                            positionMs.toFloat().coerceIn(0f, safeTotal)
                        }

                        Slider(
                            value = sliderValue,
                            onValueChange = {
                                isScrubbing = true
                                scrubValueMs = it
                            },
                            onValueChangeFinished = {
                                val target = scrubValueMs.toLong().coerceAtLeast(0L)
                                exoPlayer.seekTo(target)
                                positionMs = target
                                isScrubbing = false
                            },
                            valueRange = 0f..safeTotal,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("video_player_seek_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${formatDurationMmSs(if (isScrubbing) scrubValueMs.toLong() else positionMs)} / ${formatDurationMmSs(durationMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        isMuted = !isMuted
                                        exoPlayer.volume = if (isMuted) 0f else 1f
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("video_player_mute_btn")
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) {
                                            Icons.AutoMirrored.Outlined.VolumeOff
                                        } else {
                                            Icons.AutoMirrored.Outlined.VolumeUp
                                        },
                                        contentDescription = stringResource(R.string.video_player_mute_cd),
                                        tint = Color.White
                                    )
                                }

                                FilledTonalButton(
                                    onClick = {
                                        speedIndex = (speedIndex + 1) % speeds.size
                                        exoPlayer.setPlaybackSpeed(speeds[speedIndex])
                                    },
                                    modifier = Modifier.testTag("video_player_speed_btn")
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.video_player_speed_fmt,
                                            speeds[speedIndex].toString().removeSuffix(".0")
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                FilledTonalButton(
                                    onClick = { zoomCropMode = !zoomCropMode },
                                    modifier = Modifier.testTag("video_player_aspect_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AspectRatio,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(
                                            if (zoomCropMode) R.string.video_player_zoom_mode else R.string.video_player_fit_mode
                                        ),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}
