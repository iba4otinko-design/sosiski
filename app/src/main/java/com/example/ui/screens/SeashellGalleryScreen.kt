package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.example.R
import com.example.data.model.BeachFileItem
import com.example.data.model.FileCategory
import com.example.data.model.GalleryMediaFilter
import com.example.data.model.formatCoastalDate
import com.example.data.model.formatDurationMmSs
import com.example.data.model.formatFileSize
import com.example.ui.components.CompactSearchTriggerButton
import com.example.ui.components.ExpandableCompactSearchBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File

private const val MIN_GALLERY_COLUMNS = 2
private const val MAX_GALLERY_COLUMNS = 7

@Composable
fun SeashellGalleryScreen(
    images: List<BeachFileItem>,
    selectedShell: BeachFileItem?,
    searchQuery: String = "",
    mediaFilter: GalleryMediaFilter = GalleryMediaFilter.ALL,
    gridColumns: Int = 3,
    onSearchChange: (String) -> Unit = {},
    onMediaFilterChange: (GalleryMediaFilter) -> Unit = {},
    onGridColumnsChange: (Int) -> Unit = {},
    onOpenShell: (BeachFileItem) -> Unit,
    onSelectAdjacentShell: (BeachFileItem) -> Unit = onOpenShell,
    onCloseLightbox: () -> Unit,
    onImportPhotos: (List<Uri>) -> Unit,
    onShareShell: (BeachFileItem) -> Unit,
    onTrashShell: (BeachFileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchExpanded by remember { mutableStateOf(searchQuery.isNotBlank()) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val galleryImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(4))
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(3))
            .crossfade(false)
            .build()
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) onImportPhotos(uris)
    }

    val clampedColumns = gridColumns.coerceIn(MIN_GALLERY_COLUMNS, MAX_GALLERY_COLUMNS)
    val latestColumns by rememberUpdatedState(clampedColumns)
    val latestOnColumnsChange by rememberUpdatedState(onGridColumnsChange)

    val viewablePhotoItems = remember(images) {
        images.filter { it.category == FileCategory.IMAGES }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .testTag("seashell_gallery_screen")
    ) {
        // Slim Header + Zoom Controls + Compact Search Trigger + Add Media Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.gallery_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Zoom-out button (more columns -> smaller thumbnails)
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (clampedColumns < MAX_GALLERY_COLUMNS) {
                                onGridColumnsChange(clampedColumns + 1)
                            }
                        },
                        enabled = clampedColumns < MAX_GALLERY_COLUMNS,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("gallery_zoom_out_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ZoomOut,
                            contentDescription = stringResource(R.string.cd_gallery_zoom_out),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.gallery_columns_fmt, clampedColumns),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                    IconButton(
                        onClick = {
                            if (clampedColumns > MIN_GALLERY_COLUMNS) {
                                onGridColumnsChange(clampedColumns - 1)
                            }
                        },
                        enabled = clampedColumns > MIN_GALLERY_COLUMNS,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("gallery_zoom_in_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ZoomIn,
                            contentDescription = stringResource(R.string.cd_gallery_zoom_in),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            CompactSearchTriggerButton(
                isSearchExpanded = searchExpanded,
                activeQuery = searchQuery,
                onToggleSearch = {
                    if (searchExpanded && searchQuery.isNotEmpty()) {
                        onSearchChange("")
                    }
                    searchExpanded = !searchExpanded
                },
                testTag = "gallery_search_toggle_btn"
            )

            FilledTonalIconButton(
                onClick = {
                    mediaPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("gallery_add_photo_btn")
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = stringResource(R.string.gallery_add_shell)
                )
            }
        }

        ExpandableCompactSearchBar(
            expanded = searchExpanded,
            query = searchQuery,
            onQueryChange = onSearchChange,
            onCloseSearch = { searchExpanded = false },
            inputTestTag = "gallery_search_input"
        )

        // Filter Chips: Усі / Фото / Відео
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = mediaFilter == GalleryMediaFilter.ALL,
                onClick = { onMediaFilterChange(GalleryMediaFilter.ALL) },
                label = { Text(stringResource(R.string.gallery_filter_all)) },
                modifier = Modifier.testTag("gallery_filter_all")
            )
            FilterChip(
                selected = mediaFilter == GalleryMediaFilter.PHOTOS,
                onClick = { onMediaFilterChange(GalleryMediaFilter.PHOTOS) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Photo,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = { Text(stringResource(R.string.gallery_filter_photos)) },
                modifier = Modifier.testTag("gallery_filter_photos")
            )
            FilterChip(
                selected = mediaFilter == GalleryMediaFilter.VIDEOS,
                onClick = { onMediaFilterChange(GalleryMediaFilter.VIDEOS) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = { Text(stringResource(R.string.gallery_filter_videos)) },
                modifier = Modifier.testTag("gallery_filter_videos")
            )
        }

        if (images.isEmpty()) {
            EmptyCoastalState(
                title = stringResource(R.string.gallery_empty_title),
                subtitle = stringResource(R.string.gallery_empty_subtitle)
            )
        } else {
            val spacing = if (clampedColumns >= 5) 4.dp else 8.dp
            LazyVerticalGrid(
                columns = GridCells.Fixed(clampedColumns),
                contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalArrangement = Arrangement.spacedBy(spacing),
                modifier = Modifier
                    .fillMaxSize()
                    // Pinch-to-zoom ONLY when 2+ fingers touch the screen so 1-finger scrolling stays 100% lag-free
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            var cumulativeZoom = 1f
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size >= 2) {
                                    val zoomStep = event.calculateZoom()
                                    if (zoomStep != 1f && zoomStep.isFinite()) {
                                        cumulativeZoom *= zoomStep
                                        if (cumulativeZoom < 0.76f) {
                                            val next = (latestColumns + 1).coerceAtMost(MAX_GALLERY_COLUMNS)
                                            if (next != latestColumns) latestOnColumnsChange(next)
                                            cumulativeZoom = 1f
                                        } else if (cumulativeZoom > 1.30f) {
                                            val next = (latestColumns - 1).coerceAtLeast(MIN_GALLERY_COLUMNS)
                                            if (next != latestColumns) latestOnColumnsChange(next)
                                            cumulativeZoom = 1f
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .testTag("gallery_media_grid")
            ) {
                items(
                    items = images,
                    key = { it.key },
                    contentType = { it.category.ordinal }
                ) { mediaItem ->
                    GalleryMediaTile(
                        item = mediaItem,
                        imageLoader = galleryImageLoader,
                        columns = clampedColumns,
                        onClick = { onOpenShell(mediaItem) }
                    )
                }
            }
        }
    }

    if (selectedShell != null) {
        SeashellLightboxModal(
            item = selectedShell,
            siblingImages = viewablePhotoItems,
            onSelectSibling = onSelectAdjacentShell,
            onDismiss = onCloseLightbox,
            onShare = { onShareShell(selectedShell) },
            onTrash = { onTrashShell(selectedShell) }
        )
    }
}

@Composable
private fun GalleryMediaTile(
    item: BeachFileItem,
    imageLoader: ImageLoader,
    columns: Int,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isCompactTile = columns >= 5
    val isVideo = item.category == FileCategory.VIDEO
    val cornerRadius = if (isCompactTile) 8.dp else 16.dp
    val decodePx = when {
        columns >= 6 -> 180
        columns >= 4 -> 260
        columns == 3 -> 360
        else -> 512
    }

    val request = remember(item.pathOrUri, item.lastModifiedMillis, decodePx, isVideo) {
        val modelData: Any = if (item.isSafDocument) Uri.parse(item.pathOrUri) else File(item.pathOrUri)
        ImageRequest.Builder(context)
            .data(modelData)
            .size(decodePx, decodePx)
            .memoryCacheKey("gal_${item.pathOrUri}_${item.lastModifiedMillis}_$decodePx")
            .diskCacheKey("gal_${item.pathOrUri}_${item.lastModifiedMillis}_$decodePx")
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }

    // Zero alpha/offset animations: 100% crisp full-opacity thumbnails while scrolling
    Card(
        shape = RoundedCornerShape(cornerRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCompactTile) 1.dp else 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .testTag("gallery_shell_${item.name}")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (isVideo) {
                // Center play circle
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(if (isCompactTile) 26.dp else 38.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.56f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (isCompactTile) 16.dp else 24.dp)
                    )
                }

                // Top-end video duration badge
                val durationLabel = if (item.durationMillis > 0L) {
                    formatDurationMmSs(item.durationMillis)
                } else {
                    stringResource(R.string.video_badge_label)
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.68f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(if (isCompactTile) 3.dp else 6.dp)
                ) {
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            if (!isCompactTile) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun SeashellLightboxModal(
    item: BeachFileItem,
    siblingImages: List<BeachFileItem> = emptyList(),
    onSelectSibling: (BeachFileItem) -> Unit = {},
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    var scale by remember(item.key) { mutableFloatStateOf(1f) }
    var panOffset by remember(item.key) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var imageResolutionText by remember(item.key) { mutableStateOf("") }
    var isLoadingFullRes by remember(item.key) { mutableStateOf(true) }

    val currentIndex = remember(item.key, siblingImages) {
        siblingImages.indexOfFirst { it.key == item.key }
    }
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex >= 0 && currentIndex < siblingImages.lastIndex

    // Load 100% full original resolution without RGB565 downsampling
    val fullResRequest = remember(item.pathOrUri, item.lastModifiedMillis) {
        val modelData: Any = if (item.isSafDocument) Uri.parse(item.pathOrUri) else File(item.pathOrUri)
        ImageRequest.Builder(context)
            .data(modelData)
            .size(Size.ORIGINAL)
            .precision(Precision.EXACT)
            .allowRgb565(false)
            .crossfade(false)
            .build()
    }

    fun clampOffset(raw: Offset, targetScale: Float): Offset {
        if (targetScale <= 1.01f || viewportSize.width <= 0 || viewportSize.height <= 0) {
            return Offset.Zero
        }
        val maxX = (viewportSize.width * (targetScale - 1f)) / 2f
        val maxY = (viewportSize.height * (targetScale - 1f)) / 2f
        return Offset(
            x = raw.x.coerceIn(-maxX, maxX),
            y = raw.y.coerceIn(-maxY, maxY)
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            color = Color(0xFF050A0E),
            modifier = Modifier
                .fillMaxSize()
                .testTag("seashell_lightbox_modal")
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
            ) {
                AsyncImage(
                    model = fullResRequest,
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    onSuccess = { success ->
                        isLoadingFullRes = false
                        val d = success.result.drawable
                        if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                            imageResolutionText = "${d.intrinsicWidth} × ${d.intrinsicHeight} px"
                        }
                    },
                    onError = { isLoadingFullRes = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(item.key) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.15f) {
                                        scale = 1f
                                        panOffset = Offset.Zero
                                    } else {
                                        scale = 2.8f
                                        panOffset = Offset.Zero
                                    }
                                }
                            )
                        }
                        .pointerInput(item.key) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 6f)
                                scale = nextScale
                                panOffset = clampOffset(panOffset + pan, nextScale)
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = panOffset.x
                            translationY = panOffset.y
                        }
                )

                if (isLoadingFullRes) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // Previous / Next buttons when viewing inside a gallery or folder
                if (hasPrev && scale <= 1.05f) {
                    IconButton(
                        onClick = { onSelectSibling(siblingImages[currentIndex - 1]) },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 10.dp)
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .testTag("lightbox_prev_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.video_prev),
                            tint = Color.White
                        )
                    }
                }
                if (hasNext && scale <= 1.05f) {
                    IconButton(
                        onClick = { onSelectSibling(siblingImages[currentIndex + 1]) },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp)
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .testTag("lightbox_next_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.video_next),
                            tint = Color.White
                        )
                    }
                }

                // Top Lightbox Controls
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.48f))
                            .testTag("lightbox_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.btn_close),
                            tint = Color.White
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = onShare,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.48f))
                                .testTag("lightbox_share_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = stringResource(R.string.action_share),
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = onTrash,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.48f))
                                .testTag("lightbox_trash_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.action_move_trash),
                                tint = Color(0xFFFFB4A3)
                            )
                        }
                    }
                }

                // Bottom Metadata Caption with full resolution dimensions
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metaParts = buildList {
                        if (imageResolutionText.isNotBlank()) add(imageResolutionText)
                        add(formatFileSize(item.sizeBytes))
                        add(formatCoastalDate(item.createdAtMillis))
                    }
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.lightbox_zoom_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF7BE8F2)
                    )
                }
            }
        }
    }
}
