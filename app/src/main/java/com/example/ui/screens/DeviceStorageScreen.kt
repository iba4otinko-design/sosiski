package com.example.ui.screens

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BeachFileItem
import com.example.data.model.DeviceBreadcrumb
import com.example.data.model.FileClipboardState
import com.example.data.model.SortOption
import com.example.data.model.StorageStats
import com.example.ui.components.AllFilesAccessBannerCard
import com.example.ui.components.CompactSearchTriggerButton
import com.example.ui.components.CreateFolderDialog
import com.example.ui.components.CreateNoteDialog
import com.example.ui.components.ExpandableCompactSearchBar
import com.example.ui.components.RenameItemDialog
import com.example.ui.components.SelectableCoastalFileList
import com.example.ui.components.SelectionTopSummaryBar
import com.example.ui.components.TotalCommanderClipboardBanner

@Composable
fun DeviceStorageScreen(
    breadcrumbs: List<DeviceBreadcrumb>,
    items: List<BeachFileItem>,
    storageStats: StorageStats,
    hasAllFilesAccess: Boolean = false,
    searchQuery: String,
    sortOption: SortOption,
    canNavigateUp: Boolean,
    selectedKeys: Set<String>,
    selectedTotalBytes: Long,
    clipboard: FileClipboardState?,
    canPasteHere: Boolean,
    onRequestAllFilesAccess: () -> Unit = {},
    onJumpBreadcrumb: (Int) -> Unit,
    onNavigateUp: () -> Boolean,
    onOpenItem: (BeachFileItem) -> Unit,
    onOpenAsArchive: (BeachFileItem) -> Unit,
    onToggleSelectItem: (BeachFileItem) -> Unit,
    onSelectRange: (Set<String>) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onCopySingle: (BeachFileItem) -> Unit,
    onCutSingle: (BeachFileItem) -> Unit,
    onPasteHere: () -> Unit,
    onCopyClipboardAsTcText: () -> Unit,
    onClearClipboard: () -> Unit,
    onEnqueueAudio: (BeachFileItem) -> Unit,
    onSafFolderGranted: (Uri) -> Unit,
    onSearchChange: (String) -> Unit,
    onSortChange: (SortOption) -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateNote: (String, String) -> Unit,
    onImportFiles: (List<Uri>) -> Unit,
    onRenameItem: (BeachFileItem, String) -> Unit,
    onToggleStar: (BeachFileItem) -> Unit,
    onShareItem: (BeachFileItem) -> Unit,
    onTrashItem: (BeachFileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = canNavigateUp && selectedKeys.isEmpty()) {
        onNavigateUp()
    }
    BackHandler(enabled = selectedKeys.isNotEmpty()) {
        onClearSelection()
    }

    var searchExpanded by remember { mutableStateOf(searchQuery.isNotBlank()) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateNoteDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BeachFileItem?>(null) }

    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onSafFolderGranted(uri)
    }

    val importFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onImportFiles(uris)
    }

    fun launchSafWithDocIdHint(docId: String) {
        val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    docId
                )
            }.getOrNull()
        } else null
        safFolderLauncher.launch(initialUri)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("device_storage_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            if (!hasAllFilesAccess) {
                AllFilesAccessBannerCard(
                    onGrantClick = onRequestAllFilesAccess,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }

            // Slim Toolbar: Breadcrumbs + Magnifying Glass Search + Optional SAF Grant + Sort
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    breadcrumbs.forEachIndexed { index, crumb ->
                        val isLast = index == breadcrumbs.lastIndex
                        Surface(
                            color = if (isLast) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                            },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { onJumpBreadcrumb(index) }
                                .testTag("device_crumb_$index")
                        ) {
                            Text(
                                text = crumb.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isLast) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        if (!isLast) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (!hasAllFilesAccess) {
                    IconButton(
                        onClick = { launchSafWithDocIdHint("primary:") },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("device_grant_folder_chip")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = stringResource(R.string.device_grant_access_btn)
                        )
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
                    testTag = "device_search_toggle_btn"
                )

                Box {
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("device_sort_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Sort,
                            contentDescription = stringResource(R.string.sort_label)
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        DeviceSortMenuItem(R.string.sort_name_asc, sortOption == SortOption.NAME_ASC) {
                            onSortChange(SortOption.NAME_ASC); showSortMenu = false
                        }
                        DeviceSortMenuItem(R.string.sort_date_desc, sortOption == SortOption.DATE_DESC) {
                            onSortChange(SortOption.DATE_DESC); showSortMenu = false
                        }
                        DeviceSortMenuItem(R.string.sort_date_asc, sortOption == SortOption.DATE_ASC) {
                            onSortChange(SortOption.DATE_ASC); showSortMenu = false
                        }
                        DeviceSortMenuItem(R.string.sort_size_desc, sortOption == SortOption.SIZE_DESC) {
                            onSortChange(SortOption.SIZE_DESC); showSortMenu = false
                        }
                        DeviceSortMenuItem(R.string.sort_size_asc, sortOption == SortOption.SIZE_ASC) {
                            onSortChange(SortOption.SIZE_ASC); showSortMenu = false
                        }
                    }
                }
            }

            ExpandableCompactSearchBar(
                expanded = searchExpanded,
                query = searchQuery,
                onQueryChange = onSearchChange,
                onCloseSearch = { searchExpanded = false },
                inputTestTag = "device_search_input"
            )

            // Smart Selection Summary Bar + Total Commander Clipboard Banner
            SelectionTopSummaryBar(
                selectedCount = selectedKeys.size,
                selectedTotalBytes = selectedTotalBytes,
                onSelectAll = onSelectAll,
                onClearSelection = onClearSelection
            )

            TotalCommanderClipboardBanner(
                clipboard = clipboard,
                canPasteHere = canPasteHere,
                onPasteHere = onPasteHere,
                onCopyListAsText = onCopyClipboardAsTcText,
                onClearClipboard = onClearClipboard
            )

            // Internal Storage Directory & File List
            if (items.isEmpty()) {
                EmptyCoastalState(
                    title = stringResource(R.string.device_empty_folder),
                    subtitle = stringResource(R.string.device_empty_folder_sub),
                    modifier = Modifier.weight(1f)
                )
            } else {
                SelectableCoastalFileList(
                    items = items,
                    selectedKeys = selectedKeys,
                    listTestTag = "device_files_list",
                    rowTagPrefix = "device_item_",
                    onOpenItem = { item ->
                        val requiredSafId = item.requiresSafGrantDocId
                        if (requiredSafId != null) {
                            launchSafWithDocIdHint(requiredSafId)
                        } else {
                            onOpenItem(item)
                        }
                    },
                    onOpenAsArchive = onOpenAsArchive,
                    onToggleSelectItem = onToggleSelectItem,
                    onSelectRange = onSelectRange,
                    onCopySingle = onCopySingle,
                    onCutSingle = onCutSingle,
                    onRenameItem = { renameTarget = it },
                    onToggleStar = onToggleStar,
                    onShareItem = onShareItem,
                    onTrashItem = onTrashItem,
                    onEnqueueAudio = onEnqueueAudio,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }

        // Floating Action Button to add folder / file / import
        if (selectedKeys.isEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                FloatingActionButton(
                    onClick = { showAddMenu = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("device_add_fab")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.fab_add)
                    )
                }

                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_new_folder)) },
                        leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            showCreateFolderDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_new_note)) },
                        leadingIcon = { Icon(Icons.Outlined.NoteAdd, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            showCreateNoteDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_import_file)) },
                        leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            importFilesLauncher.launch(arrayOf("*/*"))
                        }
                    )
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { name ->
                showCreateFolderDialog = false
                onCreateFolder(name)
            }
        )
    }

    if (showCreateNoteDialog) {
        CreateNoteDialog(
            onDismiss = { showCreateNoteDialog = false },
            onConfirm = { fileName, body ->
                showCreateNoteDialog = false
                onCreateNote(fileName, body)
            }
        )
    }

    renameTarget?.let { target ->
        RenameItemDialog(
            initialName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { updated ->
                renameTarget = null
                onRenameItem(target, updated)
            }
        )
    }
}

@Composable
private fun DeviceSortMenuItem(
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(labelRes),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        },
        onClick = onClick
    )
}
