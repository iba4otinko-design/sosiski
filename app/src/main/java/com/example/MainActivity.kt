package com.example

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.DrawerCategoryType
import com.example.data.model.DrawerSelectionMode
import com.example.data.model.ExternalDocumentProviderItem
import com.example.data.model.MainDestination
import com.example.data.model.ThemePreference
import com.example.ui.components.AnimatedOceanShoreHeader
import com.example.ui.components.BatchDeleteConfirmationDialog
import com.example.ui.components.CoastalAudioMiniPlayer
import com.example.ui.components.CreateArchiveDialog
import com.example.ui.components.DestinationPickerDialog
import com.example.ui.components.ExpandedAudioPlayerSheet
import com.example.ui.components.FileOperationProgressDialog
import com.example.ui.components.FilePreviewModal
import com.example.ui.components.FilesSideDrawerSheet
import com.example.ui.components.PasteConflictDialog
import com.example.ui.components.RenameItemDialog
import com.example.ui.components.SelectionBottomActionBar
import com.example.ui.components.VideoPlayerModal
import com.example.ui.components.VirtualArchiveBrowserSheet
import com.example.ui.screens.BeachExplorerScreen
import com.example.ui.screens.CloudAndAtmosphereScreen
import com.example.ui.screens.DeviceStorageScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.DrawerCollectionScreen
import com.example.ui.screens.InstalledAppsScreen
import com.example.ui.screens.OpenDocumentsAtProviderContract
import com.example.ui.screens.SeashellGalleryScreen
import com.example.ui.screens.SeashellLightboxModal
import com.example.ui.screens.SorterAndOptimizerScreen
import com.example.ui.screens.buildProviderRootsInitialUri
import com.example.ui.theme.BeaverBeachTheme
import com.example.ui.viewmodel.BeachFileViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val viewModel: BeachFileViewModel = viewModel(
                factory = BeachFileViewModel.provideFactory(context)
            )
            BeaverBeachApp(viewModel = viewModel)
        }
    }
}

private data class NavTabInfo(
    val destination: MainDestination,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@Composable
fun BeaverBeachApp(viewModel: BeachFileViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferencesState.collectAsStateWithLifecycle()
    val backupLogs by viewModel.backupLogs.collectAsStateWithLifecycle()
    val audioState by viewModel.audioPlayerState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val providerTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.onProviderSafTreeGranted(treeUri)
        }
    }

    val providerDocumentsLauncher = rememberLauncherForActivityResult(
        contract = OpenDocumentsAtProviderContract()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importAndOpenFromProvider(uris)
        }
    }

    fun launchProviderTreeSaf(provider: ExternalDocumentProviderItem) {
        val initialUri = buildProviderRootsInitialUri(provider.authority)
        runCatching {
            providerTreeLauncher.launch(initialUri)
        }.onFailure {
            runCatching { providerDocumentsLauncher.launch(initialUri) }
        }
    }

    fun launchProviderDocumentsSaf(provider: ExternalDocumentProviderItem) {
        val initialUri = buildProviderRootsInitialUri(provider.authority)
        runCatching {
            providerDocumentsLauncher.launch(initialUri)
        }
    }

    val legacyStoragePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshStoragePermissionAndData()
    }

    val allFilesSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshStoragePermissionAndData()
    }

    val unknownSourcesSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onReturnedFromUnknownSourcesSettings()
    }

    LaunchedEffect(uiState.pendingUnknownSourcesIntent) {
        val intent = uiState.pendingUnknownSourcesIntent
        if (intent != null) {
            viewModel.consumePendingUnknownSourcesIntent()
            runCatching {
                unknownSourcesSettingsLauncher.launch(intent)
            }
        }
    }

    fun requestFullStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = viewModel.createAllFilesAccessIntent()
            runCatching {
                allFilesSettingsLauncher.launch(appIntent)
            }.onFailure {
                runCatching {
                    allFilesSettingsLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                }
            }
        } else {
            legacyStoragePermLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.soundEngine.setForeground(true)
                Lifecycle.Event.ON_RESUME -> viewModel.refreshStoragePermissionAndData()
                Lifecycle.Event.ON_STOP -> viewModel.soundEngine.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    val systemDark = isSystemInDarkTheme()
    val isDarkTheme = when (prefs.themePreference) {
        ThemePreference.SYSTEM -> systemDark
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }

    // Back handling:
    // 1. Close left side drawer first if open
    // 2. If on secondary screen (SPACE, SETTINGS, DRAWER_COLLECTION at root) and no selection active, return to lastPrimaryTab
    // 3. If on primary non-BEACH tab at root and no selection active, return to BEACH
    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }
    BackHandler(
        enabled = !drawerState.isOpen &&
            uiState.selectedKeys.isEmpty() &&
            !uiState.destination.isPrimaryTab &&
            !(uiState.destination == MainDestination.DRAWER_COLLECTION && uiState.canNavigateUpDrawerProvider)
    ) {
        viewModel.navigateBackFromMenuScreen()
    }
    BackHandler(
        enabled = !drawerState.isOpen &&
            uiState.selectedKeys.isEmpty() &&
            uiState.destination.isPrimaryTab &&
            uiState.destination != MainDestination.BEACH &&
            !(uiState.destination == MainDestination.DEVICE && uiState.canNavigateUpDevice) &&
            !(uiState.destination == MainDestination.DOWNLOADS && uiState.canNavigateUpDownloads)
    ) {
        viewModel.selectDestination(MainDestination.BEACH)
    }

    // 4 Primary Bottom Tabs: Пляж, Мушлі, Завантаження, Пристрій
    val navTabs = remember {
        listOf(
            NavTabInfo(
                MainDestination.BEACH,
                R.string.nav_beach,
                Icons.Filled.BeachAccess,
                Icons.Outlined.BeachAccess,
                "nav_tab_beach"
            ),
            NavTabInfo(
                MainDestination.SHELLS,
                R.string.nav_shells,
                Icons.Filled.Collections,
                Icons.Outlined.Collections,
                "nav_tab_shells"
            ),
            NavTabInfo(
                MainDestination.DOWNLOADS,
                R.string.nav_downloads,
                Icons.Filled.Download,
                Icons.Outlined.Download,
                "nav_tab_downloads"
            ),
            NavTabInfo(
                MainDestination.DEVICE,
                R.string.nav_device,
                Icons.Filled.Smartphone,
                Icons.Outlined.Smartphone,
                "nav_tab_device"
            )
        )
    }

    val selectedItems = uiState.selectedItems
    val hasClipboardItems = uiState.fileClipboard?.items?.isNotEmpty() == true

    BeaverBeachTheme(darkTheme = isDarkTheme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = uiState.selectedLightboxShell == null &&
                !uiState.videoPlayerState.isVisible &&
                uiState.selectedKeys.isEmpty(),
            drawerContent = {
                FilesSideDrawerSheet(
                    isDark = isDarkTheme,
                    currentDestination = uiState.destination,
                    activeDrawerMode = uiState.activeDrawerMode,
                    installedProviders = uiState.installedProviders,
                    connectedFolders = uiState.connectedFolders,
                    onSelectCategory = { catType ->
                        coroutineScope.launch { drawerState.close() }
                        viewModel.openDrawerCategory(catType)
                    },
                    onSelectInstalledApps = {
                        coroutineScope.launch { drawerState.close() }
                        viewModel.openInstalledAppsInDrawer()
                    },
                    onSelectProvider = { provider ->
                        coroutineScope.launch { drawerState.close() }
                        viewModel.openDrawerProvider(provider)
                        if (provider.connectedTreeUri.isNullOrBlank()) {
                            if (provider.supportsTreeGrant) {
                                launchProviderTreeSaf(provider)
                            } else {
                                launchProviderDocumentsSaf(provider)
                            }
                        }
                    },
                    onSelectCustomFolder = { folder ->
                        coroutineScope.launch { drawerState.close() }
                        viewModel.openCustomConnectedFolderInDrawer(folder)
                    },
                    onConnectExternalFolder = {
                        coroutineScope.launch { drawerState.close() }
                        runCatching { providerTreeLauncher.launch(null) }
                    },
                    onCloseDrawer = {
                        coroutineScope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavigationRail = maxWidth >= 600.dp
                val highlightedTab = if (uiState.destination.isPrimaryTab) {
                    uiState.destination
                } else {
                    uiState.lastPrimaryTab
                }

                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    bottomBar = {
                        if (!useNavigationRail) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                SelectionBottomActionBar(
                                    selectedItems = selectedItems,
                                    hasClipboardItems = hasClipboardItems,
                                    canPasteHere = uiState.canPasteInCurrentScreen,
                                    onCopy = viewModel::copySelectedToClipboard,
                                    onCut = viewModel::cutSelectedToClipboard,
                                    onCopyTcListAsText = viewModel::copySelectedListAsTcText,
                                    onPasteHere = viewModel::pasteClipboardToCurrentFolder,
                                    onMoveTo = viewModel::requestMoveSelectedDialog,
                                    onRenameSingle = viewModel::requestRenameFromSelection,
                                    onArchive = viewModel::requestCreateArchiveDialog,
                                    onShare = { viewModel.shareSelectedItems(context) },
                                    onDeleteRequest = viewModel::requestBatchDeleteConfirmation,
                                    onEnqueueAudio = viewModel::enqueueSelectedAudio
                                )

                                CoastalAudioMiniPlayer(
                                    playerState = audioState,
                                    onTogglePlayPause = viewModel.audioPlayerManager::togglePlayPause,
                                    onPrevious = viewModel.audioPlayerManager::skipToPrevious,
                                    onNext = viewModel.audioPlayerManager::skipToNext,
                                    onCollapseToggle = viewModel.audioPlayerManager::setMiniCollapsed,
                                    onOpenFullSheet = { viewModel.audioPlayerManager.setExpandedSheetOpen(true) },
                                    onClosePlayer = viewModel.audioPlayerManager::stopAndClosePlayer
                                )

                                NavigationBar(modifier = Modifier.testTag("bottom_nav_bar")) {
                                    navTabs.forEach { tab ->
                                        val selected = highlightedTab == tab.destination && uiState.destination.isPrimaryTab
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = { viewModel.selectDestination(tab.destination) },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                    contentDescription = stringResource(tab.labelRes)
                                                )
                                            },
                                            label = { Text(stringResource(tab.labelRes)) },
                                            modifier = Modifier.testTag(tab.testTag)
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (useNavigationRail) {
                            NavigationRail(modifier = Modifier.testTag("side_nav_rail")) {
                                navTabs.forEach { tab ->
                                    val selected = highlightedTab == tab.destination && uiState.destination.isPrimaryTab
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = { viewModel.selectDestination(tab.destination) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = stringResource(tab.labelRes)
                                            )
                                        },
                                        label = { Text(stringResource(tab.labelRes)) },
                                        modifier = Modifier.testTag(tab.testTag)
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            AnimatedOceanShoreHeader(
                                isDark = isDarkTheme,
                                surfEnabled = prefs.surfEnabled,
                                musicEnabled = prefs.musicEnabled,
                                isCloudConfigured = prefs.isCloudConfigured,
                                showHiddenFiles = prefs.showHiddenFiles,
                                onOpenDrawer = {
                                    coroutineScope.launch { drawerState.open() }
                                },
                                onToggleSurf = viewModel::toggleSurf,
                                onToggleMusic = viewModel::toggleMusic,
                                onToggleShowHiddenFiles = viewModel::toggleShowHiddenFiles,
                                onOpenSpaceFromMenu = {
                                    viewModel.selectDestination(MainDestination.SPACE)
                                },
                                onOpenSettingsFromMenu = {
                                    viewModel.selectDestination(MainDestination.SETTINGS)
                                },
                                onShareBobroPlyazhApk = {
                                    viewModel.shareBobroPlyazhSelfApk(context)
                                },
                                onQuickToggleTheme = {
                                    viewModel.toggleLightDarkExplicit(isDarkTheme)
                                }
                            )

                            AnimatedContent(
                                targetState = uiState.destination,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                label = "main_destination_transition"
                            ) { dest ->
                                when (dest) {
                                    MainDestination.BEACH -> {
                                        BeachExplorerScreen(
                                            items = uiState.currentItems,
                                            activeRoot = uiState.activeRoot,
                                            hasAllFilesAccess = uiState.hasAllFilesAccess,
                                            connectedFolders = uiState.connectedFolders,
                                            canNavigateUp = uiState.canNavigateUpFolder,
                                            currentPathOrUri = uiState.currentPathOrUri,
                                            viewMode = uiState.viewMode,
                                            searchQuery = uiState.searchQuery,
                                            selectedCategory = uiState.selectedCategoryFilter,
                                            sortOption = uiState.sortOption,
                                            tideWaveTrigger = uiState.tideWaveTrigger,
                                            selectedKeys = uiState.selectedKeys,
                                            selectedTotalBytes = uiState.selectedTotalBytes,
                                            clipboard = uiState.fileClipboard,
                                            canPasteHere = uiState.canPasteInCurrentScreen,
                                            onRequestAllFilesAccess = ::requestFullStorageAccess,
                                            onSelectRoot = viewModel::selectStorageRoot,
                                            onConnectSafDirectory = viewModel::connectSafDirectory,
                                            onNavigateUp = viewModel::navigateUpFolder,
                                            onSetViewMode = viewModel::setExplorerViewMode,
                                            onSearchChange = viewModel::updateSearchQuery,
                                            onCategorySelect = viewModel::updateCategoryFilter,
                                            onSortChange = viewModel::updateSortOption,
                                            onOpenItem = viewModel::openItem,
                                            onOpenAsArchive = viewModel::openVirtualArchive,
                                            onToggleSelectItem = viewModel::toggleSelectItem,
                                            onSelectRange = viewModel::selectKeyRange,
                                            onSelectAll = viewModel::selectAllActiveItems,
                                            onClearSelection = viewModel::clearSelection,
                                            onCopySingle = viewModel::copySingleItemToClipboard,
                                            onCutSingle = viewModel::cutSingleItemToClipboard,
                                            onPasteHere = viewModel::pasteClipboardToCurrentFolder,
                                            onCopyClipboardAsTcText = viewModel::copyClipboardListAsTcText,
                                            onClearClipboard = viewModel::clearFileClipboard,
                                            onEnqueueAudio = viewModel::enqueueSingleAudio,
                                            onSandDragEnd = viewModel::onSandDragEnd,
                                            onTriggerTideArrange = viewModel::triggerTideArrange,
                                            onCreateFolder = viewModel::createNewFolder,
                                            onCreateNote = viewModel::createNewNote,
                                            onImportUris = viewModel::importFilesFromUris,
                                            onRenameItem = viewModel::renameItem,
                                            onToggleStar = viewModel::toggleStarItem,
                                            onShareItem = { viewModel.shareItem(context, it) },
                                            onMoveToTrash = viewModel::moveItemToTrash
                                        )
                                    }

                                    MainDestination.SHELLS -> {
                                        SeashellGalleryScreen(
                                            images = uiState.galleryImages,
                                            selectedShell = uiState.selectedLightboxShell,
                                            searchQuery = uiState.gallerySearchQuery,
                                            mediaFilter = uiState.galleryMediaFilter,
                                            gridColumns = uiState.galleryColumns,
                                            onSearchChange = viewModel::updateGallerySearchQuery,
                                            onMediaFilterChange = viewModel::updateGalleryMediaFilter,
                                            onGridColumnsChange = viewModel::setGalleryColumns,
                                            onOpenShell = viewModel::openItem,
                                            onSelectAdjacentShell = viewModel::selectLightboxShell,
                                            onCloseLightbox = viewModel::closePreviewAndLightbox,
                                            onImportPhotos = { uris ->
                                                viewModel.importFilesFromUris(uris, intoGalleryFolder = true)
                                            },
                                            onShareShell = { viewModel.shareItem(context, it) },
                                            onTrashShell = viewModel::moveItemToTrash
                                        )
                                    }

                                    MainDestination.DOWNLOADS -> {
                                        DownloadsScreen(
                                            breadcrumbs = uiState.downloadsBreadcrumbs,
                                            items = uiState.downloadsItems,
                                            isSystemDownloadsMounted = uiState.isSystemDownloadsMounted,
                                            hasAllFilesAccess = uiState.hasAllFilesAccess,
                                            searchQuery = uiState.downloadsSearchQuery,
                                            sortOption = uiState.downloadsSortOption,
                                            canNavigateUp = uiState.canNavigateUpDownloads,
                                            selectedKeys = uiState.selectedKeys,
                                            selectedTotalBytes = uiState.selectedTotalBytes,
                                            clipboard = uiState.fileClipboard,
                                            canPasteHere = uiState.canPasteInCurrentScreen,
                                            onRequestAllFilesAccess = ::requestFullStorageAccess,
                                            onJumpBreadcrumb = viewModel::jumpToDownloadsBreadcrumb,
                                            onNavigateUp = viewModel::navigateUpDownloadsFolder,
                                            onOpenItem = viewModel::openDownloadsItem,
                                            onOpenAsArchive = viewModel::openVirtualArchive,
                                            onToggleSelectItem = viewModel::toggleSelectItem,
                                            onSelectRange = viewModel::selectKeyRange,
                                            onSelectAll = viewModel::selectAllActiveItems,
                                            onClearSelection = viewModel::clearSelection,
                                            onCopySingle = viewModel::copySingleItemToClipboard,
                                            onCutSingle = viewModel::cutSingleItemToClipboard,
                                            onPasteHere = viewModel::pasteClipboardToCurrentFolder,
                                            onCopyClipboardAsTcText = viewModel::copyClipboardListAsTcText,
                                            onClearClipboard = viewModel::clearFileClipboard,
                                            onEnqueueAudio = viewModel::enqueueSingleAudio,
                                            onSafDownloadsGranted = viewModel::onDownloadsSafGranted,
                                            onSearchChange = viewModel::updateDownloadsSearchQuery,
                                            onSortChange = viewModel::updateDownloadsSortOption,
                                            onCreateFolder = viewModel::createNewDownloadsFolder,
                                            onCreateNote = viewModel::createNewDownloadsNote,
                                            onImportFiles = viewModel::importFilesToDownloads,
                                            onRenameItem = viewModel::renameItem,
                                            onToggleStar = viewModel::toggleStarItem,
                                            onShareItem = { viewModel.shareItem(context, it) },
                                            onTrashItem = viewModel::moveItemToTrash
                                        )
                                    }

                                    MainDestination.DEVICE -> {
                                        DeviceStorageScreen(
                                            breadcrumbs = uiState.deviceBreadcrumbs,
                                            items = uiState.deviceItems,
                                            storageStats = uiState.storageStats,
                                            hasAllFilesAccess = uiState.hasAllFilesAccess,
                                            searchQuery = uiState.deviceSearchQuery,
                                            sortOption = uiState.deviceSortOption,
                                            canNavigateUp = uiState.canNavigateUpDevice,
                                            selectedKeys = uiState.selectedKeys,
                                            selectedTotalBytes = uiState.selectedTotalBytes,
                                            clipboard = uiState.fileClipboard,
                                            canPasteHere = uiState.canPasteInCurrentScreen,
                                            onRequestAllFilesAccess = ::requestFullStorageAccess,
                                            onJumpBreadcrumb = viewModel::jumpToDeviceBreadcrumb,
                                            onNavigateUp = viewModel::navigateUpDeviceFolder,
                                            onOpenItem = viewModel::openDeviceItem,
                                            onOpenAsArchive = viewModel::openVirtualArchive,
                                            onToggleSelectItem = viewModel::toggleSelectItem,
                                            onSelectRange = viewModel::selectKeyRange,
                                            onSelectAll = viewModel::selectAllActiveItems,
                                            onClearSelection = viewModel::clearSelection,
                                            onCopySingle = viewModel::copySingleItemToClipboard,
                                            onCutSingle = viewModel::cutSingleItemToClipboard,
                                            onPasteHere = viewModel::pasteClipboardToCurrentFolder,
                                            onCopyClipboardAsTcText = viewModel::copyClipboardListAsTcText,
                                            onClearClipboard = viewModel::clearFileClipboard,
                                            onEnqueueAudio = viewModel::enqueueSingleAudio,
                                            onSafFolderGranted = viewModel::onDeviceSafFolderGranted,
                                            onSearchChange = viewModel::updateDeviceSearchQuery,
                                            onSortChange = viewModel::updateDeviceSortOption,
                                            onCreateFolder = viewModel::createNewDeviceFolder,
                                            onCreateNote = viewModel::createNewDeviceNote,
                                            onImportFiles = viewModel::importFilesToDevice,
                                            onRenameItem = viewModel::renameItem,
                                            onToggleStar = viewModel::toggleStarItem,
                                            onShareItem = { viewModel.shareItem(context, it) },
                                            onTrashItem = viewModel::moveItemToTrash
                                        )
                                    }

                                    MainDestination.DRAWER_COLLECTION -> {
                                        if (uiState.activeDrawerMode is DrawerSelectionMode.InstalledApps) {
                                            val destinationOptions = remember(
                                                uiState.currentPathOrUri,
                                                uiState.downloadsBreadcrumbs,
                                                uiState.deviceBreadcrumbs,
                                                uiState.connectedFolders
                                            ) {
                                                viewModel.buildDestinationOptions()
                                            }
                                            InstalledAppsScreen(
                                                apps = uiState.installedApps,
                                                isLoading = uiState.isLoadingInstalledApps,
                                                showSystemApps = uiState.showSystemAppsInDrawer,
                                                searchQuery = uiState.installedAppsSearchQuery,
                                                destinationOptions = destinationOptions,
                                                onBackToBeach = viewModel::navigateBackFromMenuScreen,
                                                onToggleShowSystemApps = { viewModel.toggleShowSystemAppsInDrawer() },
                                                onSearchChange = viewModel::updateInstalledAppsSearchQuery,
                                                onRefresh = viewModel::refreshInstalledApps,
                                                onCopyAppsToFolder = viewModel::copyInstalledAppsToDestination,
                                                onCopyAppsToCustomSafTree = viewModel::copyInstalledAppsToSafTree,
                                                onCopyAppsToTcClipboard = viewModel::copyInstalledAppsToTcClipboard,
                                                onShareAppsApk = { apps ->
                                                    viewModel.shareInstalledAppsApk(context, apps)
                                                },
                                                onLaunchApp = viewModel::launchInstalledAppFromDrawer
                                            )
                                        } else {
                                            DrawerCollectionScreen(
                                                mode = uiState.activeDrawerMode,
                                                items = uiState.drawerCollectionItems,
                                                searchQuery = uiState.drawerSearchQuery,
                                                sortOption = uiState.drawerSortOption,
                                                canNavigateUpProvider = uiState.canNavigateUpDrawerProvider,
                                                selectedKeys = uiState.selectedKeys,
                                                selectedTotalBytes = uiState.selectedTotalBytes,
                                                clipboard = uiState.fileClipboard,
                                                canPasteHere = uiState.canPasteInCurrentScreen,
                                                onBackToTabs = viewModel::navigateBackFromMenuScreen,
                                                onNavigateUpProvider = viewModel::navigateUpDrawerProviderFolder,
                                                onJumpProviderBreadcrumb = viewModel::jumpToDrawerProviderBreadcrumb,
                                                onOpenItem = viewModel::openDrawerCollectionItem,
                                                onOpenAsArchive = viewModel::openVirtualArchive,
                                                onToggleSelectItem = viewModel::toggleSelectItem,
                                                onSelectRange = viewModel::selectKeyRange,
                                                onSelectAll = viewModel::selectAllActiveItems,
                                                onClearSelection = viewModel::clearSelection,
                                                onCopySingle = viewModel::copySingleItemToClipboard,
                                                onCutSingle = viewModel::cutSingleItemToClipboard,
                                                onPasteHere = viewModel::pasteClipboardToCurrentFolder,
                                                onCopyClipboardAsTcText = viewModel::copyClipboardListAsTcText,
                                                onClearClipboard = viewModel::clearFileClipboard,
                                                onEnqueueAudio = viewModel::enqueueSingleAudio,
                                                onSearchChange = viewModel::updateDrawerSearchQuery,
                                                onSortChange = viewModel::updateDrawerSortOption,
                                                onRequestMountProviderTree = { prov -> launchProviderTreeSaf(prov) },
                                                onRequestOpenProviderFiles = { prov -> launchProviderDocumentsSaf(prov) },
                                                onCreateProviderFolder = viewModel::createNewDrawerProviderFolder,
                                                onCreateProviderNote = viewModel::createNewDrawerProviderNote,
                                                onImportToProvider = viewModel::importFilesToDrawerProvider,
                                                onRenameItem = viewModel::renameItem,
                                                onToggleStar = viewModel::toggleStarItem,
                                                onShareItem = { viewModel.shareItem(context, it) },
                                                onTrashItem = viewModel::moveItemToTrash,
                                                onRestoreFromTrash = viewModel::restoreItemFromTrash,
                                                onDeleteForever = viewModel::deleteItemPermanently,
                                                onEmptyTrash = viewModel::emptyTrashAndOptimize
                                            )
                                        }
                                    }

                                    MainDestination.SPACE -> {
                                        SorterAndOptimizerScreen(
                                            swipeDeck = uiState.swipeDeck,
                                            lastUndo = uiState.lastUndoAction,
                                            storageStats = uiState.storageStats,
                                            optimizerSort = uiState.optimizerSortOption,
                                            onBack = viewModel::navigateBackFromMenuScreen,
                                            onGestureSort = viewModel::performGestureSort,
                                            onUndoSwipe = viewModel::undoLastGestureSort,
                                            onResetDeck = viewModel::resetGestureDeck,
                                            onUpdateOptimizerSort = viewModel::updateOptimizerSort,
                                            onEmptyTrash = viewModel::emptyTrashAndOptimize,
                                            onOpenItem = viewModel::openItem,
                                            onTrashItem = viewModel::moveItemToTrash
                                        )
                                    }

                                    MainDestination.SETTINGS -> {
                                        CloudAndAtmosphereScreen(
                                            prefs = prefs,
                                            backupLogs = backupLogs,
                                            isSyncing = uiState.isSyncingCloud,
                                            hasAllFilesAccess = uiState.hasAllFilesAccess,
                                            onBack = viewModel::navigateBackFromMenuScreen,
                                            onRequestAllFilesAccess = ::requestFullStorageAccess,
                                            onToggleShowHiddenFiles = viewModel::toggleShowHiddenFiles,
                                            onToggleSurf = viewModel::toggleSurf,
                                            onSurfVolumeChange = viewModel::setSurfVolume,
                                            onToggleMusic = viewModel::toggleMusic,
                                            onMusicVolumeChange = viewModel::setMusicVolume,
                                            onToggleSfx = viewModel::toggleSfx,
                                            onSetTheme = viewModel::setThemePreference,
                                            onConnectSafCloud = { uri ->
                                                viewModel.configureSafCloudBackup(context, uri)
                                            },
                                            onSaveWebDav = viewModel::saveWebDavCloudConfig,
                                            onToggleAutoBackup = viewModel::setAutoCloudBackup,
                                            onSyncNow = viewModel::syncCloudNow,
                                            onDisconnectCloud = viewModel::disconnectCloudStorage
                                        )
                                    }
                                }
                            }

                            if (useNavigationRail) {
                                SelectionBottomActionBar(
                                    selectedItems = selectedItems,
                                    hasClipboardItems = hasClipboardItems,
                                    canPasteHere = uiState.canPasteInCurrentScreen,
                                    onCopy = viewModel::copySelectedToClipboard,
                                    onCut = viewModel::cutSelectedToClipboard,
                                    onCopyTcListAsText = viewModel::copySelectedListAsTcText,
                                    onPasteHere = viewModel::pasteClipboardToCurrentFolder,
                                    onMoveTo = viewModel::requestMoveSelectedDialog,
                                    onRenameSingle = viewModel::requestRenameFromSelection,
                                    onArchive = viewModel::requestCreateArchiveDialog,
                                    onShare = { viewModel.shareSelectedItems(context) },
                                    onDeleteRequest = viewModel::requestBatchDeleteConfirmation,
                                    onEnqueueAudio = viewModel::enqueueSelectedAudio
                                )

                                CoastalAudioMiniPlayer(
                                    playerState = audioState,
                                    onTogglePlayPause = viewModel.audioPlayerManager::togglePlayPause,
                                    onPrevious = viewModel.audioPlayerManager::skipToPrevious,
                                    onNext = viewModel.audioPlayerManager::skipToNext,
                                    onCollapseToggle = viewModel.audioPlayerManager::setMiniCollapsed,
                                    onOpenFullSheet = { viewModel.audioPlayerManager.setExpandedSheetOpen(true) },
                                    onClosePlayer = viewModel.audioPlayerManager::stopAndClosePlayer
                                )
                            }
                        }
                    }
                }

                // Virtual Archive Browser Sheet (.zip, .7z, .tar, .tar.gz, .gz, .mcpack, .apk)
                uiState.virtualArchiveSession?.let { archiveSession ->
                    val destinationOptions = remember(
                        uiState.destination,
                        uiState.currentPathOrUri,
                        uiState.downloadsBreadcrumbs,
                        uiState.deviceBreadcrumbs,
                        uiState.connectedFolders
                    ) {
                        viewModel.buildDestinationOptions()
                    }
                    VirtualArchiveBrowserSheet(
                        session = archiveSession,
                        destinationOptions = destinationOptions,
                        onNavigateIntoDir = viewModel::navigateVirtualArchiveDir,
                        onNavigateUpDir = viewModel::navigateUpVirtualArchive,
                        onPreviewEntry = viewModel::previewArchiveEntry,
                        onToggleSelectEntry = viewModel::toggleArchiveEntrySelection,
                        onSelectAllCurrentDir = viewModel::selectAllArchiveEntriesInDir,
                        onClearEntrySelection = viewModel::clearArchiveEntrySelection,
                        onExtractArchive = viewModel::extractCurrentArchive,
                        onAddFilesToZip = viewModel::addFilesToCurrentZip,
                        onClose = viewModel::closeVirtualArchive
                    )
                }

                // Expanded Audio Player ModalBottomSheet
                ExpandedAudioPlayerSheet(
                    playerState = audioState,
                    onDismiss = { viewModel.audioPlayerManager.setExpandedSheetOpen(false) },
                    onTogglePlayPause = viewModel.audioPlayerManager::togglePlayPause,
                    onPrevious = viewModel.audioPlayerManager::skipToPrevious,
                    onNext = viewModel.audioPlayerManager::skipToNext,
                    onSeekTo = viewModel.audioPlayerManager::seekTo,
                    onCycleRepeat = viewModel.audioPlayerManager::cycleRepeatMode,
                    onToggleShuffle = viewModel.audioPlayerManager::toggleShuffle,
                    onVolumeChange = viewModel.audioPlayerManager::setVolume,
                    onSelectQueueIndex = viewModel.audioPlayerManager::playQueueIndex,
                    onRemoveQueueIndex = viewModel.audioPlayerManager::removeFromQueue
                )

                // Create Archive Dialog
                if (uiState.showCreateArchiveDialog) {
                    val defaultBaseName = remember(selectedItems) {
                        if (selectedItems.size == 1) {
                            val first = selectedItems.first()
                            if (first.isDirectory) first.name else first.name.substringBeforeLast('.').ifBlank { first.name }
                        } else {
                            "архів_бобра"
                        }
                    }
                    val destinationOptions = remember(
                        uiState.destination,
                        uiState.currentPathOrUri,
                        uiState.downloadsBreadcrumbs,
                        uiState.deviceBreadcrumbs,
                        uiState.connectedFolders
                    ) {
                        viewModel.buildDestinationOptions()
                    }
                    CreateArchiveDialog(
                        defaultBaseName = defaultBaseName,
                        destinationOptions = destinationOptions,
                        onDismiss = viewModel::dismissCreateArchiveDialog,
                        onConfirm = viewModel::createArchiveFromSelection
                    )
                }

                // Move Destination Picker Dialog
                if (uiState.showMovePickerDialog) {
                    val destinationOptions = remember(
                        uiState.destination,
                        uiState.currentPathOrUri,
                        uiState.downloadsBreadcrumbs,
                        uiState.deviceBreadcrumbs,
                        uiState.connectedFolders
                    ) {
                        viewModel.buildDestinationOptions()
                    }
                    DestinationPickerDialog(
                        title = stringResource(R.string.action_move_to),
                        destinations = destinationOptions,
                        onDismiss = viewModel::dismissMoveDialog,
                        onSelectDestination = viewModel::moveSelectedToDestination
                    )
                }

                // Batch Delete Confirmation Dialog
                if (uiState.showBatchDeleteConfirmDialog) {
                    val isTrashView = uiState.destination == MainDestination.DRAWER_COLLECTION &&
                        (uiState.activeDrawerMode as? DrawerSelectionMode.Category)?.type == DrawerCategoryType.TRASH
                    BatchDeleteConfirmationDialog(
                        itemCount = selectedItems.size,
                        totalBytes = uiState.selectedTotalBytes,
                        canMoveToTrash = !isTrashView && selectedItems.any { !it.isSafDocument },
                        onDismiss = viewModel::dismissBatchDeleteConfirmation,
                        onMoveToTrash = viewModel::confirmBatchMoveToTrash,
                        onDeletePermanently = viewModel::confirmBatchDeletePermanently
                    )
                }

                // Rename Dialog launched from Selection Action Bar
                uiState.renameDialogTarget?.let { target ->
                    RenameItemDialog(
                        initialName = target.name,
                        onDismiss = viewModel::dismissRenameDialog,
                        onConfirm = { newName ->
                            viewModel.renameItem(target, newName)
                        }
                    )
                }

                // Conflict Resolution Dialog (Overwrite / Auto-Rename / Skip / Apply to all)
                uiState.conflictPrompt?.let { prompt ->
                    PasteConflictDialog(
                        prompt = prompt,
                        onDecision = viewModel::resolvePasteConflict
                    )
                }

                // File Operation Progress & Cancel Dialog
                if (uiState.operationProgress.isActive && uiState.conflictPrompt == null) {
                    FileOperationProgressDialog(
                        progress = uiState.operationProgress,
                        onCancel = viewModel::cancelActiveFileOperation
                    )
                }

                // Global Lightbox for images opened outside SHELLS tab (including inside archives!)
                if (uiState.destination != MainDestination.SHELLS) {
                    uiState.selectedLightboxShell?.let { shellItem ->
                        val siblingPhotos = remember(uiState.activeListItems) {
                            uiState.activeListItems.filter {
                                !it.isDirectory && it.category == com.example.data.model.FileCategory.IMAGES
                            }
                        }
                        SeashellLightboxModal(
                            item = shellItem,
                            siblingImages = siblingPhotos,
                            onSelectSibling = viewModel::selectLightboxShell,
                            onDismiss = viewModel::closePreviewAndLightbox,
                            onShare = { viewModel.shareItem(context, shellItem) },
                            onTrash = { viewModel.moveItemToTrash(shellItem) }
                        )
                    }
                }

                // Built-in Fullscreen Video Player Modal (Media3 ExoPlayer)
                if (uiState.videoPlayerState.isVisible) {
                    VideoPlayerModal(
                        state = uiState.videoPlayerState,
                        onClose = viewModel::closeVideoPlayer,
                        onShare = { item -> viewModel.shareItem(context, item) },
                        onSelectIndex = viewModel::selectVideoPlaylistIndex,
                        onOpenExternally = { item -> viewModel.openFileInExternalApp(item) }
                    )
                }

                // File Preview Modal (Text, Metadata, External App Launch, and 1-tap "Open as Archive")
                uiState.previewFileItem?.let { item ->
                    FilePreviewModal(
                        item = item,
                        textContent = uiState.previewTextContent,
                        onDismiss = viewModel::closePreviewAndLightbox,
                        onToggleStar = { viewModel.toggleStarItem(item) },
                        onShare = { viewModel.shareItem(context, item) },
                        onMoveToTrash = { viewModel.moveItemToTrash(item) },
                        onOpenAsArchive = { viewModel.openVirtualArchive(item) },
                        onOpenExternally = { viewModel.openFileInExternalApp(item) }
                    )
                }
            }
        }
    }
}
