package com.example.data.model

import androidx.compose.runtime.Immutable
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

enum class FileCategory {
    FOLDER,
    IMAGES,
    DOCUMENTS,
    AUDIO,
    VIDEO,
    ARCHIVES_ANDROID,
    OTHER;

    companion object {
        fun fromExtensionOrMime(name: String, mimeType: String?, isDirectory: Boolean): FileCategory {
            if (isDirectory) return FOLDER
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
            return when {
                mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic") -> IMAGES
                mime.startsWith("audio/") || ext in setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus") -> AUDIO
                mime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp") -> VIDEO
                ext in setOf(
                    "apk", "apks", "xapk", "aab", "obb",
                    "zip", "rar", "7z", "tar", "gz", "tgz", "xz",
                    "mcpack", "mcaddon", "mcworld", "exe", "dll"
                ) || mime.contains("android.package") || mime.contains("zip") || mime.contains("7z") || mime.contains("tar") -> ARCHIVES_ANDROID
                mime.startsWith("text/") || mime.contains("pdf") || mime.contains("json") ||
                    ext in setOf("txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "csv", "json", "xml", "html", "log", "ini", "cfg", "properties") -> DOCUMENTS
                else -> OTHER
            }
        }
    }
}

enum class SortOption {
    DATE_DESC,
    DATE_ASC,
    SIZE_DESC,
    SIZE_ASC,
    NAME_ASC,
    CATEGORY
}

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ExplorerViewMode {
    SANDBOX,
    LIST
}

enum class MainDestination(val route: String, val isPrimaryTab: Boolean) {
    BEACH("beach_explorer", isPrimaryTab = true),
    SHELLS("seashell_gallery", isPrimaryTab = true),
    DOWNLOADS("downloads_tab", isPrimaryTab = true),
    DEVICE("device_internal_storage", isPrimaryTab = true),
    DRAWER_COLLECTION("drawer_collection", isPrimaryTab = false),
    SPACE("gesture_and_space", isPrimaryTab = false),
    SETTINGS("settings_and_cloud", isPrimaryTab = false)
}

enum class DrawerCategoryType {
    RECENT,
    VIDEOS,
    AUDIO,
    DOCUMENTS,
    STARRED,
    TRASH
}

@Immutable
data class ExternalDocumentProviderItem(
    val authority: String,
    val packageName: String,
    val title: String,
    val subtitle: String,
    val isSystemDiagnostic: Boolean,
    val isCloudLike: Boolean,
    val supportsTreeGrant: Boolean,
    val connectedTreeUri: String? = null,
    val connectedDisplayName: String? = null
)

enum class GalleryMediaFilter {
    ALL,
    PHOTOS,
    VIDEOS
}

sealed class DrawerSelectionMode {
    data class Category(val type: DrawerCategoryType) : DrawerSelectionMode()
    data class Provider(
        val provider: ExternalDocumentProviderItem,
        val breadcrumbs: List<DeviceBreadcrumb> = emptyList()
    ) : DrawerSelectionMode()
    data object InstalledApps : DrawerSelectionMode()
}

@Immutable
data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val apkFilePath: String,
    val sizeBytes: Long,
    val lastUpdatedMillis: Long,
    val isSystemApp: Boolean
) {
    val lowerAppName: String = appName.lowercase(Locale.ROOT)
    val lowerPackageName: String = packageName.lowercase(Locale.ROOT)

    val apkExportFileName: String
        get() {
            val cleanName = appName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { packageName }
            val cleanVer = versionName.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").trim()
            return if (cleanVer.isNotBlank()) "${cleanName}_v${cleanVer}.apk" else "$cleanName.apk"
        }
}

sealed class StorageRoot(
    open val id: String,
    open val title: String,
    open val subtitle: String
) {
    data class InternalShared(
        override val title: String = "Внутрішнє сховище",
        override val subtitle: String = "/storage/emulated/0"
    ) : StorageRoot("internal_shared", title, subtitle)

    data class PublicDownloads(
        override val title: String = "Download",
        override val subtitle: String = "/storage/emulated/0/Download"
    ) : StorageRoot("public_downloads", title, subtitle)

    data class BeaverCove(
        override val title: String = "Бухта Бобрів",
        override val subtitle: String = "Основний пляжний простір"
    ) : StorageRoot("beaver_cove", title, subtitle)

    data class AndroidAppFolder(
        override val title: String = "Папка Android",
        override val subtitle: String = "Android/data та медіа застосунку"
    ) : StorageRoot("android_app_dir", title, subtitle)

    data class SafTree(
        val treeUriString: String,
        override val title: String,
        override val subtitle: String = "Підключена папка пристрою"
    ) : StorageRoot("saf:$treeUriString", title, subtitle)
}

@Immutable
data class DeviceBreadcrumb(
    val title: String,
    val pathOrUri: String,
    val isSaf: Boolean,
    val safTreeUri: String = ""
)

@Immutable
data class BeachFileItem(
    val key: String,
    val name: String,
    val pathOrUri: String,
    val parentPathOrUri: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val lastModifiedMillis: Long,
    val category: FileCategory,
    val mimeType: String,
    val isSafDocument: Boolean,
    val safTreeUri: String = "",
    val requiresSafGrantDocId: String? = null,
    val sandX: Float = 0.5f,
    val sandY: Float = 0.5f,
    val isStarred: Boolean = false,
    val isTrashed: Boolean = false,
    val isBackedUp: Boolean = false,
    val childCount: Int = 0,
    val durationMillis: Long = 0L
) {
    val lowerName: String = name.lowercase(Locale.ROOT)

    val extensionLabel: String = if (isDirectory) {
        ""
    } else if (lowerName.endsWith(".tar.gz")) {
        "TAR.GZ"
    } else {
        val dotIdx = name.lastIndexOf('.')
        if (dotIdx > 0 && dotIdx < name.lastIndex) {
            name.substring(dotIdx + 1).uppercase(Locale.ROOT)
        } else {
            ""
        }
    }
}

@Immutable
data class StorageStats(
    val totalDeviceBytes: Long = 0L,
    val freeDeviceBytes: Long = 0L,
    val usedDeviceBytes: Long = 0L,
    val workspaceTotalBytes: Long = 0L,
    val trashTotalBytes: Long = 0L,
    val categoryBytes: Map<FileCategory, Long> = emptyMap(),
    val largestFiles: List<BeachFileItem> = emptyList()
)

data class AppPreferencesState(
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val surfEnabled: Boolean = true,
    val surfVolume: Float = 0.55f,
    val musicEnabled: Boolean = false,
    val musicVolume: Float = 0.45f,
    val sfxEnabled: Boolean = true,
    val autoCloudBackup: Boolean = false,
    val safCloudTreeUri: String = "",
    val safCloudFolderName: String = "",
    val webDavUrl: String = "",
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val lastCloudSyncMillis: Long = 0L,
    val showHiddenFiles: Boolean = false
) {
    val isCloudConfigured: Boolean
        get() = safCloudTreeUri.isNotBlank() || webDavUrl.isNotBlank()
}

data class SwipeUndoAction(
    val fileKey: String,
    val previousParentPath: String?,
    val movedFileNewPath: String?,
    val previousCategory: FileCategory?,
    val previousStarred: Boolean,
    val previousTrashed: Boolean,
    val description: String
)

// --- 1 & 2. Multi-Selection & Total Commander Clipboard Models ---

enum class ClipboardMode {
    COPY,
    CUT
}

@Immutable
data class FileClipboardState(
    val items: List<BeachFileItem>,
    val mode: ClipboardMode,
    val sourceFolderDisplay: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

enum class ConflictResolution {
    OVERWRITE,
    RENAME,
    SKIP,
    CANCEL_ALL
}

data class ConflictDecision(
    val resolution: ConflictResolution,
    val applyToAll: Boolean
)

data class PasteConflictPrompt(
    val requestId: Long = System.nanoTime(),
    val conflictingName: String,
    val destinationDisplay: String
)

data class FileOperationProgress(
    val isActive: Boolean = false,
    val title: String = "",
    val currentFileName: String = "",
    val processedItems: Int = 0,
    val totalItems: Int = 0,
    val canCancel: Boolean = true
)

@Immutable
data class MoveDestinationOption(
    val id: String,
    val title: String,
    val subtitle: String,
    val isSaf: Boolean,
    val targetPathOrUri: String,
    val safTreeUri: String = ""
)

// --- 3. Archiver & Virtual Archive Browser Models ---

enum class SupportedArchiveFormat(val label: String, val extensionSuffix: String) {
    ZIP("ZIP", ".zip"),
    SEVEN_Z("7Z", ".7z"),
    TAR_GZ("TAR.GZ", ".tar.gz"),
    TAR("TAR", ".tar"),
    GZIP_SINGLE("GZ", ".gz");

    val isCreatableMultiFile: Boolean
        get() = this != GZIP_SINGLE

    companion object {
        fun creatableFormats(): List<SupportedArchiveFormat> = listOf(ZIP, SEVEN_Z, TAR_GZ, TAR)

        /**
         * Detects archives that open directly as virtual folders on tap.
         */
        fun detectPrimaryArchive(fileName: String): SupportedArchiveFormat? {
            val lower = fileName.lowercase(Locale.ROOT)
            return when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TAR_GZ
                lower.endsWith(".zip") -> ZIP
                lower.endsWith(".7z") -> SEVEN_Z
                lower.endsWith(".tar") -> TAR
                lower.endsWith(".gz") -> GZIP_SINGLE
                else -> null
            }
        }

        /**
         * Detects ZIP-based packages (.mcpack, .mcaddon, .mcworld, .apk, .apks, .xapk)
         * that can also be inspected via "Відкрити як архів".
         */
        fun detectInspectableArchive(fileName: String): SupportedArchiveFormat? {
            detectPrimaryArchive(fileName)?.let { return it }
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return if (ext in setOf("mcpack", "mcaddon", "mcworld", "apk", "apks", "xapk")) {
                ZIP
            } else {
                null
            }
        }
    }
}

@Immutable
data class ArchiveEntryItem(
    val entryPath: String, // Normalized forward-slash path without leading/trailing slash
    val name: String,
    val parentPath: String, // "" for root level
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val compressedSizeBytes: Long,
    val lastModifiedMillis: Long
)

@Immutable
data class VirtualArchiveSession(
    val archiveItem: BeachFileItem,
    val format: SupportedArchiveFormat,
    val currentDirPath: String = "",
    val allEntries: List<ArchiveEntryItem> = emptyList(),
    val entriesInCurrentDir: List<ArchiveEntryItem> = emptyList(),
    val selectedEntryPaths: Set<String> = emptySet()
) {
    val canNavigateUp: Boolean
        get() = currentDirPath.isNotEmpty()
}

// --- 4. Built-in Audio Player Models ---

enum class RepeatPlayMode {
    OFF,
    ALL,
    ONE
}

data class AudioTrackItem(
    val id: String,
    val uriString: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkBytes: ByteArray? = null,
    val fileKey: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTrackItem) return false
        return id == other.id &&
            uriString == other.uriString &&
            title == other.title &&
            artist == other.artist &&
            durationMs == other.durationMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + uriString.hashCode()
        result = 31 * result + title.hashCode()
        return result
    }
}

data class AudioPlayerUiState(
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val currentTrack: AudioTrackItem? = null,
    val queue: List<AudioTrackItem> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatPlayMode = RepeatPlayMode.OFF,
    val shuffleEnabled: Boolean = false,
    val volume: Float = 1.0f,
    val isMiniCollapsed: Boolean = false,
    val isExpandedSheetOpen: Boolean = false
)

data class VideoPlayerState(
    val isVisible: Boolean = false,
    val currentItem: BeachFileItem? = null,
    val playlist: List<BeachFileItem> = emptyList(),
    val currentIndex: Int = 0
)

// --- Formatting Helpers (ThreadLocal for zero scroll allocations) ---

private val fileSizeDecimalFormatter = object : ThreadLocal<DecimalFormat>() {
    override fun initialValue(): DecimalFormat = DecimalFormat("#,##0.#")
}

private val coastalDateFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.forLanguageTag("uk-UA"))
}

private val tcTimestampFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}

private val fileSizeUnits = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 Б"
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, fileSizeUnits.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    val fmt = fileSizeDecimalFormatter.get() ?: DecimalFormat("#,##0.#")
    return fmt.format(value) + " " + fileSizeUnits[digitGroups]
}

fun formatCoastalDate(millis: Long): String {
    if (millis <= 0L) return "—"
    val fmt = coastalDateFormatter.get() ?: SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.forLanguageTag("uk-UA"))
    return fmt.format(Date(millis))
}

fun formatTotalCommanderTimestamp(millis: Long): String {
    val safeMillis = if (millis > 0L) millis else 0L
    val fmt = tcTimestampFormatter.get() ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return fmt.format(Date(safeMillis))
}

fun formatDurationMmSs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

