package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.data.model.ArchiveEntryItem
import com.example.data.model.BeachFileItem
import com.example.data.model.ConflictDecision
import com.example.data.model.ConflictResolution
import com.example.data.model.FileCategory
import com.example.data.model.SupportedArchiveFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full-featured built-in Archiver & Virtual Archive Browser supporting
 * ZIP, 7Z, TAR, TAR.GZ (.tgz), and GZIP (.gz) with strict Zip-Slip protection,
 * single-entry cache preview without full extraction, partial extraction,
 * multi-file archive creation, and adding files to existing ZIP archives.
 */
class ArchiveRepository(
    private val context: Context
) {

    private val previewCacheDir: File by lazy {
        File(context.cacheDir, "archive_preview").apply { mkdirs() }
    }

    /**
     * Validates that [entryRelativePath] stays strictly inside [destRoot]
     * and throws [SecurityException] on Zip-Slip (`../`, `/`, `\`, null bytes) attempts.
     */
    fun resolveSafeExtractionTarget(destRoot: File, entryRelativePath: String): File {
        val sanitized = sanitizeArchiveEntryPath(entryRelativePath)
        if (sanitized.isEmpty()) {
            throw SecurityException("Порожній або некоректний шлях елемента в архіві")
        }
        val destRootCanonical = destRoot.canonicalFile
        val targetFile = File(destRootCanonical, sanitized).canonicalFile
        val rootPrefix = destRootCanonical.path.trimEnd(File.separatorChar) + File.separator
        if (targetFile != destRootCanonical && !targetFile.path.startsWith(rootPrefix)) {
            throw SecurityException("Заблоковано небезпечний вихід за межі папки (Zip-Slip): $entryRelativePath")
        }
        return targetFile
    }

    /**
     * Normalizes slashes and rejects any `..` traversal or drive-letter prefixes immediately.
     */
    fun sanitizeArchiveEntryPath(rawPath: String): String {
        if (rawPath.contains('\u0000')) {
            throw SecurityException("Некоректний символ у шляху архіву")
        }
        val unified = rawPath.replace('\\', '/').trim('/')
        if (unified.isEmpty()) return ""
        val segments = unified.split('/')
        for (seg in segments) {
            if (seg == ".." || seg.contains(':')) {
                throw SecurityException("Виявлено небезпечний шлях в архіві (Zip-Slip): $rawPath")
            }
        }
        return segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    /**
     * Lists all entries inside an archive, synthesizing any implicit parent directories
     * so virtual folder browsing works cleanly at every hierarchy level.
     */
    suspend fun listArchiveEntries(
        archiveItem: BeachFileItem,
        format: SupportedArchiveFormat
    ): List<ArchiveEntryItem> = withContext(Dispatchers.IO) {
        val rawMap = LinkedHashMap<String, ArchiveEntryItem>()

        fun registerEntry(
            rawName: String,
            isDir: Boolean,
            size: Long,
            compressedSize: Long,
            lastMod: Long
        ) {
            val clean = sanitizeArchiveEntryPath(rawName)
            if (clean.isEmpty()) return
            // Synthesize ancestor folders first
            val parts = clean.split('/')
            var runningParent = ""
            for (i in 0 until parts.lastIndex) {
                val seg = parts[i]
                val dirPath = if (runningParent.isEmpty()) seg else "$runningParent/$seg"
                rawMap.putIfAbsent(
                    dirPath,
                    ArchiveEntryItem(
                        entryPath = dirPath,
                        name = seg,
                        parentPath = runningParent,
                        isDirectory = true,
                        sizeBytes = 0L,
                        compressedSizeBytes = 0L,
                        lastModifiedMillis = lastMod.coerceAtLeast(0L)
                    )
                )
                runningParent = dirPath
            }
            val leafName = parts.last()
            val leafParent = if (parts.size > 1) parts.dropLast(1).joinToString("/") else ""
            rawMap[clean] = ArchiveEntryItem(
                entryPath = clean,
                name = leafName,
                parentPath = leafParent,
                isDirectory = isDir,
                sizeBytes = if (isDir) 0L else size.coerceAtLeast(0L),
                compressedSizeBytes = if (isDir) 0L else compressedSize.coerceAtLeast(0L),
                lastModifiedMillis = lastMod.coerceAtLeast(0L)
            )
        }

        when (format) {
            SupportedArchiveFormat.ZIP -> {
                if (!archiveItem.isSafDocument) {
                    ZipFile(File(archiveItem.pathOrUri), Charsets.UTF_8).use { zf ->
                        val entries = zf.entries()
                        while (entries.hasMoreElements()) {
                            currentCoroutineContext().ensureActive()
                            val e = entries.nextElement()
                            registerEntry(
                                rawName = e.name,
                                isDir = e.isDirectory,
                                size = e.size,
                                compressedSize = e.compressedSize,
                                lastMod = e.time
                            )
                        }
                    }
                } else {
                    openArchiveInputStream(archiveItem)?.use { rawIn ->
                        ZipInputStream(BufferedInputStream(rawIn), Charsets.UTF_8).use { zis ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val e = zis.nextEntry ?: break
                                registerEntry(
                                    rawName = e.name,
                                    isDir = e.isDirectory,
                                    size = e.size,
                                    compressedSize = e.compressedSize,
                                    lastMod = e.time.takeIf { it > 0L } ?: archiveItem.lastModifiedMillis
                                )
                            }
                        }
                    }
                }
            }

            SupportedArchiveFormat.SEVEN_Z -> {
                withLocalFileForRandomAccess(archiveItem, ".7z") { file ->
                    openSevenZFileCompat(file).use { sz ->
                        for (e in sz.entries) {
                            currentCoroutineContext().ensureActive()
                            val mod = runCatching { e.lastModifiedDate.time }.getOrDefault(archiveItem.lastModifiedMillis)
                            registerEntry(
                                rawName = e.name,
                                isDir = e.isDirectory,
                                size = e.size,
                                compressedSize = 0L,
                                lastMod = mod
                            )
                        }
                    }
                }
            }

            SupportedArchiveFormat.TAR, SupportedArchiveFormat.TAR_GZ -> {
                openArchiveInputStream(archiveItem)?.use { rawIn ->
                    val baseIn = if (format == SupportedArchiveFormat.TAR_GZ) {
                        GzipCompressorInputStream(BufferedInputStream(rawIn))
                    } else {
                        BufferedInputStream(rawIn)
                    }
                    TarArchiveInputStream(baseIn, "UTF-8").use { tis ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val e = nextTarEntryCompat(tis) ?: break
                            registerEntry(
                                rawName = e.name,
                                isDir = e.isDirectory,
                                size = e.size,
                                compressedSize = e.size,
                                lastMod = e.lastModifiedDate?.time ?: archiveItem.lastModifiedMillis
                            )
                        }
                    }
                }
            }

            SupportedArchiveFormat.GZIP_SINGLE -> {
                val unpackedName = archiveItem.name.removeSuffix(".gz").removeSuffix(".GZ").ifBlank { "unpacked_file" }
                registerEntry(
                    rawName = unpackedName,
                    isDir = false,
                    size = archiveItem.sizeBytes,
                    compressedSize = archiveItem.sizeBytes,
                    lastMod = archiveItem.lastModifiedMillis
                )
            }
        }

        rawMap.values.sortedWith(
            compareByDescending<ArchiveEntryItem> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    fun filterEntriesInDir(allEntries: List<ArchiveEntryItem>, dirPath: String): List<ArchiveEntryItem> {
        val normalizedDir = dirPath.trim('/')
        return allEntries
            .filter { it.parentPath == normalizedDir }
            .sortedWith(
                compareByDescending<ArchiveEntryItem> { it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
    }

    /**
     * Extracts ONLY a single file entry from inside the archive into a temporary cache file
     * so images, text documents, and audio tracks can be opened/played without unpacking the whole archive.
     */
    suspend fun extractSingleEntryToPreviewCache(
        archiveItem: BeachFileItem,
        format: SupportedArchiveFormat,
        entry: ArchiveEntryItem
    ): BeachFileItem? = withContext(Dispatchers.IO) {
        if (entry.isDirectory) return@withContext null
        // Cap instant in-memory/cache preview at 60 MB to avoid filling cache
        if (entry.sizeBytes > 60L * 1024L * 1024L) return@withContext null

        val safeLeafName = entry.name.replace('/', '_').replace('\\', '_').ifBlank { "preview.bin" }
        val hashPrefix = kotlin.math.abs((archiveItem.pathOrUri + "|" + entry.entryPath).hashCode())
        val destFile = resolveSafeExtractionTarget(previewCacheDir, "${hashPrefix}_$safeLeafName")
        destFile.parentFile?.mkdirs()

        val extracted = when (format) {
            SupportedArchiveFormat.ZIP -> {
                if (!archiveItem.isSafDocument) {
                    ZipFile(File(archiveItem.pathOrUri), Charsets.UTF_8).use { zf ->
                        val match = findZipEntryNormalized(zf, entry.entryPath) ?: return@use false
                        zf.getInputStream(match).use { input ->
                            FileOutputStream(destFile).use { out -> copyStreamCancellable(input, out) }
                        }
                        true
                    }
                } else {
                    extractSingleFromStreamArchive(archiveItem, format, entry.entryPath, destFile)
                }
            }

            SupportedArchiveFormat.SEVEN_Z -> {
                withLocalFileForRandomAccess(archiveItem, ".7z") { file ->
                    openSevenZFileCompat(file).use { sz ->
                        var found = false
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val next: SevenZArchiveEntry = sz.nextEntry ?: break
                            if (!next.isDirectory && sanitizeArchiveEntryPath(next.name) == entry.entryPath) {
                                FileOutputStream(destFile).use { out -> copySevenZCurrentEntry(sz, out) }
                                found = true
                                break
                            }
                        }
                        found
                    }
                } ?: false
            }

            SupportedArchiveFormat.TAR, SupportedArchiveFormat.TAR_GZ -> {
                extractSingleFromStreamArchive(archiveItem, format, entry.entryPath, destFile)
            }

            SupportedArchiveFormat.GZIP_SINGLE -> {
                openArchiveInputStream(archiveItem)?.use { rawIn ->
                    GzipCompressorInputStream(BufferedInputStream(rawIn)).use { gzis ->
                        FileOutputStream(destFile).use { out -> copyStreamCancellable(gzis, out) }
                    }
                    true
                } ?: false
            }
        }

        if (!extracted || !destFile.exists()) return@withContext null

        val ext = destFile.extension.lowercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        BeachFileItem(
            key = "archive_preview:${destFile.absolutePath}",
            name = entry.name,
            pathOrUri = destFile.absolutePath,
            parentPathOrUri = previewCacheDir.absolutePath,
            isDirectory = false,
            sizeBytes = destFile.length(),
            createdAtMillis = entry.lastModifiedMillis.takeIf { it > 0L } ?: destFile.lastModified(),
            lastModifiedMillis = destFile.lastModified(),
            category = FileCategory.fromExtensionOrMime(entry.name, mime, false),
            mimeType = mime,
            isSafDocument = false
        )
    }

    private suspend fun extractSingleFromStreamArchive(
        archiveItem: BeachFileItem,
        format: SupportedArchiveFormat,
        wantedPath: String,
        destFile: File
    ): Boolean {
        val rawIn = openArchiveInputStream(archiveItem) ?: return false
        rawIn.use { stream ->
            if (format == SupportedArchiveFormat.ZIP) {
                ZipInputStream(BufferedInputStream(stream), Charsets.UTF_8).use { zis ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val next = zis.nextEntry ?: break
                        if (!next.isDirectory && sanitizeArchiveEntryPath(next.name) == wantedPath) {
                            FileOutputStream(destFile).use { out -> copyStreamCancellable(zis, out) }
                            return true
                        }
                    }
                }
            } else {
                val tarBase = if (format == SupportedArchiveFormat.TAR_GZ) {
                    GzipCompressorInputStream(BufferedInputStream(stream))
                } else {
                    BufferedInputStream(stream)
                }
                TarArchiveInputStream(tarBase, "UTF-8").use { tis ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val next = nextTarEntryCompat(tis) ?: break
                        if (!next.isDirectory && sanitizeArchiveEntryPath(next.name) == wantedPath) {
                            FileOutputStream(destFile).use { out -> copyStreamCancellable(tis, out) }
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    /**
     * Extracts either the full archive ([selectedPaths] == null) or selected entries/folders
     * into [destDirPathOrUri] (supporting both local File directories and SAF directories),
     * with strict Zip-Slip protection, conflict handling, progress reporting, and cancellation.
     */
    suspend fun extractArchive(
        archiveItem: BeachFileItem,
        format: SupportedArchiveFormat,
        isDestSaf: Boolean,
        destDirPathOrUri: String,
        selectedPaths: Set<String>? = null,
        stripPrefixDir: String = "",
        onProgress: (processed: Int, total: Int, currentName: String) -> Unit,
        onConflict: suspend (conflictingName: String, destDisplay: String) -> ConflictDecision
    ): BatchOperationSummary = withContext(Dispatchers.IO) {
        var succeeded = 0
        var skipped = 0
        var failed = 0
        var stickyResolution: ConflictResolution? = null

        fun matchesSelection(normalizedPath: String): Boolean {
            if (selectedPaths.isNullOrEmpty()) return true
            return selectedPaths.any { sel ->
                normalizedPath == sel || normalizedPath.startsWith("$sel/")
            }
        }

        fun relativeOutputPath(normalizedPath: String): String {
            val cleanPrefix = stripPrefixDir.trim('/')
            return if (cleanPrefix.isNotEmpty() && normalizedPath.startsWith("$cleanPrefix/")) {
                normalizedPath.removePrefix("$cleanPrefix/")
            } else {
                normalizedPath
            }
        }

        // If destination is SAF, unpack safely into a staging directory first (validating Zip-Slip), then move into SAF
        val localStagingDir: File? = if (isDestSaf) {
            File(context.cacheDir, "extract_stage_${System.currentTimeMillis()}").apply { mkdirs() }
        } else {
            null
        }
        val targetLocalRoot = localStagingDir ?: File(destDirPathOrUri).apply { mkdirs() }

        try {
            suspend fun writeEntryWithPolicy(
                rawEntryName: String,
                isDir: Boolean,
                writeContent: suspend (OutputStream) -> Unit
            ) {
                currentCoroutineContext().ensureActive()
                val normalized = sanitizeArchiveEntryPath(rawEntryName)
                if (normalized.isEmpty() || !matchesSelection(normalized)) return

                val relOut = relativeOutputPath(normalized)
                if (relOut.isEmpty()) return

                // Strict Zip-Slip validation before touching filesystem!
                var destFile = resolveSafeExtractionTarget(targetLocalRoot, relOut)
                if (isDir) {
                    destFile.mkdirs()
                    return
                }

                if (destFile.exists() && localStagingDir == null) {
                    val resolution = stickyResolution ?: run {
                        val decision = onConflict(destFile.name, targetLocalRoot.absolutePath)
                        if (decision.applyToAll && decision.resolution != ConflictResolution.CANCEL_ALL) {
                            stickyResolution = decision.resolution
                        }
                        decision.resolution
                    }
                    when (resolution) {
                        ConflictResolution.CANCEL_ALL -> throw CancellationException("Скасовано користувачем")
                        ConflictResolution.SKIP -> {
                            skipped++
                            return
                        }
                        ConflictResolution.OVERWRITE -> Unit
                        ConflictResolution.RENAME -> {
                            destFile = autoRenameLocalFile(destFile)
                        }
                    }
                }

                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { out -> writeContent(out) }
                succeeded++
            }

            when (format) {
                SupportedArchiveFormat.ZIP -> {
                    if (!archiveItem.isSafDocument) {
                        ZipFile(File(archiveItem.pathOrUri), Charsets.UTF_8).use { zf ->
                            val list = zf.entries().toList()
                            val total = list.size.coerceAtLeast(1)
                            list.forEachIndexed { idx, ze ->
                                onProgress(idx, total, ze.name)
                                writeEntryWithPolicy(ze.name, ze.isDirectory) { out ->
                                    zf.getInputStream(ze).use { input -> copyStreamCancellable(input, out) }
                                }
                            }
                        }
                    } else {
                        openArchiveInputStream(archiveItem)?.use { rawIn ->
                            ZipInputStream(BufferedInputStream(rawIn), Charsets.UTF_8).use { zis ->
                                var idx = 0
                                while (true) {
                                    val e = zis.nextEntry ?: break
                                    onProgress(idx++, 0, e.name)
                                    writeEntryWithPolicy(e.name, e.isDirectory) { out ->
                                        copyStreamCancellable(zis, out)
                                    }
                                }
                            }
                        }
                    }
                }

                SupportedArchiveFormat.SEVEN_Z -> {
                    withLocalFileForRandomAccess(archiveItem, ".7z") { file ->
                        openSevenZFileCompat(file).use { sz ->
                            val total = sz.entries.count().coerceAtLeast(1)
                            var idx = 0
                            while (true) {
                                val next = sz.nextEntry ?: break
                                onProgress(idx++, total, next.name)
                                writeEntryWithPolicy(next.name, next.isDirectory) { out ->
                                    copySevenZCurrentEntry(sz, out)
                                }
                            }
                        }
                    }
                }

                SupportedArchiveFormat.TAR, SupportedArchiveFormat.TAR_GZ -> {
                    openArchiveInputStream(archiveItem)?.use { rawIn ->
                        val baseIn = if (format == SupportedArchiveFormat.TAR_GZ) {
                            GzipCompressorInputStream(BufferedInputStream(rawIn))
                        } else {
                            BufferedInputStream(rawIn)
                        }
                        TarArchiveInputStream(baseIn, "UTF-8").use { tis ->
                            var idx = 0
                            while (true) {
                                val e = nextTarEntryCompat(tis) ?: break
                                onProgress(idx++, 0, e.name)
                                writeEntryWithPolicy(e.name, e.isDirectory) { out ->
                                    copyStreamCancellable(tis, out)
                                }
                            }
                        }
                    }
                }

                SupportedArchiveFormat.GZIP_SINGLE -> {
                    val outName = archiveItem.name.removeSuffix(".gz").removeSuffix(".GZ").ifBlank { "unpacked_file" }
                    onProgress(0, 1, outName)
                    openArchiveInputStream(archiveItem)?.use { rawIn ->
                        GzipCompressorInputStream(BufferedInputStream(rawIn)).use { gzis ->
                            writeEntryWithPolicy(outName, false) { out ->
                                copyStreamCancellable(gzis, out)
                            }
                        }
                    }
                }
            }

            if (localStagingDir != null) {
                copyLocalDirToSafTree(localStagingDir, Uri.parse(destDirPathOrUri))
            }

            BatchOperationSummary(succeeded, skipped, failed)
        } catch (_: CancellationException) {
            BatchOperationSummary(succeeded, skipped, failed, cancelled = true)
        } catch (se: SecurityException) {
            BatchOperationSummary(succeeded, skipped, failed + 1, errorDetails = se.message)
        } catch (e: Exception) {
            BatchOperationSummary(succeeded, skipped, failed + 1, errorDetails = e.localizedMessage)
        } finally {
            localStagingDir?.deleteRecursively()
        }
    }

    /**
     * Creates a new archive (.zip, .7z, .tar.gz, or .tar) from selected files and folders.
     */
    suspend fun createArchive(
        items: List<BeachFileItem>,
        isDestSaf: Boolean,
        destDirPathOrUri: String,
        archiveBaseName: String,
        format: SupportedArchiveFormat,
        onProgress: (processed: Int, total: Int, currentName: String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Не вибрано елементів"))
        val cleanBase = archiveBaseName.trim().replace('/', '_').replace('\\', '_').ifBlank { "archive" }
        val finalFileName = if (cleanBase.lowercase(Locale.ROOT).endsWith(format.extensionSuffix)) {
            cleanBase
        } else {
            cleanBase + format.extensionSuffix
        }

        val tempOrTargetFile = if (isDestSaf) {
            File(context.cacheDir, "create_arch_${System.currentTimeMillis()}_$finalFileName")
        } else {
            val destDir = File(destDirPathOrUri).apply { mkdirs() }
            autoRenameLocalFileIfExists(File(destDir, finalFileName))
        }

        // Flatten all inputs into (archiveRelativePath, sourceItemOrLocalFile)
        val entriesToPack = collectEntriesToPack(items)
        val total = entriesToPack.size.coerceAtLeast(1)

        try {
            when (format) {
                SupportedArchiveFormat.ZIP -> {
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(tempOrTargetFile)), Charsets.UTF_8).use { zos ->
                        entriesToPack.forEachIndexed { idx, spec ->
                            currentCoroutineContext().ensureActive()
                            onProgress(idx, total, spec.relativePath)
                            val entryName = if (spec.isDirectory) "${spec.relativePath.trimEnd('/')}/" else spec.relativePath
                            val ze = ZipEntry(entryName).apply {
                                time = spec.lastModified
                            }
                            zos.putNextEntry(ze)
                            if (!spec.isDirectory) {
                                openPackSourceInputStream(spec)?.use { input ->
                                    copyStreamCancellable(input, zos)
                                }
                            }
                            zos.closeEntry()
                        }
                    }
                }

                SupportedArchiveFormat.SEVEN_Z -> {
                    SevenZOutputFile(tempOrTargetFile).use { szOut ->
                        entriesToPack.forEachIndexed { idx, spec ->
                            currentCoroutineContext().ensureActive()
                            onProgress(idx, total, spec.relativePath)
                            val entry = SevenZArchiveEntry().apply {
                                name = spec.relativePath
                                isDirectory = spec.isDirectory
                                lastModifiedDate = java.util.Date(spec.lastModified)
                            }
                            putSevenZEntryCompat(szOut, entry)
                            if (!spec.isDirectory) {
                                openPackSourceInputStream(spec)?.use { input ->
                                    val buf = ByteArray(32 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val read = input.read(buf)
                                        if (read <= 0) break
                                        szOut.write(buf, 0, read)
                                    }
                                }
                            }
                            szOut.closeArchiveEntry()
                        }
                    }
                }

                SupportedArchiveFormat.TAR, SupportedArchiveFormat.TAR_GZ -> {
                    FileOutputStream(tempOrTargetFile).use { fos ->
                        val baseOut: OutputStream = if (format == SupportedArchiveFormat.TAR_GZ) {
                            GzipCompressorOutputStream(BufferedOutputStream(fos))
                        } else {
                            BufferedOutputStream(fos)
                        }
                        TarArchiveOutputStream(baseOut, "UTF-8").use { tos ->
                            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                            tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                            entriesToPack.forEachIndexed { idx, spec ->
                                currentCoroutineContext().ensureActive()
                                onProgress(idx, total, spec.relativePath)
                                val entryName = if (spec.isDirectory) "${spec.relativePath.trimEnd('/')}/" else spec.relativePath
                                val stagedBytes: ByteArray? = if (!spec.isDirectory && spec.localFile == null && spec.knownSize <= 0L) {
                                    openPackSourceInputStream(spec)?.use { it.readBytes() }
                                } else null
                                val exactSize = when {
                                    spec.isDirectory -> 0L
                                    stagedBytes != null -> stagedBytes.size.toLong()
                                    spec.localFile != null -> spec.localFile.length()
                                    else -> spec.knownSize
                                }
                                val te = TarArchiveEntry(entryName).apply {
                                    size = exactSize
                                    modTime = java.util.Date(spec.lastModified)
                                }
                                putTarEntryCompat(tos, te)
                                if (!spec.isDirectory) {
                                    if (stagedBytes != null) {
                                        tos.write(stagedBytes)
                                    } else {
                                        openPackSourceInputStream(spec)?.use { input ->
                                            copyStreamCancellable(input, tos)
                                        }
                                    }
                                }
                                tos.closeArchiveEntry()
                            }
                        }
                    }
                }

                SupportedArchiveFormat.GZIP_SINGLE -> {
                    return@withContext Result.failure(IllegalArgumentException("Оберіть TAR.GZ або ZIP"))
                }
            }

            if (isDestSaf) {
                val createdUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    Uri.parse(destDirPathOrUri),
                    "application/octet-stream",
                    finalFileName
                ) ?: throw IllegalStateException("Не вдалося створити архів у вибраній SAF-папці")
                FileInputStream(tempOrTargetFile).use { input ->
                    context.contentResolver.openOutputStream(createdUri)?.use { out ->
                        copyStreamCancellable(input, out)
                    }
                }
                tempOrTargetFile.delete()
                Result.success(finalFileName)
            } else {
                Result.success(tempOrTargetFile.name)
            }
        } catch (ce: CancellationException) {
            tempOrTargetFile.delete()
            throw ce
        } catch (e: Exception) {
            if (tempOrTargetFile.exists()) tempOrTargetFile.delete()
            Result.failure(e)
        }
    }

    /**
     * Appends external files into an existing ZIP archive atomically.
     */
    suspend fun addUrisToExistingZip(
        zipItem: BeachFileItem,
        uris: List<Uri>,
        targetInternalFolder: String,
        onProgress: (processed: Int, total: Int, currentName: String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext Result.success(0)
        val tempZip = File(context.cacheDir, "append_zip_${System.currentTimeMillis()}.zip")
        val prefix = targetInternalFolder.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val newEntryNames = mutableSetOf<String>()
        val resolvedNew = uris.mapIndexed { idx, u ->
            val name = resolveUriDisplayName(u) ?: "file_$idx"
            val entryPath = prefix + name.replace('/', '_')
            newEntryNames.add(entryPath)
            entryPath to u
        }

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip)), Charsets.UTF_8).use { zos ->
                // 1. Copy existing entries (skipping overwritten paths)
                openArchiveInputStream(zipItem)?.use { rawIn ->
                    ZipInputStream(BufferedInputStream(rawIn), Charsets.UTF_8).use { zis ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val existing = zis.nextEntry ?: break
                            val norm = sanitizeArchiveEntryPath(existing.name)
                            if (norm in newEntryNames) continue
                            val copyEntry = ZipEntry(existing.name).apply {
                                time = existing.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                            }
                            zos.putNextEntry(copyEntry)
                            if (!existing.isDirectory) {
                                copyStreamCancellable(zis, zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }

                // 2. Append new URIs
                resolvedNew.forEachIndexed { idx, (entryPath, uri) ->
                    currentCoroutineContext().ensureActive()
                    onProgress(idx, resolvedNew.size, entryPath)
                    val ze = ZipEntry(entryPath).apply { time = System.currentTimeMillis() }
                    zos.putNextEntry(ze)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        copyStreamCancellable(input, zos)
                    }
                    zos.closeEntry()
                }
            }

            // 3. Replace original ZIP atomically
            if (zipItem.isSafDocument) {
                context.contentResolver.openOutputStream(Uri.parse(zipItem.pathOrUri), "wt")?.use { out ->
                    FileInputStream(tempZip).use { input -> copyStreamCancellable(input, out) }
                } ?: throw IllegalStateException("Провайдер не дозволяє перезапис цього архіву")
            } else {
                val targetFile = File(zipItem.pathOrUri)
                FileInputStream(tempZip).use { input ->
                    FileOutputStream(targetFile, false).use { out -> copyStreamCancellable(input, out) }
                }
            }
            Result.success(resolvedNew.size)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempZip.delete()
        }
    }

    // --- Internal Helpers ---

    private data class PackSourceSpec(
        val relativePath: String,
        val isDirectory: Boolean,
        val localFile: File?,
        val safUri: Uri?,
        val knownSize: Long,
        val lastModified: Long
    )

    private fun collectEntriesToPack(items: List<BeachFileItem>): List<PackSourceSpec> {
        val result = mutableListOf<PackSourceSpec>()

        fun walkLocal(file: File, relPath: String) {
            if (file.isDirectory) {
                result.add(PackSourceSpec(relPath, true, file, null, 0L, file.lastModified()))
                file.listFiles().orEmpty().forEach { child ->
                    walkLocal(child, "$relPath/${child.name}")
                }
            } else {
                result.add(PackSourceSpec(relPath, false, file, null, file.length(), file.lastModified()))
            }
        }

        fun walkSaf(treeUri: Uri, docUri: Uri, name: String, isDir: Boolean, size: Long, mod: Long, relPath: String) {
            if (isDir) {
                result.add(PackSourceSpec(relPath, true, null, docUri, 0L, mod))
                val docId = runCatching { DocumentsContract.getDocumentId(docUri) }.getOrDefault("")
                if (docId.isBlank()) return
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val proj = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )
                runCatching {
                    context.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val cId = c.getString(0) ?: continue
                            val cName = c.getString(1) ?: continue
                            val cMime = c.getString(2) ?: ""
                            val cSize = if (c.isNull(3)) 0L else c.getLong(3)
                            val cMod = if (c.isNull(4)) System.currentTimeMillis() else c.getLong(4)
                            val cUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cId)
                            walkSaf(
                                treeUri,
                                cUri,
                                cName,
                                cMime == DocumentsContract.Document.MIME_TYPE_DIR,
                                cSize,
                                cMod,
                                "$relPath/$cName"
                            )
                        }
                    }
                }
            } else {
                result.add(PackSourceSpec(relPath, false, null, docUri, size, mod))
            }
        }

        for (item in items) {
            if (!item.isSafDocument) {
                val f = File(item.pathOrUri)
                if (f.exists()) walkLocal(f, f.name)
            } else {
                val docUri = Uri.parse(item.pathOrUri)
                val treeUri = Uri.parse(item.safTreeUri.ifBlank { item.pathOrUri })
                walkSaf(treeUri, docUri, item.name, item.isDirectory, item.sizeBytes, item.lastModifiedMillis, item.name)
            }
        }
        return result
    }

    private fun openPackSourceInputStream(spec: PackSourceSpec): InputStream? {
        return when {
            spec.localFile != null -> FileInputStream(spec.localFile)
            spec.safUri != null -> context.contentResolver.openInputStream(spec.safUri)
            else -> null
        }
    }

    private fun openArchiveInputStream(item: BeachFileItem): InputStream? {
        return if (item.isSafDocument) {
            context.contentResolver.openInputStream(Uri.parse(item.pathOrUri))
        } else {
            val f = File(item.pathOrUri)
            if (f.exists()) FileInputStream(f) else null
        }
    }

    private suspend fun <T> withLocalFileForRandomAccess(
        item: BeachFileItem,
        extSuffix: String,
        block: suspend (File) -> T
    ): T? {
        if (!item.isSafDocument) {
            val f = File(item.pathOrUri)
            return if (f.exists()) block(f) else null
        }
        val tempFile = File(context.cacheDir, "saf_arch_${System.currentTimeMillis()}$extSuffix")
        return try {
            context.contentResolver.openInputStream(Uri.parse(item.pathOrUri))?.use { input ->
                FileOutputStream(tempFile).use { out -> copyStreamCancellable(input, out) }
            } ?: return null
            block(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    private fun findZipEntryNormalized(zf: ZipFile, normalizedWanted: String): ZipEntry? {
        zf.getEntry(normalizedWanted)?.let { return it }
        val entries = zf.entries()
        while (entries.hasMoreElements()) {
            val next = entries.nextElement()
            if (!next.isDirectory && runCatching { sanitizeArchiveEntryPath(next.name) }.getOrNull() == normalizedWanted) {
                return next
            }
        }
        return null
    }

    private fun openSevenZFileCompat(file: File): SevenZFile {
        return try {
            SevenZFile.builder().setFile(file).get()
        } catch (_: NoSuchMethodError) {
            SevenZFile::class.java.getConstructor(File::class.java).newInstance(file)
        }
    }

    private fun putSevenZEntryCompat(szOut: SevenZOutputFile, entry: SevenZArchiveEntry) {
        try {
            szOut.putArchiveEntry(entry)
        } catch (_: NoSuchMethodError) {
            SevenZOutputFile::class.java
                .getMethod("putArchiveEntry", ArchiveEntry::class.java)
                .invoke(szOut, entry)
        }
    }

    private fun putTarEntryCompat(tos: TarArchiveOutputStream, entry: TarArchiveEntry) {
        try {
            tos.putArchiveEntry(entry)
        } catch (_: NoSuchMethodError) {
            ArchiveOutputStream::class.java
                .getMethod("putArchiveEntry", ArchiveEntry::class.java)
                .invoke(tos, entry)
        }
    }

    private fun nextTarEntryCompat(tis: TarArchiveInputStream): ArchiveEntry? {
        return try {
            tis.nextEntry
        } catch (_: NoSuchMethodError) {
            ArchiveInputStream::class.java
                .getMethod("getNextEntry")
                .invoke(tis) as? ArchiveEntry
        }
    }

    private suspend fun copySevenZCurrentEntry(sz: SevenZFile, out: OutputStream) {
        val buf = ByteArray(32 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = sz.read(buf, 0, buf.size)
            if (read <= 0) break
            out.write(buf, 0, read)
        }
        out.flush()
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

    private suspend fun copyLocalDirToSafTree(srcDir: File, destParentDocUri: Uri) {
        for (child in srcDir.listFiles().orEmpty()) {
            currentCoroutineContext().ensureActive()
            if (child.isDirectory) {
                val subUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    destParentDocUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    child.name
                ) ?: continue
                copyLocalDirToSafTree(child, subUri)
            } else {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(child.extension.lowercase(Locale.ROOT))
                    ?: "application/octet-stream"
                val docUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    destParentDocUri,
                    mime,
                    child.name
                ) ?: continue
                FileInputStream(child).use { input ->
                    context.contentResolver.openOutputStream(docUri)?.use { out ->
                        copyStreamCancellable(input, out)
                    }
                }
            }
        }
    }

    private fun autoRenameLocalFile(existing: File): File {
        val parent = existing.parentFile ?: return existing
        val stem = existing.nameWithoutExtension
        val ext = existing.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        for (i in 1..999) {
            val cand = File(parent, "$stem ($i)$ext")
            if (!cand.exists()) return cand
        }
        return File(parent, "${stem}_${System.currentTimeMillis()}$ext")
    }

    private fun autoRenameLocalFileIfExists(candidate: File): File {
        return if (!candidate.exists()) candidate else autoRenameLocalFile(candidate)
    }

    private fun resolveUriDisplayName(uri: Uri): String? {
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
}
