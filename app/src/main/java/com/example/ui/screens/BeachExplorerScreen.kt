package com.example.ui.screens

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.R
import com.example.data.local.ConnectedFolderEntity
import com.example.data.model.BeachFileItem
import com.example.data.model.ExplorerViewMode
import com.example.data.model.FileCategory
import com.example.data.model.FileClipboardState
import com.example.data.model.SortOption
import com.example.data.model.StorageRoot
import com.example.data.model.SupportedArchiveFormat
import com.example.ui.components.AllFilesAccessBannerCard
import com.example.ui.components.CompactSearchTriggerButton
import com.example.ui.components.CreateFolderDialog
import com.example.ui.components.CreateNoteDialog
import com.example.ui.components.ExpandableCompactSearchBar
import com.example.ui.components.RenameItemDialog
import com.example.ui.components.SandyFileIcon
import com.example.ui.components.SelectableCoastalFileList
import com.example.ui.components.SelectionTopSummaryBar
import com.example.ui.components.TideWashOverlay
import com.example.ui.components.TotalCommanderClipboardBanner
import com.example.ui.icons.SmartFileIcon
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun BeachExplorerScreen(
    items: List<BeachFileItem>,
    activeRoot: StorageRoot,
    hasAllFilesAccess: Boolean = false,
    connectedFolders: List<ConnectedFolderEntity>,
    canNavigateUp: Boolean,
    currentPathOrUri: String,
    viewMode: ExplorerViewMode,
    searchQuery: String,
    selectedCategory: FileCategory?,
    sortOption: SortOption,
    tideWaveTrigger: Int,
    selectedKeys: Set<String>,
    selectedTotalBytes: Long,
    clipboard: FileClipboardState?,
    canPasteHere: Boolean,
    onRequestAllFilesAccess: () -> Unit = {},
    onSelectRoot: (StorageRoot) -> Unit,
    onConnectSafDirectory: (Uri) -> Unit,
    onNavigateUp: () -> Boolean,
    onSetViewMode: (ExplorerViewMode) -> Unit,
    onSearchChange: (String) -> Unit,
    onCategorySelect: (FileCategory?) -> Unit,
    onSortChange: (SortOption) -> Unit,
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
    onSandDragEnd: (BeachFileItem, Float, Float) -> Unit,
    onTriggerTideArrange: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateNote: (String, String) -> Unit,
    onImportUris: (List<Uri>, Boolean) -> Unit,
    onRenameItem: (BeachFileItem, String) -> Unit,
    onToggleStar: (BeachFileItem) -> Unit,
    onShareItem: (BeachFileItem) -> Unit,
    onMoveToTrash: (BeachFileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = canNavigateUp && selectedKeys.isEmpty()) {
        onNavigateUp()
    }
    BackHandler(enabled = selectedKeys.isNotEmpty()) {
        onClearSelection()
    }

    var showAddMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateNoteDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BeachFileItem?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(searchQuery.isNotBlank()) }

    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onConnectSafDirectory(uri)
    }

    val importFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onImportUris(uris, false)
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) onImportUris(uris, false)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            // 1. Storage Source Selector Row (Internal Storage + Download + Beaver Cove + Android Folder + SAF)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = activeRoot is StorageRoot.InternalShared,
                    onClick = { onSelectRoot(StorageRoot.InternalShared()) },
                    label = { Text(stringResource(R.string.source_internal_shared)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Smartphone,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.testTag("root_internal_shared_chip")
                )

                FilterChip(
                    selected = activeRoot is StorageRoot.PublicDownloads,
                    onClick = { onSelectRoot(StorageRoot.PublicDownloads()) },
                    label = { Text(stringResource(R.string.source_public_downloads)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.testTag("root_public_downloads_chip")
                )

                FilterChip(
                    selected = activeRoot is StorageRoot.BeaverCove,
                    onClick = { onSelectRoot(StorageRoot.BeaverCove()) },
                    label = { Text(stringResource(R.string.source_beaver_cove)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.BeachAccess,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.testTag("root_beaver_cove_chip")
                )

                FilterChip(
                    selected = activeRoot is StorageRoot.AndroidAppFolder,
                    onClick = { onSelectRoot(StorageRoot.AndroidAppFolder()) },
                    label = { Text(stringResource(R.string.source_android_folder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Android,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.testTag("root_android_folder_chip")
                )

                connectedFolders.forEach { folder ->
                    val isSelected = (activeRoot as? StorageRoot.SafTree)?.treeUriString == folder.treeUri
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectRoot(StorageRoot.SafTree(folder.treeUri, folder.displayName)) },
                        label = { Text(folder.displayName) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }

                AssistChip(
                    onClick = {
                        val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            runCatching {
                                DocumentsContract.buildDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:Android/media"
                                )
                            }.getOrNull()
                        } else null
                        safFolderLauncher.launch(initialUri)
                    },
                    label = { Text(stringResource(R.string.source_connect_folder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.testTag("connect_saf_folder_chip")
                )
            }

            if (
                !hasAllFilesAccess &&
                (activeRoot is StorageRoot.InternalShared || activeRoot is StorageRoot.PublicDownloads)
            ) {
                AllFilesAccessBannerCard(
                    onGrantClick = onRequestAllFilesAccess,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (activeRoot is StorageRoot.AndroidAppFolder) {
                Text(
                    text = stringResource(R.string.android_folder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 2. Slim Toolbar: Back/Chips + Magnifying Glass Search + Sort + View Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canNavigateUp) {
                        AssistChip(
                            onClick = { onNavigateUp() },
                            label = {
                                val folderTitle = currentPathOrUri.substringAfterLast('/').substringAfterLast(':')
                                Text("← $folderTitle", maxLines = 1)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = stringResource(R.string.btn_up_folder),
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("navigate_up_folder_chip")
                        )
                    }

                    if (viewMode == ExplorerViewMode.SANDBOX && items.isNotEmpty()) {
                        AssistChip(
                            onClick = onTriggerTideArrange,
                            label = { Text(stringResource(R.string.btn_tide_arrange)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Waves,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("tide_arrange_button")
                        )
                    }

                    CategoryChipsRow(selectedCategory = selectedCategory, onSelect = onCategorySelect)
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
                    testTag = "explorer_search_toggle_btn"
                )

                Box {
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("explorer_sort_button")
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
                        SortMenuOption(R.string.sort_date_desc, sortOption == SortOption.DATE_DESC) {
                            onSortChange(SortOption.DATE_DESC); showSortMenu = false
                        }
                        SortMenuOption(R.string.sort_date_asc, sortOption == SortOption.DATE_ASC) {
                            onSortChange(SortOption.DATE_ASC); showSortMenu = false
                        }
                        SortMenuOption(R.string.sort_size_desc, sortOption == SortOption.SIZE_DESC) {
                            onSortChange(SortOption.SIZE_DESC); showSortMenu = false
                        }
                        SortMenuOption(R.string.sort_size_asc, sortOption == SortOption.SIZE_ASC) {
                            onSortChange(SortOption.SIZE_ASC); showSortMenu = false
                        }
                        SortMenuOption(R.string.sort_name_asc, sortOption == SortOption.NAME_ASC) {
                            onSortChange(SortOption.NAME_ASC); showSortMenu = false
                        }
                        SortMenuOption(R.string.sort_category, sortOption == SortOption.CATEGORY) {
                            onSortChange(SortOption.CATEGORY); showSortMenu = false
                        }
                    }
                }

                IconButton(
                    onClick = {
                        onSetViewMode(
                            if (viewMode == ExplorerViewMode.SANDBOX) ExplorerViewMode.LIST
                            else ExplorerViewMode.SANDBOX
                        )
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .testTag("toggle_view_mode_button")
                ) {
                    Icon(
                        imageVector = if (viewMode == ExplorerViewMode.SANDBOX) {
                            Icons.AutoMirrored.Outlined.ViewList
                        } else {
                            Icons.Outlined.GridView
                        },
                        contentDescription = stringResource(
                            if (viewMode == ExplorerViewMode.SANDBOX) R.string.mode_list else R.string.mode_sandbox
                        ),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            ExpandableCompactSearchBar(
                expanded = searchExpanded,
                query = searchQuery,
                onQueryChange = onSearchChange,
                onCloseSearch = { searchExpanded = false },
                inputTestTag = "explorer_search_input"
            )

            // 3b. Smart Selection Summary Bar + Persistent Total Commander Clipboard Banner
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

            // 4. Main Content Area (Interactive Sand Sandbox OR Smart Multi-Select List)
            AnimatedContent(
                targetState = viewMode,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "explorer_mode_switch"
            ) { mode ->
                when (mode) {
                    ExplorerViewMode.SANDBOX -> {
                        BeachSandboxCanvas(
                            items = items,
                            selectedKeys = selectedKeys,
                            tideWaveTrigger = tideWaveTrigger,
                            onOpenItem = onOpenItem,
                            onOpenAsArchive = onOpenAsArchive,
                            onToggleSelectItem = onToggleSelectItem,
                            onCopySingle = onCopySingle,
                            onCutSingle = onCutSingle,
                            onEnqueueAudio = onEnqueueAudio,
                            onDragEnd = onSandDragEnd,
                            onRename = { renameTarget = it },
                            onToggleStar = onToggleStar,
                            onShare = onShareItem,
                            onTrash = onMoveToTrash,
                            onSwitchToListMode = { onSetViewMode(ExplorerViewMode.LIST) },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 12.dp)
                        )
                    }
                    ExplorerViewMode.LIST -> {
                        if (items.isEmpty()) {
                            EmptyCoastalState(
                                title = stringResource(R.string.empty_folder_title),
                                subtitle = stringResource(R.string.empty_folder_subtitle)
                            )
                        } else {
                            SelectableCoastalFileList(
                                items = items,
                                selectedKeys = selectedKeys,
                                listTestTag = "explorer_file_list",
                                rowTagPrefix = "list_item_",
                                headerContent = null,
                                onOpenItem = onOpenItem,
                                onOpenAsArchive = onOpenAsArchive,
                                onToggleSelectItem = onToggleSelectItem,
                                onSelectRange = onSelectRange,
                                onCopySingle = onCopySingle,
                                onCutSingle = onCutSingle,
                                onRenameItem = { renameTarget = it },
                                onToggleStar = onToggleStar,
                                onShareItem = onShareItem,
                                onTrashItem = onMoveToTrash,
                                onEnqueueAudio = onEnqueueAudio,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        // Floating Action Button + Menu (hidden when multi-selection action bar is active for clean focus)
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
                    modifier = Modifier.testTag("explorer_add_fab")
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
                        },
                        modifier = Modifier.testTag("menu_create_folder")
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_new_note)) },
                        leadingIcon = { Icon(Icons.Outlined.NoteAdd, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            showCreateNoteDialog = true
                        },
                        modifier = Modifier.testTag("menu_create_note")
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_import_photo)) },
                        leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        modifier = Modifier.testTag("menu_import_photo")
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_import_file)) },
                        leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            importFilesLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.testTag("menu_import_file")
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
private fun CoastalHeroBannerCard(
    title: String,
    itemCount: Int
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_hero_beaver_beach),
                contentDescription = stringResource(R.string.cd_hero_banner),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.56f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(16.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        text = "$itemCount об'єктів на березі",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryChipsRow(
    selectedCategory: FileCategory?,
    onSelect: (FileCategory?) -> Unit
) {
    val entries = listOf(
        FileCategory.FOLDER to R.string.cat_folder,
        FileCategory.IMAGES to R.string.cat_images,
        FileCategory.VIDEO to R.string.cat_video,
        FileCategory.DOCUMENTS to R.string.cat_documents,
        FileCategory.AUDIO to R.string.cat_audio,
        FileCategory.ARCHIVES_ANDROID to R.string.cat_archives
    )
    entries.forEach { (cat, labelRes) ->
        FilterChip(
            selected = selectedCategory == cat,
            onClick = { onSelect(cat) },
            label = { Text(stringResource(labelRes)) }
        )
    }
}

@Composable
private fun SortMenuOption(
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BeachSandboxCanvas(
    items: List<BeachFileItem>,
    selectedKeys: Set<String>,
    tideWaveTrigger: Int,
    onOpenItem: (BeachFileItem) -> Unit,
    onOpenAsArchive: (BeachFileItem) -> Unit,
    onToggleSelectItem: (BeachFileItem) -> Unit,
    onCopySingle: (BeachFileItem) -> Unit,
    onCutSingle: (BeachFileItem) -> Unit,
    onEnqueueAudio: (BeachFileItem) -> Unit,
    onDragEnd: (BeachFileItem, Float, Float) -> Unit,
    onRename: (BeachFileItem) -> Unit,
    onToggleStar: (BeachFileItem) -> Unit,
    onShare: (BeachFileItem) -> Unit,
    onTrash: (BeachFileItem) -> Unit,
    onSwitchToListMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.25f

    val sandTop = if (isDark) Color(0xFF172836) else Color(0xFFF6EBD7)
    val sandBottom = if (isDark) Color(0xFF1E3242) else Color(0xFFEBD9B9)
    val duneStroke = if (isDark) Color(0xFF2B4559) else Color(0xFFDFC79F)
    val trailColor = if (isDark) Color(0xFF58D6E6).copy(alpha = 0.45f) else Color(0xFFB59467).copy(alpha = 0.55f)

    val sandTrailPoints = remember { mutableStateListOf<Offset>() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var activeDragNorm by remember { mutableStateOf<Offset?>(null) }
    val selectionModeActive = selectedKeys.isNotEmpty()

    Surface(
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 2.dp,
        modifier = modifier.testTag("beach_sandbox_canvas")
    ) {
        if (items.isEmpty()) {
            EmptyCoastalState(
                title = stringResource(R.string.empty_folder_title),
                subtitle = stringResource(R.string.empty_folder_subtitle)
            )
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(sandTop, sandBottom)))
            ) {
                val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                val tileHalfPx = with(density) { 46.dp.toPx() }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    for (band in 1..4) {
                        val baseY = size.height * (band * 0.2f)
                        val dunePath = Path().apply {
                            moveTo(0f, baseY)
                            var x = 0f
                            while (x <= size.width + 24f) {
                                val y = baseY + sin(x / size.width * 2.4f * PI + band).toFloat() * 12f
                                lineTo(x, y)
                                x += 24f
                            }
                        }
                        drawPath(
                            path = dunePath,
                            color = duneStroke.copy(alpha = 0.55f),
                            style = Stroke(width = 3f, cap = StrokeCap.Round)
                        )
                    }

                    if (sandTrailPoints.size > 1) {
                        val trailPath = Path().apply {
                            moveTo(sandTrailPoints.first().x, sandTrailPoints.first().y)
                            for (i in 1 until sandTrailPoints.size) {
                                lineTo(sandTrailPoints[i].x, sandTrailPoints[i].y)
                            }
                        }
                        drawPath(
                            path = trailPath,
                            color = trailColor,
                            style = Stroke(width = 18f, cap = StrokeCap.Round)
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp, start = 12.dp, end = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sandbox_drag_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                items.take(28).forEach { item ->
                    val isBeingDragged = draggingKey == item.key
                    val isSelected = item.key in selectedKeys
                    var localNormX by remember(item.key, item.sandX) { mutableFloatStateOf(item.sandX) }
                    var localNormY by remember(item.key, item.sandY) { mutableFloatStateOf(item.sandY) }
                    var showItemMenu by remember { mutableStateOf(false) }

                    val isDropTargetHovered = item.isDirectory &&
                        draggingKey != null &&
                        draggingKey != item.key &&
                        activeDragNorm?.let { dragPt ->
                            hypot((item.sandX - dragPt.x).toDouble(), (item.sandY - dragPt.y).toDouble()) < 0.13
                        } == true

                    val scale by animateFloatAsState(
                        targetValue = when {
                            isBeingDragged -> 1.16f
                            isDropTargetHovered -> 1.14f
                            else -> 1f
                        },
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "sand_tile_scale"
                    )

                    val centerXPx = (localNormX * widthPx).coerceIn(tileHalfPx, (widthPx - tileHalfPx).coerceAtLeast(tileHalfPx))
                    val centerYPx = (localNormY * heightPx).coerceIn(tileHalfPx * 1.15f, (heightPx - tileHalfPx).coerceAtLeast(tileHalfPx * 1.15f))

                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    x = (centerXPx - tileHalfPx).roundToInt(),
                                    y = (centerYPx - tileHalfPx).roundToInt()
                                )
                            }
                            .zIndex(if (isBeingDragged) 10f else 1f)
                            .scale(scale)
                            .width(92.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                when {
                                    isDropTargetHovered -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                                    isSelected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f)
                                    isBeingDragged -> MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                    else -> Color.Transparent
                                }
                            )
                            .combinedClickable(
                                onClick = {
                                    if (selectionModeActive) {
                                        onToggleSelectItem(item)
                                    } else {
                                        onOpenItem(item)
                                    }
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showItemMenu = true
                                }
                            )
                            .pointerInput(item.key, widthPx, heightPx) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingKey = item.key
                                        sandTrailPoints.clear()
                                        sandTrailPoints.add(Offset(localNormX * widthPx, localNormY * heightPx))
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        localNormX = (localNormX + dragAmount.x / widthPx).coerceIn(0.08f, 0.92f)
                                        localNormY = (localNormY + dragAmount.y / heightPx).coerceIn(0.12f, 0.88f)
                                        val pxPt = Offset(localNormX * widthPx, localNormY * heightPx)
                                        activeDragNorm = Offset(localNormX, localNormY)
                                        if (sandTrailPoints.size > 40) sandTrailPoints.removeAt(0)
                                        sandTrailPoints.add(pxPt)
                                    },
                                    onDragEnd = {
                                        draggingKey = null
                                        activeDragNorm = null
                                        onDragEnd(item, localNormX, localNormY)
                                    },
                                    onDragCancel = {
                                        draggingKey = null
                                        activeDragNorm = null
                                    }
                                )
                            }
                            .padding(vertical = 4.dp, horizontal = 4.dp)
                            .testTag("sandbox_item_${item.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            SmartFileIcon(
                                item = item,
                                size = 54.dp,
                                isSelected = isSelected
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        ItemContextMenu(
                            expanded = showItemMenu,
                            item = item,
                            onDismiss = { showItemMenu = false },
                            onOpen = { onOpenItem(item) },
                            onOpenAsArchive = { onOpenAsArchive(item) },
                            onSelect = { onToggleSelectItem(item) },
                            onCopy = { onCopySingle(item) },
                            onCut = { onCutSingle(item) },
                            onEnqueueAudio = { onEnqueueAudio(item) },
                            onRename = { onRename(item) },
                            onToggleStar = { onToggleStar(item) },
                            onShare = { onShare(item) },
                            onTrash = { onTrash(item) }
                        )
                    }
                }

                if (items.size > 28) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(50),
                        tonalElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp, start = 12.dp, end = 12.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable { onSwitchToListMode() }
                            .minimumInteractiveComponentSize()
                            .testTag("sandbox_more_items_pill")
                    ) {
                        Text(
                            text = stringResource(R.string.sandbox_more_items_fmt, 28, items.size),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                TideWashOverlay(trigger = tideWaveTrigger)
            }
        }
    }
}

@Composable
private fun ItemContextMenu(
    expanded: Boolean,
    item: BeachFileItem,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onOpenAsArchive: () -> Unit,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onEnqueueAudio: () -> Unit,
    onRename: () -> Unit,
    onToggleStar: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit
) {
    val canInspectArchive = !item.isDirectory && SupportedArchiveFormat.detectInspectableArchive(item.name) != null
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_open)) },
            leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
            onClick = { onDismiss(); onOpen() }
        )
        if (canInspectArchive) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.archive_open_as_archive)) },
                leadingIcon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
                onClick = { onDismiss(); onOpenAsArchive() }
            )
        }
        if (!item.isDirectory && item.category == FileCategory.AUDIO) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_enqueue_audio)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                onClick = { onDismiss(); onEnqueueAudio() }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sel_select_all)) },
            leadingIcon = { Icon(Icons.Outlined.CheckCircleOutline, contentDescription = null) },
            onClick = { onDismiss(); onSelect() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_copy)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            onClick = { onDismiss(); onCopy() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_cut)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCut, contentDescription = null) },
            onClick = { onDismiss(); onCut() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_rename)) },
            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
            onClick = { onDismiss(); onRename() }
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
            onClick = { onDismiss(); onToggleStar() }
        )
        if (!item.isDirectory) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                onClick = { onDismiss(); onShare() }
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
            onClick = { onDismiss(); onTrash() }
        )
    }
}

@Composable
fun EmptyCoastalState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SandyFileIcon(category = FileCategory.FOLDER, size = 72.dp)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
