package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BeachFileItem
import com.example.data.model.SortOption
import com.example.data.model.StorageStats
import com.example.data.model.SwipeUndoAction
import com.example.data.model.formatCoastalDate
import com.example.data.model.formatFileSize
import com.example.ui.components.SandyFileIcon
import com.example.ui.icons.SmartFileIcon
import com.example.ui.viewmodel.GestureSortDirection
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SorterAndOptimizerScreen(
    swipeDeck: List<BeachFileItem>,
    lastUndo: SwipeUndoAction?,
    storageStats: StorageStats,
    optimizerSort: SortOption,
    onBack: () -> Unit,
    onGestureSort: (BeachFileItem, GestureSortDirection) -> Unit,
    onUndoSwipe: () -> Unit,
    onResetDeck: () -> Unit,
    onUpdateOptimizerSort: (SortOption) -> Unit,
    onEmptyTrash: () -> Unit,
    onOpenItem: (BeachFileItem) -> Unit,
    onTrashItem: (BeachFileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSubTab by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("sorter_and_optimizer_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("space_screen_back_btn")
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.btn_back)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.screen_title_space),
                style = MaterialTheme.typography.titleLarge
            )
        }
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            SegmentedButton(
                selected = selectedSubTab == 0,
                onClick = { selectedSubTab = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = { Icon(Icons.Outlined.Swipe, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("subtab_gesture_sorter")
            ) {
                Text(stringResource(R.string.tab_gesture_sorter), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SegmentedButton(
                selected = selectedSubTab == 1,
                onClick = { selectedSubTab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = { Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("subtab_space_optimizer")
            ) {
                Text(stringResource(R.string.tab_space_optimizer), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        AnimatedContent(
            targetState = selectedSubTab,
            modifier = Modifier.fillMaxSize(),
            label = "space_tab_switch"
        ) { tab ->
            if (tab == 0) {
                GestureSwipeDeckContent(
                    deck = swipeDeck,
                    lastUndo = lastUndo,
                    onSwipe = onGestureSort,
                    onUndo = onUndoSwipe,
                    onResetDeck = onResetDeck
                )
            } else {
                SpaceOptimizerContent(
                    stats = storageStats,
                    sortOption = optimizerSort,
                    onSortChange = onUpdateOptimizerSort,
                    onEmptyTrash = onEmptyTrash,
                    onOpenItem = onOpenItem,
                    onTrashItem = onTrashItem
                )
            }
        }
    }
}

@Composable
private fun GestureSwipeDeckContent(
    deck: List<BeachFileItem>,
    lastUndo: SwipeUndoAction?,
    onSwipe: (BeachFileItem, GestureSortDirection) -> Unit,
    onUndo: () -> Unit,
    onResetDeck: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val topCard = deck.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.gesture_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Directional compass hints
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DirectionBadge(stringResource(R.string.gesture_left))
            DirectionBadge(stringResource(R.string.gesture_up))
            DirectionBadge(stringResource(R.string.gesture_down))
            DirectionBadge(stringResource(R.string.gesture_right))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 460.dp),
            contentAlignment = Alignment.Center
        ) {
            if (topCard == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    SandyFileIcon(category = com.example.data.model.FileCategory.IMAGES, size = 76.dp)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.gesture_done_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.gesture_done_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = onResetDeck,
                        modifier = Modifier.testTag("reset_gesture_deck_btn")
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_reset_deck))
                    }
                }
            } else {
                // Background next card preview for depth
                if (deck.size > 1) {
                    val second = deck[1]
                    Card(
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .height(250.dp)
                            .offset(y = 14.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = second.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                val offsetX = remember(topCard.key) { Animatable(0f) }
                val offsetY = remember(topCard.key) { Animatable(0f) }

                val activeHint = when {
                    offsetX.value < -90f -> stringResource(R.string.gesture_left)
                    offsetX.value > 90f -> stringResource(R.string.gesture_right)
                    offsetY.value < -90f -> stringResource(R.string.gesture_up)
                    offsetY.value > 90f -> stringResource(R.string.gesture_down)
                    else -> null
                }

                Card(
                    shape = RoundedCornerShape(28.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth(0.90f)
                        .height(268.dp)
                        .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                        .graphicsLayer {
                            rotationZ = (offsetX.value / 28f).coerceIn(-16f, 16f)
                        }
                        .pointerInput(topCard.key) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        offsetX.snapTo(offsetX.value + dragAmount.x)
                                        offsetY.snapTo(offsetY.value + dragAmount.y)
                                    }
                                },
                                onDragEnd = {
                                    val dx = offsetX.value
                                    val dy = offsetY.value
                                    val threshold = 135f
                                    scope.launch {
                                        if (abs(dx) > abs(dy) && abs(dx) > threshold) {
                                            val dir = if (dx < 0) {
                                                GestureSortDirection.LEFT_DOCUMENTS
                                            } else {
                                                GestureSortDirection.RIGHT_SHELLS
                                            }
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            offsetX.animateTo(if (dx < 0) -900f else 900f, tween(210))
                                            onSwipe(topCard, dir)
                                        } else if (abs(dy) > threshold) {
                                            val dir = if (dy < 0) {
                                                GestureSortDirection.UP_TREASURE
                                            } else {
                                                GestureSortDirection.DOWN_CLEANUP
                                            }
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            offsetY.animateTo(if (dy < 0) -900f else 900f, tween(210))
                                            onSwipe(topCard, dir)
                                        } else {
                                            launch {
                                                offsetX.animateTo(
                                                    0f,
                                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                                )
                                            }
                                            launch {
                                                offsetY.animateTo(
                                                    0f,
                                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        .testTag("swipe_sorter_active_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (activeHint != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = activeHint,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        SmartFileIcon(
                            item = topCard,
                            size = 76.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = topCard.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${formatFileSize(topCard.sizeBytes)} • ${formatCoastalDate(topCard.createdAtMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Залишилось у черзі: ${deck.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Accessible 48dp+ one-tap gesture buttons + Undo
        if (topCard != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onSwipe(topCard, GestureSortDirection.LEFT_DOCUMENTS) },
                    modifier = Modifier.weight(1f).testTag("btn_sort_left_docs")
                ) {
                    Icon(Icons.Outlined.Description, contentDescription = stringResource(R.string.gesture_left))
                }
                OutlinedButton(
                    onClick = { onSwipe(topCard, GestureSortDirection.UP_TREASURE) },
                    modifier = Modifier.weight(1f).testTag("btn_sort_up_treasure")
                ) {
                    Icon(Icons.Outlined.StarOutline, contentDescription = stringResource(R.string.gesture_up))
                }
                OutlinedButton(
                    onClick = { onSwipe(topCard, GestureSortDirection.DOWN_CLEANUP) },
                    modifier = Modifier.weight(1f).testTag("btn_sort_down_cleanup")
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.gesture_down),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                OutlinedButton(
                    onClick = { onSwipe(topCard, GestureSortDirection.RIGHT_SHELLS) },
                    modifier = Modifier.weight(1f).testTag("btn_sort_right_shells")
                ) {
                    Icon(Icons.Outlined.Photo, contentDescription = stringResource(R.string.gesture_right))
                }
            }
        }

        if (lastUndo != null) {
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(
                onClick = onUndo,
                modifier = Modifier.testTag("undo_gesture_sort_btn")
            ) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.btn_undo))
            }
        }
    }
}

@Composable
private fun DirectionBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SpaceOptimizerContent(
    stats: StorageStats,
    sortOption: SortOption,
    onSortChange: (SortOption) -> Unit,
    onEmptyTrash: () -> Unit,
    onOpenItem: (BeachFileItem) -> Unit,
    onTrashItem: (BeachFileItem) -> Unit
) {
    val usedRatio = if (stats.totalDeviceBytes > 0L) {
        (stats.usedDeviceBytes.toFloat() / stats.totalDeviceBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val sortedFiles = remember(stats.largestFiles, sortOption) {
        when (sortOption) {
            SortOption.DATE_DESC -> stats.largestFiles.sortedByDescending { it.createdAtMillis }
            SortOption.DATE_ASC -> stats.largestFiles.sortedBy { it.createdAtMillis }
            else -> stats.largestFiles.sortedByDescending { it.sizeBytes }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("space_optimizer_list")
    ) {
        item(key = "device_storage_card") {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.optimizer_device_storage),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "${(usedRatio * 100).roundToInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { usedRatio },
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(50))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.optimizer_free_of_total,
                            formatFileSize(stats.freeDeviceBytes),
                            formatFileSize(stats.totalDeviceBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onEmptyTrash,
                        enabled = stats.trashTotalBytes > 0L,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("empty_trash_optimize_btn")
                    ) {
                        Icon(Icons.Outlined.CleaningServices, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (stats.trashTotalBytes > 0L) {
                                stringResource(
                                    R.string.optimizer_trash_clean_btn,
                                    formatFileSize(stats.trashTotalBytes)
                                )
                            } else {
                                stringResource(R.string.optimizer_trash_empty)
                            }
                        )
                    }
                }
            }
        }

        item(key = "optimizer_sort_header") {
            Column {
                Text(
                    text = stringResource(R.string.optimizer_largest_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = sortOption == SortOption.SIZE_DESC,
                        onClick = { onSortChange(SortOption.SIZE_DESC) },
                        label = { Text(stringResource(R.string.sort_size_desc)) },
                        modifier = Modifier.testTag("optimizer_sort_size_chip")
                    )
                    FilterChip(
                        selected = sortOption == SortOption.DATE_DESC,
                        onClick = { onSortChange(SortOption.DATE_DESC) },
                        label = { Text(stringResource(R.string.sort_date_desc)) },
                        modifier = Modifier.testTag("optimizer_sort_date_chip")
                    )
                }
            }
        }

        items(
            items = sortedFiles,
            key = { it.key },
            contentType = { "optimizer_file_row" }
        ) { item ->
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenItem(item) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmartFileIcon(item = item, size = 44.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.optimizer_created_label,
                                formatCoastalDate(item.createdAtMillis)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = formatFileSize(item.sizeBytes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    IconButton(
                        onClick = { onTrashItem(item) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.action_move_trash),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
