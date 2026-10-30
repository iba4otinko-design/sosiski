package com.example.data.repository

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.data.local.BeachMetadataDao
import com.example.data.model.BeachFileItem
import com.example.data.model.ClipboardMode
import com.example.data.model.ConflictDecision
import com.example.data.model.ConflictResolution
import com.example.data.model.formatTotalCommanderTimestamp
import com.example.ui.icons.SmartIconLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

data class BatchOperationSummary(
    val succeededCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val cancelled: Boolean = false,
    val errorDetails: String? = null
)

/**
 * Handles Total Commander-style file clipboard formatting, system text clipboard export,
 * recursive multi-file Copy/Cut/Move across local File & SAF storage, conflict resolution,
 * and multi-file sharing.
 */
class FileOperationsManager(
    private val context: Context,
    private val dao: BeachMetadataDao
) {

    /**
     * Formats selected files and folders in Total Commander text list format:
     *
     * Total Commander file list (copy)
     * Total files: 1
     * /storage/emulated/0/Download
     * Моди/    0    2024-07-04 03:55:59
     */
    fun formatTotalCommanderText(
        items: List<BeachFileItem>,
        mode: ClipboardMode,
        currentFolderDisplay: String
    ): String {
        val opLabel = if (mode == ClipboardMode.CUT) "cut" else "copy"
        val cleanFolder = currentFolderDisplay.trim().ifBlank { "/" }
        val sb = StringBuilder()
        sb.append("Total Commander file list (").append(opLabel).append(")\n")
        sb.append("Total files: ").append(items.size).append('\n')
        sb.append(cleanFolder)
        for (item in items) {
            sb.append('\n')
            val cleanName = item.name.trimEnd('/', '\\')
            val entryLabel = if (item.isDirectory) "$cleanName/" else cleanName
            val sizeValue = if (item.isDirectory) 0L else item.sizeBytes.coerceAtLeast(0L)
            val timestamp = formatTotalCommanderTimestamp(item.lastModifiedMillis)
            sb.append(entryLabel)
                .append("    ")
                .append(sizeValue)
                .append("    ")
                .append(timestamp)
        }
        return sb.toString()
    }

    /**
     * Copies the Total Commander text representation to the Android system clipboard
     * without altering the app's internal file operations clipboard.
     */
    fun copyListAsTextToSystemClipboard(
        items: List<BeachFileItem>,
        mode: ClipboardMode,
        currentFolderDisplay: String
    ): String {
        val formatted = formatTotalCommanderText(items, mode, currentFolderDisplay)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Total Commander file list", formatted))
        return formatted
    }

    /**
     * Converts internal paths or SAF document URIs into clean human-readable directory paths
     * (e.g., `/storage/emulated/0/Download/Моди`).
     */
    fun resolveReadableFolderPath(isSaf: Boolean, pathOrUri: String): String {
        if (pathOrUri.isBlank()) return Environment.getExternalStorageDirectory().absolutePath
        if (pathOrUri == FileStorageRepository.VIRTUAL_DOWNLOADS_ROOT) {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        }
        if (pathOrUri == FileStorageRepository.VIRTUAL_ANDROID_HUB) {
            return File(Environment.getExternalStorageDirectory(), "Android").absolutePath
        }
        if (!isSaf && !pathOrUri.startsWith("content://")) {
            return pathOrUri
        }
        return runCatching {
            val uri = Uri.parse(pathOrUri)
            val docId = when {
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                else -> DocumentsContract.getTreeDocumentId(uri)
            }
            if (uri.authority == "com.android.externalstorage.documents" && docId.startsWith("primary:")) {
                val rel = docId.removePrefix("primary:").trimStart('/')
                val base = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
                if (rel.isEmpty()) base else "$base/$rel"
            } else {
                docId.ifBlank { pathOrUri }
            }
        }.getOrDefault(pathOrUri)
    }

    suspend fun executePasteOrMove(
        items: List<BeachFileItem>,
        mode: ClipboardMode,
        isDestSaf: Boolean,
        destFolderPathOrUri: String,
        destTreeUri: String = "",
        onProgress: (processed: Int, total: Int, currentName: String) -> Unit,
        onConflict: suspend (conflictingName: String, destDisplay: String) -> ConflictDecision
    ): BatchOperationSummary = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext BatchOperationSummary(0, 0, 0)

        val destDisplay = resolveReadableFolderPath(isDestSaf, destFolderPathOrUri)
        var stickyResolution: ConflictResolution? = null
        var succeeded = 0
        var skipped = 0
        var failed = 0

        try {
            val total = items.size
            for ((index, srcItem) in items.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(index, total, srcItem.name)

                // Guard against copying/moving a folder into itself or its own subfolder
                if (srcItem.isDirectory && !srcItem.isSafDocument && !isDestSaf) {
                    val srcCanonical = runCatching { File(srcItem.pathOrUri).canonicalPath }.getOrDefault(srcItem.pathOrUri)
                    val dstCanonical = runCatching { File(destFolderPathOrUri).canonicalPath }.getOrDefault(destFolderPathOrUri)
                    if (dstCanonical == srcCanonical || dstCanonical.startsWith("$srcCanonical${File.separator}")) {
                        failed++
                        continue
                    }
                }

                // Same folder no-op guard for CUT
                if (mode == ClipboardMode.CUT && srcItem.parentPathOrUri == destFolderPathOrUri) {
                    skipped++
                    continue
                }

                val existingTarget = findChildInDestination(isDestSaf, destFolderPathOrUri, destTreeUri, srcItem.name)
                var finalTargetName = srcItem.name
                var overwriteExisting = false

                // If copying into the exact same folder, auto-rename unless user decides otherwise
                if (existingTarget != null) {
                    if (srcItem.pathOrUri == existingTarget.pathOrUri) {
                        if (mode == ClipboardMode.CUT) {
                            skipped++
                            continue
                        } else {
                            finalTargetName = generateAutoRenamedTitle(isDestSaf, destFolderPathOrUri, destTreeUri, srcItem.name, srcItem.isDirectory)
                        }
                    } else {
                        val resolution = if (stickyResolution != null) {
                            stickyResolution
                        } else {
                            val decision = onConflict(srcItem.name, destDisplay)
                            if (decision.applyToAll && decision.resolution != ConflictResolution.CANCEL_ALL) {
                                stickyResolution = decision.resolution
                            }
                            decision.resolution
                        }

                        when (resolution) {
                            ConflictResolution.CANCEL_ALL -> {
                                return@withContext BatchOperationSummary(succeeded, skipped, failed, cancelled = true)
                            }
                            ConflictResolution.SKIP -> {
                                skipped++
                                continue
                            }
                            ConflictResolution.OVERWRITE -> {
                                overwriteExisting = true
                            }
                            ConflictResolution.RENAME -> {
                                finalTargetName = generateAutoRenamedTitle(
                                    isDestSaf,
                                    destFolderPathOrUri,
                                    destTreeUri,
                                    srcItem.name,
                                    srcItem.isDirectory
                                )
                            }
                        }
                    }
                }

                val itemOk = runCatching {
                    transferSingleItem(
                        srcItem = srcItem,
                        mode = mode,
                        isDestSaf = isDestSaf,
                        destParentPathOrUri = destFolderPathOrUri,
                        destTreeUri = destTreeUri,
                        targetName = finalTargetName,
                        existingToOverwrite = if (overwriteExisting) existingTarget else null
                    )
                }.getOrDefault(false)

                if (itemOk) {
                    succeeded++
                    SmartIconLoader.invalidatePath(srcItem.pathOrUri)
                } else {
                    failed++
                }
            }
            onProgress(total, total, "")
            BatchOperationSummary(succeeded, skipped, failed)
        } catch (_: CancellationException) {
            BatchOperationSummary(succeeded, skipped, failed, cancelled = true)
        } catch (e: Exception) {
            BatchOperationSummary(succeeded, skipped, failed, errorDetails = e.localizedMessage)
        }
    }

    private suspend fun transferSingleItem(
        srcItem: BeachFileItem,
        mode: ClipboardMode,
        isDestSaf: Boolean,
        destParentPathOrUri: String,
        destTreeUri: String,
        targetName: String,
        existingToOverwrite: DestChildRef?
    ): Boolean {
        currentCoroutineContext().ensureActive()

        if (existingToOverwrite != null) {
            deleteDestinationRef(existingToOverwrite)
            SmartIconLoader.invalidatePath(existingToOverwrite.pathOrUri)
        }

        // Fast path: local File -> local File CUT via atomic renameTo if on same mount
        if (!srcItem.isSafDocument && !isDestSaf) {
            val srcFile = File(srcItem.pathOrUri)
            val destDir = File(destParentPathOrUri).apply { mkdirs() }
            val destFile = File(destDir, targetName)
            if (!srcFile.exists()) return false

            if (mode == ClipboardMode.CUT && srcFile.renameTo(destFile)) {
                migrateRoomMeta(srcItem.key, "file:${destFile.absolutePath}")
                SmartIconLoader.invalidatePath(destFile.absolutePath)
                return true
            }

            val copied = copyLocalRecursively(srcFile, destFile)
            if (!copied) return false
            SmartIconLoader.invalidatePath(destFile.absolutePath)

            if (mode == ClipboardMode.CUT) {
                srcFile.deleteRecursively()
                migrateRoomMeta(srcItem.key, "file:${destFile.absolutePath}")
            }
            return true
        }

        // Cross-storage or SAF transfer
        val copiedOk = if (srcItem.isDirectory) {
            copyDirectoryCrossStorage(srcItem, isDestSaf, destParentPathOrUri, destTreeUri, targetName)
        } else {
            copyFileCrossStorage(srcItem, isDestSaf, destParentPathOrUri, targetName)
        }

        if (copiedOk && mode == ClipboardMode.CUT) {
            deleteSourceAfterMove(srcItem)
        }
        return copiedOk
    }

    private suspend fun copyLocalRecursively(src: File, dst: File): Boolean {
        currentCoroutineContext().ensureActive()
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) return false
            val children = src.listFiles().orEmpty()
            for (child in children) {
                if (!copyLocalRecursively(child, File(dst, child.name))) return false
            }
            return true
        } else {
            dst.parentFile?.mkdirs()
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    copyStreamCancellable(input, output)
                }
            }
            return true
        }
    }

    private suspend fun copyFileCrossStorage(
        srcItem: BeachFileItem,
        isDestSaf: Boolean,
        destParentPathOrUri: String,
        targetName: String
    ): Boolean {
        val mime = srcItem.mimeType.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                targetName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            )
            ?: "application/octet-stream"

        val input: InputStream = if (srcItem.isSafDocument) {
            context.contentResolver.openInputStream(Uri.parse(srcItem.pathOrUri))
        } else {
            val f = File(srcItem.pathOrUri)
            if (f.exists()) FileInputStream(f) else null
        } ?: return false

        input.use { inStream ->
            if (isDestSaf) {
                val createdUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    Uri.parse(destParentPathOrUri),
                    mime,
                    targetName
                ) ?: return false
                val out = context.contentResolver.openOutputStream(createdUri) ?: return false
                out.use { outStream -> copyStreamCancellable(inStream, outStream) }
                SmartIconLoader.invalidatePath(createdUri.toString())
            } else {
                val destDir = File(destParentPathOrUri).apply { mkdirs() }
                val destFile = File(destDir, targetName)
                FileOutputStream(destFile).use { outStream -> copyStreamCancellable(inStream, outStream) }
                SmartIconLoader.invalidatePath(destFile.absolutePath)
            }
        }
        return true
    }

    private suspend fun copyDirectoryCrossStorage(
        srcDirItem: BeachFileItem,
        isDestSaf: Boolean,
        destParentPathOrUri: String,
        destTreeUri: String,
        targetName: String
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val newDestDirPathOrUri: String = if (isDestSaf) {
            DocumentsContract.createDocument(
                context.contentResolver,
                Uri.parse(destParentPathOrUri),
                DocumentsContract.Document.MIME_TYPE_DIR,
                targetName
            )?.toString() ?: return false
        } else {
            val d = File(destParentPathOrUri, targetName)
            if (!d.exists() && !d.mkdirs()) return false
            d.absolutePath
        }

        val children = listChildrenMinimal(srcDirItem)
        for (child in children) {
            val ok = if (child.isDirectory) {
                copyDirectoryCrossStorage(child, isDestSaf, newDestDirPathOrUri, destTreeUri, child.name)
            } else {
                copyFileCrossStorage(child, isDestSaf, newDestDirPathOrUri, child.name)
            }
            if (!ok) return false
        }
        return true
    }

    private fun listChildrenMinimal(dirItem: BeachFileItem): List<BeachFileItem> {
        if (!dirItem.isSafDocument) {
            val dir = File(dirItem.pathOrUri)
            return dir.listFiles().orEmpty().map { f ->
                BeachFileItem(
                    key = "file:${f.absolutePath}",
                    name = f.name,
                    pathOrUri = f.absolutePath,
                    parentPathOrUri = dir.absolutePath,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isFile) f.length() else 0L,
                    createdAtMillis = f.lastModified(),
                    lastModifiedMillis = f.lastModified(),
                    category = com.example.data.model.FileCategory.fromExtensionOrMime(f.name, null, f.isDirectory),
                    mimeType = "",
                    isSafDocument = false
                )
            }
        } else {
            val treeUri = Uri.parse(dirItem.safTreeUri.ifBlank { dirItem.pathOrUri })
            val parentUri = Uri.parse(dirItem.pathOrUri)
            val parentDocId = runCatching { DocumentsContract.getDocumentId(parentUri) }
                .getOrElse { DocumentsContract.getTreeDocumentId(treeUri) }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val list = mutableListOf<BeachFileItem>()
            runCatching {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: "application/octet-stream"
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val mod = if (c.isNull(4)) 0L else c.getLong(4)
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        list.add(
                            BeachFileItem(
                                key = "saf:$docUri",
                                name = name,
                                pathOrUri = docUri.toString(),
                                parentPathOrUri = parentUri.toString(),
                                isDirectory = isDir,
                                sizeBytes = size,
                                createdAtMillis = mod,
                                lastModifiedMillis = mod,
                                category = com.example.data.model.FileCategory.fromExtensionOrMime(name, mime, isDir),
                                mimeType = mime,
                                isSafDocument = true,
                                safTreeUri = treeUri.toString()
                            )
                        )
                    }
                }
            }
            return list
        }
    }

    private suspend fun copyStreamCancellable(input: InputStream, output: OutputStream) {
        val buf = ByteArray(32 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buf)
            if (read <= 0) break
            output.write(buf, 0, read)
        }
        output.flush()
    }

    private data class DestChildRef(
        val name: String,
        val pathOrUri: String,
        val isSaf: Boolean
    )

    private fun findChildInDestination(
        isDestSaf: Boolean,
        destFolderPathOrUri: String,
        destTreeUri: String,
        childName: String
    ): DestChildRef? {
        if (!isDestSaf) {
            val f = File(destFolderPathOrUri, childName)
            return if (f.exists()) DestChildRef(childName, f.absolutePath, false) else null
        } else {
            val parentUri = Uri.parse(destFolderPathOrUri)
            val treeUri = if (destTreeUri.isNotBlank()) Uri.parse(destTreeUri) else parentUri
            val parentDocId = runCatching { DocumentsContract.getDocumentId(parentUri) }
                .getOrElse { runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("") }
            if (parentDocId.isBlank()) return null
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val proj = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            runCatching {
                context.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (name == childName) {
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            return DestChildRef(name, childUri.toString(), true)
                        }
                    }
                }
            }
            return null
        }
    }

    private fun generateAutoRenamedTitle(
        isDestSaf: Boolean,
        destFolderPathOrUri: String,
        destTreeUri: String,
        originalName: String,
        isDirectory: Boolean
    ): String {
        val dotIndex = if (isDirectory) -1 else originalName.lastIndexOf('.')
        val stem = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
        val ext = if (dotIndex > 0) originalName.substring(dotIndex) else ""
        for (counter in 1..999) {
            val candidate = "$stem ($counter)$ext"
            if (findChildInDestination(isDestSaf, destFolderPathOrUri, destTreeUri, candidate) == null) {
                return candidate
            }
        }
        return "${stem}_${System.currentTimeMillis()}$ext"
    }

    private fun deleteDestinationRef(ref: DestChildRef) {
        if (ref.isSaf) {
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(ref.pathOrUri))
            }
        } else {
            File(ref.pathOrUri).deleteRecursively()
        }
    }

    private suspend fun deleteSourceAfterMove(srcItem: BeachFileItem) {
        if (srcItem.isSafDocument) {
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(srcItem.pathOrUri))
            }
        } else {
            File(srcItem.pathOrUri).deleteRecursively()
        }
        dao.deleteMeta(srcItem.key)
        SmartIconLoader.invalidatePath(srcItem.pathOrUri)
    }

    private suspend fun migrateRoomMeta(oldKey: String, newKey: String) {
        val old = dao.getMeta(oldKey)
        dao.deleteMeta(oldKey)
        if (old != null) {
            dao.upsertMeta(old.copy(fileKey = newKey))
        }
    }

    /**
     * Builds an Android Share Intent for one or multiple selected files.
     * Directories are skipped for standard stream sharing (users can archive directories first).
     */
    fun createMultiShareIntent(items: List<BeachFileItem>): Intent? {
        val fileItems = items.filter { !it.isDirectory }
        if (fileItems.isEmpty()) return null

        val uris = ArrayList<Uri>(fileItems.size)
        for (item in fileItems) {
            val uri = runCatching {
                if (item.isSafDocument) {
                    Uri.parse(item.pathOrUri)
                } else {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        File(item.pathOrUri)
                    )
                }
            }.getOrNull() ?: continue
            uris.add(uri)
        }
        if (uris.isEmpty()) return null

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = fileItems.first().mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return Intent.createChooser(intent, "Поділитися (${uris.size})")
    }
}
