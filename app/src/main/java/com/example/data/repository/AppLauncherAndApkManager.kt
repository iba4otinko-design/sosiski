package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.data.model.BeachFileItem
import com.example.data.model.FileCategory
import com.example.data.model.InstalledAppInfo
import com.example.data.model.MoveDestinationOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

sealed class FileLaunchOutcome {
    data object LaunchedSuccessfully : FileLaunchOutcome()
    data class NeedsUnknownSourcesPermission(val settingsIntent: Intent, val pendingItem: BeachFileItem) : FileLaunchOutcome()
    data class FallbackToInternalPreview(val item: BeachFileItem) : FileLaunchOutcome()
    data object FileMissing : FileLaunchOutcome()
    data class LaunchError(val message: String) : FileLaunchOutcome()
}

/**
 * Handles:
 * 1. Querying installed Android applications (Total Commander style) with cached metadata.
 * 2. Exporting installed app APKs or БоброПляж's own APK cleanly with human-readable filenames.
 * 3. Launching real files via Android intents — including installing .apk packages with
 *    Android 8–14+ unknown-sources permission handling and FileProvider content URIs.
 */
class AppLauncherAndApkManager(
    private val context: Context
) {
    @Volatile
    private var cachedUserApps: List<InstalledAppInfo>? = null

    @Volatile
    private var cachedAllApps: List<InstalledAppInfo>? = null

    @Volatile
    private var lastCacheTimeMs: Long = 0L

    suspend fun loadInstalledApps(
        includeSystemApps: Boolean,
        forceRefresh: Boolean = false
    ): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastCacheTimeMs) < 45_000L) {
            val cached = if (includeSystemApps) cachedAllApps else cachedUserApps
            if (cached != null) return@withContext cached
        }

        val pm = context.packageManager
        val appInfosMap = LinkedHashMap<String, ApplicationInfo>()

        // 1. Query all launchable apps (visible via <queries> MAIN + LAUNCHER on Android 11-14+)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        runCatching {
            val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
            for (ri in resolves) {
                val ai = ri.activityInfo?.applicationInfo ?: continue
                appInfosMap[ai.packageName] = ai
            }
        }

        // 2. Merge installed applications visible to the package manager
        runCatching {
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            for (ai in installed) {
                appInfosMap.putIfAbsent(ai.packageName, ai)
            }
        }

        // Always include БоброПляж itself
        appInfosMap.putIfAbsent(context.packageName, context.applicationInfo)

        val allBuilt = ArrayList<InstalledAppInfo>(appInfosMap.size)
        val userBuilt = ArrayList<InstalledAppInfo>()

        for ((pkg, ai) in appInfosMap) {
            val sourcePath = ai.publicSourceDir ?: ai.sourceDir ?: continue
            val apkFile = File(sourcePath)
            if (!apkFile.exists()) continue

            val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
                pkg != context.packageName

            val label = runCatching { pm.getApplicationLabel(ai).toString().trim() }
                .getOrDefault(pkg)
                .ifBlank { pkg }

            val pkgInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
            }.getOrNull()

            val versionName = pkgInfo?.versionName?.takeIf { it.isNotBlank() } ?: "1.0"
            val updatedAt = pkgInfo?.lastUpdateTime?.takeIf { it > 0L } ?: apkFile.lastModified()

            val info = InstalledAppInfo(
                packageName = pkg,
                appName = label,
                versionName = versionName,
                apkFilePath = apkFile.absolutePath,
                sizeBytes = apkFile.length(),
                lastUpdatedMillis = updatedAt,
                isSystemApp = isSys
            )
            allBuilt.add(info)
            if (!isSys) {
                userBuilt.add(info)
            }
        }

        val comparator = compareBy<InstalledAppInfo> { it.appName.lowercase(Locale.forLanguageTag("uk-UA")) }
        val sortedAll = allBuilt.sortedWith(comparator)
        val sortedUser = userBuilt.sortedWith(comparator)

        cachedAllApps = sortedAll
        cachedUserApps = sortedUser
        lastCacheTimeMs = now

        if (includeSystemApps) sortedAll else sortedUser
    }

    private fun sanitizeApkFileName(appName: String, versionName: String): String {
        val cleanName = appName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "App" }
        val cleanVer = versionName.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").trim()
        return if (cleanVer.isNotBlank()) {
            "${cleanName}_v${cleanVer}.apk"
        } else {
            "$cleanName.apk"
        }
    }

    /**
     * Copies БоброПляж's own installed base APK to cache with a clean name ("БоброПляж.apk")
     * and returns a ready-to-launch ACTION_SEND chooser intent.
     */
    suspend fun createShareSelfApkIntent(chooserTitle: String): Intent? = withContext(Dispatchers.IO) {
        runCatching {
            val sourceApk = File(context.applicationInfo.publicSourceDir ?: context.applicationInfo.sourceDir)
            if (!sourceApk.exists()) return@withContext null

            val exportDir = File(context.cacheDir, "shared_apks").apply { mkdirs() }
            val targetApk = File(exportDir, "БоброПляж.apk")
            if (!targetApk.exists() || targetApk.length() != sourceApk.length() || targetApk.lastModified() < sourceApk.lastModified()) {
                sourceApk.inputStream().use { input ->
                    targetApk.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetApk
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "БоброПляж.apk")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(sendIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrNull()
    }

    /**
     * Exports one or more installed apps into temporary named .apk files in cacheDir/shared_apks
     * and builds an ACTION_SEND / ACTION_SEND_MULTIPLE intent so the user can send clean .apk files
     * via Telegram, Nearby Share, Drive, Bluetooth, etc.
     */
    suspend fun createShareInstalledAppsIntent(
        apps: List<InstalledAppInfo>,
        chooserTitle: String
    ): Intent? = withContext(Dispatchers.IO) {
        if (apps.isEmpty()) return@withContext null
        runCatching {
            val exportDir = File(context.cacheDir, "shared_apks").apply { mkdirs() }
            val uris = ArrayList<Uri>(apps.size)
            for (app in apps) {
                val src = File(app.apkFilePath)
                if (!src.exists()) continue
                val dest = File(exportDir, sanitizeApkFileName(app.appName, app.versionName))
                if (!dest.exists() || dest.length() != src.length()) {
                    src.inputStream().use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    dest
                )
                uris.add(uri)
            }
            if (uris.isEmpty()) return@withContext null

            val sendIntent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/vnd.android.package-archive"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            Intent.createChooser(sendIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrNull()
    }

    /**
     * Copies selected installed app APKs directly into any local folder or SAF folder.
     */
    suspend fun copyInstalledAppsToDestination(
        apps: List<InstalledAppInfo>,
        destination: MoveDestinationOption
    ): Int = withContext(Dispatchers.IO) {
        var copiedCount = 0
        for (app in apps) {
            val src = File(app.apkFilePath)
            if (!src.exists()) continue
            val fileName = sanitizeApkFileName(app.appName, app.versionName)
            val ok = runCatching {
                if (destination.isSaf) {
                    val parentUri = resolveSafFolderUri(destination) ?: return@runCatching false
                    deleteExistingSafChildByName(parentUri, fileName)
                    val createdUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentUri,
                        "application/vnd.android.package-archive",
                        fileName
                    ) ?: return@runCatching false
                    context.contentResolver.openOutputStream(createdUri)?.use { out ->
                        src.inputStream().use { input ->
                            input.copyTo(out, bufferSize = 64 * 1024)
                        }
                    } ?: return@runCatching false
                    true
                } else {
                    val dir = File(destination.targetPathOrUri).apply { mkdirs() }
                    val target = File(dir, fileName)
                    src.inputStream().use { input ->
                        target.outputStream().use { out ->
                            input.copyTo(out, bufferSize = 64 * 1024)
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (ok) copiedCount++
        }
        copiedCount
    }

    /**
     * Stages selected installed app APKs with clean names in cacheDir/clipboard_apks
     * and converts them to BeachFileItem objects so the user can paste them anywhere
     * using the Total Commander clipboard!
     */
    suspend fun exportAppsToBeachFileItems(apps: List<InstalledAppInfo>): List<BeachFileItem> =
        withContext(Dispatchers.IO) {
            val stageDir = File(context.cacheDir, "clipboard_apks").apply { mkdirs() }
            val result = ArrayList<BeachFileItem>(apps.size)
            for (app in apps) {
                val src = File(app.apkFilePath)
                if (!src.exists()) continue
                val fileName = sanitizeApkFileName(app.appName, app.versionName)
                val staged = File(stageDir, fileName)
                runCatching {
                    if (!staged.exists() || staged.length() != src.length()) {
                        src.inputStream().use { input ->
                            staged.outputStream().use { out ->
                                input.copyTo(out, bufferSize = 64 * 1024)
                            }
                        }
                    }
                    result.add(
                        BeachFileItem(
                            key = "local:${staged.absolutePath}",
                            name = staged.name,
                            pathOrUri = staged.absolutePath,
                            parentPathOrUri = stageDir.absolutePath,
                            isDirectory = false,
                            sizeBytes = staged.length(),
                            createdAtMillis = staged.lastModified(),
                            lastModifiedMillis = staged.lastModified(),
                            category = FileCategory.ARCHIVES_ANDROID,
                            mimeType = "application/vnd.android.package-archive",
                            isSafDocument = false
                        )
                    )
                }
            }
            result
        }

    fun launchInstalledApp(packageName: String): Boolean {
        return runCatching {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    /**
     * Opens a file or launches the Android Package Installer for .apk files.
     */
    suspend fun openOrInstallFile(item: BeachFileItem): FileLaunchOutcome = withContext(Dispatchers.IO) {
        val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isApk = ext == "apk" || item.mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)

        if (!item.isSafDocument && !item.pathOrUri.startsWith("content://") && !File(item.pathOrUri).exists()) {
            return@withContext FileLaunchOutcome.FileMissing
        }

        if (isApk) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return@withContext FileLaunchOutcome.NeedsUnknownSourcesPermission(settingsIntent, item)
            }

            val contentUri = obtainShareableContentUri(item)
                ?: return@withContext FileLaunchOutcome.LaunchError("Не вдалося підготувати APK до встановлення")

            return@withContext withContext(Dispatchers.Main) {
                runCatching {
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(installIntent)
                    FileLaunchOutcome.LaunchedSuccessfully
                }.getOrElse { err ->
                    FileLaunchOutcome.LaunchError(err.localizedMessage ?: "Помилка запуску встановлення APK")
                }
            }
        }

        // Plain-text / code notes open directly in our fast built-in viewer/editor unless user chooses "Відкрити у програмі"
        val plainTextExtensions = setOf(
            "txt", "md", "json", "xml", "log", "ini", "cfg", "properties", "csv", "yml", "yaml", "sh", "kt", "java", "py", "js"
        )
        if (ext in plainTextExtensions) {
            return@withContext FileLaunchOutcome.FallbackToInternalPreview(item)
        }

        return@withContext openExternallyWithViewIntent(item, fallbackToInternalOnMissingApp = true)
    }

    suspend fun openExternallyWithViewIntent(
        item: BeachFileItem,
        fallbackToInternalOnMissingApp: Boolean = false
    ): FileLaunchOutcome = withContext(Dispatchers.IO) {
        if (!item.isSafDocument && !item.pathOrUri.startsWith("content://") && !File(item.pathOrUri).exists()) {
            return@withContext FileLaunchOutcome.FileMissing
        }

        val contentUri = obtainShareableContentUri(item)
            ?: return@withContext if (fallbackToInternalOnMissingApp) {
                FileLaunchOutcome.FallbackToInternalPreview(item)
            } else {
                FileLaunchOutcome.LaunchError("Файл недоступний")
            }

        val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val resolvedMime = item.mimeType.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "*/*"

        withContext(Dispatchers.Main) {
            runCatching {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, resolvedMime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(viewIntent)
                FileLaunchOutcome.LaunchedSuccessfully
            }.getOrElse {
                if (fallbackToInternalOnMissingApp) {
                    FileLaunchOutcome.FallbackToInternalPreview(item)
                } else {
                    FileLaunchOutcome.LaunchError(item.name)
                }
            }
        }
    }

    private fun obtainShareableContentUri(item: BeachFileItem): Uri? {
        return runCatching {
            if (item.isSafDocument || item.pathOrUri.startsWith("content://")) {
                val srcUri = Uri.parse(item.pathOrUri)
                // For APK installation, PackageInstaller works most reliably with a FileProvider URI
                val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext == "apk") {
                    val tempDir = File(context.cacheDir, "staged_install").apply { mkdirs() }
                    val staged = File(tempDir, item.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
                    context.contentResolver.openInputStream(srcUri)?.use { input ->
                        staged.outputStream().use { out ->
                            input.copyTo(out, bufferSize = 64 * 1024)
                        }
                    } ?: return null
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
                } else {
                    srcUri
                }
            } else {
                val file = File(item.pathOrUri)
                if (!file.exists()) return null
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrElse {
                    // Fallback: stage to cacheDir if path is on an unusual external mount point
                    val tempDir = File(context.cacheDir, "staged_open").apply { mkdirs() }
                    val staged = File(tempDir, file.name)
                    if (!staged.exists() || staged.length() != file.length() || staged.lastModified() < file.lastModified()) {
                        file.inputStream().use { input ->
                            staged.outputStream().use { out ->
                                input.copyTo(out, bufferSize = 64 * 1024)
                            }
                        }
                    }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
                }
            }
        }.getOrNull()
    }

    private fun resolveSafFolderUri(dest: MoveDestinationOption): Uri? {
        return runCatching {
            val rawUri = Uri.parse(dest.targetPathOrUri)
            if (DocumentsContract.isDocumentUri(context, rawUri)) {
                rawUri
            } else {
                val treeUri = if (dest.safTreeUri.isNotBlank()) Uri.parse(dest.safTreeUri) else rawUri
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            }
        }.getOrNull()
    }

    private fun deleteExistingSafChildByName(parentDocumentUri: Uri, targetName: String) {
        runCatching {
            val docId = DocumentsContract.getDocumentId(parentDocumentUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentDocumentUri, docId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val childName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    if (childName == targetName && idIdx >= 0) {
                        val childId = cursor.getString(idIdx)
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(parentDocumentUri, childId)
                        DocumentsContract.deleteDocument(context.contentResolver, childUri)
                    }
                }
            }
        }
    }
}
