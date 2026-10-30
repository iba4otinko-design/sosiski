package com.example.data.repository

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.local.BeachMetadataDao
import com.example.data.local.CachedDirStampEntity
import com.example.data.local.CachedFileEntryEntity
import com.example.data.local.ConnectedFolderEntity
import com.example.data.local.SandItemMetaEntity
import com.example.data.model.BeachFileItem
import com.example.data.model.DeviceBreadcrumb
import com.example.data.model.DrawerCategoryType
import com.example.data.model.ExternalDocumentProviderItem
import com.example.data.model.FileCategory
import com.example.data.model.StorageRoot
import com.example.data.model.StorageStats
import com.example.data.model.formatFileSize
import com.example.ui.icons.SmartIconLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class FileStorageRepository(
    private val context: Context,
    private val dao: BeachMetadataDao
) {
    companion object {
        const val VIRTUAL_ANDROID_HUB = "virtual://android_hub"
        const val VIRTUAL_DOWNLOADS_ROOT = "virtual://downloads_root"

        private val BOTTOM_BAR_PROVIDER_AUTHORITIES = setOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
            "com.android.providers.media.documents"
        )
    }

    // L1 In-Memory Directory & Scope Cache for 0ms navigation without re-loading or scroll resets
    private val memoryScopeCache = ConcurrentHashMap<String, List<BeachFileItem>>()
    private val mimeExtensionCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedGlobalLocalFiles: List<BeachFileItem>? = null

    @Volatile
    private var cachedGlobalScanAtMs: Long = 0L

    @Volatile
    private var cachedGlobalShowHidden: Boolean = false

    val beaverCoveDir: File by lazy {
        File(context.filesDir, "beaver_cove").apply { mkdirs() }
    }

    val androidAppExternalDir: File by lazy {
        (context.getExternalFilesDir(null) ?: File(context.filesDir, "android_external_fallback")).apply {
            mkdirs()
        }
    }

    val appDownloadsDir: File by lazy {
        (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads_fallback")).apply {
            mkdirs()
        }
    }

    val publicDownloadsDir: File by lazy {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    val deviceSharedRootDir: File by lazy {
        Environment.getExternalStorageDirectory()
    }

    fun observeAllMeta(): Flow<List<SandItemMetaEntity>> = dao.observeAllMeta()
    fun observeConnectedFolders(): Flow<List<ConnectedFolderEntity>> = dao.observeConnectedFolders()
    suspend fun getAllMetaSnapshot(): Map<String, SandItemMetaEntity> =
        withContext(Dispatchers.IO) { dao.getAllMetaSnapshot().associateBy { it.fileKey } }
    suspend fun getConnectedFoldersSnapshot(): List<ConnectedFolderEntity> =
        withContext(Dispatchers.IO) { dao.getConnectedFoldersSnapshot() }

    // --- Smart Scope Keys & Two-Tier Anti-Phantom Cache ---

    fun beachScopeKey(root: StorageRoot, pathOrUri: String, showHidden: Boolean): String =
        "beach:${root.id}|$pathOrUri|h=${if (showHidden) 1 else 0}"

    fun deviceScopeKey(crumb: DeviceBreadcrumb, showHidden: Boolean): String =
        "device:${if (crumb.isSaf) 1 else 0}|${crumb.pathOrUri}|h=${if (showHidden) 1 else 0}"

    fun downloadsScopeKey(crumb: DeviceBreadcrumb, showHidden: Boolean): String =
        "downloads:${if (crumb.isSaf) 1 else 0}|${crumb.pathOrUri}|h=${if (showHidden) 1 else 0}"

    fun galleryScopeKey(showHidden: Boolean): String =
        "gallery:media_all|h=${if (showHidden) 1 else 0}"

    fun drawerCategoryScopeKey(type: DrawerCategoryType, showHidden: Boolean): String =
        "drawer:${type.name}|h=${if (showHidden) 1 else 0}"

    /**
     * Synchronous L1 memory cache lookup (< 0.05ms) so switching folders/tabs renders immediately
     * without blanking the list or resetting LazyColumn scroll state.
     */
    fun peekMemoryScope(
        scopeKey: String,
        metaMap: Map<String, SandItemMetaEntity>
    ): List<BeachFileItem>? {
        val raw = memoryScopeCache[scopeKey] ?: return null
        return applyLiveMetaAndPruneDeadLocal(raw, metaMap, checkDiskExistence = false)
    }

    /**
     * Loads persisted scope items from L1 RAM or L2 Room SQLite (`cached_file_entries`) on cold start,
     * immediately filtering out any phantom local files that no longer exist on disk.
     */
    suspend fun getCachedScopeWithAntiPhantom(
        scopeKey: String,
        metaMap: Map<String, SandItemMetaEntity>
    ): List<BeachFileItem>? = withContext(Dispatchers.IO) {
        memoryScopeCache[scopeKey]?.let { mem ->
            val validated = applyLiveMetaAndPruneDeadLocal(mem, metaMap, checkDiskExistence = true)
            if (validated.size != mem.size) {
                memoryScopeCache[scopeKey] = validated
            }
            return@withContext validated
        }

        val dbRows = dao.getCachedEntriesForScope(scopeKey)
        if (dbRows.isEmpty()) return@withContext null

        val phantoms = ArrayList<CachedFileEntryEntity>()
        val hydrated = ArrayList<BeachFileItem>(dbRows.size)
        val total = dbRows.size.coerceAtLeast(1)

        dbRows.forEachIndexed { index, row ->
            if (!isCachedEntryStillReal(row)) {
                phantoms.add(row)
                return@forEachIndexed
            }
            val meta = metaMap[row.fileKey]
            val (defX, defY) = defaultSandPosition(row.fileKey, index, total)
            val baseCat = runCatching { FileCategory.valueOf(row.categoryName) }.getOrDefault(FileCategory.OTHER)
            val finalCat = if (row.isDirectory) {
                FileCategory.FOLDER
            } else {
                meta?.customCategory?.let { runCatching { FileCategory.valueOf(it) }.getOrNull() } ?: baseCat
            }
            val trashed = meta?.isTrashed ?: false
            if (!trashed || scopeKey.startsWith("drawer:TRASH")) {
                hydrated.add(
                    BeachFileItem(
                        key = row.fileKey,
                        name = row.name,
                        pathOrUri = row.pathOrUri,
                        parentPathOrUri = row.parentPathOrUri,
                        isDirectory = row.isDirectory,
                        sizeBytes = row.sizeBytes,
                        createdAtMillis = row.createdAtMillis,
                        lastModifiedMillis = row.lastModifiedMillis,
                        category = finalCat,
                        mimeType = row.mimeType,
                        isSafDocument = row.isSafDocument,
                        safTreeUri = row.safTreeUri,
                        requiresSafGrantDocId = row.requiresSafGrantDocId,
                        sandX = meta?.sandX ?: defX,
                        sandY = meta?.sandY ?: defY,
                        isStarred = meta?.isStarred ?: false,
                        isTrashed = trashed,
                        isBackedUp = (meta?.lastBackedUpAt ?: 0L) >= row.lastModifiedMillis && (meta?.lastBackedUpAt ?: 0L) > 0L,
                        childCount = row.childCount,
                        durationMillis = row.durationMillis
                    )
                )
            }
        }

        if (phantoms.isNotEmpty()) {
            for (dead in phantoms) {
                dao.purgePhantomEverywhere(dead.fileKey, dead.pathOrUri)
                SmartIconLoader.invalidatePath(dead.pathOrUri)
            }
        }

        memoryScopeCache[scopeKey] = hydrated
        hydrated
    }

    /**
     * Fast O(N) in-memory reconciliation against L1 scope cache:
     * - Invalidates stale icon/thumbnail entries for removed or modified files.
     * - Updates L1 memory cache immediately without waiting on SQLite disk I/O so UI text renders instantly.
     */
    fun reconcileMemoryScopeFast(
        scopeKey: String,
        freshItems: List<BeachFileItem>
    ): Pair<List<BeachFileItem>, Boolean> {
        val prev = memoryScopeCache[scopeKey]
        val changed = prev == null || !areFileListsEquivalent(prev, freshItems)

        if (prev != null && changed) {
            val freshByKey = freshItems.associateBy { it.key }
            for (oldItem in prev) {
                val nowItem = freshByKey[oldItem.key]
                if (nowItem == null || nowItem.lastModifiedMillis != oldItem.lastModifiedMillis || nowItem.sizeBytes != oldItem.sizeBytes) {
                    SmartIconLoader.invalidatePath(oldItem.pathOrUri)
                }
            }
        }

        memoryScopeCache[scopeKey] = freshItems
        return freshItems to changed
    }

    /**
     * Persists a reconciled scope snapshot into Room SQLite in the background after UI text has already rendered.
     */
    suspend fun persistReconciledScopeToDisk(
        scopeKey: String,
        freshItems: List<BeachFileItem>,
        dirLastModified: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val entities = freshItems.take(1500).mapIndexed { idx, item ->
            CachedFileEntryEntity(
                scopeKey = scopeKey,
                fileKey = item.key,
                name = item.name,
                pathOrUri = item.pathOrUri,
                parentPathOrUri = item.parentPathOrUri,
                isDirectory = item.isDirectory,
                sizeBytes = item.sizeBytes,
                createdAtMillis = item.createdAtMillis,
                lastModifiedMillis = item.lastModifiedMillis,
                categoryName = item.category.name,
                mimeType = item.mimeType,
                isSafDocument = item.isSafDocument,
                safTreeUri = item.safTreeUri,
                requiresSafGrantDocId = item.requiresSafGrantDocId,
                childCount = item.childCount,
                durationMillis = item.durationMillis,
                sortOrder = idx
            )
        }
        val stamp = CachedDirStampEntity(
            scopeKey = scopeKey,
            dirLastModified = dirLastModified,
            entryCount = freshItems.size,
            checkedAtMillis = System.currentTimeMillis()
        )
        dao.replaceCachedScopeAtomically(scopeKey, entities, stamp)
    }

    /**
     * Reconciles a freshly scanned list of real items against L1 + Room cache:
     * - Purges any phantom items that disappeared from disk.
     * - Saves added/modified items atomically into Room `cached_file_entries`.
     * - Returns `(freshItems, didChange)` so UI state only updates when something actually changed!
     */
    suspend fun commitReconciledScope(
        scopeKey: String,
        freshItems: List<BeachFileItem>,
        dirLastModified: Long = System.currentTimeMillis()
    ): Pair<List<BeachFileItem>, Boolean> = withContext(Dispatchers.IO) {
        val (reconciled, changed) = reconcileMemoryScopeFast(scopeKey, freshItems)
        if (changed) {
            persistReconciledScopeToDisk(scopeKey, reconciled, dirLastModified)
        }
        reconciled to changed
    }

    /**
     * Immediately removes a deleted/moved file key across all L1 scopes and Room cache tables
     * so zero phantom entries can ever remain visible in any tab.
     */
    suspend fun purgePhantomItemEverywhere(fileKey: String, pathOrUri: String) = withContext(Dispatchers.IO) {
        cachedGlobalLocalFiles = null
        SmartIconLoader.invalidatePath(pathOrUri)
        for ((scope, list) in memoryScopeCache.entries) {
            if (list.any { it.key == fileKey || it.pathOrUri == pathOrUri || it.pathOrUri.startsWith("$pathOrUri/") }) {
                memoryScopeCache[scope] = list.filterNot {
                    it.key == fileKey || it.pathOrUri == pathOrUri || it.pathOrUri.startsWith("$pathOrUri/")
                }
            }
        }
        dao.purgePhantomEverywhere(fileKey, pathOrUri)
        if (!pathOrUri.startsWith("content://") && pathOrUri.isNotBlank()) {
            dao.purgePhantomSubtree("$pathOrUri/")
        }
    }

    fun markDirectoryMutated() {
        cachedGlobalLocalFiles = null
    }

    private fun isCachedEntryStillReal(row: CachedFileEntryEntity): Boolean {
        if (row.requiresSafGrantDocId != null || row.fileKey.startsWith("virtual:")) return true
        if (row.isSafDocument || row.pathOrUri.startsWith("content://")) return true
        val f = File(row.pathOrUri)
        return f.exists()
    }

    private fun applyLiveMetaAndPruneDeadLocal(
        items: List<BeachFileItem>,
        metaMap: Map<String, SandItemMetaEntity>,
        checkDiskExistence: Boolean
    ): List<BeachFileItem> {
        val out = ArrayList<BeachFileItem>(items.size)
        for (item in items) {
            if (checkDiskExistence &&
                !item.isSafDocument &&
                !item.pathOrUri.startsWith("content://") &&
                item.requiresSafGrantDocId == null &&
                !item.key.startsWith("virtual:")
            ) {
                if (!File(item.pathOrUri).exists()) {
                    SmartIconLoader.invalidatePath(item.pathOrUri)
                    continue
                }
            }
            val meta = metaMap[item.key]
            val isTrashed = meta?.isTrashed ?: item.isTrashed
            if (isTrashed) continue
            val updatedCat = if (item.isDirectory) {
                FileCategory.FOLDER
            } else {
                meta?.customCategory?.let { runCatching { FileCategory.valueOf(it) }.getOrNull() } ?: item.category
            }
            out.add(
                item.copy(
                    category = updatedCat,
                    sandX = meta?.sandX ?: item.sandX,
                    sandY = meta?.sandY ?: item.sandY,
                    isStarred = meta?.isStarred ?: item.isStarred,
                    isTrashed = false,
                    isBackedUp = (meta?.lastBackedUpAt ?: 0L) >= item.lastModifiedMillis && (meta?.lastBackedUpAt ?: 0L) > 0L
                )
            )
        }
        return out
    }

    fun areFileListsEquivalent(a: List<BeachFileItem>, b: List<BeachFileItem>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x.key != y.key ||
                x.name != y.name ||
                x.sizeBytes != y.sizeBytes ||
                x.lastModifiedMillis != y.lastModifiedMillis ||
                x.childCount != y.childCount ||
                x.isStarred != y.isStarred ||
                x.isTrashed != y.isTrashed ||
                x.category != y.category ||
                x.requiresSafGrantDocId != y.requiresSafGrantDocId
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Checks whether full shared-storage file manager access is granted.
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun createAllFilesAccessIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    suspend fun ensureWorkspaceInitialized() = withContext(Dispatchers.IO) {
        beaverCoveDir.mkdirs()
        File(beaverCoveDir, "Корчі_Кошик").mkdirs()
        androidAppExternalDir.mkdirs()
        appDownloadsDir.mkdirs()

        val purgeMarker = File(context.filesDir, ".demo_purged_v2")
        if (!purgeMarker.exists()) {
            purgeLegacySeededDemoFiles()
            File(context.filesDir, ".cove_seeded_v1").delete()
            File(context.filesDir, ".downloads_seeded_v1").delete()
            runCatching { purgeMarker.writeText("purged") }
        }
    }

    private suspend fun purgeLegacySeededDemoFiles() {
        val legacyFiles = listOf(
            File(appDownloadsDir, "довідник_завантажень_бобра.txt"),
            File(appDownloadsDir, "атлас_берегової_затоки.zip"),
            File(appDownloadsDir, "передзвін_мушлі.wav"),
            File(appDownloadsDir, "береговий_пак_бобра.mcpack"),
            File(beaverCoveDir, "мушля_лагуни.jpg"),
            File(beaverCoveDir, "креслення_греблі.txt"),
            File(beaverCoveDir, "послання_в_пляшці.zip"),
            File(File(beaverCoveDir, "Мушлі_Пляжу"), "сонячний_пляж_бобра.jpg"),
            File(File(beaverCoveDir, "Мушлі_Пляжу"), "перламутрові_мушлі.jpg"),
            File(File(beaverCoveDir, "Мушлі_Пляжу"), "місячна_затока_хатка.jpg"),
            File(File(beaverCoveDir, "Сувої_Греблі"), "щоденник_припливів.md"),
            File(File(beaverCoveDir, "Скарбниця_Бобра"), "карта_скарбів_затоки.json"),
            File(androidAppExternalDir, "android_space_info.txt"),
            File(File(androidAppExternalDir, "Android_Media_Cache"), "coastal_telemetry_bundle.log")
        )
        for (file in legacyFiles) {
            if (file.exists() && file.isFile) {
                file.delete()
            }
            dao.deleteMeta("file:${file.absolutePath}")
            dao.purgePhantomEverywhere("file:${file.absolutePath}", file.absolutePath)
        }

        val legacyDirs = listOf(
            File(beaverCoveDir, "Мушлі_Пляжу"),
            File(beaverCoveDir, "Сувої_Греблі"),
            File(beaverCoveDir, "Скарбниця_Бобра"),
            File(androidAppExternalDir, "Android_Media_Cache")
        )
        for (dir in legacyDirs) {
            if (dir.exists() && dir.isDirectory && dir.list().isNullOrEmpty()) {
                dir.delete()
                dao.deleteMeta("file:${dir.absolutePath}")
            }
        }
    }

    private fun shouldIncludeName(name: String, showHidden: Boolean): Boolean {
        if (name == "Корчі_Кошик") return false
        if (!showHidden && name.startsWith(".")) return false
        return true
    }

    fun rootPathFor(root: StorageRoot): String = when (root) {
        is StorageRoot.InternalShared -> deviceSharedRootDir.absolutePath
        is StorageRoot.PublicDownloads -> publicDownloadsDir.absolutePath
        is StorageRoot.BeaverCove -> beaverCoveDir.absolutePath
        is StorageRoot.AndroidAppFolder -> androidAppExternalDir.absolutePath
        is StorageRoot.SafTree -> {
            val treeUri = Uri.parse(root.treeUriString)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
        }
    }

    fun initialDeviceBreadcrumb(): DeviceBreadcrumb {
        return DeviceBreadcrumb(
            title = context.getString(R.string.device_root_crumb),
            pathOrUri = deviceSharedRootDir.absolutePath,
            isSaf = false
        )
    }

    /**
     * Fast gallery query returning BOTH real Photos AND Videos (`IMAGES` + `VIDEO`)
     * via Android `MediaStore.Files` + workspace directories, with strict `File.exists()`
     * verification so deleted files never appear as phantom thumbnails.
     */
    suspend fun queryGalleryMediaItems(
        metaMap: Map<String, SandItemMetaEntity>,
        showHidden: Boolean = false
    ): List<BeachFileItem> = withContext(Dispatchers.IO) {
        val byPath = LinkedHashMap<String, BeachFileItem>(256)

        // 1. Fast MediaStore query for both images and videos across shared storage
        runCatching {
            val collection = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Video.VideoColumns.DURATION
            )
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
            val selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )
            val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val modCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val durCol = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)

                var count = 0
                while (cursor.moveToNext() && count < 2000) {
                    val id = cursor.getLong(idCol)
                    val dataPath = if (dataCol >= 0 && !cursor.isNull(dataCol)) cursor.getString(dataCol) else null
                    val mediaType = cursor.getInt(typeCol)
                    val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val mime = cursor.getString(mimeCol) ?: if (isVideo) "video/mp4" else "image/jpeg"
                    val durationMs = if (durCol >= 0 && !cursor.isNull(durCol)) cursor.getLong(durCol).coerceAtLeast(0L) else 0L

                    if (!dataPath.isNullOrBlank()) {
                        val file = File(dataPath)
                        // Anti-phantom check: skip MediaStore rows whose actual file was deleted or is 0 bytes!
                        if (!file.exists() || !file.isFile || file.length() <= 0L) continue
                        if (!shouldIncludeName(file.name, showHidden)) continue
                        if (!showHidden && dataPath.contains("/.")) continue

                        val key = "file:${file.absolutePath}"
                        val meta = metaMap[key]
                        if (meta?.isTrashed == true) continue

                        val modifiedMillis = file.lastModified().takeIf { it > 0L }
                            ?: (cursor.getLong(modCol) * 1000L)
                        val category = if (isVideo) FileCategory.VIDEO else FileCategory.IMAGES

                        byPath[file.absolutePath] = BeachFileItem(
                            key = key,
                            name = file.name,
                            pathOrUri = file.absolutePath,
                            parentPathOrUri = file.parentFile?.absolutePath.orEmpty(),
                            isDirectory = false,
                            sizeBytes = file.length(),
                            createdAtMillis = modifiedMillis,
                            lastModifiedMillis = modifiedMillis,
                            category = category,
                            mimeType = mime,
                            isSafDocument = false,
                            sandX = meta?.sandX ?: 0.5f,
                            sandY = meta?.sandY ?: 0.5f,
                            isStarred = meta?.isStarred ?: false,
                            isTrashed = false,
                            isBackedUp = (meta?.lastBackedUpAt ?: 0L) >= modifiedMillis && (meta?.lastBackedUpAt ?: 0L) > 0L,
                            durationMillis = durationMs
                        )
                        count++
                    } else {
                        val name = cursor.getString(nameCol) ?: continue
                        if (!shouldIncludeName(name, showHidden)) continue
                        val size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                        if (size <= 0L) continue
                        val baseUri = if (isVideo) {
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        } else {
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }
                        val contentUri = ContentUris.withAppendedId(baseUri, id).toString()
                        val key = "saf:$contentUri"
                        val meta = metaMap[key]
                        if (meta?.isTrashed == true) continue
                        val modMs = cursor.getLong(modCol) * 1000L

                        byPath[contentUri] = BeachFileItem(
                            key = key,
                            name = name,
                            pathOrUri = contentUri,
                            parentPathOrUri = "",
                            isDirectory = false,
                            sizeBytes = size,
                            createdAtMillis = modMs,
                            lastModifiedMillis = modMs,
                            category = if (isVideo) FileCategory.VIDEO else FileCategory.IMAGES,
                            mimeType = mime,
                            isSafDocument = true,
                            isStarred = meta?.isStarred ?: false,
                            durationMillis = durationMs
                        )
                        count++
                    }
                }
            }
        }

        // 2. Merge newly created/imported media in app workspaces & key public media dirs that MediaStore may not have indexed yet
        val quickMediaDirs = buildList {
            add(beaverCoveDir)
            add(appDownloadsDir)
            if (hasAllFilesAccess()) {
                add(publicDownloadsDir)
                add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
                add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))
                add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
            }
        }

        fun scanMediaDirQuick(dir: File, depth: Int) {
            if (depth > 3 || byPath.size > 2500 || !dir.exists()) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                val name = child.name
                if (!shouldIncludeName(name, showHidden)) continue
                if (child.isDirectory) {
                    scanMediaDirQuick(child, depth + 1)
                } else if (child.isFile && child.length() > 0L) {
                    val abs = child.absolutePath
                    if (abs in byPath) continue
                    val cat = FileCategory.fromExtensionOrMime(name, null, false)
                    if (cat == FileCategory.IMAGES || cat == FileCategory.VIDEO) {
                        val item = toBeachFileItem(child, metaMap, byPath.size, 100, showHidden)
                        if (!item.isTrashed) {
                            byPath[abs] = item
                        }
                    }
                }
            }
        }

        for (d in quickMediaDirs) {
            scanMediaDirQuick(d, 0)
        }

        byPath.values.sortedByDescending { it.lastModifiedMillis }
    }

    suspend fun listDeviceBreadcrumb(
        crumb: DeviceBreadcrumb,
        connectedFolders: List<ConnectedFolderEntity>,
        metaMap: Map<String, SandItemMetaEntity>,
        showHidden: Boolean = false
    ): List<BeachFileItem> = withContext(Dispatchers.IO) {
        if (crumb.isSaf && crumb.safTreeUri.isNotBlank()) {
            return@withContext listSafChildren(
                treeUri = Uri.parse(crumb.safTreeUri),
                parentDocUri = Uri.parse(crumb.pathOrUri),
                metaMap = metaMap,
                showHidden = showHidden
            )
        }

        if (crumb.pathOrUri == VIRTUAL_ANDROID_HUB) {
            return@withContext buildAndroidHubEntries(connectedFolders, metaMap, showHidden)
        }

        val currentDir = File(crumb.pathOrUri)
        val isRootShared = currentDir.absolutePath == deviceSharedRootDir.absolutePath

        if (isRootShared) {
            if (!hasAllFilesAccess()) {
                val rootSaf = findMatchingConnectedSaf(connectedFolders, "primary:")
                if (rootSaf != null) {
                    val treeUri = Uri.parse(rootSaf.treeUri)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    val safItems = listSafChildren(treeUri, docUri, metaMap, showHidden).toMutableList()
                    safItems.add(
                        0,
                        toBeachFileItem(beaverCoveDir, metaMap, 0, safItems.size + 1, showHidden).copy(
                            name = context.getString(R.string.device_app_storage_folder)
                        )
                    )
                    return@withContext safItems
                }
            }

            return@withContext buildDeviceRootEntries(connectedFolders, metaMap, showHidden)
        }

        listLocalDirFiltered(currentDir, metaMap, showHidden)
    }

    private fun buildAndroidHubEntries(
        connectedFolders: List<ConnectedFolderEntity>,
        metaMap: Map<String, SandItemMetaEntity>,
        showHidden: Boolean
    ): List<BeachFileItem> {
        val results = mutableListOf<BeachFileItem>()
        val appDataItem = toBeachFileItem(androidAppExternalDir, metaMap, 0, 4, showHidden).copy(
            name = "data (${context.packageName})",
            parentPathOrUri = VIRTUAL_ANDROID_HUB,
            category = FileCategory.FOLDER
        )
        results.add(appDataItem)

        val appMediaDir = context.externalMediaDirs.firstOrNull()?.apply { mkdirs() }
        if (appMediaDir != null && appMediaDir.exists()) {
            results.add(
                toBeachFileItem(appMediaDir, metaMap, 1, 4, showHidden).copy(
                    name = "media (${context.packageName})",
                    parentPathOrUri = VIRTUAL_ANDROID_HUB,
                    category = FileCategory.FOLDER
                )
            )
        }

        val systemMediaDir = File(deviceSharedRootDir, "Android/media")
        val directMediaReadable = hasAllFilesAccess() && systemMediaDir.exists() &&
            runCatching { systemMediaDir.list() }.getOrNull() != null
        val mediaSaf = findMatchingConnectedSaf(connectedFolders, "primary:Android/media")
        when {
            directMediaReadable -> {
                results.add(
                    toBeachFileItem(systemMediaDir, metaMap, results.size, 4, showHidden).copy(
                        name = "media (усі медіа застосунків)",
                        parentPathOrUri = VIRTUAL_ANDROID_HUB,
                        category = FileCategory.FOLDER
                    )
                )
            }
            mediaSaf != null -> {
                val treeUri = Uri.parse(mediaSaf.treeUri)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
                results.add(
                    BeachFileItem(
                        key = "saf:$docUri",
                        name = "media (усі медіа застосунків)",
                        pathOrUri = docUri.toString(),
                        parentPathOrUri = VIRTUAL_ANDROID_HUB,
                        isDirectory = true,
                        sizeBytes = 0L,
                        createdAtMillis = mediaSaf.addedAtMillis,
                        lastModifiedMillis = mediaSaf.addedAtMillis,
                        category = FileCategory.FOLDER,
                        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                        isSafDocument = true,
                        safTreeUri = mediaSaf.treeUri
                    )
                )
            }
            else -> {
                results.add(
                    BeachFileItem(
                        key = "saf_prompt:primary:Android/media",
                        name = "media (відкрити системну папку)",
                        pathOrUri = VIRTUAL_ANDROID_HUB,
                        parentPathOrUri = VIRTUAL_ANDROID_HUB,
                        isDirectory = true,
                        sizeBytes = 0L,
                        createdAtMillis = System.currentTimeMillis(),
                        lastModifiedMillis = System.currentTimeMillis(),
                        category = FileCategory.FOLDER,
                        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                        isSafDocument = false,
                        requiresSafGrantDocId = "primary:Android/media"
                    )
                )
            }
        }

        val androidRoot = File(deviceSharedRootDir, "Android")
        runCatching { androidRoot.listFiles() }.getOrNull()?.forEach { child ->
            if (child.name in setOf("data", "media")) return@forEach
            if (!shouldIncludeName(child.name, showHidden)) return@forEach
            if (child.isFile || runCatching { child.list() }.getOrNull() != null) {
                results.add(
                    toBeachFileItem(child, metaMap, results.size, results.size + 2, showHidden).copy(
                        parentPathOrUri = VIRTUAL_ANDROID_HUB
                    )
                )
            }
        }

        return results
    }

    private fun buildDeviceRootEntries(
        connectedFolders: List<ConnectedFolderEntity>,
        metaMap: Map<String, SandItemMetaEntity>,
        showHidden: Boolean
    ): List<BeachFileItem> {
        val entries = mutableListOf<BeachFileItem>()
        val fullAccess = hasAllFilesAccess()

        val rawChildren = runCatching { deviceSharedRootDir.listFiles()?.toList() }.getOrNull().orEmpty()
        val visibleRealChildren = rawChildren.filter { shouldIncludeName(it.name, showHidden) }

        val folderNames = linkedSetOf<String>()
        val looseRootFiles = mutableListOf<File>()

        visibleRealChildren.forEach { child ->
            if (child.isDirectory) {
                folderNames.add(child.name)
            } else if (child.isFile) {
                looseRootFiles.add(child)
            }
        }

        if (!fullAccess) {
            folderNames.addAll(
                listOf(
                    "Download",
                    "DCIM",
                    "Pictures",
                    "Documents",
                    "Movies",
                    "Music",
                    "Notifications",
                    "Podcasts",
                    "Android"
                )
            )
        } else if ("Android" !in folderNames && File(deviceSharedRootDir, "Android").exists()) {
            folderNames.add("Android")
        }

        val totalEstimate = (folderNames.size + looseRootFiles.size + 1).coerceAtLeast(1)
        var idx = 0

        val coveHasFiles = beaverCoveDir.list()?.any { shouldIncludeName(it, showHidden) } == true
        if (coveHasFiles || !fullAccess) {
            entries.add(
                toBeachFileItem(beaverCoveDir, metaMap, idx++, totalEstimate, showHidden).copy(
                    name = context.getString(R.string.device_app_storage_folder)
                )
            )
        }

        for (folderName in folderNames) {
            if (folderName == "Android") {
                entries.add(
                    BeachFileItem(
                        key = "virtual:android_hub",
                        name = "Android",
                        pathOrUri = VIRTUAL_ANDROID_HUB,
                        parentPathOrUri = deviceSharedRootDir.absolutePath,
                        isDirectory = true,
                        sizeBytes = 0L,
                        createdAtMillis = androidAppExternalDir.lastModified(),
                        lastModifiedMillis = androidAppExternalDir.lastModified(),
                        category = FileCategory.ARCHIVES_ANDROID,
                        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                        isSafDocument = false,
                        childCount = 3
                    )
                )
                idx++
                continue
            }

            val docIdCandidate = "primary:$folderName"
            val matchedSaf = findMatchingConnectedSaf(connectedFolders, docIdCandidate)
            val rawDir = File(deviceSharedRootDir, folderName)
            val directReadable = rawDir.exists() && runCatching { rawDir.list() }.getOrNull() != null

            when {
                fullAccess && directReadable -> {
                    entries.add(toBeachFileItem(rawDir, metaMap, idx, totalEstimate, showHidden))
                }
                matchedSaf != null -> {
                    val treeUri = Uri.parse(matchedSaf.treeUri)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    entries.add(
                        BeachFileItem(
                            key = "saf:$docUri",
                            name = folderName,
                            pathOrUri = docUri.toString(),
                            parentPathOrUri = deviceSharedRootDir.absolutePath,
                            isDirectory = true,
                            sizeBytes = 0L,
                            createdAtMillis = rawDir.lastModified().takeIf { it > 0L } ?: matchedSaf.addedAtMillis,
                            lastModifiedMillis = rawDir.lastModified().takeIf { it > 0L } ?: matchedSaf.addedAtMillis,
                            category = FileCategory.FOLDER,
                            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                            isSafDocument = true,
                            safTreeUri = matchedSaf.treeUri
                        )
                    )
                }
                directReadable -> {
                    entries.add(toBeachFileItem(rawDir, metaMap, idx, totalEstimate, showHidden))
                }
                else -> {
                    val mod = rawDir.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                    entries.add(
                        BeachFileItem(
                            key = "saf_prompt:$docIdCandidate",
                            name = folderName,
                            pathOrUri = rawDir.absolutePath,
                            parentPathOrUri = deviceSharedRootDir.absolutePath,
                            isDirectory = true,
                            sizeBytes = 0L,
                            createdAtMillis = mod,
                            lastModifiedMillis = mod,
                            category = FileCategory.FOLDER,
                            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                            isSafDocument = false,
                            requiresSafGrantDocId = docIdCandidate
                        )
                    )
                }
            }
            idx++
        }

        for (file in looseRootFiles) {
            val item = toBeachFileItem(file, metaMap, idx++, totalEstimate, showHidden)
            if (!item.isTrashed) {
                entries.add(item)
            }
        }

        connectedFolders.forEach { customSaf ->
            val treeUri = Uri.parse(customSaf.treeUri)
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
            val isPrimaryStandard = treeDocId.startsWith("primary:", ignoreCase = true) && fullAccess
            val alreadyListed = entries.any { it.safTreeUri == customSaf.treeUri }
            if (!isPrimaryStandard && !alreadyListed && treeDocId.isNotBlank()) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
                entries.add(
                    BeachFileItem(
                        key = "saf:$docUri",
                        name = customSaf.displayName,
                        pathOrUri = docUri.toString(),
                        parentPathOrUri = deviceSharedRootDir.absolutePath,
                        isDirectory = true,
                        sizeBytes = 0L,
                        createdAtMillis = customSaf.addedAtMillis,
                        lastModifiedMillis = customSaf.addedAtMillis,
                        category = FileCategory.FOLDER,
                        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                        isSafDocument = true,
                        safTreeUri = customSaf.treeUri
                    )
                )
            }
        }

        return entries
    }

    private fun findMatchingConnectedSaf(
        connectedFolders: List<ConnectedFolderEntity>,
        expectedDocId: String
    ): ConnectedFolderEntity? {
        return connectedFolders.firstOrNull { entity ->
            val treeDocId = runCatching {
                DocumentsContract.getTreeDocumentId(Uri.parse(entity.treeUri))
            }.getOrDefault("")
            treeDocId.equals(expectedDocId, ignoreCase = true)
        }
    }

    suspend fun listDirectory(
        root: StorageRoot,
        currentPathOrUri: String,
        metaMap: Map<String, SandItemMetaEntity>,
        showHidden: Boolean = false
    ): List<BeachFileItem> = withContext(Dispatchers.IO) {
        when (root) {
            is StorageRoot.InternalShared -> {
                if (currentPathOrUri == VIRTUAL_ANDROID_HUB) {
                    return@withContext buildAndroidHubEntries(emptyList(), metaMap, showHidden)
                }
                val dir = File(currentPathOrUri)
                if (dir.absolutePath == deviceSharedRootDir.absolutePath) {
                    return@withContext buildDeviceRootEntries(emptyList(), metaMap, showHidden)
                        .filter { it.requiresSafGrantDocId == null }
                }
                listLocalDirFiltered(dir, metaMap, showHidden)
            }
            is StorageRoot.PublicDownloads -> {
                val dir = File(currentPathOrUri)
                listLocalDirFiltered(dir, metaMap, showHidden)
            }
            is StorageRoot.BeaverCove, is StorageRoot.AndroidAppFolder -> {
                val dir = File(currentPathOrUri)
                listLocalDirFiltered(dir, metaMap, showHidden)
            }
            is StorageRoot.SafTree -> {
                listSafChildren(Uri.parse(root.treeUriString), Uri.parse(currentPathOrUri), metaMap, showHidden)
            }
        }
    }

    private fun listSafChildren(
        treeUri: Uri,
        parentDocUri: Uri,
        metaMap: Map<String, SandItemMetaEntity>,
        showHidden: Boolean = false
    ): List<BeachFileItem> {
        val parentDocId = runCatching { DocumentsContract.getDocumentId(parentDocUri) }
            .getOrElse { DocumentsContract.getTreeDocumentId(treeUri) }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val results = mutableListOf<BeachFileItem>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                var idx = 0
                val total = cursor.count.coerceAtLeast(1)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: "Об'єкт"
                    if (!shouldIncludeName(name, showHidden)) continue
                    val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                    val modified = if (cursor.isNull(modCol)) System.currentTimeMillis() else cursor.getLong(modCol)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val key = "saf:$docUri"
                    val meta = metaMap[key]
                    val (defaultX, defaultY) = defaultSandPosition(key, idx, total)
                    val detectedCat = FileCategory.fromExtensionOrMime(name, mime, isDir)
                    val finalCat = meta?.customCategory?.let {
                        runCatching { FileCategory.valueOf(it) }.getOrNull()
                    } ?: detectedCat

                    if (meta?.isTrashed != true) {
                        results.add(
                            BeachFileItem(
                                key = key,
                                name = name,
                                pathOrUri = docUri.toString(),
                                parentPathOrUri = parentDocUri.toString(),
                                isDirectory = isDir,
                                sizeBytes = size,
                                createdAtMillis = modified,
                                lastModifiedMillis = modified,
                                category = finalCat,
                                mimeType = mime,
                                isSafDocument = true,
                                safTreeUri = treeUri.toString(),
                                sandX = meta?.sandX ?: defaultX,
                                sandY = meta?.sandY ?: defaultY,
                                isStarred = meta?.isStarred ?: false,
                                isTrashed = false,
                                isBackedUp = (meta?.lastBackedUpAt ?: 0L) >= modified
                            )
                        )
                    }
                    idx++
                }
            }
        }
        return results
    }

    /**
     * Ultra-fast conversion from File to BeachFileItem:
     * - Zero recursive `computeDirSizeShallow` (prevents O(N*M) freeze on large folders)
     * - Zero `Files.readAttributes` NIO syscall per item
     * - Lightweight `file.list()` name-only syscall for directory childCount
     */
    private fun toBeachFileItem(
        file: File,
        metaMap: Map<String, SandItemMetaEntity>,
        index: Int,
        totalCount: Int,
        showHidden: Boolean = false
    ): BeachFileItem {
        val absPath = file.absolutePath
        val key = "file:$absPath"
        val meta = metaMap[key]
        val isDir = file.isDirectory
        val name = file.name
        val mime = if (isDir) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext.isEmpty()) {
                "application/octet-stream"
            } else {
                mimeExtensionCache.getOrPut(ext) {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                }
            }
        }
        val modifiedMillis = file.lastModified()
        val size = if (isDir) 0L else file.length()
        val childCount = when {
            !isDir -> 0
            totalCount > 64 -> -1
            else -> countShallowChildrenBounded(file, showHidden, maxInspect = 500)
        }
        val (defX, defY) = defaultSandPosition(key, index, totalCount.coerceAtLeast(1))
        val detectedCat = FileCategory.fromExtensionOrMime(name, mime, isDir)
        val finalCat = if (isDir) {
            FileCategory.FOLDER
        } else {
            meta?.customCategory?.let { runCatching { FileCategory.valueOf(it) }.getOrNull() } ?: detectedCat
        }

        return BeachFileItem(
            key = key,
            name = name,
            pathOrUri = absPath,
            parentPathOrUri = file.parent ?: "",
            isDirectory = isDir,
            sizeBytes = size,
            createdAtMillis = modifiedMillis,
            lastModifiedMillis = modifiedMillis,
            category = finalCat,
            mimeType = mime,
            isSafDocument = false,
            sandX = meta?.sandX ?: defX,
            sandY = meta?.sandY ?: defY,
            isStarred = meta?.isStarred ?: false,
            isTrashed = meta?.isTrashed ?: false,
            isBackedUp = (meta?.lastBackedUpAt ?: 0L) >= modifiedMillis && (meta?.lastBackedUpAt ?: 0L) > 0L,
            childCount = childCount
        )
    }

    private fun countShallowChildrenBounded(dir: File, showHidden: Boolean, maxInspect: Int): Int {
        return runCatching {
            Files.newDirectoryStream(dir.toPath()).use { stream ->
                var matched = 0
                var inspected = 0
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    val entryName = iterator.next().fileName?.toString() ?: continue
                    inspected++
                    if (shouldIncludeName(entryName, showHidden)) {
                        matched++
                    }
                    if (inspected >= maxInspect) {
                        return@use -1
                    }
                }
                matched
            }
        }.getOrDefault(-1)
    }

    private fun defaultSandPosition(key: String, index: Int, total: Int): Pair<Float, Float> {
        val cols = 3
        val col = index % cols
        val row = index / cols
        val totalRows = ((total + cols - 1) / cols).coerceAtLeast(1)
        val jitterSeed = abs(key.hashCode())
        val jx = ((jitterSeed % 100) / 100f - 0.5f) * 0.08f
        val jy = (((jitterSeed / 100) % 100) / 100f - 0.5f) * 0.06f
        val baseX = (0.18f + col * 0.32f + jx).coerceIn(0.12f, 0.88f)
        val rowSpacing = (0.64f / totalRows.coerceAtLeast(2)).coerceIn(0.16f, 0.28f)
        val baseY = (0.18f + row * rowSpacing + jy).coerceIn(0.14f, 0.84f)
        return baseX to baseY
    }

    /**
     * Instant (< 0.2ms) device storage capacity check using StatFs without walking files.
     */
    fun computeQuickDeviceStorageStats(existing: StorageStats = StorageStats()): StorageStats {
        return runCatching {
            val statFs = StatFs(Environment.getDataDirectory().absolutePath)
            val totalBytes = statFs.totalBytes
            val freeBytes = statFs.availableBytes
            val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
            existing.copy(
                totalDeviceBytes = totalBytes,
                freeDeviceBytes = freeBytes,
                usedDeviceBytes = usedBytes
            )
        }.getOrDefault(existing)
    }

    suspend fun collectAllLocalFiles(
        metaMap: Map<String, SandItemMetaEntity>,
        includeTrashed: Boolean = false,
        showHidden: Boolean = false
    ): List<BeachFileItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedGlobalLocalFiles
        if (!includeTrashed && cached != null && cachedGlobalShowHidden == showHidden && (now - cachedGlobalScanAtMs) < 20_000L) {
            val pruned = applyLiveMetaAndPruneDeadLocal(cached, metaMap, checkDiskExistence = true)
            cachedGlobalLocalFiles = pruned
            return@withContext pruned
        }

        val seenPaths = HashSet<String>(512)
        val collectedFiles = ArrayList<File>(256)
        val maxTotalFiles = 2000
        val maxDepth = 4
        val androidDataRootPath = File(deviceSharedRootDir, "Android/data").absolutePath
        val androidObbRootPath = File(deviceSharedRootDir, "Android/obb").absolutePath

        fun walkDir(dir: File, depth: Int) {
            if (depth > maxDepth || collectedFiles.size >= maxTotalFiles) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (collectedFiles.size >= maxTotalFiles) break
                val name = child.name
                if (!showHidden && name.startsWith(".")) continue
                if (name == "Корчі_Кошик" && !includeTrashed) continue
                val absPath = child.absolutePath
                if (child.isDirectory) {
                    if (absPath == androidDataRootPath || absPath == androidObbRootPath) continue
                    walkDir(child, depth + 1)
                } else if (child.isFile) {
                    if (seenPaths.add(absPath)) {
                        collectedFiles.add(child)
                    }
                }
            }
        }

        if (deviceSharedRootDir.exists()) {
            walkDir(deviceSharedRootDir, depth = 0)
        }
        val publicDirs = listOf(
            publicDownloadsDir,
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        )
        for (pubDir in publicDirs) {
            if (pubDir.exists()) {
                walkDir(pubDir, depth = 0)
            }
        }
        walkDir(beaverCoveDir, depth = 0)
        walkDir(appDownloadsDir, depth = 0)
        walkDir(androidAppExternalDir, depth = 0)

        val built = collectedFiles.mapIndexed { idx, file ->
            toBeachFileItem(file, metaMap, idx, collectedFiles.size, showHidden)
        }
        if (!includeTrashed) {
            val nonTrashed = built.filter { !it.isTrashed }
            cachedGlobalLocalFiles = nonTrashed
            cachedGlobalScanAtMs = now
            cachedGlobalShowHidden = showHidden
            nonTrashed
        } else {
            built
        }
    }

    suspend fun computeStorageStats(
        metaMap: Map<String, SandItemMetaEntity>,
        showHidden: Boolean = false
    ): StorageStats = withContext(Dispatchers.IO) {
        val statFs = StatFs(Environment.getDataDirectory().absolutePath)
        val totalBytes = statFs.totalBytes
        val freeBytes = statFs.availableBytes
        val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)

        val allItems = collectAllLocalFiles(metaMap, includeTrashed = true, showHidden = showHidden)
        val activeItems = allItems.filter { !it.isTrashed && !it.pathOrUri.contains("Корчі_Кошик") }
        val trashedItems = allItems.filter { it.isTrashed || it.pathOrUri.contains("Корчі_Кошик") }

        val categoryBreakdown = activeItems.groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.sizeBytes } }

        val largest = activeItems.sortedByDescending { it.sizeBytes }.take(15)

        StorageStats(
            totalDeviceBytes = totalBytes,
            freeDeviceBytes = freeBytes,
            usedDeviceBytes = usedBytes,
            workspaceTotalBytes = activeItems.sumOf { it.sizeBytes },
            trashTotalBytes = trashedItems.sumOf { it.sizeBytes },
            categoryBytes = categoryBreakdown,
            largestFiles = largest
        )
    }

    suspend fun updateSandPosition(item: BeachFileItem, normX: Float, normY: Float) {
        val existing = dao.getMeta(item.key)
        dao.upsertMeta(
            (existing ?: SandItemMetaEntity(fileKey = item.key, sandX = normX, sandY = normY)).copy(
                sandX = normX.coerceIn(0.08f, 0.92f),
                sandY = normY.coerceIn(0.10f, 0.88f)
            )
        )
    }

    suspend fun arrangeItemsOnSand(items: List<BeachFileItem>) {
        if (items.isEmpty()) return
        val cols = 3
        val updates = items.mapIndexed { index, item ->
            val col = index % cols
            val row = index / cols
            val totalRows = ((items.size + cols - 1) / cols).coerceAtLeast(2)
            val x = (0.18f + col * 0.32f).coerceIn(0.12f, 0.88f)
            val y = (0.18f + row * (0.66f / totalRows)).coerceIn(0.14f, 0.86f)
            val existing = dao.getMeta(item.key)
            (existing ?: SandItemMetaEntity(fileKey = item.key, sandX = x, sandY = y)).copy(
                sandX = x,
                sandY = y
            )
        }
        dao.upsertAllMeta(updates)
    }

    suspend fun createFolder(isSaf: Boolean, parentPathOrUri: String, folderName: String): Boolean =
        withContext(Dispatchers.IO) {
            val cleanName = folderName.trim().replace("/", "_")
            if (cleanName.isBlank()) return@withContext false
            val created = if (isSaf) {
                runCatching {
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        Uri.parse(parentPathOrUri),
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        cleanName
                    ) != null
                }.getOrDefault(false)
            } else {
                val targetBase = when {
                    parentPathOrUri == VIRTUAL_ANDROID_HUB -> androidAppExternalDir.absolutePath
                    parentPathOrUri == deviceSharedRootDir.absolutePath && !deviceSharedRootDir.canWrite() ->
                        beaverCoveDir.absolutePath
                    else -> parentPathOrUri
                }
                val newDir = File(targetBase, cleanName)
                newDir.mkdirs() || newDir.exists()
            }
            if (created) markDirectoryMutated()
            created
        }

    suspend fun createNoteFile(
        isSaf: Boolean,
        parentPathOrUri: String,
        fileName: String,
        content: String
    ): Boolean = withContext(Dispatchers.IO) {
        val base = fileName.trim().replace("/", "_").ifBlank { "сувій_${System.currentTimeMillis()}.txt" }
        val finalName = if (base.contains('.')) base else "$base.txt"
        val created = if (isSaf) {
            runCatching {
                val uri = DocumentsContract.createDocument(
                    context.contentResolver,
                    Uri.parse(parentPathOrUri),
                    "text/plain",
                    finalName
                ) ?: return@withContext false
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                }
                true
            }.getOrDefault(false)
        } else {
            runCatching {
                val targetBase = when {
                    parentPathOrUri == VIRTUAL_ANDROID_HUB -> androidAppExternalDir.absolutePath
                    parentPathOrUri == deviceSharedRootDir.absolutePath && !deviceSharedRootDir.canWrite() ->
                        beaverCoveDir.absolutePath
                    else -> parentPathOrUri
                }
                File(targetBase, finalName).writeText(content)
                true
            }.getOrDefault(false)
        }
        if (created) markDirectoryMutated()
        created
    }

    suspend fun importExternalUriToTarget(
        uri: Uri,
        isSafTarget: Boolean,
        targetPathOrUri: String,
        fallbackPrefix: String = "файл"
    ): Boolean = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(uri) ?: "${fallbackPrefix}_${System.currentTimeMillis()}"
        val ok = if (isSafTarget) {
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val destUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    Uri.parse(targetPathOrUri),
                    mime,
                    displayName
                ) ?: return@withContext false
                context.contentResolver.openInputStream(uri)?.use { input ->
                    context.contentResolver.openOutputStream(destUri)?.use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }.getOrDefault(false)
        } else {
            val baseDir = when {
                targetPathOrUri == VIRTUAL_ANDROID_HUB -> androidAppExternalDir.absolutePath
                targetPathOrUri == deviceSharedRootDir.absolutePath && !deviceSharedRootDir.canWrite() ->
                    beaverCoveDir.absolutePath
                else -> targetPathOrUri
            }
            importExternalUri(uri, baseDir, fallbackPrefix) != null
        }
        if (ok) markDirectoryMutated()
        ok
    }

    suspend fun importExternalUri(uri: Uri, targetDirPath: String, fallbackPrefix: String = "мушля"): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val targetDir = File(targetDirPath).apply { mkdirs() }
                val displayName = resolveDisplayName(uri) ?: "${fallbackPrefix}_${System.currentTimeMillis()}.jpg"
                var dest = File(targetDir, displayName)
                if (dest.exists()) {
                    val nameWithoutExt = dest.nameWithoutExtension
                    val ext = dest.extension.let { if (it.isNotEmpty()) ".$it" else "" }
                    dest = File(targetDir, "${nameWithoutExt}_${System.currentTimeMillis() % 10000}$ext")
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                markDirectoryMutated()
                dest
            }.getOrNull()
        }

    private fun resolveDisplayName(uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    suspend fun moveFileIntoFolder(item: BeachFileItem, targetFolder: BeachFileItem): String? =
        withContext(Dispatchers.IO) {
            if (!targetFolder.isDirectory || item.key == targetFolder.key) return@withContext null
            if (!item.isSafDocument && !targetFolder.isSafDocument) {
                val src = File(item.pathOrUri)
                val destDir = File(targetFolder.pathOrUri)
                if (!src.exists() || !destDir.isDirectory) return@withContext null
                val dest = File(destDir, src.name)
                if (dest.absolutePath == src.absolutePath) return@withContext null
                val moved = src.renameTo(dest)
                if (moved) {
                    purgePhantomItemEverywhere(item.key, item.pathOrUri)
                    val oldMeta = dao.getMeta(item.key)
                    dao.deleteMeta(item.key)
                    if (oldMeta != null) {
                        dao.upsertMeta(oldMeta.copy(fileKey = "file:${dest.absolutePath}"))
                    }
                    return@withContext dest.absolutePath
                }
            }
            null
        }

    suspend fun renameItem(item: BeachFileItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        val clean = newName.trim().replace("/", "_")
        if (clean.isBlank() || clean == item.name) return@withContext false
        if (item.isSafDocument) {
            val ok = runCatching {
                DocumentsContract.renameDocument(context.contentResolver, Uri.parse(item.pathOrUri), clean) != null
            }.getOrDefault(false)
            if (ok) {
                purgePhantomItemEverywhere(item.key, item.pathOrUri)
            }
            ok
        } else {
            val src = File(item.pathOrUri)
            val dest = File(src.parentFile, clean)
            if (dest.exists()) return@withContext false
            val ok = src.renameTo(dest)
            if (ok) {
                purgePhantomItemEverywhere(item.key, item.pathOrUri)
                SmartIconLoader.invalidatePath(dest.absolutePath)
                val meta = dao.getMeta(item.key)
                dao.deleteMeta(item.key)
                if (meta != null) {
                    dao.upsertMeta(meta.copy(fileKey = "file:${dest.absolutePath}"))
                }
            }
            ok
        }
    }

    suspend fun toggleStar(item: BeachFileItem) {
        val existing = dao.getMeta(item.key)
        dao.upsertMeta(
            (existing ?: SandItemMetaEntity(fileKey = item.key, sandX = item.sandX, sandY = item.sandY))
                .copy(isStarred = !item.isStarred)
        )
    }

    suspend fun categorizeByGesture(
        item: BeachFileItem,
        targetCategory: FileCategory?,
        star: Boolean = item.isStarred,
        trash: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        var movedNewPath: String? = null
        if (!item.isSafDocument && !item.isDirectory && !trash && targetCategory != null) {
            val destFolderName = when (targetCategory) {
                FileCategory.IMAGES -> "Мушлі_Пляжу"
                FileCategory.DOCUMENTS -> "Сувої_Греблі"
                FileCategory.ARCHIVES_ANDROID -> "Скарбниця_Бобра"
                else -> null
            }
            if (destFolderName != null) {
                val destDir = File(beaverCoveDir, destFolderName).apply { mkdirs() }
                val src = File(item.pathOrUri)
                if (src.exists() && src.parentFile?.absolutePath != destDir.absolutePath) {
                    val destFile = File(destDir, src.name)
                    if (!destFile.exists() && src.renameTo(destFile)) {
                        movedNewPath = destFile.absolutePath
                    }
                }
            }
        }

        val finalKey = if (movedNewPath != null) "file:$movedNewPath" else item.key
        if (movedNewPath != null || trash) {
            purgePhantomItemEverywhere(item.key, item.pathOrUri)
        }
        if (movedNewPath != null) dao.deleteMeta(item.key)

        val existing = dao.getMeta(finalKey)
        dao.upsertMeta(
            (existing ?: SandItemMetaEntity(fileKey = finalKey, sandX = item.sandX, sandY = item.sandY)).copy(
                customCategory = targetCategory?.name ?: existing?.customCategory,
                isStarred = star,
                isTrashed = trash
            )
        )
        movedNewPath
    }

    suspend fun undoSwipeAction(
        fileKey: String,
        movedNewPath: String?,
        previousParentPath: String?,
        previousCategory: FileCategory?,
        previousStarred: Boolean,
        previousTrashed: Boolean
    ) = withContext(Dispatchers.IO) {
        var restoredKey = fileKey
        if (movedNewPath != null && previousParentPath != null) {
            val movedFile = File(movedNewPath)
            val origFile = File(previousParentPath, movedFile.name)
            if (movedFile.exists() && movedFile.renameTo(origFile)) {
                purgePhantomItemEverywhere("file:$movedNewPath", movedNewPath)
                dao.deleteMeta("file:$movedNewPath")
                restoredKey = "file:${origFile.absolutePath}"
            }
        }
        markDirectoryMutated()
        val existing = dao.getMeta(restoredKey)
        dao.upsertMeta(
            (existing ?: SandItemMetaEntity(fileKey = restoredKey, sandX = 0.5f, sandY = 0.5f)).copy(
                customCategory = previousCategory?.name,
                isStarred = previousStarred,
                isTrashed = previousTrashed
            )
        )
    }

    suspend fun deletePermanently(item: BeachFileItem): Boolean = withContext(Dispatchers.IO) {
        val deleted = if (item.isSafDocument) {
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(item.pathOrUri))
            }.getOrDefault(false)
        } else {
            val f = File(item.pathOrUri)
            !f.exists() || f.deleteRecursively()
        }
        if (deleted) {
            purgePhantomItemEverywhere(item.key, item.pathOrUri)
            dao.deleteMeta(item.key)
        }
        deleted
    }

    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        var count = 0
        val trashedMeta = dao.getTrashedMeta()
        trashedMeta.forEach { meta ->
            if (meta.fileKey.startsWith("file:")) {
                val path = meta.fileKey.removePrefix("file:")
                if (File(path).deleteRecursively()) {
                    count++
                }
                purgePhantomItemEverywhere(meta.fileKey, path)
            }
            dao.deleteMeta(meta.fileKey)
        }
        val trashDir = File(beaverCoveDir, "Корчі_Кошик")
        trashDir.listFiles()?.forEach {
            val path = it.absolutePath
            if (it.deleteRecursively()) count++
            purgePhantomItemEverywhere("file:$path", path)
        }
        count
    }

    suspend fun saveConnectedSafFolder(treeUri: Uri): ConnectedFolderEntity = withContext(Dispatchers.IO) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        val authority = treeUri.authority.orEmpty()
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
        val tail = docId.substringAfterLast(':').trim('/')
        val providerTitle = resolveProviderTitleForAuthority(authority)
        val niceName = when {
            authority == "com.android.externalstorage.documents" && tail.isBlank() -> "Внутрішнє сховище"
            authority == "com.android.externalstorage.documents" -> tail
            providerTitle.isNotBlank() && tail.isNotBlank() && !tail.equals(providerTitle, ignoreCase = true) ->
                "$providerTitle ($tail)"
            providerTitle.isNotBlank() -> providerTitle
            tail.isNotBlank() -> tail
            else -> "Зовнішня папка"
        }
        val entity = ConnectedFolderEntity(
            treeUri = treeUri.toString(),
            displayName = niceName
        )
        dao.upsertConnectedFolder(entity)
        entity
    }

    private fun resolveProviderTitleForAuthority(authority: String): String {
        if (authority.isBlank()) return ""
        return runCatching {
            val pm = context.packageManager
            val providerInfo = pm.resolveContentProvider(authority, 0) ?: return@runCatching ""
            val pLabel = providerInfo.loadLabel(pm).toString().trim()
            if (pLabel.isNotBlank() && !pLabel.contains('.')) {
                pLabel
            } else {
                providerInfo.applicationInfo?.loadLabel(pm)?.toString()?.trim().orEmpty()
            }
        }.getOrDefault("")
    }

    fun isSystemDownloadsMounted(connectedFolders: List<ConnectedFolderEntity>): Boolean {
        if (hasAllFilesAccess()) return true
        if (findMatchingConnectedSaf(connectedFolders, "primary:Download") != null) return true
        if (findMatchingConnectedSaf(connectedFolders, "primary:") != null) return true
        return false
    }

    fun resolveWritableDownloadsDir(): File {
        if (hasAllFilesAccess()) {
            runCatching { publicDownloadsDir.mkdirs() }
            if (publicDownloadsDir.exists() && publicDownloadsDir.canWrite()) {
                return publicDownloadsDir
            }
        }
        return appDownloadsDir
    }

    fun initialDownloadsBreadcrumb(connectedFolders: List<ConnectedFolderEntity>): DeviceBreadcrumb {
        if (!hasAllFilesAccess()) {
            val downloadSaf = findMatchingConnectedSaf(connectedFolders, "primary:Download")
            if (downloadSaf != null) {
                val treeUri = Uri.parse(downloadSaf.treeUri)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
                return DeviceBreadcrumb(
                    title = context.getString(R.string.downloads_title),
                    pathOrUri = docUri.toString(),
                    isSaf = true,
                    safTreeUri = downloadSaf.treeUri
                )
            }
        }
        return DeviceBreadcrumb(
            title = context.getString(R.string.downloads_title),
            pathOrUri = VIRTUAL_DOWNLOADS_ROOT,
            isSaf = false
        )
    }

    suspend fun listDownloadsBreadcrumb(
        crumb: DeviceBreadcrumb,
        connectedFolders: List<ConnectedFolderEntity>,
        metaMap: Map<String, SandItemMetaEntity>,
        showHidden: Boolean = false
    ): List<BeachFileItem> = withContext(Dispatchers.IO) {
        if (crumb.isSaf && crumb.safTreeUri.isNotBlank()) {
            val safItems = listSafChildren(
                treeUri = Uri.parse(crumb.safTreeUri),
                parentDocUri = Uri.parse(crumb.pathOrUri),
                metaMap = metaMap,
                showHidden = showHidden
            )
            val isRootDownloadSaf = runCatching {
                DocumentsContract.getDocumentId(Uri.parse(crumb.pathOrUri)).equals("primary:Download", ignoreCase = true)
            }.getOrDefault(false)
            if (!isRootDownloadSaf) return@withContext safItems
            val localExtras = listLocalDirFiltered(appDownloadsDir, metaMap, showHidden)
            return@withContext (safItems + localExtras).distinctBy { it.key }
        }

        if (crumb.pathOrUri == VIRTUAL_DOWNLOADS_ROOT) {
            val merged = mutableListOf<BeachFileItem>()
            val pubFiles = runCatching { publicDownloadsDir.listFiles() }.getOrNull()
            if (pubFiles != null) {
                val clean = pubFiles.filter { shouldIncludeName(it.name, showHidden) }
                merged.addAll(
                    clean.mapIndexed { idx, f -> toBeachFileItem(f, metaMap, idx, clean.size, showHidden) }
                        .filter { !it.isTrashed }
                )
            }
            if (!hasAllFilesAccess()) {
                val primaryRootSaf = findMatchingConnectedSaf(connectedFolders, "primary:")
                if (primaryRootSaf != null) {
                    val treeUri = Uri.parse(primaryRootSaf.treeUri)
                    val dlDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Download")
                    merged.addAll(listSafChildren(treeUri, dlDocUri, metaMap, showHidden))
                }
            }
            merged.addAll(listLocalDirFiltered(appDownloadsDir, metaMap, showHidden))
            return@withContext merged.distinctBy { it.key }
        }

        val dir = File(crumb.pathOrUri)
        listLocalDirFiltered(dir, metaMap, showHidden)
    }

    private fun listLocalDirFiltered(
        dir: File,
        metaMap: Map<String, SandItemMetaEntity>,
        showHidden: Boolean = false
    ): List<BeachFileItem> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val raw = dir.listFiles()?.filter { shouldIncludeName(it.name, showHidden) }.orEmpty()
        return raw.mapIndexed { idx, f -> toBeachFileItem(f, metaMap, idx, raw.size, showHidden) }
            .filter { !it.isTrashed }
    }

    suspend fun queryInstalledDocumentProviders(
        connectedFolders: List<ConnectedFolderEntity>,
        freeDeviceBytes: Long
    ): List<ExternalDocumentProviderItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent("android.content.action.DOCUMENTS_PROVIDER")
        val resolveInfos = runCatching { pm.queryIntentContentProviders(intent, 0) }.getOrDefault(emptyList())

        val rawAuthorities = resolveInfos.mapNotNull { it.providerInfo?.authority }.toSet()
        val seenAuthorities = mutableSetOf<String>()
        val results = mutableListOf<ExternalDocumentProviderItem>()

        for (resolveInfo in resolveInfos) {
            val providerInfo = resolveInfo.providerInfo ?: continue
            val authority = providerInfo.authority ?: continue
            if (authority in BOTTOM_BAR_PROVIDER_AUTHORITIES) continue
            if (authority == "${context.packageName}.fileprovider") continue
            if (
                authority == "com.google.android.apps.docs.storage.legacy" &&
                "com.google.android.apps.docs.storage" in rawAuthorities
            ) {
                continue
            }
            if (!seenAuthorities.add(authority)) continue

            val pkg = providerInfo.packageName.orEmpty()
            val rawProviderLabel = runCatching { providerInfo.loadLabel(pm).toString().trim() }.getOrDefault("")
            val rawAppLabel = runCatching {
                providerInfo.applicationInfo?.loadLabel(pm)?.toString()?.trim().orEmpty()
            }.getOrDefault("")

            val title = when {
                authority == "com.android.shell.documents" ->
                    rawProviderLabel.takeIf { it.isNotBlank() && !it.contains('.') && !it.equals("Shell", true) }
                        ?: "Звіти про помилки"
                authority == "com.android.traceur.documents" ->
                    rawProviderLabel.takeIf { it.isNotBlank() && !it.contains('.') }
                        ?: "Трасування системи"
                authority.startsWith("com.google.android.apps.docs.storage") ->
                    rawAppLabel.takeIf { it.isNotBlank() && !it.contains('.') }
                        ?: rawProviderLabel.takeIf { it.isNotBlank() && !it.contains('.') }
                        ?: "Диск"
                rawProviderLabel.isNotBlank() && !rawProviderLabel.contains('.') -> rawProviderLabel
                rawAppLabel.isNotBlank() -> rawAppLabel
                else -> authority.substringBeforeLast(".documents").substringAfterLast('.')
                    .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }

            val isDiagnostic = authority == "com.android.shell.documents" ||
                authority == "com.android.traceur.documents" ||
                authority == "com.android.mtp.documents"

            val lowerAuthPkg = "$authority $pkg".lowercase(Locale.ROOT)
            val isCloudLike = listOf(
                "docs.storage", "drive", "terabox", "dubox", "nextcloud",
                "owncloud", "onedrive", "skydrive", "dropbox", "mega", "box.android", "yandex.disk"
            ).any { lowerAuthPkg.contains(it) }

            val supportsTreeGrant = !isDiagnostic && !authority.startsWith("com.google.android.apps.docs.storage")

            val matchedConnected = connectedFolders.firstOrNull {
                runCatching { Uri.parse(it.treeUri).authority == authority }.getOrDefault(false)
            }

            val subtitle = when {
                matchedConnected != null ->
                    context.getString(R.string.drawer_provider_connected_fmt, matchedConnected.displayName)
                isDiagnostic -> ""
                isCloudLike -> context.getString(R.string.drawer_provider_cloud_sub)
                freeDeviceBytes > 0L ->
                    context.getString(R.string.drawer_free_space_fmt, formatFileSize(freeDeviceBytes))
                rawAppLabel.isNotBlank() && !rawAppLabel.equals(title, ignoreCase = true) -> rawAppLabel
                else -> ""
            }

            results.add(
                ExternalDocumentProviderItem(
                    authority = authority,
                    packageName = pkg,
                    title = title,
                    subtitle = subtitle,
                    isSystemDiagnostic = isDiagnostic,
                    isCloudLike = isCloudLike,
                    supportsTreeGrant = supportsTreeGrant,
                    connectedTreeUri = matchedConnected?.treeUri,
                    connectedDisplayName = matchedConnected?.displayName
                )
            )
        }

        results.sortedWith(
            compareByDescending<ExternalDocumentProviderItem> { it.authority.startsWith("com.google.android.apps.docs") }
                .thenByDescending { it.isSystemDiagnostic }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        )
    }

    suspend fun listDrawerCategoryItems(
        type: DrawerCategoryType,
        connectedFolders: List<ConnectedFolderEntity>,
        metaMap: Map<String, SandItemMetaEntity>,
        showHidden: Boolean = false
    ): List<BeachFileItem> = withContext(Dispatchers.IO) {
        if (type == DrawerCategoryType.TRASH) {
            val allWithTrash = collectAllLocalFiles(metaMap, includeTrashed = true, showHidden = showHidden)
            return@withContext allWithTrash
                .filter { it.isTrashed || it.pathOrUri.contains("Корчі_Кошик") }
                .sortedByDescending { it.lastModifiedMillis }
        }

        if (type == DrawerCategoryType.VIDEOS) {
            return@withContext queryGalleryMediaItems(metaMap, showHidden)
                .filter { it.category == FileCategory.VIDEO }
        }

        val combined = mutableListOf<BeachFileItem>()
        combined.addAll(collectAllLocalFiles(metaMap, includeTrashed = false, showHidden = showHidden))

        fun collectSafRecursive(treeUri: Uri, parentDocUri: Uri, depth: Int) {
            if (depth > 2 || combined.size > 2500) return
            val children = listSafChildren(treeUri, parentDocUri, metaMap, showHidden)
            for (child in children) {
                if (child.isDirectory) {
                    collectSafRecursive(treeUri, Uri.parse(child.pathOrUri), depth + 1)
                } else {
                    combined.add(child)
                }
            }
        }

        val fullAccess = hasAllFilesAccess()
        connectedFolders.forEach { folder ->
            val treeUri = runCatching { Uri.parse(folder.treeUri) }.getOrNull() ?: return@forEach
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return@forEach
            if (fullAccess && docId.startsWith("primary:", ignoreCase = true)) return@forEach
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            collectSafRecursive(treeUri, docUri, depth = 0)
        }

        val unique = combined.distinctBy { it.key }.filter { !it.isTrashed }

        when (type) {
            DrawerCategoryType.RECENT ->
                unique.filter { !it.isDirectory }
                    .sortedByDescending { it.lastModifiedMillis }
                    .take(80)
            DrawerCategoryType.VIDEOS ->
                unique.filter { !it.isDirectory && it.category == FileCategory.VIDEO }
                    .sortedByDescending { it.lastModifiedMillis }
            DrawerCategoryType.AUDIO ->
                unique.filter { !it.isDirectory && it.category == FileCategory.AUDIO }
                    .sortedByDescending { it.lastModifiedMillis }
            DrawerCategoryType.DOCUMENTS ->
                unique.filter {
                    !it.isDirectory && it.category in setOf(
                        FileCategory.DOCUMENTS,
                        FileCategory.ARCHIVES_ANDROID,
                        FileCategory.OTHER
                    )
                }.sortedByDescending { it.lastModifiedMillis }
            DrawerCategoryType.STARRED ->
                unique.filter { it.isStarred }
                    .sortedByDescending { it.lastModifiedMillis }
            DrawerCategoryType.TRASH -> emptyList()
        }
    }

    suspend fun restoreFromTrash(item: BeachFileItem) = withContext(Dispatchers.IO) {
        var finalKey = item.key
        if (!item.isSafDocument && item.pathOrUri.contains("Корчі_Кошик")) {
            val src = File(item.pathOrUri)
            val dest = File(beaverCoveDir, src.name)
            if (src.exists() && !dest.exists() && src.renameTo(dest)) {
                purgePhantomItemEverywhere(item.key, item.pathOrUri)
                dao.deleteMeta(item.key)
                finalKey = "file:${dest.absolutePath}"
            }
        }
        markDirectoryMutated()
        val existing = dao.getMeta(finalKey)
        if (existing != null) {
            dao.upsertMeta(existing.copy(isTrashed = false))
        }
    }

    suspend fun readTextPreview(item: BeachFileItem, maxChars: Int = 4000): String = withContext(Dispatchers.IO) {
        runCatching {
            if (item.isSafDocument || item.pathOrUri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(item.pathOrUri))
                    ?.bufferedReader()
                    ?.use { reader ->
                        val buf = CharArray(maxChars)
                        val read = reader.read(buf)
                        if (read > 0) String(buf, 0, read) else ""
                    }.orEmpty()
            } else {
                val file = File(item.pathOrUri)
                file.bufferedReader().use { reader ->
                    val buf = CharArray(maxChars)
                    val read = reader.read(buf)
                    if (read > 0) String(buf, 0, read) else ""
                }
            }
        }.getOrDefault("")
    }

    fun createShareIntent(item: BeachFileItem): Intent? {
        return runCatching {
            val shareUri = if (item.isSafDocument || item.pathOrUri.startsWith("content://")) {
                Uri.parse(item.pathOrUri)
            } else {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(item.pathOrUri)
                )
            }
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = item.mimeType.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                item.name
            )
        }.getOrNull()
    }
}
