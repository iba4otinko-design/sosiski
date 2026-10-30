package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.data.local.BackupLogEntity
import com.example.data.local.BeachMetadataDao
import com.example.data.local.PreferencesRepository
import com.example.data.local.SandItemMetaEntity
import com.example.data.model.AppPreferencesState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class SyncResult(
    val isSuccess: Boolean,
    val filesSynced: Int,
    val bytesSynced: Long,
    val message: String
)

/**
 * Optional Cloud Backup & Sync engine.
 * Supports Android SAF Cloud Providers (Google Drive, OneDrive, Nextcloud, external folders)
 * and WebDAV HTTP servers via OkHttp. Never blocks or requires setup for offline local usage.
 */
class CloudSyncRepository(
    private val context: Context,
    private val fileRepository: FileStorageRepository,
    private val preferencesRepository: PreferencesRepository,
    private val dao: BeachMetadataDao
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun observeBackupLogs(): Flow<List<BackupLogEntity>> = dao.observeBackupLogs()

    suspend fun performBackupIfAutoEnabled() {
        val prefs = preferencesRepository.preferencesFlow.first()
        if (prefs.autoCloudBackup && prefs.isCloudConfigured) {
            performBackupNow(prefs)
        }
    }

    suspend fun performBackupNow(prefsOverride: AppPreferencesState? = null): SyncResult =
        withContext(Dispatchers.IO) {
            val prefs = prefsOverride ?: preferencesRepository.preferencesFlow.first()
            if (!prefs.isCloudConfigured) {
                return@withContext SyncResult(
                    isSuccess = false,
                    filesSynced = 0,
                    bytesSynced = 0L,
                    message = "Хмарне сховище не вибрано. Застосунок працює локально."
                )
            }

            val metaList = dao.observeAllMeta().first()
            val metaMap = metaList.associateBy { it.fileKey }
            val filesToBackup = collectWorkspaceFiles(fileRepository.beaverCoveDir)
            val manifestJson = buildManifestJson(metaList, filesToBackup)

            val result = if (prefs.safCloudTreeUri.isNotBlank()) {
                syncToSafCloudProvider(
                    treeUri = Uri.parse(prefs.safCloudTreeUri),
                    folderLabel = prefs.safCloudFolderName.ifBlank { "Хмарна папка SAF" },
                    files = filesToBackup,
                    manifestJson = manifestJson,
                    metaMap = metaMap
                )
            } else {
                syncToWebDavServer(
                    baseUrl = prefs.webDavUrl,
                    username = prefs.webDavUsername,
                    password = prefs.webDavPassword,
                    files = filesToBackup,
                    manifestJson = manifestJson,
                    metaMap = metaMap
                )
            }

            val now = System.currentTimeMillis()
            if (result.isSuccess) {
                preferencesRepository.updateLastSyncTime(now)
            }

            val providerTitle = if (prefs.safCloudTreeUri.isNotBlank()) {
                prefs.safCloudFolderName.ifBlank { "Хмарний провайдер Android" }
            } else {
                "WebDAV Хмара"
            }

            dao.insertBackupLog(
                BackupLogEntity(
                    timestampMillis = now,
                    providerName = providerTitle,
                    syncedFilesCount = result.filesSynced,
                    syncedBytes = result.bytesSynced,
                    message = result.message,
                    isSuccess = result.isSuccess
                )
            )

            result
        }

    private fun collectWorkspaceFiles(rootDir: File): List<File> {
        val collected = mutableListOf<File>()
        fun walk(dir: File) {
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith(".") || f.name == "Корчі_Кошик") return@forEach
                if (f.isDirectory) walk(f) else collected.add(f)
            }
        }
        walk(rootDir)
        return collected
    }

    private fun buildManifestJson(metaList: List<SandItemMetaEntity>, files: List<File>): String {
        val root = JSONObject()
        root.put("app", "БоброПляж")
        root.put("exportedAtMillis", System.currentTimeMillis())
        root.put("fileCount", files.size)
        val itemsArr = JSONArray()
        metaList.forEach { m ->
            val obj = JSONObject()
            obj.put("fileKey", m.fileKey)
            obj.put("sandX", m.sandX.toDouble())
            obj.put("sandY", m.sandY.toDouble())
            obj.put("customCategory", m.customCategory ?: JSONObject.NULL)
            obj.put("isStarred", m.isStarred)
            itemsArr.put(obj)
        }
        root.put("sandMetadata", itemsArr)
        return root.toString(2)
    }

    private suspend fun syncToSafCloudProvider(
        treeUri: Uri,
        folderLabel: String,
        files: List<File>,
        manifestJson: String,
        metaMap: Map<String, SandItemMetaEntity>
    ): SyncResult {
        return runCatching {
            val resolver = context.contentResolver
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

            val backupFolderUri = findOrCreateSafDirectory(treeUri, rootDocUri, "BobroPlyazh_Backup")
                ?: return SyncResult(false, 0, 0L, "Не вдалося отримати доступ до папки $folderLabel")

            var syncedCount = 0
            var syncedBytes = 0L
            val syncTimestamp = System.currentTimeMillis()

            // Write manifest JSON
            writeSafFile(treeUri, backupFolderUri, "beaver_sand_manifest.json", "application/json") { out ->
                val bytes = manifestJson.toByteArray(Charsets.UTF_8)
                out.write(bytes)
                syncedBytes += bytes.size
            }

            // Copy workspace files preserving flat relative names
            files.forEach { file ->
                val relName = file.relativeTo(fileRepository.beaverCoveDir).path.replace(File.separatorChar, '_')
                val ok = writeSafFile(treeUri, backupFolderUri, relName, "application/octet-stream") { out ->
                    file.inputStream().use { input ->
                        syncedBytes += input.copyTo(out)
                    }
                }
                if (ok) {
                    syncedCount++
                    val key = "file:${file.absolutePath}"
                    val existing = metaMap[key]
                    dao.upsertMeta(
                        (existing ?: SandItemMetaEntity(key, 0.5f, 0.5f)).copy(lastBackedUpAt = syncTimestamp)
                    )
                }
            }

            SyncResult(
                isSuccess = true,
                filesSynced = syncedCount,
                bytesSynced = syncedBytes,
                message = "Резервну копію успішно збережено у «$folderLabel»"
            )
        }.getOrElse { e ->
            SyncResult(
                isSuccess = false,
                filesSynced = 0,
                bytesSynced = 0L,
                message = "Помилка SAF синхронізації: ${e.localizedMessage ?: "невідома помилка"}"
            )
        }
    }

    private fun findOrCreateSafDirectory(treeUri: Uri, parentDocUri: Uri, dirName: String): Uri? {
        val existing = findSafChildUri(treeUri, parentDocUri, dirName)
        if (existing != null) return existing
        return DocumentsContract.createDocument(
            context.contentResolver,
            parentDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            dirName
        )
    }

    private fun writeSafFile(
        treeUri: Uri,
        parentDocUri: Uri,
        fileName: String,
        mimeType: String,
        writer: (java.io.OutputStream) -> Unit
    ): Boolean {
        val resolver = context.contentResolver
        findSafChildUri(treeUri, parentDocUri, fileName)?.let { oldUri ->
            runCatching { DocumentsContract.deleteDocument(resolver, oldUri) }
        }
        val createdUri = DocumentsContract.createDocument(resolver, parentDocUri, mimeType, fileName) ?: return false
        resolver.openOutputStream(createdUri, "w")?.use(writer) ?: return false
        return true
    }

    private fun findSafChildUri(treeUri: Uri, parentDocUri: Uri, targetName: String): Uri? {
        val parentDocId = DocumentsContract.getDocumentId(parentDocUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0)
                val name = c.getString(1)
                if (name == targetName) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                }
            }
        }
        return null
    }

    private suspend fun syncToWebDavServer(
        baseUrl: String,
        username: String,
        password: String,
        files: List<File>,
        manifestJson: String,
        metaMap: Map<String, SandItemMetaEntity>
    ): SyncResult {
        return runCatching {
            val normalizedBase = baseUrl.trimEnd('/') + "/BobroPlyazh_Backup"
            val authHeader = if (username.isNotBlank()) Credentials.basic(username, password) else null

            // Attempt MKCOL to ensure remote backup directory exists (201 Created or 405 Already Exists)
            val mkcolReq = Request.Builder()
                .url(normalizedBase)
                .method("MKCOL", null)
                .apply { if (authHeader != null) header("Authorization", authHeader) }
                .build()
            httpClient.newCall(mkcolReq).execute().close()

            var syncedCount = 0
            var syncedBytes = 0L
            val syncTime = System.currentTimeMillis()

            // Upload manifest
            val manifestBody = manifestJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            val manifestPut = Request.Builder()
                .url("$normalizedBase/beaver_sand_manifest.json")
                .put(manifestBody)
                .apply { if (authHeader != null) header("Authorization", authHeader) }
                .build()
            httpClient.newCall(manifestPut).execute().use { resp ->
                if (!resp.isSuccessful && resp.code !in 200..299) {
                    return SyncResult(false, 0, 0L, "WebDAV сервер відповів кодом HTTP ${resp.code}")
                }
                syncedBytes += manifestJson.toByteArray().size
            }

            files.forEach { file ->
                val safeName = Uri.encode(file.relativeTo(fileRepository.beaverCoveDir).path.replace(File.separatorChar, '_'))
                val putReq = Request.Builder()
                    .url("$normalizedBase/$safeName")
                    .put(file.asRequestBody("application/octet-stream".toMediaType()))
                    .apply { if (authHeader != null) header("Authorization", authHeader) }
                    .build()
                httpClient.newCall(putReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        syncedCount++
                        syncedBytes += file.length()
                        val key = "file:${file.absolutePath}"
                        val existing = metaMap[key]
                        dao.upsertMeta(
                            (existing ?: SandItemMetaEntity(key, 0.5f, 0.5f)).copy(lastBackedUpAt = syncTime)
                        )
                    }
                }
            }

            SyncResult(
                isSuccess = true,
                filesSynced = syncedCount,
                bytesSynced = syncedBytes,
                message = "Синхронізовано $syncedCount файлів із сервером WebDAV"
            )
        }.getOrElse { e ->
            SyncResult(
                isSuccess = false,
                filesSynced = 0,
                bytesSynced = 0L,
                message = "Не вдалося з'єднатися з WebDAV: ${e.localizedMessage ?: "перевірте URL"}"
            )
        }
    }
}
