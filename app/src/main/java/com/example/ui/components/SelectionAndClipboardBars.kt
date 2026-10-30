package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BeachFileItem
import com.example.data.model.ClipboardMode
import com.example.data.model.FileCategory
import com.example.data.model.FileClipboardState
import com.example.data.model.SupportedArchiveFormat
import com.example.data.model.formatCoastalDate
import com.example.data.model.formatFileSize
import com.example.ui.icons.SmartFileIcon
import kotlinx.coroutines.delay

/**
 * Top selection status bar showing selected count, total byte size, Select All, and Cancel.
 */
@Composable
fun SelectionTopSummaryBar(
    selectedCount: Int,
    selectedTotalBytes: Long,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = selectedCount > 0,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("selection_top_summary_bar")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onClearSelection,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("selection_clear_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.sel_clear_all)
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.sel_count_and_size,
                            selectedCount,
                            formatFileSize(selectedTotalBytes)
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(
                    onClick = onSelectAll,
                    modifier = Modifier.testTag("selection_select_all_btn")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.sel_select_all))
                }
            }
        }
    }
}

/**
 * Persistent Total Commander-style file clipboard banner shown whenever files are copied/cut.
 */
@Composable
fun TotalCommanderClipboardBanner(
    clipboard: FileClipboardState?,
    canPasteHere: Boolean,
    onPasteHere: () -> Unit,
    onCopyListAsText: () -> Unit,
    onClearClipboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = clipboard != null && clipboard.items.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        val state = clipboard ?: return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("tc_clipboard_banner")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (state.mode == ClipboardMode.CUT) {
                                R.string.clipboard_banner_cut
                            } else {
                                R.string.clipboard_banner_copy
                            },
                            state.items.size
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.sourceFolderDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onCopyListAsText,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("clipboard_copy_tc_text_btn")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = stringResource(R.string.action_copy_tc_list),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                if (canPasteHere) {
                    FilledTonalButton(
                        onClick = onPasteHere,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("clipboard_paste_here_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_paste_here))
                    }
                }

                IconButton(
                    onClick = onClearClipboard,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("clipboard_clear_btn")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.clipboard_clear_cd),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

/**
 * Contextual Bottom Action Panel for multi-selected items:
 * Copy, Cut, Paste, Move, Rename, Archive, Share, Delete, TC Text List, and Add to Audio Queue.
 */
@Composable
fun SelectionBottomActionBar(
    selectedItems: List<BeachFileItem>,
    hasClipboardItems: Boolean,
    canPasteHere: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onCopyTcListAsText: () -> Unit,
    onPasteHere: () -> Unit,
    onMoveTo: () -> Unit,
    onRenameSingle: (BeachFileItem) -> Unit,
    onArchive: () -> Unit,
    onShare: () -> Unit,
    onDeleteRequest: () -> Unit,
    onEnqueueAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = selectedItems.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        val hasAudioSelected = remember(selectedItems) {
            selectedItems.any { !it.isDirectory && it.category == FileCategory.AUDIO }
        }
        val hasSharableFiles = remember(selectedItems) {
            selectedItems.any { !it.isDirectory }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("selection_bottom_action_bar")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionPillButton(
                    icon = Icons.Outlined.ContentCopy,
                    label = stringResource(R.string.action_copy),
                    onClick = onCopy,
                    testTag = "sel_action_copy"
                )
                ActionPillButton(
                    icon = Icons.Outlined.ContentCut,
                    label = stringResource(R.string.action_cut),
                    onClick = onCut,
                    testTag = "sel_action_cut"
                )
                ActionPillButton(
                    icon = Icons.Outlined.Description,
                    label = stringResource(R.string.action_copy_tc_list),
                    onClick = onCopyTcListAsText,
                    testTag = "sel_action_tc_text"
                )
                if (hasClipboardItems && canPasteHere) {
                    ActionPillButton(
                        icon = Icons.Outlined.ContentPaste,
                        label = stringResource(R.string.action_paste_here),
                        onClick = onPasteHere,
                        testTag = "sel_action_paste"
                    )
                }
                ActionPillButton(
                    icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                    label = stringResource(R.string.action_move_to),
                    onClick = onMoveTo,
                    testTag = "sel_action_move"
                )
                if (selectedItems.size == 1) {
                    ActionPillButton(
                        icon = Icons.Outlined.DriveFileRenameOutline,
                        label = stringResource(R.string.action_rename),
                        onClick = { onRenameSingle(selectedItems.first()) },
                        testTag = "sel_action_rename"
                    )
                }
                ActionPillButton(
                    icon = Icons.Outlined.Archive,
                    label = stringResource(R.string.action_archive),
                    onClick = onArchive,
                    testTag = "sel_action_archive"
                )
                if (hasAudioSelected) {
                    ActionPillButton(
                        icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                        label = stringResource(R.string.action_enqueue_audio),
                        onClick = onEnqueueAudio,
                        testTag = "sel_action_enqueue_audio"
                    )
                }
                if (hasSharableFiles) {
                    ActionPillButton(
                        icon = Icons.Outlined.Share,
                        label = stringResource(R.string.action_share),
                        onClick = onShare,
                        testTag = "sel_action_share"
                    )
                }
                ActionPillButton(
                    icon = Icons.Outlined.DeleteOutline,
                    label = stringResource(R.string.action_delete),
                    onClick = onDeleteRequest,
                    isDestructive = true,
                    testTag = "sel_action_delete"
                )
            }
        }
    }
}

@Composable
private fun ActionPillButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String,
    isDestructive: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * Unified LazyColumn supporting:
 * - Long-press to activate selection + continuous vertical finger sweep to multi-select across rows
 * - Auto-scrolling near top/bottom viewport edges during drag-select
 * - Left-gutter 1-finger sweep while in selection mode without interfering with normal list scrolling
 * - SmartFileIcon real thumbnails/icons + full context menu
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableCoastalFileList(
    items: List<BeachFileItem>,
    selectedKeys: Set<String>,
    listTestTag: String,
    rowTagPrefix: String = "list_item_",
    isTrashMode: Boolean = false,
    headerContent: (@Composable () -> Unit)? = null,
    onOpenItem: (BeachFileItem) -> Unit,
    onOpenAsArchive: (BeachFileItem) -> Unit,
    onToggleSelectItem: (BeachFileItem) -> Unit,
    onSelectRange: (Set<String>) -> Unit,
    onCopySingle: (BeachFileItem) -> Unit,
    onCutSingle: (BeachFileItem) -> Unit,
    onRenameItem: (BeachFileItem) -> Unit,
    onToggleStar: (BeachFileItem) -> Unit,
    onShareItem: (BeachFileItem) -> Unit,
    onTrashItem: (BeachFileItem) -> Unit,
    onEnqueueAudio: (BeachFileItem) -> Unit,
    onRestoreFromTrash: (BeachFileItem) -> Unit = {},
    onDeleteForever: (BeachFileItem) -> Unit = {},
    modifier: Modifier = Modifier,
    lazyListState: LazyListState = rememberLazyListState()
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val selectionModeActive = selectedKeys.isNotEmpty()

    val currentItems by rememberUpdatedState(items)
    val currentSelectedKeys by rememberUpdatedState(selectedKeys)

    val indexByKey = remember(items) {
        items.mapIndexed { index, beachFileItem -> beachFileItem.key to index }.toMap()
    }
    val updatedIndexByKey by rememberUpdatedState(indexByKey)

    var listHeightPx by remember { mutableFloatStateOf(1f) }
    var dragAnchorIndex by remember { mutableIntStateOf(-1) }
    var lastHoveredIndex by remember { mutableIntStateOf(-1) }
    var preDragSelectedSnapshot by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeFingerLocalY by remember { mutableFloatStateOf(-1f) }
    var autoScrollVelocityPx by remember { mutableFloatStateOf(0f) }

    val edgeZonePx = with(density) { 60.dp.toPx() }
    val maxScrollStepPx = with(density) { 18.dp.toPx() }

    fun findItemIndexAtLocalY(localY: Float): Int? {
        val visible = lazyListState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return null
        val directHit = visible.firstOrNull { info ->
            val keyStr = info.key as? String ?: return@firstOrNull false
            keyStr in updatedIndexByKey && localY >= info.offset && localY <= (info.offset + info.size)
        }
        if (directHit != null) {
            return updatedIndexByKey[directHit.key as String]
        }
        // Clamp to nearest visible file item when dragging slightly above/below gaps
        val fileVisible = visible.filter { (it.key as? String) in updatedIndexByKey }
        if (fileVisible.isEmpty()) return null
        val nearest = if (localY < fileVisible.first().offset) {
            fileVisible.first()
        } else if (localY > fileVisible.last().offset + fileVisible.last().size) {
            fileVisible.last()
        } else {
            fileVisible.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - localY) }
        }
        return nearest?.let { updatedIndexByKey[it.key as String] }
    }

    fun applySweepAtLocalY(localY: Float) {
        val anchor = dragAnchorIndex
        if (anchor !in currentItems.indices) return
        val hovered = findItemIndexAtLocalY(localY) ?: return
        if (hovered != lastHoveredIndex) {
            lastHoveredIndex = hovered
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        val minIdx = minOf(anchor, hovered)
        val maxIdx = maxOf(anchor, hovered)
        val rangeKeys = currentItems.subList(minIdx, maxIdx + 1)
            .filter { it.requiresSafGrantDocId == null && !it.key.startsWith("virtual:") }
            .map { it.key }
            .toSet()
        onSelectRange(preDragSelectedSnapshot + rangeKeys)
    }

    fun updateEdgeAutoScroll(localY: Float) {
        activeFingerLocalY = localY
        autoScrollVelocityPx = when {
            localY < edgeZonePx -> {
                val factor = ((edgeZonePx - localY) / edgeZonePx).coerceIn(0.15f, 1f)
                -maxScrollStepPx * factor
            }
            localY > listHeightPx - edgeZonePx -> {
                val factor = ((localY - (listHeightPx - edgeZonePx)) / edgeZonePx).coerceIn(0.15f, 1f)
                maxScrollStepPx * factor
            }
            else -> 0f
        }
    }

    fun finishSweepGesture() {
        dragAnchorIndex = -1
        lastHoveredIndex = -1
        autoScrollVelocityPx = 0f
        activeFingerLocalY = -1f
    }

    // Smooth auto-scroll loop when dragging near top or bottom of the LazyColumn
    LaunchedEffect(autoScrollVelocityPx) {
        if (autoScrollVelocityPx == 0f) return@LaunchedEffect
        while (autoScrollVelocityPx != 0f && dragAnchorIndex >= 0) {
            val scrolled = lazyListState.scrollBy(autoScrollVelocityPx)
            if (scrolled != 0f && activeFingerLocalY >= 0f) {
                applySweepAtLocalY(activeFingerLocalY)
            }
            delay(16L)
        }
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = PaddingValues(bottom = 96.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .onSizeChanged { size ->
                val h = size.height.toFloat().coerceAtLeast(1f)
                if (listHeightPx != h) {
                    listHeightPx = h
                }
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startOffset ->
                        val hitIdx = findItemIndexAtLocalY(startOffset.y) ?: return@detectDragGesturesAfterLongPress
                        val item = currentItems.getOrNull(hitIdx) ?: return@detectDragGesturesAfterLongPress
                        if (item.requiresSafGrantDocId != null || item.key.startsWith("virtual:")) {
                            return@detectDragGesturesAfterLongPress
                        }
                        dragAnchorIndex = hitIdx
                        lastHoveredIndex = hitIdx
                        preDragSelectedSnapshot = currentSelectedKeys
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelectRange(preDragSelectedSnapshot + item.key)
                    },
                    onDrag = { change, _ ->
                        if (dragAnchorIndex >= 0) {
                            change.consume()
                            updateEdgeAutoScroll(change.position.y)
                            applySweepAtLocalY(change.position.y)
                        }
                    },
                    onDragEnd = { finishSweepGesture() },
                    onDragCancel = { finishSweepGesture() }
                )
            }
            .testTag(listTestTag)
    ) {
        if (headerContent != null) {
            item(key = "header_banner_slot") {
                headerContent()
            }
        }

        items(
            items = items,
            key = { it.key },
            contentType = { if (it.isDirectory) "folder_row" else "file_row" }
        ) { item ->
            val isSelected = item.key in selectedKeys
            val isSelectable = item.requiresSafGrantDocId == null && !item.key.startsWith("virtual:")

            SmartCoastalFileRow(
                item = item,
                isSelected = isSelected,
                selectionModeActive = selectionModeActive,
                isSelectable = isSelectable,
                isTrashMode = isTrashMode,
                rowTestTag = "$rowTagPrefix${item.name}",
                onRowClick = {
                    if (selectionModeActive && isSelectable) {
                        onToggleSelectItem(item)
                    } else {
                        onOpenItem(item)
                    }
                },
                onRowLongClick = {
                    if (isSelectable) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleSelectItem(item)
                    }
                },
                onLeftGutterSweepStart = { localInIconY ->
                    if (selectionModeActive && isSelectable) {
                        val rowOffset = lazyListState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == item.key }?.offset ?: 0
                        val listLocalY = rowOffset + localInIconY
                        val idx = updatedIndexByKey[item.key] ?: -1
                        if (idx >= 0) {
                            dragAnchorIndex = idx
                            lastHoveredIndex = idx
                            preDragSelectedSnapshot = currentSelectedKeys
                            applySweepAtLocalY(listLocalY)
                        }
                    }
                },
                onLeftGutterSweepMove = { localInIconY ->
                    if (dragAnchorIndex >= 0) {
                        val rowOffset = lazyListState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == item.key }?.offset ?: 0
                        val listLocalY = rowOffset + localInIconY
                        updateEdgeAutoScroll(listLocalY)
                        applySweepAtLocalY(listLocalY)
                    }
                },
                onLeftGutterSweepEnd = { finishSweepGesture() },
                onOpenAsArchive = { onOpenAsArchive(item) },
                onCopy = { onCopySingle(item) },
                onCut = { onCutSingle(item) },
                onRename = { onRenameItem(item) },
                onToggleStar = { onToggleStar(item) },
                onShare = { onShareItem(item) },
                onTrash = { onTrashItem(item) },
                onEnqueueAudio = { onEnqueueAudio(item) },
                onRestoreFromTrash = { onRestoreFromTrash(item) },
                onDeleteForever = { onDeleteForever(item) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmartCoastalFileRow(
    item: BeachFileItem,
    isSelected: Boolean,
    selectionModeActive: Boolean,
    isSelectable: Boolean,
    isTrashMode: Boolean,
    rowTestTag: String,
    onRowClick: () -> Unit,
    onRowLongClick: () -> Unit,
    onLeftGutterSweepStart: (Float) -> Unit,
    onLeftGutterSweepMove: (Float) -> Unit,
    onLeftGutterSweepEnd: () -> Unit,
    onOpenAsArchive: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onToggleStar: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit,
    onEnqueueAudio: () -> Unit,
    onRestoreFromTrash: () -> Unit,
    onDeleteForever: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val needsAccessPrompt = item.requiresSafGrantDocId != null
    val canInspectArchive = remember(item.name, item.isDirectory) {
        !item.isDirectory && SupportedArchiveFormat.detectInspectableArchive(item.name) != null
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 3.dp else 1.dp),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onRowClick,
                onLongClick = onRowLongClick
            )
            .testTag(rowTestTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left icon / quick sweep gutter when in selection mode
            Box(
                modifier = if (selectionModeActive && isSelectable) {
                    Modifier.pointerInput(item.key, selectionModeActive) {
                        detectVerticalDragGestures(
                            onDragStart = { offset -> onLeftGutterSweepStart(offset.y) },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                onLeftGutterSweepMove(change.position.y)
                            },
                            onDragEnd = { onLeftGutterSweepEnd() },
                            onDragCancel = { onLeftGutterSweepEnd() }
                        )
                    }
                } else {
                    Modifier
                }
            ) {
                SmartFileIcon(
                    item = item,
                    size = 48.dp,
                    isSelected = isSelected
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.isBackedUp) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Outlined.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                val tapToMountText = stringResource(R.string.device_tap_to_mount)
                val subtitle = remember(
                    needsAccessPrompt,
                    tapToMountText,
                    item.isDirectory,
                    item.isSafDocument,
                    item.childCount,
                    item.extensionLabel,
                    item.sizeBytes,
                    item.lastModifiedMillis
                ) {
                    when {
                        needsAccessPrompt -> tapToMountText
                        item.isDirectory && (item.isSafDocument || item.childCount < 0) ->
                            "Папка • ${formatCoastalDate(item.lastModifiedMillis)}"
                        item.isDirectory ->
                            "${item.childCount} елемент(ів) • ${formatCoastalDate(item.lastModifiedMillis)}"
                        item.extensionLabel.isNotEmpty() ->
                            "${item.extensionLabel} • ${formatFileSize(item.sizeBytes)} • ${formatCoastalDate(item.lastModifiedMillis)}"
                        else ->
                            "${formatFileSize(item.sizeBytes)} • ${formatCoastalDate(item.lastModifiedMillis)}"
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (needsAccessPrompt) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (needsAccessPrompt) {
                IconButton(
                    onClick = onRowClick,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.LockOpen,
                        contentDescription = stringResource(R.string.device_tap_to_mount),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (isTrashMode) {
                IconButton(
                    onClick = onRestoreFromTrash,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("restore_trash_${item.name}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RestoreFromTrash,
                        contentDescription = stringResource(R.string.action_restore_from_trash),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onDeleteForever,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("delete_forever_${item.name}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteForever,
                        contentDescription = stringResource(R.string.action_delete_forever),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else if (!item.key.startsWith("virtual:") && !selectionModeActive) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.cd_more_menu)
                        )
                    }
                    if (menuExpanded) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_open)) },
                                leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                                onClick = { menuExpanded = false; onRowClick() }
                            )
                            if (canInspectArchive) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.archive_open_as_archive)) },
                                    leadingIcon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
                                    onClick = { menuExpanded = false; onOpenAsArchive() }
                                )
                            }
                            if (!item.isDirectory && item.category == FileCategory.AUDIO) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_enqueue_audio)) },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null)
                                    },
                                    onClick = { menuExpanded = false; onEnqueueAudio() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_copy)) },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                onClick = { menuExpanded = false; onCopy() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_cut)) },
                                leadingIcon = { Icon(Icons.Outlined.ContentCut, contentDescription = null) },
                                onClick = { menuExpanded = false; onCut() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rename)) },
                                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                                onClick = { menuExpanded = false; onRename() }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (item.isStarred) R.string.action_unstar else R.string.action_star
                                        )
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (item.isStarred) Icons.Outlined.StarBorder else Icons.Outlined.Star,
                                        contentDescription = null
                                    )
                                },
                                onClick = { menuExpanded = false; onToggleStar() }
                            )
                            if (!item.isDirectory) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_share)) },
                                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                    onClick = { menuExpanded = false; onShare() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_move_trash)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = { menuExpanded = false; onTrash() }
                            )
                        }
                    }
                }
            }
        }
    }
}
