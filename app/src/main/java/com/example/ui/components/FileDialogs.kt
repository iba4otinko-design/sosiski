package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.FolderZip
import com.example.R
import com.example.data.model.BeachFileItem
import com.example.data.model.SupportedArchiveFormat
import com.example.data.model.formatCoastalDate
import com.example.data.model.formatFileSize
import com.example.ui.icons.SmartFileIcon

@Composable
fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_folder_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.dialog_folder_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_folder_input")
            )
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("create_folder_confirm_btn")
            ) {
                Text(stringResource(R.string.btn_save))
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
fun CreateNoteDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var fileName by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(stringResource(R.string.dialog_note_name_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_note_name_input")
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.dialog_note_body_hint)) },
                    minLines = 4,
                    maxLines = 7,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_note_body_input")
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onConfirm(fileName, content) },
                modifier = Modifier.testTag("create_note_confirm_btn")
            ) {
                Text(stringResource(R.string.btn_save))
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
fun RenameItemDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename_title)) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename_item_input")
            )
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { if (newName.isNotBlank()) onConfirm(newName) },
                enabled = newName.isNotBlank(),
                modifier = Modifier.testTag("rename_confirm_btn")
            ) {
                Text(stringResource(R.string.btn_save))
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
fun FilePreviewModal(
    item: BeachFileItem,
    textContent: String,
    onDismiss: () -> Unit,
    onToggleStar: () -> Unit,
    onShare: () -> Unit,
    onMoveToTrash: () -> Unit,
    onOpenAsArchive: (() -> Unit)? = null,
    onOpenExternally: (() -> Unit)? = null
) {
    val canInspectArchive = remember(item.name, item.isDirectory) {
        !item.isDirectory && SupportedArchiveFormat.detectInspectableArchive(item.name) != null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SmartFileIcon(item = item, size = 52.dp) },
        title = {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${formatFileSize(item.sizeBytes)} • ${formatCoastalDate(item.createdAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.mimeType,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                if (!item.isDirectory && onOpenExternally != null) {
                    FilledTonalButton(
                        onClick = onOpenExternally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("preview_open_externally_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.action_open_externally))
                    }
                }

                if (canInspectArchive && onOpenAsArchive != null) {
                    OutlinedButton(
                        onClick = onOpenAsArchive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("preview_open_as_archive_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderZip,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.archive_open_as_archive))
                    }
                }

                if (textContent.isNotBlank()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        Text(
                            text = textContent,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onToggleStar,
                        modifier = Modifier.weight(1f).testTag("preview_star_btn")
                    ) {
                        Icon(
                            imageVector = if (item.isStarred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            contentDescription = null
                        )
                    }
                    OutlinedButton(
                        onClick = onShare,
                        modifier = Modifier.weight(1f).testTag("preview_share_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.action_share)
                        )
                    }
                    OutlinedButton(
                        onClick = onMoveToTrash,
                        modifier = Modifier.weight(1f).testTag("preview_trash_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.action_move_trash),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("preview_close_btn")
            ) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}
