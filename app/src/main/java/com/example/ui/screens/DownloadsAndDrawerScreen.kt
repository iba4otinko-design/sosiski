package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BeachFileItem
import com.example.data.model.DeviceBreadcrumb
import com.example.data.model.DrawerCategoryType
import com.example.data.model.DrawerSelectionMode
import com.example.data.model.ExternalDocumentProviderItem
import com.example.data.model.FileClipboardState
import com.example.data.model.SortOption
import com.example.ui.components.AllFilesAccessBannerCard
import com.example.ui.components.CompactSearchTriggerButton
import com.example.ui.components.CreateFolderDialog
import com.example.ui.components.CreateNoteDialog
import com.example.ui.components.ExpandableCompactSearchBar
import com.example.ui.components.RenameItemDialog
import com.example.ui.components.SandyFileIcon
import com.example.ui.components.SelectableCoastalFileList
import com.example.ui.components.SelectionTopSummaryBar
import com.example.ui.components.TotalCommanderClipboardBanner

/**
 * Custom contract that opens ACTION_OPEN_DOCUMENT pre-targeted at a specific DocumentsProvider's root URI.
 */
class OpenDocumentsAtProviderContract : ActivityResultContract<Uri?, List<Uri>>() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            if (input != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
            }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val result = mutableListOf<Uri>()
        intent.data?.let { result.add(it) }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uri ->
                    if (uri !in result) result.add(uri)
                }
            }
        }
        return result
    }
}

fun buildProviderRootsInitialUri(authority: String): Uri? {
    if (authority.isBlank()) return null
    return runCatching { DocumentsContract.buildRootsUri(authority) }.getOrNull()
}

@Composable
fun DownloadsScreen(
    breadcrumbs: List<DeviceBreadcrumb>,
    items: List<BeachFileItem>,
    isSystemDownloadsMounted: Boolean,
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
    onSafDownloadsGranted: (Uri) -> Unit,
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

    val downloadsSafLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onSafDownloadsGranted(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onImportFiles(uris)
    }

    fun launchSystemDownloadsMount() {
        val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download"
                )
            }.getOrNull()
        } else null
        downloadsSafLauncher.launch(initialUri)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("downloads_screen")
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

            // Slim Toolbar: Breadcrumbs + Magnifying Glass Search + Optional SAF Mount + Sort
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (breadcrumbs.isNotEmpty()) {
                        BreadcrumbStrip(
                            breadcrumbs = breadcrumbs,
                            onJumpBreadcrumb = onJumpBreadcrumb
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.downloads_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (!isSystemDownloadsMounted && !hasAllFilesAccess) {
                    IconButton(
                        onClick = { launchSystemDownloadsMount() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("downloads_mount_chip")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = stringResource(R.string.downloads_mount_btn)
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
                    testTag = "downloads_search_toggle_btn"
                )

                SortMenuIconButton(
                    sortOption = sortOption,
                    showSortMenu = showSortMenu,
                    onShowSortMenuChange = { showSortMenu = it },
                    onSortChange = onSortChange,
                    sortTag = "downloads_sort_button"
                )
            }

            ExpandableCompactSearchBar(
                expanded = searchExpanded,
                query = searchQuery,
                onQueryChange = onSearchChange,
                onCloseSearch = { searchExpanded = false },
                inputTestTag = "downloads_search_input"
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

            // Downloads File List
            if (items.isEmpty()) {
                EmptyCoastalPlaceholder(
                    title = stringResource(R.string.downloads_empty_title),
                    subtitle = stringResource(R.string.downloads_empty_sub),
                    modifier = Modifier.weight(1f)
                )
            } else {
                SelectableCoastalFileList(
                    items = items,
                    selectedKeys = selectedKeys,
                    listTestTag = "downloads_items_list",
                    rowTagPrefix = "coastal_file_row_",
                    onOpenItem = onOpenItem,
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

        // FAB for creating/importing into Downloads
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
                    modifier = Modifier.testTag("downloads_add_fab")
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
                        text = { Text(stringResource(R.string.action_import_file)) },
                        leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            importLauncher.launch(arrayOf("*/*"))
                        }
                    )
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
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = {
                showCreateFolderDialog = false
                onCreateFolder(it)
            }
        )
    }

    if (showCreateNoteDialog) {
        CreateNoteDialog(
            onDismiss = { showCreateNoteDialog = false },
            onConfirm = { name, body ->
                showCreateNoteDialog = false
                onCreateNote(name, body)
            }
        )
    }

    renameTarget?.let { target ->
        RenameItemDialog(
            initialName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                onRenameItem(target, newName)
            }
        )
    }
}

@Composable
fun DrawerCollectionScreen(
    mode: DrawerSelectionMode,
    items: List<BeachFileItem>,
    searchQuery: String,
    sortOption: SortOption,
    canNavigateUpProvider: Boolean,
    selectedKeys: Set<String>,
    selectedTotalBytes: Long,
    clipboard: FileClipboardState?,
    canPasteHere: Boolean,
    onBackToTabs: () -> Unit,
    onNavigateUpProvider: () -> Boolean,
    onJumpProviderBreadcrumb: (Int) -> Unit,
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
    onSearchChange: (String) -> Unit,
    onSortChange: (SortOption) -> Unit,
    onRequestMountProviderTree: (ExternalDocumentProviderItem) -> Unit,
    onRequestOpenProviderFiles: (ExternalDocumentProviderItem) -> Unit,
    onCreateProviderFolder: (String) -> Unit,
    onCreateProviderNote: (String, String) -> Unit,
    onImportToProvider: (List<Uri>) -> Unit,
    onRenameItem: (BeachFileItem, String) -> Unit,
    onToggleStar: (BeachFileItem) -> Unit,
    onShareItem: (BeachFileItem) -> Unit,
    onTrashItem: (BeachFileItem) -> Unit,
    onRestoreFromTrash: (BeachFileItem) -> Unit,
    onDeleteForever: (BeachFileItem) -> Unit,
    onEmptyTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        if (selectedKeys.isNotEmpty()) {
            onClearSelection()
        } else if (canNavigateUpProvider) {
            onNavigateUpProvider()
        } else {
            onBackToTabs()
        }
    }

    var searchExpanded by remember { mutableStateOf(searchQuery.isNotBlank()) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateNoteDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BeachFileItem?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onImportToProvider(uris)
    }

    val isTrashCategory = (mode as? DrawerSelectionMode.Category)?.type == DrawerCategoryType.TRASH
    val providerMode = mode as? DrawerSelectionMode.Provider
    val isProviderMounted = providerMode?.breadcrumbs?.isNotEmpty() == true
    val showSearchAndSortControls = providerMode == null || isProviderMounted

    val screenTitle = when (mode) {
        is DrawerSelectionMode.Category -> when (mode.type) {
            DrawerCategoryType.RECENT -> stringResource(R.string.drawer_cat_recent)
            DrawerCategoryType.VIDEOS -> stringResource(R.string.drawer_cat_videos)
            DrawerCategoryType.AUDIO -> stringResource(R.string.drawer_cat_audio)
            DrawerCategoryType.DOCUMENTS -> stringResource(R.string.drawer_cat_documents)
            DrawerCategoryType.STARRED -> stringResource(R.string.drawer_cat_starred)
            DrawerCategoryType.TRASH -> stringResource(R.string.drawer_cat_trash)
        }
        is DrawerSelectionMode.Provider -> mode.provider.title
        DrawerSelectionMode.InstalledApps -> stringResource(R.string.drawer_installed_apps)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("drawer_collection_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            // Top Title + Back Bar + Compact Search & Sort
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = {
                            if (selectedKeys.isNotEmpty()) {
                                onClearSelection()
                            } else if (canNavigateUpProvider) {
                                onNavigateUpProvider()
                            } else {
                                onBackToTabs()
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("drawer_collection_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    Column {
                        Text(
                            text = screenTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (providerMode != null && providerMode.provider.subtitle.isNotBlank()) {
                            Text(
                                text = providerMode.provider.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (isTrashCategory && items.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = onEmptyTrash,
                        modifier = Modifier.testTag("drawer_empty_trash_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = stringResource(R.string.drawer_cat_trash))
                    }
                } else if (providerMode != null) {
                    AssistChip(
                        onClick = { onRequestOpenProviderFiles(providerMode.provider) },
                        label = { Text(stringResource(R.string.provider_open_files_btn)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.FileOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("provider_pick_files_chip")
                    )
                }

                if (showSearchAndSortControls) {
                    CompactSearchTriggerButton(
                        isSearchExpanded = searchExpanded,
                        activeQuery = searchQuery,
                        onToggleSearch = {
                            if (searchExpanded && searchQuery.isNotEmpty()) {
                                onSearchChange("")
                            }
                            searchExpanded = !searchExpanded
                        },
                        testTag = "drawer_collection_search_toggle_btn"
                    )

                    SortMenuIconButton(
                        sortOption = sortOption,
                        showSortMenu = showSortMenu,
                        onShowSortMenuChange = { showSortMenu = it },
                        onSortChange = onSortChange,
                        sortTag = "drawer_collection_sort_button"
                    )
                }
            }

            if (providerMode != null && !isProviderMounted) {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SandyFileIcon(
                            category = com.example.data.model.FileCategory.FOLDER,
                            size = 64.dp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = providerMode.provider.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.provider_hub_desc, providerMode.provider.title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { onRequestOpenProviderFiles(providerMode.provider) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("provider_hub_open_files_btn")
                        ) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.provider_open_files_btn))
                        }

                        if (providerMode.provider.supportsTreeGrant) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { onRequestMountProviderTree(providerMode.provider) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("provider_hub_mount_tree_btn")
                            ) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.provider_mount_folder_btn))
                            }
                        }
                    }
                }
            } else {
                ExpandableCompactSearchBar(
                    expanded = searchExpanded,
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    onCloseSearch = { searchExpanded = false },
                    inputTestTag = "drawer_collection_search_input"
                )

                if (providerMode != null && providerMode.breadcrumbs.size > 1) {
                    Spacer(modifier = Modifier.height(4.dp))
                    BreadcrumbStrip(
                        breadcrumbs = providerMode.breadcrumbs,
                        onJumpBreadcrumb = onJumpProviderBreadcrumb
                    )
                }

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

                if (items.isEmpty()) {
                    EmptyCoastalPlaceholder(
                        title = stringResource(
                            if (isTrashCategory) R.string.optimizer_trash_empty else R.string.collection_empty_title
                        ),
                        subtitle = stringResource(R.string.collection_empty_sub),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    SelectableCoastalFileList(
                        items = items,
                        selectedKeys = selectedKeys,
                        listTestTag = "drawer_collection_items_list",
                        rowTagPrefix = "coastal_file_row_",
                        isTrashMode = isTrashCategory,
                        onOpenItem = onOpenItem,
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
                        onRestoreFromTrash = onRestoreFromTrash,
                        onDeleteForever = onDeleteForever,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }

        if (isProviderMounted && selectedKeys.isEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                FloatingActionButton(
                    onClick = { showAddMenu = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("provider_add_fab")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.fab_add))
                }

                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_import_file)) },
                        leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            importLauncher.launch(arrayOf("*/*"))
                        }
                    )
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
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = {
                showCreateFolderDialog = false
                onCreateProviderFolder(it)
            }
        )
    }

    if (showCreateNoteDialog) {
        CreateNoteDialog(
            onDismiss = { showCreateNoteDialog = false },
            onConfirm = { name, body ->
                showCreateNoteDialog = false
                onCreateProviderNote(name, body)
            }
        )
    }

    renameTarget?.let { target ->
        RenameItemDialog(
            initialName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                onRenameItem(target, newName)
            }
        )
    }
}

@Composable
private fun SortMenuIconButton(
    sortOption: SortOption,
    showSortMenu: Boolean,
    onShowSortMenuChange: (Boolean) -> Unit,
    onSortChange: (SortOption) -> Unit,
    sortTag: String
) {
    Box {
        IconButton(
            onClick = { onShowSortMenuChange(true) },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag(sortTag)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Sort,
                contentDescription = stringResource(R.string.sort_label)
            )
        }

        DropdownMenu(
            expanded = showSortMenu,
            onDismissRequest = { onShowSortMenuChange(false) }
        ) {
            val options = listOf(
                SortOption.DATE_DESC to R.string.sort_date_desc,
                SortOption.DATE_ASC to R.string.sort_date_asc,
                SortOption.SIZE_DESC to R.string.sort_size_desc,
                SortOption.SIZE_ASC to R.string.sort_size_asc,
                SortOption.NAME_ASC to R.string.sort_name_asc,
                SortOption.CATEGORY to R.string.sort_category
            )
            options.forEach { (opt, labelRes) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(labelRes),
                            fontWeight = if (sortOption == opt) FontWeight.Bold else FontWeight.Normal,
                            color = if (sortOption == opt) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    onClick = {
                        onShowSortMenuChange(false)
                        onSortChange(opt)
                    }
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbStrip(
    breadcrumbs: List<DeviceBreadcrumb>,
    onJumpBreadcrumb: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            val isLast = index == breadcrumbs.lastIndex
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isLast) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onJumpBreadcrumb(index) }
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = crumb.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
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
}

@Composable
private fun EmptyCoastalPlaceholder(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SandyFileIcon(
            category = com.example.data.model.FileCategory.OTHER,
            size = 64.dp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
