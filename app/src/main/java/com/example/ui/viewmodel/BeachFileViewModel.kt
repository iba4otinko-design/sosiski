package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.audio.AudioPlayerManager
import com.example.audio.BeachSoundEngine
import com.example.data.local.BackupLogEntity
import com.example.data.local.BeachDatabase
import com.example.data.local.ConnectedFolderEntity
import com.example.data.local.PreferencesRepository
import com.example.data.local.SandItemMetaEntity
import com.example.data.model.AppPreferencesState
import com.example.data.model.ArchiveEntryItem
import com.example.data.model.AudioPlayerUiState
import com.example.data.model.BeachFileItem
import com.example.data.model.ClipboardMode
import com.example.data.model.ConflictDecision
import com.example.data.model.ConflictResolution
import com.example.data.model.DeviceBreadcrumb
import com.example.data.model.DrawerCategoryType
import com.example.data.model.DrawerSelectionMode
import com.example.data.model.ExplorerViewMode
import com.example.data.model.ExternalDocumentProviderItem
import com.example.data.model.FileCategory
import android.os.FileObserver
import android.os.Looper
import com.example.data.model.FileClipboardState
import com.example.data.model.FileOperationProgress
import com.example.data.model.GalleryMediaFilter
import com.example.data.model.InstalledAppInfo
import com.example.data.model.MainDestination
import com.example.data.model.MoveDestinationOption
import com.example.data.model.PasteConflictPrompt
import com.example.data.model.SortOption
import com.example.data.model.StorageRoot
import com.example.data.model.StorageStats
import com.example.data.model.SupportedArchiveFormat
import com.example.data.model.SwipeUndoAction
import com.example.data.model.ThemePreference
import com.example.data.model.VideoPlayerState
import com.example.data.model.VirtualArchiveSession
import com.example.data.repository.AppLauncherAndApkManager
import com.example.data.repository.ArchiveRepository
import com.example.data.repository.CloudSyncRepository
import com.example.data.repository.FileLaunchOutcome
import com.example.data.repository.FileOperationsManager
import com.example.data.repository.FileStorageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.File

data class BeachUiState(
    val isInitialized: Boolean = false,
    val destination: MainDestination = MainDestination.BEACH,
    val lastPrimaryTab: MainDestination = MainDestination.BEACH,
    val viewMode: ExplorerViewMode = ExplorerViewMode.SANDBOX,
    val activeRoot: StorageRoot = StorageRoot.BeaverCove(),
    val hasAllFilesAccess: Boolean = false,
    val connectedFolders: List<ConnectedFolderEntity> = emptyList(),
    val pathStack: List<String> = emptyList(),
    val currentItems: List<BeachFileItem> = emptyList(),
    // Downloads ("Завантаження") bottom tab state
    val downloadsBreadcrumbs: List<DeviceBreadcrumb> = emptyList(),
    val downloadsItems: List<BeachFileItem> = emptyList(),
    val isSystemDownloadsMounted: Boolean = false,
    val downloadsSearchQuery: String = "",
    val downloadsSortOption: SortOption = SortOption.DATE_DESC,
    // Device Internal Storage ("Пристрій") state
    val deviceBreadcrumbs: List<DeviceBreadcrumb> = emptyList(),
    val deviceItems: List<BeachFileItem> = emptyList(),
    val deviceSearchQuery: String = "",
    val deviceSortOption: SortOption = SortOption.NAME_ASC,
    // Left Side Drawer ("Files"-style categories & external DocumentsProviders)
    val installedProviders: List<ExternalDocumentProviderItem> = emptyList(),
    val activeDrawerMode: DrawerSelectionMode = DrawerSelectionMode.Category(DrawerCategoryType.RECENT),
    val drawerCollectionItems: List<BeachFileItem> = emptyList(),
    val drawerSearchQuery: String = "",
    val drawerSortOption: SortOption = SortOption.DATE_DESC,
    // Gallery, Video Player, Installed Apps, Gesture Sorter & Space Optimizer
    val galleryImages: List<BeachFileItem> = emptyList(),
    val gallerySearchQuery: String = "",
    val galleryMediaFilter: GalleryMediaFilter = GalleryMediaFilter.ALL,
    val galleryColumns: Int = 3,
    val videoPlayerState: VideoPlayerState = VideoPlayerState(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingInstalledApps: Boolean = false,
    val showSystemAppsInDrawer: Boolean = false,
    val installedAppsSearchQuery: String = "",
    val pendingUnknownSourcesIntent: Intent? = null,
    val swipeDeck: List<BeachFileItem> = emptyList(),
    val storageStats: StorageStats = StorageStats(),
    val searchQuery: String = "",
    val selectedCategoryFilter: FileCategory? = null,
    val sortOption: SortOption = SortOption.DATE_DESC,
    val optimizerSortOption: SortOption = SortOption.SIZE_DESC,
    val tideWaveTrigger: Int = 0,
    val selectedLightboxShell: BeachFileItem? = null,
    val previewFileItem: BeachFileItem? = null,
    val previewTextContent: String = "",
    val lastUndoAction: SwipeUndoAction? = null,
    val isSyncingCloud: Boolean = false,
    // Smart Multi-Selection, Total Commander Clipboard, Conflict & Archiver State
    val selectedKeys: Set<String> = emptySet(),
    val fileClipboard: FileClipboardState? = null,
    val operationProgress: FileOperationProgress = FileOperationProgress(),
    val conflictPrompt: PasteConflictPrompt? = null,
    val virtualArchiveSession: VirtualArchiveSession? = null,
    val showCreateArchiveDialog: Boolean = false,
    val showMovePickerDialog: Boolean = false,
    val showBatchDeleteConfirmDialog: Boolean = false,
    val renameDialogTarget: BeachFileItem? = null,
    val snackbarMessage: String? = null
) {
    val currentPathOrUri: String
        get() = pathStack.lastOrNull().orEmpty()

    val canNavigateUpFolder: Boolean
        get() = pathStack.size > 1

    val canNavigateUpDownloads: Boolean
        get() = downloadsBreadcrumbs.size > 1

    val canNavigateUpDevice: Boolean
        get() = deviceBreadcrumbs.size > 1

    val canNavigateUpDrawerProvider: Boolean
        get() = (activeDrawerMode as? DrawerSelectionMode.Provider)?.breadcrumbs?.let { it.size > 1 } == true

    val activeListItems: List<BeachFileItem>
        get() = when (destination) {
            MainDestination.BEACH -> currentItems
            MainDestination.DOWNLOADS -> downloadsItems
            MainDestination.DEVICE -> deviceItems
            MainDestination.DRAWER_COLLECTION -> drawerCollectionItems
            MainDestination.SHELLS -> galleryImages
            else -> emptyList()
        }

    val selectedItems: List<BeachFileItem>
        get() = if (selectedKeys.isEmpty()) {
            emptyList()
        } else {
            activeListItems.filter { it.key in selectedKeys }
        }

    val selectedTotalBytes: Long
        get() = selectedItems.sumOf { it.sizeBytes.coerceAtLeast(0L) }

    val canPasteInCurrentScreen: Boolean
        get() = when (destination) {
            MainDestination.BEACH -> true
            MainDestination.DOWNLOADS -> true
            MainDestination.DEVICE -> {
                val crumb = deviceBreadcrumbs.lastOrNull()
                crumb != null && crumb.pathOrUri != FileStorageRepository.VIRTUAL_ANDROID_HUB
            }
            MainDestination.DRAWER_COLLECTION -> {
                val mode = activeDrawerMode
                mode is DrawerSelectionMode.Provider && mode.breadcrumbs.isNotEmpty()
            }
            else -> false
        }
}

class BeachFileViewModel(
    private val appContext: Context,
    private val fileRepository: FileStorageRepository,
    private val preferencesRepository: PreferencesRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val fileOpsManager: FileOperationsManager,
    private val archiveRepository: ArchiveRepository,
    val soundEngine: BeachSoundEngine,
    val appLauncherManager: AppLauncherAndApkManager = AppLauncherAndApkManager(appContext),
    connectAudioService: Boolean = true
) : ViewModel() {

    private val _uiState = MutableStateFlow(BeachUiState())
    val uiState: StateFlow<BeachUiState> = _uiState.asStateFlow()

    val audioPlayerManager = AudioPlayerManager(
        context = appContext,
        scope = viewModelScope,
        connectToService = connectAudioService
    )
    val audioPlayerState: StateFlow<AudioPlayerUiState>
        get() = audioPlayerManager.state

    val preferencesState: StateFlow<AppPreferencesState> = preferencesRepository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = AppPreferencesState()
        )

    val backupLogs: StateFlow<List<BackupLogEntity>> = cloudSyncRepository.observeBackupLogs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    private var latestMetaMap: Map<String, SandItemMetaEntity> = emptyMap()
    private var latestConnectedFolders: List<ConnectedFolderEntity> = emptyList()
    private var latestShowHidden: Boolean = false
    private val dismissedFromDeckKeys = mutableSetOf<String>()
    private var autoBackupJob: Job? = null
    private var activeFileOpJob: Job? = null
    private var pendingConflictDeferred: CompletableDeferred<ConflictDecision>? = null
    private var pendingApkInstallItem: BeachFileItem? = null

    // L1 Raw Unfiltered Scope Buffers (enables 0ms instant search, sort, and tab switches)
    private var rawBeachItems: List<BeachFileItem> = emptyList()
    private var rawDownloadsItems: List<BeachFileItem> = emptyList()
    private var rawDeviceItems: List<BeachFileItem> = emptyList()
    private var rawDrawerItems: List<BeachFileItem> = emptyList()
    private var rawGalleryMedia: List<BeachFileItem> = emptyList()
    private var rawInstalledApps: List<InstalledAppInfo> = emptyList()

    private var beachReconcileJob: Job? = null
    private var downloadsReconcileJob: Job? = null
    private var deviceReconcileJob: Job? = null
    private var galleryReconcileJob: Job? = null
    private var drawerReconcileJob: Job? = null
    private var spaceStatsJob: Job? = null
    private var fileObserverDebounceJob: Job? = null
    private var activeDirObserver: FileObserver? = null
    private var observedLocalDirPath: String? = null
    private var lastResumeReconcileAtMs: Long = 0L

    private data class ActiveFolderTarget(
        val isSaf: Boolean,
        val pathOrUri: String,
        val safTreeUri: String,
        val readableDisplayPath: String,
        val isWritable: Boolean
    )

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                fileRepository.ensureWorkspaceInitialized()
            }
            val metaSnap = fileRepository.getAllMetaSnapshot()
            val foldersSnap = fileRepository.getConnectedFoldersSnapshot()
            latestMetaMap = metaSnap
            latestConnectedFolders = foldersSnap

            val fullAccess = fileRepository.hasAllFilesAccess()
            val coveHasUserFiles = withContext(Dispatchers.IO) {
                fileRepository.beaverCoveDir.listFiles()
                    ?.any { it.name != "Корчі_Кошик" && !it.name.startsWith(".") } == true
            }
            val initialRoot = if (fullAccess && !coveHasUserFiles) {
                StorageRoot.InternalShared()
            } else {
                StorageRoot.BeaverCove()
            }
            val initialRootPath = fileRepository.rootPathFor(initialRoot)
            val rootDeviceCrumb = fileRepository.initialDeviceBreadcrumb()
            val rootDownloadsCrumb = fileRepository.initialDownloadsBreadcrumb(foldersSnap)
            val sysDownloadsMounted = fileRepository.isSystemDownloadsMounted(foldersSnap)

            // Hydrate immediately from persistent Room index (with anti-phantom existence verification)
            val beachKey = fileRepository.beachScopeKey(initialRoot, initialRootPath, latestShowHidden)
            val dlKey = fileRepository.downloadsScopeKey(rootDownloadsCrumb, latestShowHidden)
            val devKey = fileRepository.deviceScopeKey(rootDeviceCrumb, latestShowHidden)
            val galKey = fileRepository.galleryScopeKey(latestShowHidden)

            val cachedBeach = fileRepository.getCachedScopeWithAntiPhantom(beachKey, metaSnap)
            val cachedDl = fileRepository.getCachedScopeWithAntiPhantom(dlKey, metaSnap)
            val cachedDev = fileRepository.getCachedScopeWithAntiPhantom(devKey, metaSnap)
            val cachedGal = fileRepository.getCachedScopeWithAntiPhantom(galKey, metaSnap)
            val quickStats = withContext(Dispatchers.IO) {
                fileRepository.computeQuickDeviceStorageStats()
            }

            if (cachedBeach != null) rawBeachItems = cachedBeach
            if (cachedDl != null) rawDownloadsItems = cachedDl
            if (cachedDev != null) rawDeviceItems = cachedDev
            if (cachedGal != null) rawGalleryMedia = cachedGal

            _uiState.update { state ->
                state.copy(
                    isInitialized = true,
                    hasAllFilesAccess = fullAccess,
                    connectedFolders = foldersSnap,
                    activeRoot = initialRoot,
                    pathStack = listOf(initialRootPath),
                    currentItems = applyFilterAndSort(
                        items = rawBeachItems,
                        query = state.searchQuery,
                        category = state.selectedCategoryFilter,
                        sortOption = state.sortOption
                    ),
                    downloadsBreadcrumbs = listOf(rootDownloadsCrumb),
                    downloadsItems = applyFilterAndSort(
                        items = rawDownloadsItems,
                        query = state.downloadsSearchQuery,
                        category = null,
                        sortOption = state.downloadsSortOption
                    ),
                    isSystemDownloadsMounted = sysDownloadsMounted,
                    deviceBreadcrumbs = listOf(rootDeviceCrumb),
                    deviceItems = applyFilterAndSort(
                        items = rawDeviceItems,
                        query = state.deviceSearchQuery,
                        category = null,
                        sortOption = state.deviceSortOption
                    ),
                    galleryImages = filterGalleryMedia(
                        items = rawGalleryMedia,
                        query = state.gallerySearchQuery,
                        filter = state.galleryMediaFilter
                    ),
                    storageStats = quickStats
                )
            }

            attachActiveDirectoryObserver()
            lastResumeReconcileAtMs = System.currentTimeMillis()

            // Reconcile active tab first, then secondary scopes quietly in background
            reconcileBeachScope(forceEmitIfUnchanged = cachedBeach == null)
            viewModelScope.launch(Dispatchers.IO) {
                val providers = fileRepository.queryInstalledDocumentProviders(
                    connectedFolders = latestConnectedFolders,
                    freeDeviceBytes = quickStats.freeDeviceBytes
                )
                _uiState.update { it.copy(installedProviders = providers) }
                reconcileDownloadsScope(forceEmitIfUnchanged = cachedDl == null)
                reconcileDeviceScope(forceEmitIfUnchanged = cachedDev == null)
                reconcileGalleryScope(forceEmitIfUnchanged = cachedGal == null)
            }
        }

        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collect { prefs ->
                soundEngine.updateSettings(
                    surfEnabled = prefs.surfEnabled,
                    surfVol = prefs.surfVolume,
                    musicEnabled = prefs.musicEnabled,
                    musicVol = prefs.musicVolume,
                    sfxEnabled = prefs.sfxEnabled
                )
                val hiddenChanged = prefs.showHiddenFiles != latestShowHidden
                latestShowHidden = prefs.showHiddenFiles
                if (hiddenChanged && _uiState.value.isInitialized) {
                    refreshAllData()
                }
            }
        }

        viewModelScope.launch {
            combine(
                fileRepository.observeAllMeta(),
                fileRepository.observeConnectedFolders()
            ) { metaList, folders ->
                metaList.associateBy { it.fileKey } to folders
            }.collect { (metaMap, folders) ->
                val foldersChanged = folders != latestConnectedFolders
                latestMetaMap = metaMap
                latestConnectedFolders = folders
                if (!_uiState.value.isInitialized) return@collect

                // Re-apply star/trash/sand-coordinates metadata in-memory in <1ms without disk rescan
                rawBeachItems = applyMetaOverlay(rawBeachItems, metaMap)
                rawDownloadsItems = applyMetaOverlay(rawDownloadsItems, metaMap)
                rawDeviceItems = applyMetaOverlay(rawDeviceItems, metaMap)
                rawGalleryMedia = applyMetaOverlay(rawGalleryMedia, metaMap)
                rawDrawerItems = applyMetaOverlay(rawDrawerItems, metaMap)

                _uiState.update { prev ->
                    val updatedDlCrumbs = if (prev.downloadsBreadcrumbs.size <= 1) {
                        listOf(fileRepository.initialDownloadsBreadcrumb(folders))
                    } else {
                        prev.downloadsBreadcrumbs
                    }
                    prev.copy(
                        connectedFolders = folders,
                        downloadsBreadcrumbs = updatedDlCrumbs,
                        isSystemDownloadsMounted = fileRepository.isSystemDownloadsMounted(folders),
                        currentItems = applyFilterAndSort(
                            rawBeachItems,
                            prev.searchQuery,
                            prev.selectedCategoryFilter,
                            prev.sortOption
                        ),
                        downloadsItems = applyFilterAndSort(
                            rawDownloadsItems,
                            prev.downloadsSearchQuery,
                            null,
                            prev.downloadsSortOption
                        ),
                        deviceItems = applyFilterAndSort(
                            rawDeviceItems,
                            prev.deviceSearchQuery,
                            null,
                            prev.deviceSortOption
                        ),
                        galleryImages = filterGalleryMedia(
                            rawGalleryMedia,
                            prev.gallerySearchQuery,
                            prev.galleryMediaFilter
                        ),
                        drawerCollectionItems = applyFilterAndSort(
                            rawDrawerItems,
                            prev.drawerSearchQuery,
                            null,
                            prev.drawerSortOption
                        )
                    )
                }

                if (foldersChanged) {
                    reconcileDownloadsScope(forceEmitIfUnchanged = true)
                    reconcileDeviceScope(forceEmitIfUnchanged = true)
                }
            }
        }
    }

    private fun applyMetaOverlay(
        items: List<BeachFileItem>,
        metaMap: Map<String, SandItemMetaEntity>
    ): List<BeachFileItem> {
        if (items.isEmpty()) return items
        val isTrashView = _uiState.value.destination == MainDestination.DRAWER_COLLECTION &&
            (_uiState.value.activeDrawerMode as? DrawerSelectionMode.Category)?.type == DrawerCategoryType.TRASH
        return items.mapNotNull { item ->
            val meta = metaMap[item.key]
            val isTrashed = meta?.isTrashed == true
            if (isTrashed && !isTrashView && !item.pathOrUri.contains("Корчі_Кошик")) {
                return@mapNotNull null
            }
            if (meta == null) {
                if (item.isStarred || item.isTrashed) {
                    item.copy(isStarred = false, isTrashed = false)
                } else {
                    item
                }
            } else {
                val customCat = meta.customCategory?.let { runCatching { FileCategory.valueOf(it) }.getOrNull() }
                item.copy(
                    category = if (item.isDirectory) FileCategory.FOLDER else (customCat ?: item.category),
                    sandX = meta.sandX,
                    sandY = meta.sandY,
                    isStarred = meta.isStarred,
                    isTrashed = meta.isTrashed
                )
            }
        }
    }

    private fun attachActiveDirectoryObserver() {
        val state = _uiState.value
        val targetLocalPath: String? = when (state.destination) {
            MainDestination.BEACH -> {
                if (state.activeRoot is StorageRoot.SafTree) null
                else state.currentPathOrUri.takeIf { it.isNotBlank() && !it.startsWith("virtual:") }
            }
            MainDestination.DOWNLOADS -> {
                val crumb = state.downloadsBreadcrumbs.lastOrNull()
                when {
                    crumb == null -> null
                    crumb.isSaf -> null
                    crumb.pathOrUri == FileStorageRepository.VIRTUAL_DOWNLOADS_ROOT ->
                        fileRepository.publicDownloadsDir.absolutePath
                    crumb.pathOrUri.startsWith("virtual:") -> null
                    else -> crumb.pathOrUri
                }
            }
            MainDestination.DEVICE -> {
                val crumb = state.deviceBreadcrumbs.lastOrNull()
                if (crumb == null || crumb.isSaf || crumb.pathOrUri.startsWith("virtual:")) null
                else crumb.pathOrUri
            }
            else -> null
        }

        if (targetLocalPath == observedLocalDirPath) return
        runCatching { activeDirObserver?.stopWatching() }
        activeDirObserver = null
        observedLocalDirPath = targetLocalPath

        if (targetLocalPath.isNullOrBlank()) return
        val dirFile = File(targetLocalPath)
        if (!dirFile.exists() || !dirFile.isDirectory) return

        val mask = FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE or
            FileObserver.DELETE_SELF

        @Suppress("DEPRECATION")
        val observer = object : FileObserver(dirFile.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.endsWith(".journal")) return
                fileObserverDebounceJob?.cancel()
                fileObserverDebounceJob = viewModelScope.launch {
                    delay(260L)
                    reconcileActiveDestination(forceEmitIfUnchanged = false)
                }
            }
        }
        runCatching {
            observer.startWatching()
            activeDirObserver = observer
        }
    }

    fun createAllFilesAccessIntent(): Intent = fileRepository.createAllFilesAccessIntent()

    fun refreshStoragePermissionAndData() {
        val fullAccess = fileRepository.hasAllFilesAccess()
        val prevState = _uiState.value
        val wasGranted = prevState.hasAllFilesAccess
        if (fullAccess != wasGranted) {
            val shouldSwitchRootToInternal = fullAccess &&
                prevState.activeRoot is StorageRoot.BeaverCove &&
                prevState.pathStack.size <= 1 &&
                prevState.currentItems.isEmpty()
            _uiState.update { state ->
                val nextRoot = if (shouldSwitchRootToInternal) StorageRoot.InternalShared() else state.activeRoot
                val nextStack = if (shouldSwitchRootToInternal) {
                    listOf(fileRepository.rootPathFor(nextRoot))
                } else {
                    state.pathStack
                }
                val nextDlCrumbs = if (state.downloadsBreadcrumbs.size <= 1) {
                    listOf(fileRepository.initialDownloadsBreadcrumb(latestConnectedFolders))
                } else {
                    state.downloadsBreadcrumbs
                }
                state.copy(
                    hasAllFilesAccess = fullAccess,
                    activeRoot = nextRoot,
                    pathStack = nextStack,
                    downloadsBreadcrumbs = nextDlCrumbs
                )
            }
            if (prevState.isInitialized) {
                attachActiveDirectoryObserver()
                refreshAllData()
            }
        } else if (prevState.isInitialized) {
            val now = System.currentTimeMillis()
            if (now - lastResumeReconcileAtMs > 1_200L) {
                lastResumeReconcileAtMs = now
                reconcileActiveDestination(forceEmitIfUnchanged = false)
            }
        }
    }

    fun toggleShowHiddenFiles() {
        soundEngine.playWaterDroplet()
        viewModelScope.launch {
            val next = !preferencesState.value.showHiddenFiles
            preferencesRepository.setShowHiddenFiles(next)
            showMessage(
                appContext.getString(
                    if (next) R.string.menu_show_hidden_files else R.string.menu_hide_hidden_files
                )
            )
        }
    }

    /**
     * Reconciles ONLY the currently visible screen against the filesystem so switching tabs,
     * navigating folders, or returning from background never reloads everything from scratch.
     */
    private fun reconcileActiveDestination(forceEmitIfUnchanged: Boolean = false) {
        when (_uiState.value.destination) {
            MainDestination.BEACH -> reconcileBeachScope(forceEmitIfUnchanged)
            MainDestination.SHELLS -> reconcileGalleryScope(forceEmitIfUnchanged)
            MainDestination.DOWNLOADS -> reconcileDownloadsScope(forceEmitIfUnchanged)
            MainDestination.DEVICE -> reconcileDeviceScope(forceEmitIfUnchanged)
            MainDestination.DRAWER_COLLECTION -> reconcileDrawerScope(forceEmitIfUnchanged)
            MainDestination.SPACE -> refreshSpaceDeckAndStats()
            MainDestination.SETTINGS -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val quick = fileRepository.computeQuickDeviceStorageStats(_uiState.value.storageStats)
                    _uiState.update { it.copy(storageStats = quick) }
                }
            }
        }
    }

    /**
     * Called after explicit file mutations (create, rename, move, paste, delete, archive, import).
     * Reconciles active scope first + updates persistent Room index and purges any phantom files.
     */
    fun refreshAllData() {
        reconcileActiveDestination(forceEmitIfUnchanged = true)
        val dest = _uiState.value.destination
        if (dest != MainDestination.BEACH) reconcileBeachScope(forceEmitIfUnchanged = false)
        if (dest != MainDestination.DOWNLOADS) reconcileDownloadsScope(forceEmitIfUnchanged = false)
        if (dest != MainDestination.DEVICE) reconcileDeviceScope(forceEmitIfUnchanged = false)
        if (dest != MainDestination.SHELLS) reconcileGalleryScope(forceEmitIfUnchanged = false)
        if (dest == MainDestination.SPACE) refreshSpaceDeckAndStats()
    }

    private fun reconcileBeachScope(forceEmitIfUnchanged: Boolean = false) {
        beachReconcileJob?.cancel()
        val snapState = _uiState.value
        val root = snapState.activeRoot
        val currentPath = snapState.currentPathOrUri.ifBlank { fileRepository.rootPathFor(root) }
        val showHidden = latestShowHidden
        val scopeKey = fileRepository.beachScopeKey(root, currentPath, showHidden)

        // Step 1: Synchronous L1 memory hit (0ms)
        val l1Cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
        if (l1Cached != null && forceEmitIfUnchanged) {
            rawBeachItems = l1Cached
            recomputeBeachUiItems()
        }

        beachReconcileJob = viewModelScope.launch(Dispatchers.IO) {
            // Step 2: If L1 missed, hydrate from persistent Room cache with anti-phantom verification
            if (l1Cached == null) {
                val roomCached = fileRepository.getCachedScopeWithAntiPhantom(scopeKey, latestMetaMap)
                if (roomCached != null && isStillOnBeachPath(root, currentPath)) {
                    rawBeachItems = roomCached
                    recomputeBeachUiItems()
                }
            }

            // Step 3: Live directory listing + delta reconciliation against persistent cache
            val freshItems = fileRepository.listDirectory(
                root = root,
                currentPathOrUri = currentPath,
                metaMap = latestMetaMap,
                showHidden = showHidden
            )
            val dirModified = if (root !is StorageRoot.SafTree && !currentPath.startsWith("virtual:")) {
                runCatching { File(currentPath).lastModified() }.getOrDefault(0L)
            } else 0L

            val (reconciled, didChange) = fileRepository.reconcileMemoryScopeFast(
                scopeKey = scopeKey,
                freshItems = freshItems
            )

            if (isStillOnBeachPath(root, currentPath) && (didChange || forceEmitIfUnchanged || l1Cached == null)) {
                rawBeachItems = reconciled
                recomputeBeachUiItems()
            }

            if (didChange) {
                fileRepository.persistReconciledScopeToDisk(
                    scopeKey = scopeKey,
                    freshItems = reconciled,
                    dirLastModified = dirModified
                )
            }
        }
    }

    private fun isStillOnBeachPath(root: StorageRoot, path: String): Boolean {
        val cur = _uiState.value
        val curPath = cur.currentPathOrUri.ifBlank { fileRepository.rootPathFor(cur.activeRoot) }
        return cur.activeRoot == root && curPath == path
    }

    private fun reconcileDownloadsScope(forceEmitIfUnchanged: Boolean = false) {
        downloadsReconcileJob?.cancel()
        val snapState = _uiState.value
        val activeCrumb = snapState.downloadsBreadcrumbs.lastOrNull()
            ?: fileRepository.initialDownloadsBreadcrumb(latestConnectedFolders)
        val showHidden = latestShowHidden
        val scopeKey = fileRepository.downloadsScopeKey(activeCrumb, showHidden)

        val l1Cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
        if (l1Cached != null && forceEmitIfUnchanged) {
            rawDownloadsItems = l1Cached
            recomputeDownloadsUiItems()
        }

        downloadsReconcileJob = viewModelScope.launch(Dispatchers.IO) {
            if (l1Cached == null) {
                val roomCached = fileRepository.getCachedScopeWithAntiPhantom(scopeKey, latestMetaMap)
                if (roomCached != null && _uiState.value.downloadsBreadcrumbs.lastOrNull()?.pathOrUri == activeCrumb.pathOrUri) {
                    rawDownloadsItems = roomCached
                    recomputeDownloadsUiItems()
                }
            }

            val freshItems = fileRepository.listDownloadsBreadcrumb(
                crumb = activeCrumb,
                connectedFolders = latestConnectedFolders,
                metaMap = latestMetaMap,
                showHidden = showHidden
            )
            val (reconciled, didChange) = fileRepository.reconcileMemoryScopeFast(
                scopeKey = scopeKey,
                freshItems = freshItems
            )
            if (
                (_uiState.value.downloadsBreadcrumbs.lastOrNull()?.pathOrUri ?: activeCrumb.pathOrUri) == activeCrumb.pathOrUri &&
                (didChange || forceEmitIfUnchanged || l1Cached == null)
            ) {
                rawDownloadsItems = reconciled
                recomputeDownloadsUiItems()
            }
            if (didChange) {
                fileRepository.persistReconciledScopeToDisk(
                    scopeKey = scopeKey,
                    freshItems = reconciled
                )
            }
        }
    }

    private fun reconcileDeviceScope(forceEmitIfUnchanged: Boolean = false) {
        deviceReconcileJob?.cancel()
        val snapState = _uiState.value
        val activeCrumb = snapState.deviceBreadcrumbs.lastOrNull() ?: fileRepository.initialDeviceBreadcrumb()
        val showHidden = latestShowHidden
        val scopeKey = fileRepository.deviceScopeKey(activeCrumb, showHidden)

        val l1Cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
        if (l1Cached != null && forceEmitIfUnchanged) {
            rawDeviceItems = l1Cached
            recomputeDeviceUiItems()
        }

        deviceReconcileJob = viewModelScope.launch(Dispatchers.IO) {
            if (l1Cached == null) {
                val roomCached = fileRepository.getCachedScopeWithAntiPhantom(scopeKey, latestMetaMap)
                if (roomCached != null && _uiState.value.deviceBreadcrumbs.lastOrNull()?.pathOrUri == activeCrumb.pathOrUri) {
                    rawDeviceItems = roomCached
                    recomputeDeviceUiItems()
                }
            }

            val freshItems = fileRepository.listDeviceBreadcrumb(
                crumb = activeCrumb,
                connectedFolders = latestConnectedFolders,
                metaMap = latestMetaMap,
                showHidden = showHidden
            )
            val quickStats = fileRepository.computeQuickDeviceStorageStats(_uiState.value.storageStats)
            val (reconciled, didChange) = fileRepository.reconcileMemoryScopeFast(
                scopeKey = scopeKey,
                freshItems = freshItems
            )
            if (
                (_uiState.value.deviceBreadcrumbs.lastOrNull()?.pathOrUri ?: activeCrumb.pathOrUri) == activeCrumb.pathOrUri &&
                (didChange || forceEmitIfUnchanged || l1Cached == null)
            ) {
                rawDeviceItems = reconciled
                _uiState.update { it.copy(storageStats = quickStats) }
                recomputeDeviceUiItems()
            }
            if (didChange) {
                fileRepository.persistReconciledScopeToDisk(
                    scopeKey = scopeKey,
                    freshItems = reconciled
                )
            }
        }
    }

    private fun reconcileGalleryScope(forceEmitIfUnchanged: Boolean = false) {
        galleryReconcileJob?.cancel()
        val showHidden = latestShowHidden
        val scopeKey = fileRepository.galleryScopeKey(showHidden)

        val l1Cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
        if (l1Cached != null && forceEmitIfUnchanged) {
            rawGalleryMedia = l1Cached
            recomputeGalleryUiItems()
        }

        galleryReconcileJob = viewModelScope.launch(Dispatchers.IO) {
            if (l1Cached == null) {
                val roomCached = fileRepository.getCachedScopeWithAntiPhantom(scopeKey, latestMetaMap)
                if (roomCached != null) {
                    rawGalleryMedia = roomCached
                    recomputeGalleryUiItems()
                }
            }

            val freshMedia = fileRepository.queryGalleryMediaItems(
                metaMap = latestMetaMap,
                showHidden = showHidden
            )
            val (reconciled, didChange) = fileRepository.reconcileMemoryScopeFast(
                scopeKey = scopeKey,
                freshItems = freshMedia
            )
            if (didChange || forceEmitIfUnchanged || l1Cached == null) {
                rawGalleryMedia = reconciled
                recomputeGalleryUiItems()
            }
            if (didChange) {
                fileRepository.persistReconciledScopeToDisk(
                    scopeKey = scopeKey,
                    freshItems = reconciled
                )
            }
        }
    }

    private fun reconcileDrawerScope(forceEmitIfUnchanged: Boolean = false) {
        drawerReconcileJob?.cancel()
        val mode = _uiState.value.activeDrawerMode
        if (mode is DrawerSelectionMode.InstalledApps) {
            loadInstalledAppsInternal(forceRefresh = forceEmitIfUnchanged && rawInstalledApps.isEmpty())
            return
        }

        val showHidden = latestShowHidden
        val scopeKey: String? = when (mode) {
            is DrawerSelectionMode.Category -> fileRepository.drawerCategoryScopeKey(mode.type, showHidden)
            is DrawerSelectionMode.Provider -> mode.breadcrumbs.lastOrNull()?.let {
                fileRepository.deviceScopeKey(it, showHidden)
            }
            DrawerSelectionMode.InstalledApps -> null
        }

        if (scopeKey != null) {
            val l1Cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
            if (l1Cached != null && forceEmitIfUnchanged) {
                rawDrawerItems = l1Cached
                recomputeDrawerUiItems()
            }
        }

        drawerReconcileJob = viewModelScope.launch(Dispatchers.IO) {
            if (scopeKey != null) {
                val roomCached = fileRepository.getCachedScopeWithAntiPhantom(scopeKey, latestMetaMap)
                if (roomCached != null && _uiState.value.activeDrawerMode == mode) {
                    rawDrawerItems = roomCached
                    recomputeDrawerUiItems()
                }
            }

            val freshItems = when (mode) {
                is DrawerSelectionMode.Category -> {
                    fileRepository.listDrawerCategoryItems(
                        type = mode.type,
                        connectedFolders = latestConnectedFolders,
                        metaMap = latestMetaMap,
                        showHidden = showHidden
                    )
                }
                is DrawerSelectionMode.Provider -> {
                    val crumb = mode.breadcrumbs.lastOrNull()
                    if (crumb != null && crumb.isSaf && crumb.safTreeUri.isNotBlank()) {
                        fileRepository.listDeviceBreadcrumb(
                            crumb = crumb,
                            connectedFolders = latestConnectedFolders,
                            metaMap = latestMetaMap,
                            showHidden = showHidden
                        )
                    } else {
                        emptyList()
                    }
                }
                DrawerSelectionMode.InstalledApps -> emptyList()
            }

            if (scopeKey != null) {
                val (reconciled, didChange) = fileRepository.reconcileMemoryScopeFast(scopeKey, freshItems)
                if (_uiState.value.activeDrawerMode == mode && (didChange || forceEmitIfUnchanged)) {
                    rawDrawerItems = reconciled
                    recomputeDrawerUiItems()
                }
                if (didChange) {
                    fileRepository.persistReconciledScopeToDisk(scopeKey, reconciled)
                }
            } else if (_uiState.value.activeDrawerMode == mode) {
                rawDrawerItems = freshItems
                recomputeDrawerUiItems()
            }
        }
    }

    private fun refreshSpaceDeckAndStats() {
        spaceStatsJob?.cancel()
        spaceStatsJob = viewModelScope.launch(Dispatchers.IO) {
            val showHidden = latestShowHidden
            val allLocalFiles = fileRepository.collectAllLocalFiles(
                metaMap = latestMetaMap,
                includeTrashed = false,
                showHidden = showHidden
            )
            val deckCandidates = allLocalFiles
                .filter { !it.isDirectory && it.key !in dismissedFromDeckKeys }
                .sortedByDescending { it.lastModifiedMillis }
            val stats = fileRepository.computeStorageStats(latestMetaMap, showHidden = showHidden)
            _uiState.update {
                it.copy(
                    swipeDeck = deckCandidates,
                    storageStats = stats
                )
            }
        }
    }

    private var uiSortJob: Job? = null

    private fun shouldOffloadSortToBackground(itemCount: Int): Boolean {
        return itemCount > 300 && Looper.myLooper() == Looper.getMainLooper()
    }

    private fun recomputeBeachUiItems() {
        val snapshotItems = rawBeachItems
        if (shouldOffloadSortToBackground(snapshotItems.size)) {
            uiSortJob?.cancel()
            uiSortJob = viewModelScope.launch(Dispatchers.Default) {
                val snap = _uiState.value
                val sorted = applyFilterAndSort(
                    items = snapshotItems,
                    query = snap.searchQuery,
                    category = snap.selectedCategoryFilter,
                    sortOption = snap.sortOption
                )
                val hasAccess = fileRepository.hasAllFilesAccess()
                _uiState.update { prev ->
                    val pruned = if (prev.destination == MainDestination.BEACH && prev.selectedKeys.isNotEmpty()) {
                        val valid = sorted.mapTo(HashSet(sorted.size)) { it.key }
                        prev.selectedKeys.intersect(valid)
                    } else prev.selectedKeys
                    prev.copy(
                        hasAllFilesAccess = hasAccess,
                        currentItems = sorted,
                        selectedKeys = pruned
                    )
                }
            }
            return
        }
        val snap = _uiState.value
        val sorted = applyFilterAndSort(
            items = snapshotItems,
            query = snap.searchQuery,
            category = snap.selectedCategoryFilter,
            sortOption = snap.sortOption
        )
        val hasAccess = fileRepository.hasAllFilesAccess()
        _uiState.update { prev ->
            val pruned = if (prev.destination == MainDestination.BEACH && prev.selectedKeys.isNotEmpty()) {
                val valid = sorted.mapTo(HashSet(sorted.size)) { it.key }
                prev.selectedKeys.intersect(valid)
            } else prev.selectedKeys
            prev.copy(
                hasAllFilesAccess = hasAccess,
                currentItems = sorted,
                selectedKeys = pruned
            )
        }
    }

    private fun recomputeDownloadsUiItems() {
        val snapshotItems = rawDownloadsItems
        if (shouldOffloadSortToBackground(snapshotItems.size)) {
            uiSortJob?.cancel()
            uiSortJob = viewModelScope.launch(Dispatchers.Default) {
                val snap = _uiState.value
                val sorted = applyFilterAndSort(
                    items = snapshotItems,
                    query = snap.downloadsSearchQuery,
                    category = null,
                    sortOption = snap.downloadsSortOption
                )
                val mounted = fileRepository.isSystemDownloadsMounted(latestConnectedFolders)
                _uiState.update { prev ->
                    val pruned = if (prev.destination == MainDestination.DOWNLOADS && prev.selectedKeys.isNotEmpty()) {
                        val valid = sorted.mapTo(HashSet(sorted.size)) { it.key }
                        prev.selectedKeys.intersect(valid)
                    } else prev.selectedKeys
                    prev.copy(
                        downloadsItems = sorted,
                        isSystemDownloadsMounted = mounted,
                        selectedKeys = pruned
                    )
                }
            }
            return
        }
        val snap = _uiState.value
        val sorted = applyFilterAndSort(
            items = snapshotItems,
            query = snap.downloadsSearchQuery,
            category = null,
            sortOption = snap.downloadsSortOption
        )
        val mounted = fileRepository.isSystemDownloadsMounted(latestConnectedFolders)
        _uiState.update { prev ->
            val pruned = if (prev.destination == MainDestination.DOWNLOADS && prev.selectedKeys.isNotEmpty()) {
                val valid = sorted.mapTo(HashSet(sorted.size)) { it.key }
                prev.selectedKeys.intersect(valid)
            } else prev.selectedKeys
            prev.copy(
                downloadsItems = sorted,
                isSystemDownloadsMounted = mounted,
                selectedKeys = pruned
            )
        }
    }

    private fun recomputeDeviceUiItems() {
        val snapshotItems = rawDeviceItems
        if (shouldOffloadSortToBackground(snapshotItems.size)) {
            uiSortJob?.cancel()
            uiSortJob = viewModelScope.launch(Dispatchers.Default) {
                val snap = _uiState.value
                val sorted = applyFilterAndSort(
                    items = snapshotItems,
                    query = snap.deviceSearchQuery,
                    category = null,
                    sortOption = snap.deviceSortOption
                )
                _uiState.update { prev ->
                    val pruned = if (prev.destination == MainDestination.DEVICE && prev.selectedKeys.isNotEmpty()) {
                        val valid = sorted.mapTo(HashSet(sorted.size)) { it.key }
                        prev.selectedKeys.intersect(valid)
                    } else prev.selectedKeys
                    prev.copy(
                        deviceItems = sorted,
                        selectedKeys = pruned
                    )
                }
            }
            return
        }
        val snap = _uiState.value
        val sorted = applyFilterAndSort(
            items = snapshotItems,
            query = snap.deviceSearchQuery,
            category = null,
            sortOption = snap.deviceSortOption
        )
        _uiState.update { prev ->
            val pruned = if (prev.destination == MainDestination.DEVICE && prev.selectedKeys.isNotEmpty()) {
                val valid = sorted.mapTo(HashSet(sorted.size)) { it.key }
                prev.selectedKeys.intersect(valid)
            } else prev.selectedKeys
            prev.copy(
                deviceItems = sorted,
                selectedKeys = pruned
            )
        }
    }

    private fun recomputeDrawerUiItems() {
        val snapshotItems = rawDrawerItems
        if (shouldOffloadSortToBackground(snapshotItems.size)) {
            uiSortJob?.cancel()
            uiSortJob = viewModelScope.launch(Dispatchers.Default) {
                val snap = _uiState.value
                val sorted = applyFilterAndSort(
                    items = snapshotItems,
                    query = snap.drawerSearchQuery,
                    category = null,
                    sortOption = snap.drawerSortOption
                )
                _uiState.update { prev ->
                    val pruned = if (prev.destination == MainDestination.DRAWER_COLLECTION && prev.selectedKeys.isNotEmpty()) {
                        val valid = sorted.mapTo(HashSet(sorted.size)) { it.key }
                        prev.selectedKeys.intersect(valid)
                    } else prev.selectedKeys
                    prev.copy(
                        drawerCollectionItems = sorted,
                        selectedKeys = pruned
                    )
                }
            }
            return
        }
        val snap = _uiState.value
        val sorted = applyFilterAndSort(
            items = snapshotItems,
            query = snap.drawerSearchQuery,
            category = null,
            sortOption = snap.drawerSortOption
        )
        _uiState.update { prev ->
            val pruned = if (prev.destination == MainDestination.DRAWER_COLLECTION && prev.selectedKeys.isNotEmpty()) {
                val valid = sorted.mapTo(HashSet(sorted.size)) { it.key }
                prev.selectedKeys.intersect(valid)
            } else prev.selectedKeys
            prev.copy(
                drawerCollectionItems = sorted,
                selectedKeys = pruned
            )
        }
    }

    private fun recomputeGalleryUiItems() {
        val snapshotItems = rawGalleryMedia
        if (shouldOffloadSortToBackground(snapshotItems.size)) {
            uiSortJob?.cancel()
            uiSortJob = viewModelScope.launch(Dispatchers.Default) {
                val snap = _uiState.value
                val sorted = filterGalleryMedia(
                    items = snapshotItems,
                    query = snap.gallerySearchQuery,
                    filter = snap.galleryMediaFilter
                )
                _uiState.update { prev ->
                    prev.copy(galleryImages = sorted)
                }
            }
            return
        }
        val snap = _uiState.value
        val sorted = filterGalleryMedia(
            items = snapshotItems,
            query = snap.gallerySearchQuery,
            filter = snap.galleryMediaFilter
        )
        _uiState.update { prev ->
            prev.copy(galleryImages = sorted)
        }
    }

    private fun filterGalleryMedia(
        items: List<BeachFileItem>,
        query: String,
        filter: GalleryMediaFilter
    ): List<BeachFileItem> {
        val q = query.trim().lowercase(Locale.ROOT)
        return items.filter { item ->
            val matchesFilter = when (filter) {
                GalleryMediaFilter.ALL -> item.category == FileCategory.IMAGES || item.category == FileCategory.VIDEO
                GalleryMediaFilter.PHOTOS -> item.category == FileCategory.IMAGES
                GalleryMediaFilter.VIDEOS -> item.category == FileCategory.VIDEO
            }
            val matchesQuery = q.isEmpty() || item.lowerName.contains(q)
            matchesFilter && matchesQuery
        }.sortedByDescending { it.lastModifiedMillis }
    }

    private fun applyFilterAndSort(
        items: List<BeachFileItem>,
        query: String,
        category: FileCategory?,
        sortOption: SortOption
    ): List<BeachFileItem> {
        val trimmedQuery = query.trim().lowercase(Locale.ROOT)
        val filtered = if (trimmedQuery.isEmpty() && category == null) {
            items
        } else {
            items.filter { item ->
                val matchesQuery = trimmedQuery.isEmpty() || item.lowerName.contains(trimmedQuery)
                val matchesCat = category == null || item.category == category
                matchesQuery && matchesCat
            }
        }

        return when (sortOption) {
            SortOption.DATE_DESC -> filtered.sortedWith(
                compareByDescending<BeachFileItem> { it.isDirectory }.thenByDescending { it.lastModifiedMillis }
            )
            SortOption.DATE_ASC -> filtered.sortedWith(
                compareByDescending<BeachFileItem> { it.isDirectory }.thenBy { it.lastModifiedMillis }
            )
            SortOption.SIZE_DESC -> filtered.sortedWith(
                compareByDescending<BeachFileItem> { it.sizeBytes }.thenBy { it.lowerName }
            )
            SortOption.SIZE_ASC -> filtered.sortedWith(
                compareBy<BeachFileItem> { it.sizeBytes }.thenBy { it.lowerName }
            )
            SortOption.NAME_ASC -> filtered.sortedWith(
                compareByDescending<BeachFileItem> { it.isDirectory }.thenBy { it.lowerName }
            )
            SortOption.CATEGORY -> filtered.sortedWith(
                compareBy<BeachFileItem> { it.category.ordinal }.thenBy { it.lowerName }
            )
        }
    }

    fun selectDestination(dest: MainDestination) {
        _uiState.update { current ->
            current.copy(
                destination = dest,
                lastPrimaryTab = if (dest.isPrimaryTab) dest else current.lastPrimaryTab,
                selectedKeys = emptySet()
            )
        }
        attachActiveDirectoryObserver()
        reconcileActiveDestination(forceEmitIfUnchanged = false)
    }

    fun navigateBackFromMenuScreen() {
        _uiState.update { it.copy(destination = it.lastPrimaryTab, selectedKeys = emptySet()) }
        attachActiveDirectoryObserver()
        reconcileActiveDestination(forceEmitIfUnchanged = false)
    }

    fun setExplorerViewMode(mode: ExplorerViewMode) {
        soundEngine.playWaterDroplet()
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun selectStorageRoot(root: StorageRoot) {
        soundEngine.playWaterDroplet()
        val rootPath = fileRepository.rootPathFor(root)
        val scopeKey = fileRepository.beachScopeKey(root, rootPath, latestShowHidden)
        val cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
        if (cached != null) rawBeachItems = cached
        _uiState.update { prev ->
            prev.copy(
                activeRoot = root,
                pathStack = listOf(rootPath),
                searchQuery = "",
                selectedCategoryFilter = null,
                selectedKeys = emptySet(),
                currentItems = if (cached != null) {
                    applyFilterAndSort(cached, "", null, prev.sortOption)
                } else {
                    prev.currentItems
                }
            )
        }
        attachActiveDirectoryObserver()
        reconcileBeachScope(forceEmitIfUnchanged = cached == null)
    }

    fun openItem(item: BeachFileItem) {
        if (item.isDirectory) {
            soundEngine.playSandBrush()
            val root = _uiState.value.activeRoot
            val scopeKey = fileRepository.beachScopeKey(root, item.pathOrUri, latestShowHidden)
            val cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
            if (cached != null) rawBeachItems = cached
            _uiState.update { prev ->
                prev.copy(
                    pathStack = prev.pathStack + item.pathOrUri,
                    selectedKeys = emptySet(),
                    currentItems = if (cached != null) {
                        applyFilterAndSort(cached, prev.searchQuery, prev.selectedCategoryFilter, prev.sortOption)
                    } else {
                        emptyList()
                    }
                )
            }
            attachActiveDirectoryObserver()
            reconcileBeachScope(forceEmitIfUnchanged = true)
        } else {
            val siblings = when (_uiState.value.destination) {
                MainDestination.SHELLS -> _uiState.value.galleryImages
                MainDestination.BEACH -> _uiState.value.currentItems
                else -> listOf(item)
            }
            openFilePreviewOrLightbox(item, siblings)
        }
    }

    fun selectLightboxShell(item: BeachFileItem) {
        _uiState.update { it.copy(selectedLightboxShell = item) }
    }

    fun openDeviceItem(item: BeachFileItem) {
        if (item.isDirectory) {
            soundEngine.playSandBrush()
            val newCrumb = DeviceBreadcrumb(
                title = item.name,
                pathOrUri = item.pathOrUri,
                isSaf = item.isSafDocument,
                safTreeUri = item.safTreeUri
            )
            val scopeKey = fileRepository.deviceScopeKey(newCrumb, latestShowHidden)
            val cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
            if (cached != null) rawDeviceItems = cached
            _uiState.update { prev ->
                prev.copy(
                    deviceBreadcrumbs = prev.deviceBreadcrumbs + newCrumb,
                    selectedKeys = emptySet(),
                    deviceItems = if (cached != null) {
                        applyFilterAndSort(cached, prev.deviceSearchQuery, null, prev.deviceSortOption)
                    } else {
                        emptyList()
                    }
                )
            }
            attachActiveDirectoryObserver()
            reconcileDeviceScope(forceEmitIfUnchanged = true)
        } else {
            openFilePreviewOrLightbox(item, _uiState.value.deviceItems)
        }
    }

    private fun openFilePreviewOrLightbox(
        item: BeachFileItem,
        siblingItems: List<BeachFileItem> = emptyList()
    ) {
        // Guard against phantom local files deleted milliseconds ago outside the app
        if (!item.isSafDocument && !item.pathOrUri.startsWith("virtual:") && !File(item.pathOrUri).exists()) {
            viewModelScope.launch {
                fileRepository.purgePhantomItemEverywhere(item.key, item.pathOrUri)
                reconcileActiveDestination(forceEmitIfUnchanged = true)
                showMessage(appContext.getString(R.string.msg_phantom_removed))
            }
            return
        }

        when {
            item.category == FileCategory.IMAGES -> {
                soundEngine.playShellChime()
                _uiState.update { it.copy(selectedLightboxShell = item) }
            }

            item.category == FileCategory.VIDEO -> {
                soundEngine.playShellChime()
                pauseBackgroundAudioForVideo()
                val videoSiblings = siblingItems
                    .filter { !it.isDirectory && it.category == FileCategory.VIDEO }
                    .ifEmpty { listOf(item) }
                val startIdx = videoSiblings.indexOfFirst { it.key == item.key }.coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        videoPlayerState = VideoPlayerState(
                            isVisible = true,
                            currentItem = videoSiblings[startIdx],
                            playlist = videoSiblings,
                            currentIndex = startIdx
                        )
                    )
                }
            }

            item.category == FileCategory.AUDIO -> {
                soundEngine.playShellChime()
                if (preferencesState.value.musicEnabled) {
                    viewModelScope.launch { preferencesRepository.setMusicEnabled(false) }
                }
                val contextQueue = siblingItems
                    .filter { !it.isDirectory && it.category == FileCategory.AUDIO }
                    .ifEmpty { listOf(item) }
                audioPlayerManager.playFileWithContextQueue(item, contextQueue)
            }

            SupportedArchiveFormat.detectPrimaryArchive(item.name) != null -> {
                openVirtualArchive(item)
            }

            else -> {
                soundEngine.playWaterDroplet()
                if (item.name.lowercase().endsWith(".rar")) {
                    showMessage(appContext.getString(R.string.archive_rar_unsupported))
                }
                viewModelScope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        appLauncherManager.openOrInstallFile(item)
                    }
                    handleFileLaunchOutcome(item, outcome)
                }
            }
        }
    }

    private suspend fun handleFileLaunchOutcome(item: BeachFileItem, outcome: FileLaunchOutcome) {
        when (outcome) {
            FileLaunchOutcome.LaunchedSuccessfully -> Unit

            is FileLaunchOutcome.NeedsUnknownSourcesPermission -> {
                pendingApkInstallItem = item
                _uiState.update { it.copy(pendingUnknownSourcesIntent = outcome.settingsIntent) }
                showMessage(appContext.getString(R.string.apk_install_permission_needed))
            }

            is FileLaunchOutcome.FallbackToInternalPreview -> {
                val target = outcome.item
                val text = if (target.category == FileCategory.DOCUMENTS || target.sizeBytes < 256_000L) {
                    withContext(Dispatchers.IO) { fileRepository.readTextPreview(target) }
                } else {
                    ""
                }
                _uiState.update {
                    it.copy(
                        previewFileItem = target,
                        previewTextContent = text
                    )
                }
            }

            FileLaunchOutcome.FileMissing -> {
                fileRepository.purgePhantomItemEverywhere(item.key, item.pathOrUri)
                reconcileActiveDestination(forceEmitIfUnchanged = true)
                showMessage(appContext.getString(R.string.msg_phantom_removed))
            }

            is FileLaunchOutcome.LaunchError -> {
                showMessage(outcome.message)
            }
        }
    }

    private fun pauseBackgroundAudioForVideo() {
        if (audioPlayerState.value.isPlaying) {
            audioPlayerManager.togglePlayPause()
        }
        if (preferencesState.value.musicEnabled) {
            viewModelScope.launch { preferencesRepository.setMusicEnabled(false) }
        }
    }

    fun consumePendingUnknownSourcesIntent() {
        _uiState.update { it.copy(pendingUnknownSourcesIntent = null) }
    }

    fun onReturnedFromUnknownSourcesSettings() {
        val pending = pendingApkInstallItem ?: return
        pendingApkInstallItem = null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            appContext.packageManager.canRequestPackageInstalls()
        ) {
            viewModelScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    appLauncherManager.openOrInstallFile(pending)
                }
                if (outcome is FileLaunchOutcome.LaunchError) {
                    showMessage(outcome.message)
                }
            }
        }
    }

    fun openFileInExternalApp(item: BeachFileItem) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                appLauncherManager.openExternallyWithViewIntent(item, fallbackToInternalOnMissingApp = false)
            }
            when (outcome) {
                is FileLaunchOutcome.NeedsUnknownSourcesPermission -> {
                    pendingApkInstallItem = item
                    _uiState.update { it.copy(pendingUnknownSourcesIntent = outcome.settingsIntent) }
                    showMessage(appContext.getString(R.string.apk_install_permission_needed))
                }
                is FileLaunchOutcome.LaunchError -> showMessage(outcome.message)
                FileLaunchOutcome.FileMissing -> {
                    fileRepository.purgePhantomItemEverywhere(item.key, item.pathOrUri)
                    reconcileActiveDestination(forceEmitIfUnchanged = true)
                    showMessage(appContext.getString(R.string.msg_phantom_removed))
                }
                else -> {
                    closeVideoPlayer()
                    closePreviewAndLightbox()
                }
            }
        }
    }

    fun navigateUpFolder(): Boolean {
        val stack = _uiState.value.pathStack
        if (stack.size <= 1) return false
        soundEngine.playSandBrush()
        val nextStack = stack.dropLast(1)
        val parentPath = nextStack.last()
        val scopeKey = fileRepository.beachScopeKey(_uiState.value.activeRoot, parentPath, latestShowHidden)
        val cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
        if (cached != null) rawBeachItems = cached
        _uiState.update { prev ->
            prev.copy(
                pathStack = nextStack,
                selectedKeys = emptySet(),
                currentItems = if (cached != null) {
                    applyFilterAndSort(cached, prev.searchQuery, prev.selectedCategoryFilter, prev.sortOption)
                } else {
                    prev.currentItems
                }
            )
        }
        attachActiveDirectoryObserver()
        reconcileBeachScope(forceEmitIfUnchanged = cached == null)
        return true
    }

    fun navigateUpDeviceFolder(): Boolean {
        val crumbs = _uiState.value.deviceBreadcrumbs
        if (crumbs.size <= 1) return false
        soundEngine.playSandBrush()
        val nextCrumbs = crumbs.dropLast(1)
        val parentCrumb = nextCrumbs.last()
        val scopeKey = fileRepository.deviceScopeKey(parentCrumb, latestShowHidden)
        val cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
        if (cached != null) rawDeviceItems = cached
        _uiState.update { prev ->
            prev.copy(
                deviceBreadcrumbs = nextCrumbs,
                selectedKeys = emptySet(),
                deviceItems = if (cached != null) {
                    applyFilterAndSort(cached, prev.deviceSearchQuery, null, prev.deviceSortOption)
                } else {
                    prev.deviceItems
                }
            )
        }
        attachActiveDirectoryObserver()
        reconcileDeviceScope(forceEmitIfUnchanged = cached == null)
        return true
    }

    fun jumpToDeviceBreadcrumb(index: Int) {
        val crumbs = _uiState.value.deviceBreadcrumbs
        if (index !in crumbs.indices) return
        soundEngine.playSandBrush()
        val nextCrumbs = crumbs.take(index + 1)
        val targetCrumb = nextCrumbs.last()
        val scopeKey = fileRepository.deviceScopeKey(targetCrumb, latestShowHidden)
        val cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
        if (cached != null) rawDeviceItems = cached
        _uiState.update { prev ->
            prev.copy(
                deviceBreadcrumbs = nextCrumbs,
                selectedKeys = emptySet(),
                deviceItems = if (cached != null) {
                    applyFilterAndSort(cached, prev.deviceSearchQuery, null, prev.deviceSortOption)
                } else {
                    prev.deviceItems
                }
            )
        }
        attachActiveDirectoryObserver()
        reconcileDeviceScope(forceEmitIfUnchanged = cached == null)
    }

    fun updateDeviceSearchQuery(query: String) {
        _uiState.update { it.copy(deviceSearchQuery = query) }
        recomputeDeviceUiItems()
    }

    fun updateDeviceSortOption(option: SortOption) {
        soundEngine.playWaterDroplet()
        _uiState.update { it.copy(deviceSortOption = option) }
        recomputeDeviceUiItems()
    }

    fun onDeviceSafFolderGranted(treeUri: Uri) {
        viewModelScope.launch {
            val saved = fileRepository.saveConnectedSafFolder(treeUri)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val newCrumb = DeviceBreadcrumb(
                title = saved.displayName,
                pathOrUri = docUri.toString(),
                isSaf = true,
                safTreeUri = saved.treeUri
            )
            if (docId.equals("primary:", ignoreCase = true)) {
                _uiState.update {
                    it.copy(
                        deviceBreadcrumbs = listOf(fileRepository.initialDeviceBreadcrumb()),
                        selectedKeys = emptySet()
                    )
                }
            } else {
                _uiState.update {
                    val base = it.deviceBreadcrumbs.take(1)
                    it.copy(
                        deviceBreadcrumbs = base + newCrumb,
                        selectedKeys = emptySet()
                    )
                }
            }
            soundEngine.playWaterDroplet()
            showMessage("Відкрито папку «${saved.displayName}»")
            reconcileDeviceScope(forceEmitIfUnchanged = true)
        }
    }

    fun createNewDeviceFolder(name: String) {
        viewModelScope.launch {
            val crumb = _uiState.value.deviceBreadcrumbs.lastOrNull() ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                fileRepository.createFolder(crumb.isSaf, crumb.pathOrUri, name)
            }
            if (ok) {
                soundEngine.playWaterDroplet()
                showMessage("Створено папку «$name»")
                reconcileDeviceScope(forceEmitIfUnchanged = true)
            } else {
                showMessage("Оберіть відкриту папку для створення")
            }
        }
    }

    fun createNewDeviceNote(fileName: String, content: String) {
        viewModelScope.launch {
            val crumb = _uiState.value.deviceBreadcrumbs.lastOrNull() ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                fileRepository.createNoteFile(crumb.isSaf, crumb.pathOrUri, fileName, content)
            }
            if (ok) {
                soundEngine.playShellChime()
                showMessage("Файл збережено у внутрішньому сховищі")
                reconcileDeviceScope(forceEmitIfUnchanged = true)
            } else {
                showMessage("Не вдалося зберегти файл у цій папці")
            }
        }
    }

    fun importFilesToDevice(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val crumb = _uiState.value.deviceBreadcrumbs.lastOrNull() ?: return@launch
            val count = withContext(Dispatchers.IO) {
                var c = 0
                uris.forEach { uri ->
                    if (fileRepository.importExternalUriToTarget(uri, crumb.isSaf, crumb.pathOrUri)) {
                        c++
                    }
                }
                c
            }
            if (count > 0) {
                soundEngine.playShellChime()
                showMessage("Імпортовано файлів: $count")
                reconcileDeviceScope(forceEmitIfUnchanged = true)
                reconcileGalleryScope(forceEmitIfUnchanged = false)
            }
        }
    }

    // --- Downloads ("Завантаження") Tab Actions ---

    fun openDownloadsItem(item: BeachFileItem) {
        if (item.isDirectory) {
            soundEngine.playSandBrush()
            val newCrumb = DeviceBreadcrumb(
                title = item.name,
                pathOrUri = item.pathOrUri,
                isSaf = item.isSafDocument,
                safTreeUri = item.safTreeUri
            )
            val scopeKey = fileRepository.downloadsScopeKey(newCrumb, latestShowHidden)
            val cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
            if (cached != null) rawDownloadsItems = cached
            _uiState.update { prev ->
                prev.copy(
                    downloadsBreadcrumbs = prev.downloadsBreadcrumbs + newCrumb,
                    selectedKeys = emptySet(),
                    downloadsItems = if (cached != null) {
                        applyFilterAndSort(cached, prev.downloadsSearchQuery, null, prev.downloadsSortOption)
                    } else {
                        emptyList()
                    }
                )
            }
            attachActiveDirectoryObserver()
            reconcileDownloadsScope(forceEmitIfUnchanged = true)
        } else {
            openFilePreviewOrLightbox(item, _uiState.value.downloadsItems)
        }
    }

    fun navigateUpDownloadsFolder(): Boolean {
        val crumbs = _uiState.value.downloadsBreadcrumbs
        if (crumbs.size <= 1) return false
        soundEngine.playSandBrush()
        val nextCrumbs = crumbs.dropLast(1)
        val parentCrumb = nextCrumbs.last()
        val scopeKey = fileRepository.downloadsScopeKey(parentCrumb, latestShowHidden)
        val cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
        if (cached != null) rawDownloadsItems = cached
        _uiState.update { prev ->
            prev.copy(
                downloadsBreadcrumbs = nextCrumbs,
                selectedKeys = emptySet(),
                downloadsItems = if (cached != null) {
                    applyFilterAndSort(cached, prev.downloadsSearchQuery, null, prev.downloadsSortOption)
                } else {
                    prev.downloadsItems
                }
            )
        }
        attachActiveDirectoryObserver()
        reconcileDownloadsScope(forceEmitIfUnchanged = cached == null)
        return true
    }

    fun jumpToDownloadsBreadcrumb(index: Int) {
        val crumbs = _uiState.value.downloadsBreadcrumbs
        if (index !in crumbs.indices) return
        soundEngine.playSandBrush()
        val nextCrumbs = crumbs.take(index + 1)
        val targetCrumb = nextCrumbs.last()
        val scopeKey = fileRepository.downloadsScopeKey(targetCrumb, latestShowHidden)
        val cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
        if (cached != null) rawDownloadsItems = cached
        _uiState.update { prev ->
            prev.copy(
                downloadsBreadcrumbs = nextCrumbs,
                selectedKeys = emptySet(),
                downloadsItems = if (cached != null) {
                    applyFilterAndSort(cached, prev.downloadsSearchQuery, null, prev.downloadsSortOption)
                } else {
                    prev.downloadsItems
                }
            )
        }
        attachActiveDirectoryObserver()
        reconcileDownloadsScope(forceEmitIfUnchanged = cached == null)
    }

    fun updateDownloadsSearchQuery(query: String) {
        _uiState.update { it.copy(downloadsSearchQuery = query) }
        recomputeDownloadsUiItems()
    }

    fun updateDownloadsSortOption(option: SortOption) {
        soundEngine.playWaterDroplet()
        _uiState.update { it.copy(downloadsSortOption = option) }
        recomputeDownloadsUiItems()
    }

    fun onDownloadsSafGranted(treeUri: Uri) {
        viewModelScope.launch {
            val saved = fileRepository.saveConnectedSafFolder(treeUri)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val rootCrumb = DeviceBreadcrumb(
                title = saved.displayName,
                pathOrUri = docUri.toString(),
                isSaf = true,
                safTreeUri = saved.treeUri
            )
            _uiState.update {
                it.copy(downloadsBreadcrumbs = listOf(rootCrumb), selectedKeys = emptySet())
            }
            soundEngine.playWaterDroplet()
            showMessage("Підключено системну папку «${saved.displayName}»")
            reconcileDownloadsScope(forceEmitIfUnchanged = true)
        }
    }

    fun createNewDownloadsFolder(name: String) {
        viewModelScope.launch {
            val crumb = _uiState.value.downloadsBreadcrumbs.lastOrNull()
            val targetPath = if (crumb == null || crumb.pathOrUri == FileStorageRepository.VIRTUAL_DOWNLOADS_ROOT) {
                fileRepository.resolveWritableDownloadsDir().absolutePath
            } else {
                crumb.pathOrUri
            }
            val isSaf = crumb?.isSaf == true
            val ok = withContext(Dispatchers.IO) { fileRepository.createFolder(isSaf, targetPath, name) }
            if (ok) {
                soundEngine.playWaterDroplet()
                showMessage("Створено папку «$name» у Завантаженнях")
                reconcileDownloadsScope(forceEmitIfUnchanged = true)
            } else {
                showMessage("Не вдалося створити папку")
            }
        }
    }

    fun createNewDownloadsNote(fileName: String, content: String) {
        viewModelScope.launch {
            val crumb = _uiState.value.downloadsBreadcrumbs.lastOrNull()
            val targetPath = if (crumb == null || crumb.pathOrUri == FileStorageRepository.VIRTUAL_DOWNLOADS_ROOT) {
                fileRepository.resolveWritableDownloadsDir().absolutePath
            } else {
                crumb.pathOrUri
            }
            val isSaf = crumb?.isSaf == true
            val ok = withContext(Dispatchers.IO) {
                fileRepository.createNoteFile(isSaf, targetPath, fileName, content)
            }
            if (ok) {
                soundEngine.playShellChime()
                showMessage("Файл збережено у Завантаженнях")
                reconcileDownloadsScope(forceEmitIfUnchanged = true)
            } else {
                showMessage("Не вдалося зберегти файл")
            }
        }
    }

    fun importFilesToDownloads(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val crumb = _uiState.value.downloadsBreadcrumbs.lastOrNull()
            val targetPath = if (crumb == null || crumb.pathOrUri == FileStorageRepository.VIRTUAL_DOWNLOADS_ROOT) {
                fileRepository.resolveWritableDownloadsDir().absolutePath
            } else {
                crumb.pathOrUri
            }
            val isSaf = crumb?.isSaf == true
            val count = withContext(Dispatchers.IO) {
                var c = 0
                uris.forEach { uri ->
                    if (fileRepository.importExternalUriToTarget(uri, isSaf, targetPath, "завантаження")) {
                        c++
                    }
                }
                c
            }
            if (count > 0) {
                soundEngine.playShellChime()
                showMessage("Додано у Завантаження: $count файл(ів)")
                reconcileDownloadsScope(forceEmitIfUnchanged = true)
                reconcileGalleryScope(forceEmitIfUnchanged = false)
            }
        }
    }

    // --- Left Side Drawer ("Files"-style categories, Installed Apps & DocumentsProviders) ---

    fun openDrawerCategory(type: DrawerCategoryType) {
        soundEngine.playWaterDroplet()
        val scopeKey = fileRepository.drawerCategoryScopeKey(type, latestShowHidden)
        val cached = fileRepository.peekMemoryScope(scopeKey, latestMetaMap)
        if (cached != null) rawDrawerItems = cached
        _uiState.update { prev ->
            prev.copy(
                destination = MainDestination.DRAWER_COLLECTION,
                activeDrawerMode = DrawerSelectionMode.Category(type),
                drawerSearchQuery = "",
                selectedKeys = emptySet(),
                drawerCollectionItems = if (cached != null) {
                    applyFilterAndSort(cached, "", null, prev.drawerSortOption)
                } else {
                    emptyList()
                }
            )
        }
        attachActiveDirectoryObserver()
        reconcileDrawerScope(forceEmitIfUnchanged = true)
    }

    fun openInstalledAppsInDrawer() {
        soundEngine.playWaterDroplet()
        _uiState.update {
            it.copy(
                destination = MainDestination.DRAWER_COLLECTION,
                activeDrawerMode = DrawerSelectionMode.InstalledApps,
                installedAppsSearchQuery = "",
                selectedKeys = emptySet()
            )
        }
        attachActiveDirectoryObserver()
        loadInstalledAppsInternal(forceRefresh = rawInstalledApps.isEmpty())
    }

    private fun loadInstalledAppsInternal(forceRefresh: Boolean) {
        viewModelScope.launch {
            if (rawInstalledApps.isEmpty() || forceRefresh) {
                _uiState.update { it.copy(isLoadingInstalledApps = true) }
            }
            val includeSystem = _uiState.value.showSystemAppsInDrawer
            val apps = withContext(Dispatchers.IO) {
                appLauncherManager.loadInstalledApps(
                    includeSystemApps = includeSystem,
                    forceRefresh = forceRefresh
                )
            }
            rawInstalledApps = apps
            recomputeInstalledAppsUiItems(loading = false)
        }
    }

    private fun recomputeInstalledAppsUiItems(loading: Boolean = _uiState.value.isLoadingInstalledApps) {
        val q = _uiState.value.installedAppsSearchQuery.trim().lowercase(Locale.ROOT)
        val filtered = if (q.isEmpty()) {
            rawInstalledApps
        } else {
            rawInstalledApps.filter { app ->
                app.lowerAppName.contains(q) || app.lowerPackageName.contains(q)
            }
        }
        _uiState.update {
            it.copy(
                installedApps = filtered,
                isLoadingInstalledApps = loading
            )
        }
    }

    fun updateInstalledAppsSearchQuery(query: String) {
        _uiState.update { it.copy(installedAppsSearchQuery = query) }
        recomputeInstalledAppsUiItems()
    }

    fun toggleShowSystemAppsInDrawer() {
        soundEngine.playWaterDroplet()
        val next = !_uiState.value.showSystemAppsInDrawer
        _uiState.update { it.copy(showSystemAppsInDrawer = next, isLoadingInstalledApps = true) }
        loadInstalledAppsInternal(forceRefresh = true)
    }

    fun refreshInstalledApps() {
        soundEngine.playWaterDroplet()
        loadInstalledAppsInternal(forceRefresh = true)
    }

    fun launchInstalledAppFromDrawer(app: InstalledAppInfo) {
        if (!appLauncherManager.launchInstalledApp(app.packageName)) {
            showMessage("Ця системна програма не має стартового екрана")
        }
    }

    fun shareInstalledAppsApk(context: Context, apps: List<InstalledAppInfo>) {
        if (apps.isEmpty()) return
        viewModelScope.launch {
            val chooserTitle = appContext.getString(R.string.apps_action_share_apk)
            val intent = withContext(Dispatchers.IO) {
                appLauncherManager.createShareInstalledAppsIntent(apps, chooserTitle)
            }
            if (intent != null) {
                soundEngine.playShellChime()
                runCatching { context.startActivity(intent) }
            } else {
                showMessage("Не вдалося підготувати APK для надсилання")
            }
        }
    }

    fun copyInstalledAppsToTcClipboard(apps: List<InstalledAppInfo>) {
        if (apps.isEmpty()) return
        viewModelScope.launch {
            val exportedItems = withContext(Dispatchers.IO) {
                appLauncherManager.exportAppsToBeachFileItems(apps)
            }
            if (exportedItems.isNotEmpty()) {
                soundEngine.playWaterDroplet()
                _uiState.update {
                    it.copy(
                        fileClipboard = FileClipboardState(
                            items = exportedItems,
                            mode = ClipboardMode.COPY,
                            sourceFolderDisplay = appContext.getString(R.string.drawer_installed_apps)
                        )
                    )
                }
                showMessage(appContext.getString(R.string.msg_apk_copied_to_clipboard, exportedItems.size))
            } else {
                showMessage("Не вдалося скопіювати APK у буфер")
            }
        }
    }

    fun copyInstalledAppsToDestination(
        apps: List<InstalledAppInfo>,
        destination: MoveDestinationOption
    ) {
        if (apps.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    operationProgress = FileOperationProgress(
                        isActive = true,
                        title = appContext.getString(R.string.apps_action_copy_to_folder),
                        currentFileName = apps.first().apkExportFileName,
                        processedItems = 0,
                        totalItems = apps.size,
                        canCancel = false
                    )
                )
            }
            val copiedCount = withContext(Dispatchers.IO) {
                appLauncherManager.copyInstalledAppsToDestination(apps, destination)
            }
            _uiState.update { it.copy(operationProgress = FileOperationProgress()) }
            if (copiedCount > 0) {
                soundEngine.playShellChime()
                showMessage(appContext.getString(R.string.msg_apk_saved_to_folder, copiedCount, destination.title))
                reconcileBeachScope(forceEmitIfUnchanged = false)
                reconcileDownloadsScope(forceEmitIfUnchanged = false)
                reconcileDeviceScope(forceEmitIfUnchanged = false)
            } else {
                showMessage("Не вдалося зберегти APK у вибрану папку")
            }
        }
    }

    fun copyInstalledAppsToSafTree(apps: List<InstalledAppInfo>, treeUri: Uri) {
        if (apps.isEmpty()) return
        viewModelScope.launch {
            val saved = fileRepository.saveConnectedSafFolder(treeUri)
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
            val dest = MoveDestinationOption(
                id = "saf_apk_export",
                title = saved.displayName,
                subtitle = saved.displayName,
                isSaf = true,
                targetPathOrUri = docUri,
                safTreeUri = saved.treeUri
            )
            copyInstalledAppsToDestination(apps, dest)
        }
    }

    fun shareBobroPlyazhSelfApk(context: Context) {
        viewModelScope.launch {
            val chooserTitle = appContext.getString(R.string.share_self_apk_chooser)
            val shareIntent = withContext(Dispatchers.IO) {
                appLauncherManager.createShareSelfApkIntent(chooserTitle)
            }
            if (shareIntent != null) {
                soundEngine.playShellChime()
                runCatching { context.startActivity(shareIntent) }
            } else {
                showMessage(appContext.getString(R.string.msg_self_apk_share_failed))
            }
        }
    }

    fun openDrawerProvider(provider: ExternalDocumentProviderItem) {
        soundEngine.playWaterDroplet()
        val crumbs = if (!provider.connectedTreeUri.isNullOrBlank()) {
            val treeUri = Uri.parse(provider.connectedTreeUri)
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            listOf(
                DeviceBreadcrumb(
                    title = provider.title,
                    pathOrUri = docUri.toString(),
                    isSaf = true,
                    safTreeUri = provider.connectedTreeUri
                )
            )
        } else {
            emptyList()
        }
        _uiState.update {
            it.copy(
                destination = MainDestination.DRAWER_COLLECTION,
                activeDrawerMode = DrawerSelectionMode.Provider(provider, crumbs),
                drawerSearchQuery = "",
                selectedKeys = emptySet(),
                drawerCollectionItems = emptyList()
            )
        }
        attachActiveDirectoryObserver()
        reconcileDrawerScope(forceEmitIfUnchanged = true)
    }

    fun openCustomConnectedFolderInDrawer(folder: ConnectedFolderEntity) {
        val authority = runCatching { Uri.parse(folder.treeUri).authority.orEmpty() }.getOrDefault("")
        val treeUri = Uri.parse(folder.treeUri)
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val providerItem = ExternalDocumentProviderItem(
            authority = authority,
            packageName = "",
            title = folder.displayName,
            subtitle = "Підключена папка",
            isSystemDiagnostic = false,
            isCloudLike = false,
            supportsTreeGrant = true,
            connectedTreeUri = folder.treeUri,
            connectedDisplayName = folder.displayName
        )
        val crumb = DeviceBreadcrumb(
            title = folder.displayName,
            pathOrUri = docUri.toString(),
            isSaf = true,
            safTreeUri = folder.treeUri
        )
        soundEngine.playWaterDroplet()
        _uiState.update {
            it.copy(
                destination = MainDestination.DRAWER_COLLECTION,
                activeDrawerMode = DrawerSelectionMode.Provider(providerItem, listOf(crumb)),
                drawerSearchQuery = "",
                selectedKeys = emptySet()
            )
        }
        attachActiveDirectoryObserver()
        reconcileDrawerScope(forceEmitIfUnchanged = true)
    }

    fun onProviderSafTreeGranted(treeUri: Uri) {
        viewModelScope.launch {
            val saved = fileRepository.saveConnectedSafFolder(treeUri)
            val authority = treeUri.authority.orEmpty()
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val matchedProvider = _uiState.value.installedProviders.firstOrNull { it.authority == authority }
            val providerItem = (matchedProvider ?: ExternalDocumentProviderItem(
                authority = authority,
                packageName = "",
                title = saved.displayName,
                subtitle = saved.displayName,
                isSystemDiagnostic = false,
                isCloudLike = false,
                supportsTreeGrant = true
            )).copy(
                connectedTreeUri = saved.treeUri,
                connectedDisplayName = saved.displayName
            )
            val crumb = DeviceBreadcrumb(
                title = providerItem.title,
                pathOrUri = docUri.toString(),
                isSaf = true,
                safTreeUri = saved.treeUri
            )
            soundEngine.playWaterDroplet()
            _uiState.update {
                it.copy(
                    destination = MainDestination.DRAWER_COLLECTION,
                    activeDrawerMode = DrawerSelectionMode.Provider(providerItem, listOf(crumb)),
                    selectedKeys = emptySet()
                )
            }
            showMessage("Підключено джерело «${saved.displayName}»")
            reconcileDrawerScope(forceEmitIfUnchanged = true)
        }
    }

    fun importAndOpenFromProvider(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val writableDlDir = fileRepository.resolveWritableDownloadsDir()
            val importedFiles = withContext(Dispatchers.IO) {
                val list = mutableListOf<File>()
                uris.forEach { uri ->
                    fileRepository.importExternalUri(
                        uri = uri,
                        targetDirPath = writableDlDir.absolutePath,
                        fallbackPrefix = "провайдер"
                    )?.let { list.add(it) }
                }
                list
            }
            if (importedFiles.isNotEmpty()) {
                soundEngine.playShellChime()
                showMessage("Відкрито з провайдера: ${importedFiles.size} файл(ів)")
                reconcileDownloadsScope(forceEmitIfUnchanged = true)
                reconcileGalleryScope(forceEmitIfUnchanged = false)
                if (importedFiles.size == 1) {
                    val single = importedFiles.first()
                    val dlItems = withContext(Dispatchers.IO) {
                        fileRepository.listDownloadsBreadcrumb(
                            crumb = DeviceBreadcrumb("Завантаження", writableDlDir.absolutePath, false),
                            connectedFolders = latestConnectedFolders,
                            metaMap = latestMetaMap,
                            showHidden = latestShowHidden
                        )
                    }
                    dlItems.firstOrNull { it.pathOrUri == single.absolutePath }?.let { item ->
                        openFilePreviewOrLightbox(item, dlItems)
                    }
                }
            }
        }
    }

    fun openDrawerCollectionItem(item: BeachFileItem) {
        val mode = _uiState.value.activeDrawerMode
        if (item.isDirectory && mode is DrawerSelectionMode.Provider) {
            soundEngine.playSandBrush()
            val newCrumb = DeviceBreadcrumb(
                title = item.name,
                pathOrUri = item.pathOrUri,
                isSaf = item.isSafDocument,
                safTreeUri = item.safTreeUri
            )
            _uiState.update {
                it.copy(
                    activeDrawerMode = mode.copy(breadcrumbs = mode.breadcrumbs + newCrumb),
                    selectedKeys = emptySet()
                )
            }
            reconcileDrawerScope(forceEmitIfUnchanged = true)
        } else {
            openFilePreviewOrLightbox(item, _uiState.value.drawerCollectionItems)
        }
    }

    fun navigateUpDrawerProviderFolder(): Boolean {
        val mode = _uiState.value.activeDrawerMode as? DrawerSelectionMode.Provider ?: return false
        if (mode.breadcrumbs.size <= 1) return false
        soundEngine.playSandBrush()
        _uiState.update {
            it.copy(
                activeDrawerMode = mode.copy(breadcrumbs = mode.breadcrumbs.dropLast(1)),
                selectedKeys = emptySet()
            )
        }
        reconcileDrawerScope(forceEmitIfUnchanged = true)
        return true
    }

    fun jumpToDrawerProviderBreadcrumb(index: Int) {
        val mode = _uiState.value.activeDrawerMode as? DrawerSelectionMode.Provider ?: return
        if (index !in mode.breadcrumbs.indices) return
        soundEngine.playSandBrush()
        _uiState.update {
            it.copy(
                activeDrawerMode = mode.copy(breadcrumbs = mode.breadcrumbs.take(index + 1)),
                selectedKeys = emptySet()
            )
        }
        reconcileDrawerScope(forceEmitIfUnchanged = true)
    }

    fun updateDrawerSearchQuery(query: String) {
        _uiState.update { it.copy(drawerSearchQuery = query) }
        recomputeDrawerUiItems()
    }

    fun updateDrawerSortOption(option: SortOption) {
        soundEngine.playWaterDroplet()
        _uiState.update { it.copy(drawerSortOption = option) }
        recomputeDrawerUiItems()
    }

    fun createNewDrawerProviderFolder(name: String) {
        val mode = _uiState.value.activeDrawerMode as? DrawerSelectionMode.Provider ?: return
        val crumb = mode.breadcrumbs.lastOrNull() ?: return
        viewModelScope.launch {
            if (fileRepository.createFolder(crumb.isSaf, crumb.pathOrUri, name)) {
                soundEngine.playWaterDroplet()
                showMessage("Створено папку «$name»")
                refreshAllData()
            } else {
                showMessage("Провайдер не підтримує створення папки тут")
            }
        }
    }

    fun createNewDrawerProviderNote(fileName: String, content: String) {
        val mode = _uiState.value.activeDrawerMode as? DrawerSelectionMode.Provider ?: return
        val crumb = mode.breadcrumbs.lastOrNull() ?: return
        viewModelScope.launch {
            if (fileRepository.createNoteFile(crumb.isSaf, crumb.pathOrUri, fileName, content)) {
                soundEngine.playShellChime()
                showMessage("Файл створено у сховищі провайдера")
                refreshAllData()
            } else {
                showMessage("Не вдалося зберегти файл у цьому провайдері")
            }
        }
    }

    fun importFilesToDrawerProvider(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val mode = _uiState.value.activeDrawerMode as? DrawerSelectionMode.Provider ?: return
        val crumb = mode.breadcrumbs.lastOrNull() ?: return
        viewModelScope.launch {
            var count = 0
            uris.forEach { uri ->
                if (fileRepository.importExternalUriToTarget(uri, crumb.isSaf, crumb.pathOrUri)) {
                    count++
                }
            }
            if (count > 0) {
                soundEngine.playShellChime()
                showMessage("Імпортовано файлів: $count")
                refreshAllData()
            }
        }
    }

    // --- 1. Smart Multi-File Selection Actions ---

    fun toggleSelectItem(item: BeachFileItem) {
        if (item.requiresSafGrantDocId != null || item.key.startsWith("virtual:")) return
        _uiState.update { state ->
            val next = if (item.key in state.selectedKeys) {
                state.selectedKeys - item.key
            } else {
                state.selectedKeys + item.key
            }
            state.copy(selectedKeys = next)
        }
    }

    fun selectKeyRange(keys: Set<String>) {
        _uiState.update { it.copy(selectedKeys = keys) }
    }

    fun selectAllActiveItems() {
        soundEngine.playWaterDroplet()
        _uiState.update { state ->
            val selectableKeys = state.activeListItems
                .filter { it.requiresSafGrantDocId == null && !it.key.startsWith("virtual:") }
                .mapTo(LinkedHashSet()) { it.key }
            state.copy(selectedKeys = selectableKeys)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedKeys = emptySet()) }
    }

    // --- 2. Total Commander-Style File Clipboard & File Operations ---

    private fun resolveActiveFolderTarget(): ActiveFolderTarget {
        val state = _uiState.value
        return when (state.destination) {
            MainDestination.BEACH -> {
                val isSaf = state.activeRoot is StorageRoot.SafTree
                val rawPath = state.currentPathOrUri.ifBlank { fileRepository.rootPathFor(state.activeRoot) }
                val pathOrUri = if (!isSaf && rawPath == FileStorageRepository.VIRTUAL_ANDROID_HUB) {
                    fileRepository.androidAppExternalDir.absolutePath
                } else {
                    rawPath
                }
                val treeUri = (state.activeRoot as? StorageRoot.SafTree)?.treeUriString.orEmpty()
                val writable = isSaf || runCatching { File(pathOrUri).canWrite() }.getOrDefault(false)
                ActiveFolderTarget(
                    isSaf = isSaf,
                    pathOrUri = pathOrUri,
                    safTreeUri = treeUri,
                    readableDisplayPath = fileOpsManager.resolveReadableFolderPath(isSaf, pathOrUri),
                    isWritable = writable
                )
            }

            MainDestination.DOWNLOADS -> {
                val crumb = state.downloadsBreadcrumbs.lastOrNull()
                if (crumb == null || crumb.pathOrUri == FileStorageRepository.VIRTUAL_DOWNLOADS_ROOT) {
                    val writableDl = fileRepository.resolveWritableDownloadsDir().absolutePath
                    ActiveFolderTarget(
                        isSaf = false,
                        pathOrUri = writableDl,
                        safTreeUri = "",
                        readableDisplayPath = fileOpsManager.resolveReadableFolderPath(false, writableDl),
                        isWritable = true
                    )
                } else {
                    ActiveFolderTarget(
                        isSaf = crumb.isSaf,
                        pathOrUri = crumb.pathOrUri,
                        safTreeUri = crumb.safTreeUri,
                        readableDisplayPath = fileOpsManager.resolveReadableFolderPath(crumb.isSaf, crumb.pathOrUri),
                        isWritable = true
                    )
                }
            }

            MainDestination.DEVICE -> {
                val crumb = state.deviceBreadcrumbs.lastOrNull()
                if (crumb == null || crumb.pathOrUri == FileStorageRepository.VIRTUAL_ANDROID_HUB) {
                    ActiveFolderTarget(
                        isSaf = false,
                        pathOrUri = FileStorageRepository.VIRTUAL_ANDROID_HUB,
                        safTreeUri = "",
                        readableDisplayPath = fileOpsManager.resolveReadableFolderPath(
                            false,
                            FileStorageRepository.VIRTUAL_ANDROID_HUB
                        ),
                        isWritable = false
                    )
                } else {
                    val writable = crumb.isSaf || runCatching { File(crumb.pathOrUri).canWrite() }.getOrDefault(false)
                    ActiveFolderTarget(
                        isSaf = crumb.isSaf,
                        pathOrUri = crumb.pathOrUri,
                        safTreeUri = crumb.safTreeUri,
                        readableDisplayPath = fileOpsManager.resolveReadableFolderPath(crumb.isSaf, crumb.pathOrUri),
                        isWritable = writable
                    )
                }
            }

            MainDestination.DRAWER_COLLECTION -> {
                val mode = state.activeDrawerMode
                if (mode is DrawerSelectionMode.Provider && mode.breadcrumbs.isNotEmpty()) {
                    val crumb = mode.breadcrumbs.last()
                    ActiveFolderTarget(
                        isSaf = crumb.isSaf,
                        pathOrUri = crumb.pathOrUri,
                        safTreeUri = crumb.safTreeUri,
                        readableDisplayPath = fileOpsManager.resolveReadableFolderPath(crumb.isSaf, crumb.pathOrUri),
                        isWritable = true
                    )
                } else {
                    val sampleParent = state.selectedItems.firstOrNull()?.let { first ->
                        fileOpsManager.resolveReadableFolderPath(first.isSafDocument, first.parentPathOrUri)
                    } ?: fileOpsManager.resolveReadableFolderPath(false, fileRepository.beaverCoveDir.absolutePath)
                    ActiveFolderTarget(
                        isSaf = false,
                        pathOrUri = fileRepository.beaverCoveDir.absolutePath,
                        safTreeUri = "",
                        readableDisplayPath = sampleParent,
                        isWritable = false
                    )
                }
            }

            else -> ActiveFolderTarget(
                isSaf = false,
                pathOrUri = fileRepository.beaverCoveDir.absolutePath,
                safTreeUri = "",
                readableDisplayPath = fileOpsManager.resolveReadableFolderPath(
                    false,
                    fileRepository.beaverCoveDir.absolutePath
                ),
                isWritable = false
            )
        }
    }

    fun copySelectedToClipboard() {
        val selected = _uiState.value.selectedItems
        if (selected.isEmpty()) return
        val folderDisplay = resolveActiveFolderTarget().readableDisplayPath
        soundEngine.playWaterDroplet()
        _uiState.update {
            it.copy(
                fileClipboard = FileClipboardState(
                    items = selected,
                    mode = ClipboardMode.COPY,
                    sourceFolderDisplay = folderDisplay
                ),
                selectedKeys = emptySet()
            )
        }
        showMessage(appContext.getString(R.string.msg_copied_to_clipboard, selected.size))
    }

    fun cutSelectedToClipboard() {
        val selected = _uiState.value.selectedItems
        if (selected.isEmpty()) return
        val folderDisplay = resolveActiveFolderTarget().readableDisplayPath
        soundEngine.playSandBrush()
        _uiState.update {
            it.copy(
                fileClipboard = FileClipboardState(
                    items = selected,
                    mode = ClipboardMode.CUT,
                    sourceFolderDisplay = folderDisplay
                ),
                selectedKeys = emptySet()
            )
        }
        showMessage(appContext.getString(R.string.msg_cut_to_clipboard, selected.size))
    }

    fun copySingleItemToClipboard(item: BeachFileItem) {
        val folderDisplay = fileOpsManager.resolveReadableFolderPath(item.isSafDocument, item.parentPathOrUri)
        soundEngine.playWaterDroplet()
        _uiState.update {
            it.copy(
                fileClipboard = FileClipboardState(
                    items = listOf(item),
                    mode = ClipboardMode.COPY,
                    sourceFolderDisplay = folderDisplay
                )
            )
        }
        showMessage(appContext.getString(R.string.msg_copied_to_clipboard, 1))
    }

    fun cutSingleItemToClipboard(item: BeachFileItem) {
        val folderDisplay = fileOpsManager.resolveReadableFolderPath(item.isSafDocument, item.parentPathOrUri)
        soundEngine.playSandBrush()
        _uiState.update {
            it.copy(
                fileClipboard = FileClipboardState(
                    items = listOf(item),
                    mode = ClipboardMode.CUT,
                    sourceFolderDisplay = folderDisplay
                )
            )
        }
        showMessage(appContext.getString(R.string.msg_cut_to_clipboard, 1))
    }

    /**
     * Copies selected items in Total Commander text list format to the Android system clipboard
     * WITHOUT overwriting the internal file operations clipboard.
     */
    fun copySelectedListAsTcText() {
        val selected = _uiState.value.selectedItems
        if (selected.isEmpty()) return
        val folderDisplay = resolveActiveFolderTarget().readableDisplayPath
        fileOpsManager.copyListAsTextToSystemClipboard(
            items = selected,
            mode = ClipboardMode.COPY,
            currentFolderDisplay = folderDisplay
        )
        soundEngine.playShellChime()
        showMessage(appContext.getString(R.string.msg_tc_list_copied, selected.size))
    }

    /**
     * Copies the active internal file clipboard's items in Total Commander text list format
     * (preserving whether they were copied or cut) to the Android system clipboard.
     */
    fun copyClipboardListAsTcText() {
        val clip = _uiState.value.fileClipboard ?: return
        if (clip.items.isEmpty()) return
        fileOpsManager.copyListAsTextToSystemClipboard(
            items = clip.items,
            mode = clip.mode,
            currentFolderDisplay = clip.sourceFolderDisplay
        )
        soundEngine.playShellChime()
        showMessage(appContext.getString(R.string.msg_tc_list_copied, clip.items.size))
    }

    fun clearFileClipboard() {
        _uiState.update { it.copy(fileClipboard = null) }
    }

    fun pasteClipboardToCurrentFolder() {
        val clip = _uiState.value.fileClipboard ?: return
        if (clip.items.isEmpty()) return
        val target = resolveActiveFolderTarget()
        if (!target.isWritable) {
            showMessage("Для вставки в цю системну папку спочатку надайте SAF-доступ")
            return
        }
        executeTransferJob(
            items = clip.items,
            mode = clip.mode,
            isDestSaf = target.isSaf,
            destPathOrUri = target.pathOrUri,
            destTreeUri = target.safTreeUri,
            clearClipboardOnComplete = (clip.mode == ClipboardMode.CUT),
            clearSelectionOnComplete = true
        )
    }

    fun requestMoveSelectedDialog() {
        if (_uiState.value.selectedItems.isEmpty()) return
        _uiState.update { it.copy(showMovePickerDialog = true) }
    }

    fun dismissMoveDialog() {
        _uiState.update { it.copy(showMovePickerDialog = false) }
    }

    fun moveSelectedToDestination(dest: MoveDestinationOption) {
        val selected = _uiState.value.selectedItems
        _uiState.update { it.copy(showMovePickerDialog = false) }
        if (selected.isEmpty()) return
        executeTransferJob(
            items = selected,
            mode = ClipboardMode.CUT,
            isDestSaf = dest.isSaf,
            destPathOrUri = dest.targetPathOrUri,
            destTreeUri = dest.safTreeUri,
            clearClipboardOnComplete = false,
            clearSelectionOnComplete = true
        )
    }

    private fun executeTransferJob(
        items: List<BeachFileItem>,
        mode: ClipboardMode,
        isDestSaf: Boolean,
        destPathOrUri: String,
        destTreeUri: String,
        clearClipboardOnComplete: Boolean,
        clearSelectionOnComplete: Boolean
    ) {
        if (activeFileOpJob?.isActive == true) return
        val opTitle = appContext.getString(
            if (mode == ClipboardMode.CUT) R.string.progress_moving else R.string.progress_copying
        )
        activeFileOpJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    operationProgress = FileOperationProgress(
                        isActive = true,
                        title = opTitle,
                        currentFileName = items.firstOrNull()?.name.orEmpty(),
                        processedItems = 0,
                        totalItems = items.size,
                        canCancel = true
                    )
                )
            }

            val summary = fileOpsManager.executePasteOrMove(
                items = items,
                mode = mode,
                isDestSaf = isDestSaf,
                destFolderPathOrUri = destPathOrUri,
                destTreeUri = destTreeUri,
                onProgress = { processed, total, currentName ->
                    _uiState.update { state ->
                        state.copy(
                            operationProgress = state.operationProgress.copy(
                                processedItems = processed,
                                totalItems = total,
                                currentFileName = currentName
                            )
                        )
                    }
                },
                onConflict = { conflictingName, destDisplay ->
                    awaitUserConflictDecision(conflictingName, destDisplay)
                }
            )

            _uiState.update { prev ->
                prev.copy(
                    operationProgress = FileOperationProgress(),
                    conflictPrompt = null,
                    fileClipboard = if (clearClipboardOnComplete && !summary.cancelled && summary.succeededCount > 0) {
                        null
                    } else {
                        prev.fileClipboard
                    },
                    selectedKeys = if (clearSelectionOnComplete) emptySet() else prev.selectedKeys
                )
            }

            when {
                summary.cancelled -> showMessage(appContext.getString(R.string.msg_operation_cancelled))
                summary.succeededCount > 0 -> {
                    soundEngine.playShellChime()
                    showMessage(appContext.getString(R.string.msg_paste_done, summary.succeededCount))
                    scheduleAutoCloudBackup()
                }
                summary.skippedCount > 0 -> {
                    showMessage("Пропущено об'єктів: ${summary.skippedCount}")
                }
                else -> {
                    showMessage(summary.errorDetails ?: "Не вдалося виконати файлову операцію")
                }
            }
            refreshAllData()
        }
    }

    private suspend fun awaitUserConflictDecision(
        conflictingName: String,
        destDisplay: String
    ): ConflictDecision {
        val deferred = CompletableDeferred<ConflictDecision>()
        pendingConflictDeferred = deferred
        _uiState.update {
            it.copy(
                conflictPrompt = PasteConflictPrompt(
                    conflictingName = conflictingName,
                    destinationDisplay = destDisplay
                )
            )
        }
        return try {
            deferred.await()
        } finally {
            pendingConflictDeferred = null
            _uiState.update { it.copy(conflictPrompt = null) }
        }
    }

    fun resolvePasteConflict(decision: ConflictDecision) {
        pendingConflictDeferred?.complete(decision)
    }

    fun cancelActiveFileOperation() {
        pendingConflictDeferred?.complete(ConflictDecision(ConflictResolution.CANCEL_ALL, false))
        activeFileOpJob?.cancel()
        activeFileOpJob = null
        _uiState.update {
            it.copy(
                operationProgress = FileOperationProgress(),
                conflictPrompt = null
            )
        }
        showMessage(appContext.getString(R.string.msg_operation_cancelled))
    }

    // --- Batch Delete (with Confirmation), Rename & Multi-Share ---

    fun requestBatchDeleteConfirmation() {
        if (_uiState.value.selectedItems.isEmpty()) return
        _uiState.update { it.copy(showBatchDeleteConfirmDialog = true) }
    }

    fun dismissBatchDeleteConfirmation() {
        _uiState.update { it.copy(showBatchDeleteConfirmDialog = false) }
    }

    fun confirmBatchMoveToTrash() {
        val selected = _uiState.value.selectedItems
        _uiState.update { it.copy(showBatchDeleteConfirmDialog = false, selectedKeys = emptySet()) }
        if (selected.isEmpty()) return
        soundEngine.playSandBrush()
        viewModelScope.launch {
            var count = 0
            for (item in selected) {
                if (item.isSafDocument) {
                    if (fileRepository.deletePermanently(item)) count++
                } else {
                    fileRepository.categorizeByGesture(item, targetCategory = null, star = false, trash = true)
                    count++
                }
            }
            showMessage("Переміщено у кошик: $count")
            refreshAllData()
        }
    }

    fun confirmBatchDeletePermanently() {
        val selected = _uiState.value.selectedItems
        _uiState.update { it.copy(showBatchDeleteConfirmDialog = false, selectedKeys = emptySet()) }
        if (selected.isEmpty()) return
        soundEngine.playWaterDroplet()
        viewModelScope.launch {
            var deleted = 0
            for (item in selected) {
                if (fileRepository.deletePermanently(item)) deleted++
            }
            showMessage(appContext.getString(R.string.msg_deleted_count, deleted))
            refreshAllData()
        }
    }

    fun requestRenameFromSelection(item: BeachFileItem) {
        _uiState.update { it.copy(renameDialogTarget = item) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(renameDialogTarget = null) }
    }

    fun shareSelectedItems(context: Context) {
        val selected = _uiState.value.selectedItems
        if (selected.isEmpty()) return
        val intent = fileOpsManager.createMultiShareIntent(selected)
        if (intent != null) {
            runCatching { context.startActivity(intent) }
        } else {
            showMessage("Оберіть файли (або спершу запакуйте папку в архів) для надсилання")
        }
    }

    // --- 3. Built-in Archiver & Virtual Archive Browser ---

    fun buildDestinationOptions(): List<MoveDestinationOption> {
        val options = LinkedHashMap<String, MoveDestinationOption>()
        val activeTarget = resolveActiveFolderTarget()
        if (activeTarget.isWritable && activeTarget.pathOrUri.isNotBlank()) {
            options[activeTarget.pathOrUri] = MoveDestinationOption(
                id = "current_active",
                title = "Поточна папка",
                subtitle = activeTarget.readableDisplayPath,
                isSaf = activeTarget.isSaf,
                targetPathOrUri = activeTarget.pathOrUri,
                safTreeUri = activeTarget.safTreeUri
            )
        }

        if (fileRepository.hasAllFilesAccess()) {
            val sharedRoot = fileRepository.deviceSharedRootDir.absolutePath
            options.putIfAbsent(
                sharedRoot,
                MoveDestinationOption(
                    id = "device_shared_root",
                    title = "Внутрішнє сховище",
                    subtitle = sharedRoot,
                    isSaf = false,
                    targetPathOrUri = sharedRoot
                )
            )
            val pubDl = fileRepository.publicDownloadsDir.absolutePath
            options.putIfAbsent(
                pubDl,
                MoveDestinationOption(
                    id = "public_downloads",
                    title = "Системні Завантаження (Download)",
                    subtitle = pubDl,
                    isSaf = false,
                    targetPathOrUri = pubDl
                )
            )
        }

        val covePath = fileRepository.beaverCoveDir.absolutePath
        options.putIfAbsent(
            covePath,
            MoveDestinationOption(
                id = "beaver_cove",
                title = "Бухта Бобра (Пляж)",
                subtitle = covePath,
                isSaf = false,
                targetPathOrUri = covePath
            )
        )

        val dlPath = fileRepository.resolveWritableDownloadsDir().absolutePath
        options.putIfAbsent(
            dlPath,
            MoveDestinationOption(
                id = "app_downloads",
                title = "Завантаження",
                subtitle = dlPath,
                isSaf = false,
                targetPathOrUri = dlPath
            )
        )

        val extPath = fileRepository.androidAppExternalDir.absolutePath
        options.putIfAbsent(
            extPath,
            MoveDestinationOption(
                id = "app_external",
                title = "Простір Android/data",
                subtitle = extPath,
                isSaf = false,
                targetPathOrUri = extPath
            )
        )

        // Include top-level folders inside Beaver Cove for fast 1-tap Move/Extract
        fileRepository.beaverCoveDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.take(6)
            ?.forEach { subDir ->
                options.putIfAbsent(
                    subDir.absolutePath,
                    MoveDestinationOption(
                        id = "cove_sub_${subDir.name}",
                        title = "Бухта / ${subDir.name}",
                        subtitle = subDir.absolutePath,
                        isSaf = false,
                        targetPathOrUri = subDir.absolutePath
                    )
                )
            }

        // Include user-mounted SAF directories
        latestConnectedFolders.forEachIndexed { idx, folder ->
            runCatching {
                val treeUri = Uri.parse(folder.treeUri)
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
                options.putIfAbsent(
                    docUri,
                    MoveDestinationOption(
                        id = "saf_mount_$idx",
                        title = folder.displayName,
                        subtitle = fileOpsManager.resolveReadableFolderPath(true, docUri),
                        isSaf = true,
                        targetPathOrUri = docUri,
                        safTreeUri = folder.treeUri
                    )
                )
            }
        }

        return options.values.toList()
    }

    fun openVirtualArchive(item: BeachFileItem) {
        val format = SupportedArchiveFormat.detectInspectableArchive(item.name)
        if (format == null) {
            showMessage(appContext.getString(R.string.archive_rar_unsupported))
            return
        }
        soundEngine.playWaterDroplet()
        _uiState.update { it.copy(previewFileItem = null, previewTextContent = "") }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    operationProgress = FileOperationProgress(
                        isActive = true,
                        title = item.name,
                        currentFileName = "Читання вмісту архіву…",
                        canCancel = false
                    )
                )
            }
            val loaded = runCatching {
                archiveRepository.listArchiveEntries(item, format)
            }
            _uiState.update { it.copy(operationProgress = FileOperationProgress()) }

            loaded.onSuccess { entries ->
                val rootEntries = archiveRepository.filterEntriesInDir(entries, "")
                _uiState.update {
                    it.copy(
                        virtualArchiveSession = VirtualArchiveSession(
                            archiveItem = item,
                            format = format,
                            currentDirPath = "",
                            allEntries = entries,
                            entriesInCurrentDir = rootEntries,
                            selectedEntryPaths = emptySet()
                        )
                    )
                }
            }.onFailure { err ->
                showMessage("Не вдалося прочитати архів: ${err.localizedMessage ?: "пошкоджений формат"}")
            }
        }
    }

    fun closeVirtualArchive() {
        _uiState.update { it.copy(virtualArchiveSession = null) }
    }

    fun navigateVirtualArchiveDir(dirPath: String) {
        val session = _uiState.value.virtualArchiveSession ?: return
        val normalized = dirPath.trim('/')
        val children = archiveRepository.filterEntriesInDir(session.allEntries, normalized)
        soundEngine.playSandBrush()
        _uiState.update {
            it.copy(
                virtualArchiveSession = session.copy(
                    currentDirPath = normalized,
                    entriesInCurrentDir = children,
                    selectedEntryPaths = emptySet()
                )
            )
        }
    }

    fun navigateUpVirtualArchive() {
        val session = _uiState.value.virtualArchiveSession ?: return
        if (!session.canNavigateUp) return
        val parent = session.currentDirPath.substringBeforeLast('/', "")
        navigateVirtualArchiveDir(parent)
    }

    fun toggleArchiveEntrySelection(entry: ArchiveEntryItem) {
        val session = _uiState.value.virtualArchiveSession ?: return
        val next = if (entry.entryPath in session.selectedEntryPaths) {
            session.selectedEntryPaths - entry.entryPath
        } else {
            session.selectedEntryPaths + entry.entryPath
        }
        _uiState.update {
            it.copy(virtualArchiveSession = session.copy(selectedEntryPaths = next))
        }
    }

    fun selectAllArchiveEntriesInDir() {
        val session = _uiState.value.virtualArchiveSession ?: return
        val allInDir = session.entriesInCurrentDir.mapTo(LinkedHashSet()) { it.entryPath }
        _uiState.update {
            it.copy(virtualArchiveSession = session.copy(selectedEntryPaths = allInDir))
        }
    }

    fun clearArchiveEntrySelection() {
        val session = _uiState.value.virtualArchiveSession ?: return
        _uiState.update {
            it.copy(virtualArchiveSession = session.copy(selectedEntryPaths = emptySet()))
        }
    }

    /**
     * Extracts ONLY a single entry to cache so images, text, or audio inside an archive
     * can be previewed immediately without unpacking the entire archive.
     */
    fun previewArchiveEntry(entry: ArchiveEntryItem) {
        val session = _uiState.value.virtualArchiveSession ?: return
        if (entry.isDirectory) {
            navigateVirtualArchiveDir(entry.entryPath)
            return
        }
        viewModelScope.launch {
            val previewItem = runCatching {
                archiveRepository.extractSingleEntryToPreviewCache(
                    archiveItem = session.archiveItem,
                    format = session.format,
                    entry = entry
                )
            }.getOrNull()

            if (previewItem == null) {
                showMessage("Оберіть розпакування для великих файлів понад 60 МБ")
                return@launch
            }

            when (previewItem.category) {
                FileCategory.IMAGES -> {
                    soundEngine.playShellChime()
                    _uiState.update { it.copy(selectedLightboxShell = previewItem) }
                }
                FileCategory.VIDEO -> {
                    soundEngine.playShellChime()
                    pauseBackgroundAudioForVideo()
                    _uiState.update {
                        it.copy(
                            videoPlayerState = VideoPlayerState(
                                isVisible = true,
                                currentItem = previewItem,
                                playlist = listOf(previewItem),
                                currentIndex = 0
                            )
                        )
                    }
                }
                FileCategory.AUDIO -> {
                    soundEngine.playShellChime()
                    if (preferencesState.value.musicEnabled) {
                        preferencesRepository.setMusicEnabled(false)
                    }
                    audioPlayerManager.playFileWithContextQueue(previewItem, listOf(previewItem))
                }
                else -> {
                    val outcome = withContext(Dispatchers.IO) {
                        appLauncherManager.openOrInstallFile(previewItem)
                    }
                    handleFileLaunchOutcome(previewItem, outcome)
                }
            }
        }
    }

    fun extractCurrentArchive(dest: MoveDestinationOption, selectedOnly: Boolean) {
        val session = _uiState.value.virtualArchiveSession ?: return
        if (activeFileOpJob?.isActive == true) return
        val selectedPaths = if (selectedOnly) session.selectedEntryPaths else null
        val stripPrefix = if (selectedOnly) session.currentDirPath else ""

        activeFileOpJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    operationProgress = FileOperationProgress(
                        isActive = true,
                        title = appContext.getString(R.string.progress_extracting),
                        currentFileName = session.archiveItem.name,
                        processedItems = 0,
                        totalItems = session.allEntries.size,
                        canCancel = true
                    )
                )
            }

            val summary = archiveRepository.extractArchive(
                archiveItem = session.archiveItem,
                format = session.format,
                isDestSaf = dest.isSaf,
                destDirPathOrUri = dest.targetPathOrUri,
                selectedPaths = selectedPaths,
                stripPrefixDir = stripPrefix,
                onProgress = { processed, total, currentName ->
                    _uiState.update { state ->
                        state.copy(
                            operationProgress = state.operationProgress.copy(
                                processedItems = processed,
                                totalItems = total,
                                currentFileName = currentName
                            )
                        )
                    }
                },
                onConflict = { conflictingName, destDisplay ->
                    awaitUserConflictDecision(conflictingName, destDisplay)
                }
            )

            _uiState.update { prev ->
                prev.copy(
                    operationProgress = FileOperationProgress(),
                    conflictPrompt = null,
                    virtualArchiveSession = prev.virtualArchiveSession?.copy(selectedEntryPaths = emptySet())
                )
            }

            when {
                summary.cancelled -> showMessage(appContext.getString(R.string.msg_operation_cancelled))
                summary.errorDetails != null && summary.succeededCount == 0 -> {
                    showMessage(summary.errorDetails)
                }
                else -> {
                    soundEngine.playShellChime()
                    showMessage(appContext.getString(R.string.msg_archive_extracted, summary.succeededCount))
                    refreshAllData()
                }
            }
        }
    }

    fun requestCreateArchiveDialog() {
        if (_uiState.value.selectedItems.isEmpty()) return
        _uiState.update { it.copy(showCreateArchiveDialog = true) }
    }

    fun dismissCreateArchiveDialog() {
        _uiState.update { it.copy(showCreateArchiveDialog = false) }
    }

    fun createArchiveFromSelection(
        baseName: String,
        format: SupportedArchiveFormat,
        destination: MoveDestinationOption
    ) {
        val selected = _uiState.value.selectedItems
        _uiState.update { it.copy(showCreateArchiveDialog = false) }
        if (selected.isEmpty() || activeFileOpJob?.isActive == true) return

        activeFileOpJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    operationProgress = FileOperationProgress(
                        isActive = true,
                        title = appContext.getString(R.string.progress_archiving),
                        currentFileName = baseName,
                        processedItems = 0,
                        totalItems = selected.size,
                        canCancel = true
                    )
                )
            }

            val result = runCatching {
                archiveRepository.createArchive(
                    items = selected,
                    isDestSaf = destination.isSaf,
                    destDirPathOrUri = destination.targetPathOrUri,
                    archiveBaseName = baseName,
                    format = format,
                    onProgress = { processed, total, currentName ->
                        _uiState.update { state ->
                            state.copy(
                                operationProgress = state.operationProgress.copy(
                                    processedItems = processed,
                                    totalItems = total,
                                    currentFileName = currentName
                                )
                            )
                        }
                    }
                )
            }.getOrElse { Result.failure(it) }

            _uiState.update {
                it.copy(
                    operationProgress = FileOperationProgress(),
                    selectedKeys = emptySet()
                )
            }

            result.onSuccess { createdName ->
                soundEngine.playShellChime()
                showMessage(appContext.getString(R.string.msg_archive_created, createdName))
                refreshAllData()
                scheduleAutoCloudBackup()
            }.onFailure { err ->
                showMessage("Помилка створення архіву: ${err.localizedMessage ?: "невідома помилка"}")
            }
        }
    }

    fun addFilesToCurrentZip(uris: List<Uri>) {
        val session = _uiState.value.virtualArchiveSession ?: return
        if (uris.isEmpty() || session.format != SupportedArchiveFormat.ZIP || activeFileOpJob?.isActive == true) return

        activeFileOpJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    operationProgress = FileOperationProgress(
                        isActive = true,
                        title = appContext.getString(R.string.progress_archiving),
                        currentFileName = session.archiveItem.name,
                        processedItems = 0,
                        totalItems = uris.size,
                        canCancel = true
                    )
                )
            }

            val result = runCatching {
                archiveRepository.addUrisToExistingZip(
                    zipItem = session.archiveItem,
                    uris = uris,
                    targetInternalFolder = session.currentDirPath,
                    onProgress = { processed, total, currentName ->
                        _uiState.update { state ->
                            state.copy(
                                operationProgress = state.operationProgress.copy(
                                    processedItems = processed,
                                    totalItems = total,
                                    currentFileName = currentName
                                )
                            )
                        }
                    }
                )
            }.getOrElse { Result.failure(it) }

            _uiState.update { it.copy(operationProgress = FileOperationProgress()) }

            result.onSuccess { addedCount ->
                val updatedEntries = runCatching {
                    archiveRepository.listArchiveEntries(session.archiveItem, session.format)
                }.getOrDefault(session.allEntries)
                val updatedDirEntries = archiveRepository.filterEntriesInDir(updatedEntries, session.currentDirPath)
                _uiState.update {
                    it.copy(
                        virtualArchiveSession = session.copy(
                            allEntries = updatedEntries,
                            entriesInCurrentDir = updatedDirEntries
                        )
                    )
                }
                soundEngine.playShellChime()
                showMessage("Додано в архів файлів: $addedCount")
                refreshAllData()
            }.onFailure { err ->
                showMessage("Не вдалося оновити ZIP: ${err.localizedMessage ?: "помилка запису"}")
            }
        }
    }

    // --- 4. Built-in Audio Player Queue Actions ---

    fun enqueueSelectedAudio() {
        val audioItems = _uiState.value.selectedItems.filter { !it.isDirectory && it.category == FileCategory.AUDIO }
        if (audioItems.isEmpty()) return
        if (preferencesState.value.musicEnabled) {
            viewModelScope.launch { preferencesRepository.setMusicEnabled(false) }
        }
        audioPlayerManager.enqueueFiles(audioItems)
        soundEngine.playShellChime()
        _uiState.update { it.copy(selectedKeys = emptySet()) }
        showMessage("Додано в чергу плеєра: ${audioItems.size}")
    }

    fun enqueueSingleAudio(item: BeachFileItem) {
        if (item.isDirectory || item.category != FileCategory.AUDIO) return
        if (preferencesState.value.musicEnabled) {
            viewModelScope.launch { preferencesRepository.setMusicEnabled(false) }
        }
        audioPlayerManager.enqueueFiles(listOf(item))
        soundEngine.playShellChime()
        showMessage("Додано в чергу плеєра: «${item.name}»")
    }

    // --- Trash, Preview, Sandbox, Sorter & Cloud Actions ---

    fun restoreItemFromTrash(item: BeachFileItem) {
        soundEngine.playShellChime()
        viewModelScope.launch {
            fileRepository.restoreFromTrash(item)
            showMessage("Відновлено «${item.name}» на берег")
            refreshAllData()
        }
    }

    fun deleteItemPermanently(item: BeachFileItem) {
        soundEngine.playWaterDroplet()
        viewModelScope.launch {
            if (fileRepository.deletePermanently(item)) {
                closePreviewAndLightbox()
                showMessage("Видалено назавжди: «${item.name}»")
                refreshAllData()
            }
        }
    }

    fun closePreviewAndLightbox() {
        _uiState.update {
            it.copy(
                selectedLightboxShell = null,
                previewFileItem = null,
                previewTextContent = ""
            )
        }
    }

    fun closeVideoPlayer() {
        _uiState.update { it.copy(videoPlayerState = VideoPlayerState()) }
    }

    fun selectVideoPlaylistIndex(index: Int) {
        val vp = _uiState.value.videoPlayerState
        if (index !in vp.playlist.indices) return
        _uiState.update {
            it.copy(
                videoPlayerState = vp.copy(
                    currentIndex = index,
                    currentItem = vp.playlist[index]
                )
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        recomputeBeachUiItems()
    }

    fun updateCategoryFilter(category: FileCategory?) {
        _uiState.update {
            it.copy(selectedCategoryFilter = if (it.selectedCategoryFilter == category) null else category)
        }
        recomputeBeachUiItems()
    }

    fun updateSortOption(option: SortOption) {
        soundEngine.playWaterDroplet()
        _uiState.update { it.copy(sortOption = option) }
        recomputeBeachUiItems()
    }

    fun updateGallerySearchQuery(query: String) {
        _uiState.update { it.copy(gallerySearchQuery = query) }
        recomputeGalleryUiItems()
    }

    fun updateGalleryMediaFilter(filter: GalleryMediaFilter) {
        soundEngine.playWaterDroplet()
        _uiState.update { it.copy(galleryMediaFilter = filter) }
        recomputeGalleryUiItems()
    }

    fun setGalleryColumns(columns: Int) {
        val clamped = columns.coerceIn(2, 7)
        if (_uiState.value.galleryColumns != clamped) {
            _uiState.update { it.copy(galleryColumns = clamped) }
        }
    }

    fun updateOptimizerSort(option: SortOption) {
        _uiState.update { it.copy(optimizerSortOption = option) }
    }

    fun onSandDragEnd(item: BeachFileItem, normX: Float, normY: Float) {
        soundEngine.playSandBrush()
        viewModelScope.launch {
            val targetFolder = _uiState.value.currentItems.firstOrNull { candidate ->
                candidate.isDirectory &&
                    candidate.key != item.key &&
                    kotlin.math.hypot(
                        (candidate.sandX - normX).toDouble(),
                        (candidate.sandY - normY).toDouble()
                    ) < 0.13
            }

            if (targetFolder != null && !item.isDirectory && !item.isSafDocument) {
                val movedPath = fileRepository.moveFileIntoFolder(item, targetFolder)
                if (movedPath != null) {
                    soundEngine.playWaterDroplet()
                    showMessage("Переміщено «${item.name}» у папку «${targetFolder.name}»")
                    refreshAllData()
                    scheduleAutoCloudBackup()
                    return@launch
                }
            }

            fileRepository.updateSandPosition(item, normX, normY)
            scheduleAutoCloudBackup()
        }
    }

    fun triggerTideArrange() {
        soundEngine.playWaterDroplet()
        _uiState.update { it.copy(tideWaveTrigger = it.tideWaveTrigger + 1) }
        viewModelScope.launch {
            fileRepository.arrangeItemsOnSand(_uiState.value.currentItems)
            showMessage("Приплив охайно впорядкував об'єкти на піску")
        }
    }

    fun createNewFolder(name: String) {
        viewModelScope.launch {
            val state = _uiState.value
            val isSaf = state.activeRoot is StorageRoot.SafTree
            val ok = fileRepository.createFolder(isSaf, state.currentPathOrUri, name)
            if (ok) {
                soundEngine.playWaterDroplet()
                showMessage("Створено папку «$name»")
                refreshAllData()
                scheduleAutoCloudBackup()
            } else {
                showMessage("Не вдалося створити папку")
            }
        }
    }

    fun createNewNote(fileName: String, content: String) {
        viewModelScope.launch {
            val state = _uiState.value
            val isSaf = state.activeRoot is StorageRoot.SafTree
            val ok = fileRepository.createNoteFile(isSaf, state.currentPathOrUri, fileName, content)
            if (ok) {
                soundEngine.playShellChime()
                showMessage("Сувій збережено на березі")
                refreshAllData()
                scheduleAutoCloudBackup()
            } else {
                showMessage("Не вдалося зберегти файл")
            }
        }
    }

    fun importFilesFromUris(uris: List<Uri>, intoGalleryFolder: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val state = _uiState.value
            val isSafTarget = !intoGalleryFolder && state.activeRoot is StorageRoot.SafTree
            val targetPathOrUri = if (intoGalleryFolder) {
                if (fileRepository.hasAllFilesAccess()) {
                    File(fileRepository.deviceSharedRootDir, "Pictures/БоброПляж").apply { mkdirs() }.absolutePath
                } else {
                    File(fileRepository.beaverCoveDir, "Мушлі_Пляжу").apply { mkdirs() }.absolutePath
                }
            } else {
                state.currentPathOrUri.ifBlank { fileRepository.rootPathFor(state.activeRoot) }
            }
            var imported = 0
            uris.forEach { uri ->
                if (fileRepository.importExternalUriToTarget(uri, isSafTarget, targetPathOrUri, "мушля")) {
                    imported++
                }
            }
            if (imported > 0) {
                soundEngine.playShellChime()
                showMessage("Додано на берег: $imported об'єкт(ів)")
                refreshAllData()
                scheduleAutoCloudBackup()
            }
        }
    }

    fun connectSafDirectory(treeUri: Uri) {
        viewModelScope.launch {
            val saved = fileRepository.saveConnectedSafFolder(treeUri)
            val newRoot = StorageRoot.SafTree(saved.treeUri, saved.displayName)
            selectStorageRoot(newRoot)
            showMessage("Підключено папку «${saved.displayName}»")
        }
    }

    fun renameItem(item: BeachFileItem, newName: String) {
        viewModelScope.launch {
            val ok = fileRepository.renameItem(item, newName)
            if (ok) {
                _uiState.update { it.copy(renameDialogTarget = null, selectedKeys = emptySet()) }
                showMessage("Перейменовано на «$newName»")
                refreshAllData()
                scheduleAutoCloudBackup()
            } else {
                showMessage("Не вдалося перейменувати файл")
            }
        }
    }

    fun toggleStarItem(item: BeachFileItem) {
        soundEngine.playShellChime()
        viewModelScope.launch {
            fileRepository.toggleStar(item)
            scheduleAutoCloudBackup()
        }
    }

    fun moveItemToTrash(item: BeachFileItem) {
        soundEngine.playSandBrush()
        viewModelScope.launch {
            if (item.isSafDocument) {
                fileRepository.deletePermanently(item)
            } else {
                fileRepository.categorizeByGesture(item, targetCategory = null, star = false, trash = true)
            }
            closePreviewAndLightbox()
            showMessage("Переміщено «${item.name}» у корчі (кошик)")
            refreshAllData()
        }
    }

    fun shareItem(context: Context, item: BeachFileItem) {
        val intent = fileRepository.createShareIntent(item)
        if (intent != null) {
            context.startActivity(intent)
        } else {
            showMessage("Не вдалося підготувати файл для надсилання")
        }
    }

    fun performGestureSort(
        item: BeachFileItem,
        direction: GestureSortDirection
    ) {
        viewModelScope.launch {
            val prevMeta = latestMetaMap[item.key]
            val prevCat = prevMeta?.customCategory?.let { runCatching { FileCategory.valueOf(it) }.getOrNull() }
            val prevParent = if (!item.isSafDocument) File(item.pathOrUri).parentFile?.absolutePath else null

            val movedNewPath: String?
            val actionLabel: String
            when (direction) {
                GestureSortDirection.LEFT_DOCUMENTS -> {
                    soundEngine.playSandBrush()
                    movedNewPath = fileRepository.categorizeByGesture(item, FileCategory.DOCUMENTS)
                    actionLabel = "У Сувої-документи: ${item.name}"
                }
                GestureSortDirection.RIGHT_SHELLS -> {
                    soundEngine.playShellChime()
                    movedNewPath = fileRepository.categorizeByGesture(item, FileCategory.IMAGES)
                    actionLabel = "У Мушлі-медіа: ${item.name}"
                }
                GestureSortDirection.UP_TREASURE -> {
                    soundEngine.playShellChime()
                    movedNewPath = fileRepository.categorizeByGesture(
                        item,
                        FileCategory.ARCHIVES_ANDROID,
                        star = true
                    )
                    actionLabel = "У Скарбницю Бобра: ${item.name}"
                }
                GestureSortDirection.DOWN_CLEANUP -> {
                    soundEngine.playWaterDroplet()
                    movedNewPath = fileRepository.categorizeByGesture(item, null, star = false, trash = true)
                    actionLabel = "У кошик для очищення: ${item.name}"
                }
            }

            val resultingKey = if (movedNewPath != null) "file:$movedNewPath" else item.key
            dismissedFromDeckKeys.add(item.key)
            dismissedFromDeckKeys.add(resultingKey)

            _uiState.update {
                it.copy(
                    lastUndoAction = SwipeUndoAction(
                        fileKey = item.key,
                        previousParentPath = prevParent,
                        movedFileNewPath = movedNewPath,
                        previousCategory = prevCat,
                        previousStarred = item.isStarred,
                        previousTrashed = item.isTrashed,
                        description = actionLabel
                    )
                )
            }
            showMessage(actionLabel)
            refreshAllData()
            scheduleAutoCloudBackup()
        }
    }

    fun undoLastGestureSort() {
        val undo = _uiState.value.lastUndoAction ?: return
        viewModelScope.launch {
            soundEngine.playWaterDroplet()
            dismissedFromDeckKeys.remove(undo.fileKey)
            undo.movedFileNewPath?.let { dismissedFromDeckKeys.remove("file:$it") }
            fileRepository.undoSwipeAction(
                fileKey = undo.fileKey,
                movedNewPath = undo.movedFileNewPath,
                previousParentPath = undo.previousParentPath,
                previousCategory = undo.previousCategory,
                previousStarred = undo.previousStarred,
                previousTrashed = undo.previousTrashed
            )
            _uiState.update { it.copy(lastUndoAction = null) }
            showMessage("Дію скасовано")
            refreshAllData()
        }
    }

    fun resetGestureDeck() {
        soundEngine.playWaterDroplet()
        dismissedFromDeckKeys.clear()
        refreshAllData()
    }

    fun emptyTrashAndOptimize() {
        viewModelScope.launch {
            val removed = fileRepository.emptyTrash()
            soundEngine.playWaterDroplet()
            showMessage("Очищено об'єктів: $removed. Простір оптимізовано!")
            refreshAllData()
        }
    }

    fun toggleSurf() {
        viewModelScope.launch {
            preferencesRepository.setSurfEnabled(!preferencesState.value.surfEnabled)
        }
    }

    fun setSurfVolume(vol: Float) {
        viewModelScope.launch { preferencesRepository.setSurfVolume(vol) }
    }

    fun toggleMusic() {
        viewModelScope.launch {
            preferencesRepository.setMusicEnabled(!preferencesState.value.musicEnabled)
        }
    }

    fun setMusicVolume(vol: Float) {
        viewModelScope.launch { preferencesRepository.setMusicVolume(vol) }
    }

    fun toggleSfx(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setSfxEnabled(enabled) }
    }

    fun setThemePreference(pref: ThemePreference) {
        viewModelScope.launch { preferencesRepository.setTheme(pref) }
    }

    fun toggleLightDarkExplicit(currentlyDark: Boolean) {
        setThemePreference(if (currentlyDark) ThemePreference.LIGHT else ThemePreference.DARK)
    }

    fun configureSafCloudBackup(context: Context, treeUri: Uri) {
        viewModelScope.launch {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            }
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
            val folderLabel = docId.substringAfterLast(':').ifBlank { "Хмарне сховище" }
            preferencesRepository.setSafCloudTarget(treeUri.toString(), folderLabel)
            preferencesRepository.setAutoCloudBackup(true)
            showMessage("Підключено хмарну папку «$folderLabel»")
            syncCloudNow()
        }
    }

    fun saveWebDavCloudConfig(url: String, user: String, pass: String) {
        viewModelScope.launch {
            if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                showMessage("Введіть коректну адресу http:// або https://")
                return@launch
            }
            preferencesRepository.setWebDavConfig(url, user, pass)
            showMessage("Налаштування WebDAV збережено")
        }
    }

    fun setAutoCloudBackup(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoCloudBackup(enabled)
            if (enabled && preferencesState.value.isCloudConfigured) {
                syncCloudNow()
            }
        }
    }

    fun disconnectCloudStorage() {
        viewModelScope.launch {
            preferencesRepository.disconnectCloud()
            showMessage("Хмарне сховище від'єднано. Працює автономний режим.")
        }
    }

    fun syncCloudNow() {
        if (_uiState.value.isSyncingCloud) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingCloud = true) }
            val result = cloudSyncRepository.performBackupNow()
            _uiState.update { it.copy(isSyncingCloud = false) }
            if (result.isSuccess) soundEngine.playShellChime()
            showMessage(result.message)
            refreshAllData()
        }
    }

    private fun scheduleAutoCloudBackup() {
        autoBackupJob?.cancel()
        autoBackupJob = viewModelScope.launch {
            delay(1800L)
            cloudSyncRepository.performBackupIfAutoEnabled()
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun showMessage(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { activeDirObserver?.stopWatching() }
        activeDirObserver = null
        audioPlayerManager.release()
        soundEngine.release()
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appContext = context.applicationContext
                    val db = BeachDatabase.getInstance(appContext)
                    val prefs = PreferencesRepository(appContext)
                    val fileRepo = FileStorageRepository(appContext, db.metadataDao())
                    val cloudRepo = CloudSyncRepository(appContext, fileRepo, prefs, db.metadataDao())
                    val fileOps = FileOperationsManager(appContext, db.metadataDao())
                    val archiveRepo = ArchiveRepository(appContext)
                    val sound = BeachSoundEngine()
                    return BeachFileViewModel(
                        appContext = appContext,
                        fileRepository = fileRepo,
                        preferencesRepository = prefs,
                        cloudSyncRepository = cloudRepo,
                        fileOpsManager = fileOps,
                        archiveRepository = archiveRepo,
                        soundEngine = sound
                    ) as T
                }
            }
    }
}

enum class GestureSortDirection {
    LEFT_DOCUMENTS,
    RIGHT_SHELLS,
    UP_TREASURE,
    DOWN_CLEANUP
}
