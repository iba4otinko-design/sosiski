package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ArchiveEntryItem
import com.example.data.model.ConflictDecision
import com.example.data.model.ConflictResolution
import com.example.data.model.FileOperationProgress
import com.example.data.model.MoveDestinationOption
import com.example.data.model.PasteConflictPrompt
import com.example.data.model.SupportedArchiveFormat
import com.example.data.model.VirtualArchiveSession
import com.example.data.model.formatCoastalDate
import com.example.data.model.formatFileSize
import com.example.ui.icons.SmartArchiveEntryIcon

/**
 * Virtual Archive Browser Sheet allowing users to browse inside .zip, .7z, .tar, .tar.gz, and .gz
 * like a real folder, preview individual files without extracting the whole archive,
 * extract selected entries or the entire archive, and add new files to .zip archives.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VirtualArchiveBrowserSheet(
    session: VirtualArchiveSession,
    destinationOptions: List<MoveDestinationOption>,
    onNavigateIntoDir: (String) -> Unit,
    onNavigateUpDir: () -> Unit,
    onPreviewEntry: (ArchiveEntryItem) -> Unit,
    onToggleSelectEntry: (ArchiveEntryItem) -> Unit,
    onSelectAllCurrentDir: () -> Unit,
    onClearEntrySelection: () -> Unit,
    onExtractArchive: (MoveDestinationOption, Boolean) -> Unit,
    onAddFilesToZip: (List<Uri>) -> Unit,
    onClose: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showExtractDestPicker by remember { mutableStateOf<Boolean?>(null) } // true = selectedOnly, false = all

    val addFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onAddFilesToZip(uris)
    }

    BackHandler(enabled = session.canNavigateUp) {
        onNavigateUpDir()
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        modifier = Modifier.testTag("virtual_archive_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 380.dp, max = 680.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (session.canNavigateUp) {
                        IconButton(
                            onClick = onNavigateUpDir,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("archive_up_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.btn_back)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.archiveItem.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.archive_viewer_subtitle, session.format.label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("archive_close_btn")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.btn_close)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Internal path breadcrumb trail inside the archive
            val segments = remember(session.currentDirPath) {
                if (session.currentDirPath.isBlank()) emptyList() else session.currentDirPath.split('/')
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (segments.isEmpty()) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.clickable { onNavigateIntoDir("") }
                ) {
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                segments.forEachIndexed { idx, seg ->
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    val targetPath = segments.take(idx + 1).joinToString("/")
                    val isLast = idx == segments.lastIndex
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isLast) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.clickable { onNavigateIntoDir(targetPath) }
                    ) {
                        Text(
                            text = seg,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Archive Actions Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { showExtractDestPicker = false },
                    modifier = Modifier.testTag("archive_extract_all_btn")
                ) {
                    Icon(Icons.Outlined.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.archive_extract_all))
                }

                if (session.selectedEntryPaths.isNotEmpty()) {
                    Button(
                        onClick = { showExtractDestPicker = true },
                        modifier = Modifier.testTag("archive_extract_selected_btn")
                    ) {
                        Icon(Icons.Outlined.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.archive_extract_selected, session.selectedEntryPaths.size))
                    }
                    TextButton(onClick = onClearEntrySelection) {
                        Text(stringResource(R.string.sel_clear_all))
                    }
                } else if (session.entriesInCurrentDir.isNotEmpty()) {
                    OutlinedButton(onClick = onSelectAllCurrentDir) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.sel_select_all))
                    }
                }

                if (session.format == SupportedArchiveFormat.ZIP) {
                    OutlinedButton(
                        onClick = { addFilesLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag("archive_add_to_zip_btn")
                    ) {
                        Icon(Icons.Outlined.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.archive_add_files))
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Entries list
            if (session.entriesInCurrentDir.isEmpty()) {
                Text(
                    text = stringResource(R.string.archive_empty_dir),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .testTag("virtual_archive_entries_list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(
                        items = session.entriesInCurrentDir,
                        key = { it.entryPath },
                        contentType = { if (it.isDirectory) "archive_dir_row" else "archive_file_row" }
                    ) { entry ->
                        val isSelected = entry.entryPath in session.selectedEntryPaths
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            ),
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (session.selectedEntryPaths.isNotEmpty()) {
                                            onToggleSelectEntry(entry)
                                        } else if (entry.isDirectory) {
                                            onNavigateIntoDir(entry.entryPath)
                                        } else {
                                            onPreviewEntry(entry)
                                        }
                                    },
                                    onLongClick = { onToggleSelectEntry(entry) }
                                )
                                .testTag("archive_entry_${entry.name}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SmartArchiveEntryIcon(
                                    entryName = entry.name,
                                    isDirectory = entry.isDirectory,
                                    isSelected = isSelected
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (entry.isDirectory) "${entry.name}/" else entry.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val detail = if (entry.isDirectory) {
                                        entry.entryPath
                                    } else {
                                        "${formatFileSize(entry.sizeBytes)} • ${formatCoastalDate(entry.lastModifiedMillis)}"
                                    }
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onToggleSelectEntry(entry) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    showExtractDestPicker?.let { selectedOnly ->
        DestinationPickerDialog(
            title = if (selectedOnly) {
                stringResource(R.string.archive_extract_selected, session.selectedEntryPaths.size)
            } else {
                stringResource(R.string.archive_extract_all)
            },
            destinations = destinationOptions,
            onDismiss = { showExtractDestPicker = null },
            onSelectDestination = { dest ->
                showExtractDestPicker = null
                onExtractArchive(dest, selectedOnly)
            }
        )
    }
}

/**
 * Dialog for creating a new ZIP / 7Z / TAR.GZ / TAR archive from selected items.
 */
@Composable
fun CreateArchiveDialog(
    defaultBaseName: String,
    destinationOptions: List<MoveDestinationOption>,
    onDismiss: () -> Unit,
    onConfirm: (baseName: String, format: SupportedArchiveFormat, destination: MoveDestinationOption) -> Unit
) {
    var baseName by remember(defaultBaseName) { mutableStateOf(defaultBaseName) }
    var selectedFormat by remember { mutableStateOf(SupportedArchiveFormat.ZIP) }
    var selectedDestIndex by remember { mutableIntStateOf(0) }
    val formats = remember { SupportedArchiveFormat.creatableFormats() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.archive_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = baseName,
                    onValueChange = { baseName = it },
                    label = { Text(stringResource(R.string.archive_name_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_archive_name_input")
                )

                Text(
                    text = stringResource(R.string.archive_format_label),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    formats.forEach { fmt ->
                        FilterChip(
                            selected = selectedFormat == fmt,
                            onClick = { selectedFormat = fmt },
                            label = { Text(fmt.label) },
                            modifier = Modifier.testTag("archive_fmt_${fmt.name}")
                        )
                    }
                }

                if (destinationOptions.isNotEmpty()) {
                    Text(
                        text = "Папка збереження:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        destinationOptions.take(4).forEachIndexed { idx, opt ->
                            FilterChip(
                                selected = selectedDestIndex == idx,
                                onClick = { selectedDestIndex = idx },
                                label = { Text(opt.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val dest = destinationOptions.getOrNull(selectedDestIndex) ?: return@FilledTonalButton
                    if (baseName.isNotBlank()) {
                        onConfirm(baseName.trim(), selectedFormat, dest)
                    }
                },
                enabled = baseName.isNotBlank() && destinationOptions.isNotEmpty(),
                modifier = Modifier.testTag("create_archive_confirm_btn")
            ) {
                Text(stringResource(R.string.archive_create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
fun DestinationPickerDialog(
    title: String,
    destinations: List<MoveDestinationOption>,
    onDismiss: () -> Unit,
    onSelectDestination: (MoveDestinationOption) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                items(destinations, key = { it.id }) { opt ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDestination(opt) }
                            .testTag("dest_opt_${opt.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = opt.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = opt.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

/**
 * Confirmation dialog before permanent deletion (also offering safe Move to Trash for local items).
 */
@Composable
fun BatchDeleteConfirmationDialog(
    itemCount: Int,
    totalBytes: Long,
    canMoveToTrash: Boolean,
    onDismiss: () -> Unit,
    onMoveToTrash: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.dialog_delete_confirm_body,
                    itemCount,
                    formatFileSize(totalBytes)
                )
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canMoveToTrash) {
                    FilledTonalButton(
                        onClick = onMoveToTrash,
                        modifier = Modifier.testTag("confirm_move_to_trash_btn")
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.drawer_cat_trash))
                    }
                }
                Button(
                    onClick = onDeletePermanently,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_forever_btn")
                ) {
                    Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_delete_forever))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

/**
 * Conflict resolution dialog shown when Paste/Move/Extract encounters an existing file or folder.
 */
@Composable
fun PasteConflictDialog(
    prompt: PasteConflictPrompt,
    onDecision: (ConflictDecision) -> Unit
) {
    var applyToAll by remember(prompt.requestId) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onDecision(ConflictDecision(ConflictResolution.CANCEL_ALL, false)) },
        title = { Text(stringResource(R.string.conflict_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.conflict_dialog_body, prompt.conflictingName))
                Text(
                    text = prompt.destinationDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { applyToAll = !applyToAll }
                ) {
                    Checkbox(
                        checked = applyToAll,
                        onCheckedChange = { applyToAll = it }
                    )
                    Text(
                        text = stringResource(R.string.conflict_apply_all),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalButton(
                        onClick = { onDecision(ConflictDecision(ConflictResolution.RENAME, applyToAll)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("conflict_rename_btn")
                    ) {
                        Text(stringResource(R.string.conflict_rename_auto))
                    }
                    OutlinedButton(
                        onClick = { onDecision(ConflictDecision(ConflictResolution.OVERWRITE, applyToAll)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("conflict_overwrite_btn")
                    ) {
                        Text(stringResource(R.string.conflict_overwrite))
                    }
                    OutlinedButton(
                        onClick = { onDecision(ConflictDecision(ConflictResolution.SKIP, applyToAll)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("conflict_skip_btn")
                    ) {
                        Text(stringResource(R.string.conflict_skip))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = { onDecision(ConflictDecision(ConflictResolution.CANCEL_ALL, false)) }
            ) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

/**
 * Progress & cancellation modal for long-running Copy/Move/Archive/Extract operations.
 */
@Composable
fun FileOperationProgressDialog(
    progress: FileOperationProgress,
    onCancel: () -> Unit
) {
    if (!progress.isActive) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(progress.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (progress.totalItems > 0) {
                    val ratio = (progress.processedItems.toFloat() / progress.totalItems.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${progress.processedItems} / ${progress.totalItems}",
                        style = MaterialTheme.typography.labelMedium
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (progress.currentFileName.isNotBlank()) {
                    Text(
                        text = progress.currentFileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            if (progress.canCancel) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("operation_cancel_btn")
                ) {
                    Text(stringResource(R.string.progress_cancel_btn))
                }
            }
        }
    )
}
