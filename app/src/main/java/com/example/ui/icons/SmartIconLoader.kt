package com.example.ui.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size as AndroidSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BeachFileItem
import com.example.data.model.FileCategory
import com.example.ui.components.SandyFileIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * Total Commander–style Two-Tier (L1 RAM LruCache + L2 Persistent WebP Disk Cache) Smart File Icon Loader:
 * - Never blocks scrolling: synchronous L1 lookup (`getCached`) + lightweight Canvas placeholder on miss.
 * - LIFO Viewport-Priority Work Queue (4 bounded IO workers): prioritizes currently visible items over
 *   scrolled-past items, coalesces duplicate concurrent requests for the same key, and immediately evicts
 *   queued off-screen tasks when their composable leaves the viewport.
 * - Zero UI-thread heavy work: PackageManager queries, Drawable rasterization, image/video/audio decoding,
 *   disk cache I/O, and `Bitmap.prepareToDraw()` execute strictly on `Dispatchers.IO`.
 */
object SmartIconLoader {

    private const val MAX_CONCURRENT_WORKERS = 4
    private const val DISK_CACHE_FOLDER = "smart_thumbs_v2"

    private val EXTRACTABLE_CATEGORIES = setOf(
        FileCategory.IMAGES,
        FileCategory.AUDIO,
        FileCategory.VIDEO
    )

    private val EXTRACTABLE_EXTENSIONS = setOf(
        "apk", "apks", "xapk", "exe", "dll", "mcpack", "mcaddon", "mcworld"
    )

    private val maxBitmapCacheKb: Int = runCatching {
        val maxHeapKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        (maxHeapKb / 8).coerceIn(16 * 1024, 48 * 1024)
    }.getOrDefault(24 * 1024)

    private val bitmapCache = object : LruCache<String, ImageBitmap>(maxBitmapCacheKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return ((value.width * value.height * 4) / 1024).coerceAtLeast(1)
        }
    }

    private val negativeCache = object : LruCache<String, Boolean>(1600) {}

    // --- LIFO Viewport-Priority Scheduler & In-Flight Request Deduplicator ---
    private class InFlightIconTask(
        val cacheKey: String,
        val loader: suspend () -> ImageBitmap?
    ) {
        val waiters = LinkedHashSet<CompletableDeferred<ImageBitmap?>>()
        var isRunning: Boolean = false
        var workerChildJob: Job? = null
    }

    private val schedulerLock = Any()
    private val lifoQueue = ArrayDeque<InFlightIconTask>()
    private val inFlightByKey = HashMap<String, InFlightIconTask>()
    private var activeWorkers = 0
    private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val evictedBeforeRunCounter = AtomicInteger(0)
    private val executedExtractionCounter = AtomicInteger(0)

    internal fun evictedQueueTaskCount(): Int = evictedBeforeRunCounter.get()
    internal fun executedExtractionCount(): Int = executedExtractionCounter.get()

    fun buildCacheKey(pathOrUri: String, lastModifiedMillis: Long, sizeBytes: Long): String {
        return "$pathOrUri|$lastModifiedMillis|$sizeBytes"
    }

    fun getCached(cacheKey: String): ImageBitmap? = synchronized(bitmapCache) {
        bitmapCache.get(cacheKey)
    }

    fun isKnownNegative(cacheKey: String): Boolean = synchronized(negativeCache) {
        negativeCache.get(cacheKey) == true
    }

    fun canAttemptCustomIcon(item: BeachFileItem): Boolean {
        if (item.isDirectory || item.requiresSafGrantDocId != null) return false
        if (item.category in EXTRACTABLE_CATEGORIES) return true
        val ext = item.lowerName.substringAfterLast('.', "")
        return ext in EXTRACTABLE_EXTENSIONS
    }

    fun invalidatePath(pathOrUri: String) {
        val prefix = "$pathOrUri|"
        synchronized(bitmapCache) {
            bitmapCache.snapshot().keys.filter { it.startsWith(prefix) }.forEach {
                bitmapCache.remove(it)
            }
        }
        synchronized(negativeCache) {
            negativeCache.snapshot().keys.filter { it.startsWith(prefix) }.forEach {
                negativeCache.remove(it)
            }
        }
    }

    fun clearAll(context: Context? = null) {
        val jobsToCancel = ArrayList<Job>()
        val waitersToCancel = ArrayList<CompletableDeferred<ImageBitmap?>>()
        synchronized(schedulerLock) {
            for (queued in lifoQueue) {
                waitersToCancel.addAll(queued.waiters)
                queued.waiters.clear()
            }
            lifoQueue.clear()
            for (running in inFlightByKey.values) {
                waitersToCancel.addAll(running.waiters)
                running.waiters.clear()
                running.workerChildJob?.let { jobsToCancel.add(it) }
            }
            inFlightByKey.clear()
        }
        jobsToCancel.forEach { it.cancel() }
        waitersToCancel.forEach { it.cancel() }

        synchronized(bitmapCache) { bitmapCache.evictAll() }
        synchronized(negativeCache) { negativeCache.evictAll() }
        evictedBeforeRunCounter.set(0)
        executedExtractionCounter.set(0)
        context?.let {
            runCatching { File(it.cacheDir, DISK_CACHE_FOLDER).deleteRecursively() }
        }
    }

    private suspend fun enqueuePrioritized(
        cacheKey: String,
        loader: suspend () -> ImageBitmap?
    ): ImageBitmap? {
        getCached(cacheKey)?.let { return it }
        if (isKnownNegative(cacheKey)) return null

        val myWaiter = CompletableDeferred<ImageBitmap?>()
        val registeredTask: InFlightIconTask

        synchronized(schedulerLock) {
            getCached(cacheKey)?.let { return it }
            if (isKnownNegative(cacheKey)) return null

            val existing = inFlightByKey[cacheKey]
            if (existing != null) {
                existing.waiters.add(myWaiter)
                // Promote already-queued task to the top of the LIFO stack because it is in the active viewport now
                if (!existing.isRunning && lifoQueue.firstOrNull() !== existing) {
                    if (lifoQueue.remove(existing)) {
                        lifoQueue.addFirst(existing)
                    }
                }
                registeredTask = existing
            } else {
                val created = InFlightIconTask(cacheKey = cacheKey, loader = loader)
                created.waiters.add(myWaiter)
                inFlightByKey[cacheKey] = created
                // LIFO push: newest visible viewport items always sit at the head of the queue
                lifoQueue.addFirst(created)
                if (activeWorkers < MAX_CONCURRENT_WORKERS) {
                    startWorkerLocked()
                }
                registeredTask = created
            }
        }

        return try {
            myWaiter.await()
        } finally {
            if (!myWaiter.isCompleted) {
                var jobToCancel: Job? = null
                synchronized(schedulerLock) {
                    if (registeredTask.waiters.remove(myWaiter) && registeredTask.waiters.isEmpty()) {
                        if (!registeredTask.isRunning) {
                            if (lifoQueue.remove(registeredTask)) {
                                evictedBeforeRunCounter.incrementAndGet()
                            }
                            if (inFlightByKey[cacheKey] === registeredTask) {
                                inFlightByKey.remove(cacheKey)
                            }
                        } else {
                            if (inFlightByKey[cacheKey] === registeredTask) {
                                inFlightByKey.remove(cacheKey)
                            }
                            jobToCancel = registeredTask.workerChildJob
                        }
                    }
                }
                jobToCancel?.cancel()
            }
        }
    }

    private fun startWorkerLocked() {
        activeWorkers++
        schedulerScope.launch {
            try {
                while (true) {
                    val task: InFlightIconTask = synchronized(schedulerLock) {
                        var candidate = lifoQueue.removeFirstOrNull()
                        while (candidate != null && candidate.waiters.isEmpty()) {
                            if (inFlightByKey[candidate.cacheKey] === candidate) {
                                inFlightByKey.remove(candidate.cacheKey)
                            }
                            evictedBeforeRunCounter.incrementAndGet()
                            candidate = lifoQueue.removeFirstOrNull()
                        }
                        if (candidate == null) {
                            activeWorkers--
                            return@launch
                        }
                        candidate.isRunning = true
                        candidate
                    }

                    var output: ImageBitmap? = null
                    var wasCancelled = false

                    try {
                        coroutineScope {
                            val child = async {
                                task.loader()
                            }
                            val cancelNow = synchronized(schedulerLock) {
                                task.workerChildJob = child
                                task.waiters.isEmpty()
                            }
                            if (cancelNow) {
                                child.cancel()
                            }
                            output = child.await()
                        }
                    } catch (_: CancellationException) {
                        wasCancelled = true
                    } catch (_: Throwable) {
                        output = null
                    }

                    val waitersSnapshot: List<CompletableDeferred<ImageBitmap?>> = synchronized(schedulerLock) {
                        if (inFlightByKey[task.cacheKey] === task) {
                            inFlightByKey.remove(task.cacheKey)
                        }
                        val copy = task.waiters.toList()
                        task.waiters.clear()
                        task.workerChildJob = null
                        copy
                    }

                    if (!wasCancelled) {
                        for (w in waitersSnapshot) {
                            w.complete(output)
                        }
                    } else {
                        for (w in waitersSnapshot) {
                            w.cancel()
                        }
                    }
                }
            } catch (_: Throwable) {
                synchronized(schedulerLock) {
                    if (activeWorkers > 0) activeWorkers--
                    if (lifoQueue.isNotEmpty() && activeWorkers < MAX_CONCURRENT_WORKERS) {
                        startWorkerLocked()
                    }
                }
            }
        }
    }

    private fun prepareAndCacheBitmap(cacheKey: String, bmp: Bitmap): ImageBitmap {
        runCatching { bmp.prepareToDraw() }
        val img = bmp.asImageBitmap()
        synchronized(bitmapCache) {
            bitmapCache.put(cacheKey, img)
        }
        return img
    }

    internal suspend fun loadPrioritizedForTest(
        cacheKey: String,
        recordNegativeOnNull: Boolean = true,
        extractor: suspend () -> Bitmap?
    ): ImageBitmap? {
        return enqueuePrioritized(cacheKey) {
            currentCoroutineContext().ensureActive()
            getCached(cacheKey)?.let { return@enqueuePrioritized it }
            executedExtractionCounter.incrementAndGet()
            val bmp = extractor()
            currentCoroutineContext().ensureActive()
            if (bmp != null) {
                prepareAndCacheBitmap(cacheKey, bmp)
            } else {
                if (recordNegativeOnNull) {
                    synchronized(negativeCache) { negativeCache.put(cacheKey, true) }
                }
                null
            }
        }
    }

    private fun diskThumbFile(context: Context, cacheKey: String): File {
        val dir = File(context.cacheDir, DISK_CACHE_FOLDER).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
        val hex = buildString(32) {
            for (i in 0 until 16) {
                append(String.format(Locale.US, "%02x", digest[i]))
            }
        }
        return File(dir, "$hex.webp")
    }

    private fun readFromDiskCache(context: Context, cacheKey: String): Bitmap? {
        return runCatching {
            val f = diskThumbFile(context, cacheKey)
            if (!f.exists() || f.length() <= 0L) return null
            BitmapFactory.decodeFile(f.absolutePath)
        }.getOrNull()
    }

    private fun saveToDiskCache(context: Context, cacheKey: String, bitmap: Bitmap) {
        runCatching {
            val f = diskThumbFile(context, cacheKey)
            FileOutputStream(f).use { out ->
                @Suppress("DEPRECATION")
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                bitmap.compress(format, 82, out)
            }
        }
    }

    suspend fun loadInstalledAppBitmap(
        context: Context,
        packageName: String,
        apkFilePath: String,
        lastUpdatedMillis: Long
    ): ImageBitmap? {
        val cacheKey = "app_icon:$packageName|$lastUpdatedMillis"
        getCached(cacheKey)?.let { return it }
        if (isKnownNegative(cacheKey)) return null

        val appCtx = context.applicationContext
        return enqueuePrioritized(cacheKey) {
            currentCoroutineContext().ensureActive()
            getCached(cacheKey)?.let { return@enqueuePrioritized it }

            readFromDiskCache(appCtx, cacheKey)?.let { diskBmp ->
                return@enqueuePrioritized prepareAndCacheBitmap(cacheKey, diskBmp)
            }

            currentCoroutineContext().ensureActive()
            executedExtractionCounter.incrementAndGet()
            val bmp = runCatching {
                val pm = appCtx.packageManager
                val drawable = runCatching { pm.getApplicationIcon(packageName) }.getOrElse {
                    val f = File(apkFilePath)
                    if (!f.exists()) return@runCatching null
                    val pkgInfo = pm.getPackageArchiveInfo(f.absolutePath, 0) ?: return@runCatching null
                    val ai = pkgInfo.applicationInfo ?: return@runCatching null
                    ai.sourceDir = f.absolutePath
                    ai.publicSourceDir = f.absolutePath
                    ai.loadIcon(pm)
                }
                drawableToBitmap(drawable, 128)
            }.getOrNull()

            currentCoroutineContext().ensureActive()
            if (bmp != null) {
                saveToDiskCache(appCtx, cacheKey, bmp)
                prepareAndCacheBitmap(cacheKey, bmp)
            } else {
                synchronized(negativeCache) { negativeCache.put(cacheKey, true) }
                null
            }
        }
    }

    suspend fun loadSmartBitmap(context: Context, item: BeachFileItem): ImageBitmap? {
        if (!canAttemptCustomIcon(item)) return null
        val cacheKey = buildCacheKey(item.pathOrUri, item.lastModifiedMillis, item.sizeBytes)
        getCached(cacheKey)?.let { return it }
        if (isKnownNegative(cacheKey)) return null

        val ext = item.lowerName.substringAfterLast('.', "")
        val appCtx = context.applicationContext

        return enqueuePrioritized(cacheKey) {
            currentCoroutineContext().ensureActive()
            getCached(cacheKey)?.let { return@enqueuePrioritized it }

            // Anti-phantom guard: if local file no longer exists on disk, remove any disk thumb and return null
            if (!item.isSafDocument && !item.pathOrUri.startsWith("content://")) {
                val realFile = File(item.pathOrUri)
                if (!realFile.exists()) {
                    runCatching { diskThumbFile(appCtx, cacheKey).delete() }
                    return@enqueuePrioritized null
                }
            }

            // Fast L2 disk cache check inside deduplicated background task
            readFromDiskCache(appCtx, cacheKey)?.let { diskBmp ->
                return@enqueuePrioritized prepareAndCacheBitmap(cacheKey, diskBmp)
            }

            currentCoroutineContext().ensureActive()
            executedExtractionCounter.incrementAndGet()

            val bmp: Bitmap? = runCatching {
                when {
                    ext == "apk" -> extractApkIcon(appCtx, item)
                    ext in setOf("apks", "xapk") -> extractZipEmbeddedImage(appCtx, item, APK_BUNDLE_ICON_NAMES)
                    ext in setOf("mcpack", "mcaddon", "mcworld") ->
                        extractZipEmbeddedImage(appCtx, item, MINECRAFT_ICON_NAMES)
                    ext in setOf("exe", "dll") -> extractPeIcon(appCtx, item)
                    item.category == FileCategory.IMAGES -> extractImageThumbnail(appCtx, item)
                    item.category == FileCategory.AUDIO -> extractAudioCoverArt(appCtx, item)
                    item.category == FileCategory.VIDEO -> extractVideoFrame(appCtx, item)
                    else -> null
                }
            }.getOrNull()

            currentCoroutineContext().ensureActive()
            if (bmp != null) {
                saveToDiskCache(appCtx, cacheKey, bmp)
                prepareAndCacheBitmap(cacheKey, bmp)
            } else {
                synchronized(negativeCache) { negativeCache.put(cacheKey, true) }
                null
            }
        }
    }

    private val APK_BUNDLE_ICON_NAMES = setOf("icon.png")
    private val MINECRAFT_ICON_NAMES = setOf("pack_icon.png", "world_icon.jpeg", "world_icon.png")

    private fun extractApkIcon(context: Context, item: BeachFileItem): Bitmap? {
        val pm = context.packageManager
        if (!item.isSafDocument) {
            val file = File(item.pathOrUri)
            if (!file.exists()) return null
            val pkgInfo = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
            val appInfo = pkgInfo.applicationInfo ?: return null
            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath
            return drawableToBitmap(appInfo.loadIcon(pm), 128)
        } else if (item.sizeBytes in 1..(65L * 1024L * 1024L)) {
            val tempApk = File(context.cacheDir, "icon_probe_${item.key.hashCode()}_${Thread.currentThread().id}.apk")
            return try {
                context.contentResolver.openInputStream(Uri.parse(item.pathOrUri))?.use { input ->
                    FileOutputStream(tempApk).use { out -> input.copyTo(out) }
                } ?: return null
                val pkgInfo = pm.getPackageArchiveInfo(tempApk.absolutePath, 0) ?: return null
                val appInfo = pkgInfo.applicationInfo ?: return null
                appInfo.sourceDir = tempApk.absolutePath
                appInfo.publicSourceDir = tempApk.absolutePath
                drawableToBitmap(appInfo.loadIcon(pm), 128)
            } finally {
                tempApk.delete()
            }
        }
        return null
    }

    private fun extractZipEmbeddedImage(
        context: Context,
        item: BeachFileItem,
        candidateNames: Set<String>
    ): Bitmap? {
        val stream = if (item.isSafDocument) {
            context.contentResolver.openInputStream(Uri.parse(item.pathOrUri))
        } else {
            val f = File(item.pathOrUri)
            if (f.exists()) f.inputStream() else null
        } ?: return null

        stream.use { raw ->
            ZipInputStream(raw).use { zis ->
                var inspected = 0
                while (inspected < 120) {
                    val entry = zis.nextEntry ?: break
                    inspected++
                    if (!entry.isDirectory) {
                        val simpleName = entry.name.substringAfterLast('/').lowercase(Locale.ROOT)
                        if (simpleName in candidateNames) {
                            val bytes = zis.readBytes()
                            if (bytes.isNotEmpty() && bytes.size <= 4 * 1024 * 1024) {
                                return decodeByteArrayScaled(bytes, 128)
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun extractPeIcon(context: Context, item: BeachFileItem): Bitmap? {
        return if (!item.isSafDocument) {
            PeIconExtractor.extractFromFile(File(item.pathOrUri))
        } else {
            context.contentResolver.openInputStream(Uri.parse(item.pathOrUri))?.use { stream ->
                PeIconExtractor.extractFromStream(stream)
            }
        }
    }

    private fun extractImageThumbnail(context: Context, item: BeachFileItem): Bitmap? {
        val targetPx = 128
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                if (!item.isSafDocument && !item.pathOrUri.startsWith("content://")) {
                    return ThumbnailUtils.createImageThumbnail(File(item.pathOrUri), AndroidSize(targetPx, targetPx), null)
                } else {
                    return context.contentResolver.loadThumbnail(Uri.parse(item.pathOrUri), AndroidSize(targetPx, targetPx), null)
                }
            }
        }
        if (!item.isSafDocument) {
            val path = item.pathOrUri
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            }
            return BitmapFactory.decodeFile(path, opts)
        } else {
            val uri = Uri.parse(item.pathOrUri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            }
            return context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    }

    private fun extractAudioCoverArt(context: Context, item: BeachFileItem): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (item.isSafDocument) {
                retriever.setDataSource(context, Uri.parse(item.pathOrUri))
            } else {
                retriever.setDataSource(item.pathOrUri)
            }
            val embedded = retriever.embeddedPicture ?: return null
            decodeByteArrayScaled(embedded, 128)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractVideoFrame(context: Context, item: BeachFileItem): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                if (!item.isSafDocument && !item.pathOrUri.startsWith("content://")) {
                    return ThumbnailUtils.createVideoThumbnail(File(item.pathOrUri), AndroidSize(144, 144), null)
                } else {
                    return context.contentResolver.loadThumbnail(Uri.parse(item.pathOrUri), AndroidSize(144, 144), null)
                }
            }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            if (item.isSafDocument) {
                retriever.setDataSource(context, Uri.parse(item.pathOrUri))
            } else {
                retriever.setDataSource(item.pathOrUri)
            }
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null
            val maxDim = 128
            if (frame.width > maxDim || frame.height > maxDim) {
                val ratio = minOf(maxDim.toFloat() / frame.width, maxDim.toFloat() / frame.height)
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * ratio).toInt().coerceAtLeast(1),
                    (frame.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                frame
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeByteArrayScaled(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun computeSampleSize(w: Int, h: Int, targetPx: Int): Int {
        var sample = 1
        while (w / sample > targetPx * 2 || h / sample > targetPx * 2) {
            sample *= 2
        }
        return sample
    }

    private fun drawableToBitmap(drawable: Drawable, maxPx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val src = drawable.bitmap
            if (src.width <= maxPx && src.height <= maxPx) return src
            val ratio = minOf(maxPx.toFloat() / src.width, maxPx.toFloat() / src.height)
            return Bitmap.createScaledBitmap(
                src,
                (src.width * ratio).toInt().coerceAtLeast(1),
                (src.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        }
        val w = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(maxPx) ?: maxPx
        val h = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(maxPx) ?: maxPx
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }
}

@Composable
fun InstalledAppIcon(
    packageName: String,
    apkFilePath: String,
    lastUpdatedMillis: Long,
    appName: String,
    size: Dp = 46.dp,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cacheKey = remember(packageName, lastUpdatedMillis) {
        "app_icon:$packageName|$lastUpdatedMillis"
    }
    var loadedBitmap by remember(cacheKey) {
        mutableStateOf(SmartIconLoader.getCached(cacheKey))
    }

    if (loadedBitmap == null) {
        LaunchedEffect(cacheKey) {
            if (!SmartIconLoader.isKnownNegative(cacheKey)) {
                delay(16L)
                val bmp = SmartIconLoader.loadInstalledAppBitmap(
                    context = context,
                    packageName = packageName,
                    apkFilePath = apkFilePath,
                    lastUpdatedMillis = lastUpdatedMillis
                )
                if (bmp != null) {
                    loadedBitmap = bmp
                }
            }
        }
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        val bmp = loadedBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = appName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize(0.92f)
                    .clip(RoundedCornerShape(size * 0.24f))
            )
        } else {
            SpecialFormatSandIcon(
                kind = SpecialGlyphKind.ANDROID_PKG,
                isStarred = false,
                size = size
            )
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.42f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(size * 0.28f)
                )
            }
        }
    }
}

/**
 * Renders a smart file icon in the Beaver Beach coastal aesthetic:
 * - Instant 0ms display from L1 RAM cache on scroll-back, or instant lightweight Canvas placeholder on miss.
 * - Zero coroutine overhead for folders, plain documents/archives, L1-cached items, and negative-cached items.
 * - LIFO viewport-priority background extraction so only visible items decode first and off-screen items cancel.
 * - Leaf-isolated `loadedBitmap` state so icon readiness updates ONLY this 48dp slot, never the list.
 */
@Composable
fun SmartFileIcon(
    item: BeachFileItem,
    size: Dp = 48.dp,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cacheKey = remember(item.pathOrUri, item.lastModifiedMillis, item.sizeBytes) {
        SmartIconLoader.buildCacheKey(item.pathOrUri, item.lastModifiedMillis, item.sizeBytes)
    }
    var loadedBitmap by remember(cacheKey) {
        mutableStateOf(SmartIconLoader.getCached(cacheKey))
    }

    val canAttempt = remember(item.isDirectory, item.requiresSafGrantDocId, item.category, item.lowerName) {
        SmartIconLoader.canAttemptCustomIcon(item)
    }

    if (loadedBitmap == null && canAttempt) {
        LaunchedEffect(cacheKey) {
            if (!SmartIconLoader.isKnownNegative(cacheKey)) {
                delay(16L)
                val bmp = SmartIconLoader.loadSmartBitmap(context, item)
                if (bmp != null) {
                    loadedBitmap = bmp
                }
            }
        }
    }

    val primaryBadgeText = remember(item.lowerName, item.isDirectory) {
        if (item.isDirectory) null else resolveBadgeLabel(item.lowerName)
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        val bmp = loadedBitmap
        if (bmp != null) {
            val isCropThumb = item.category == FileCategory.IMAGES ||
                item.category == FileCategory.VIDEO ||
                item.category == FileCategory.AUDIO
            Surface(
                shape = RoundedCornerShape(size * 0.28f),
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxSize(0.90f)
                    .border(
                        width = 1.5.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        },
                        shape = RoundedCornerShape(size * 0.28f)
                    )
            ) {
                Image(
                    bitmap = bmp,
                    contentDescription = item.name,
                    contentScale = if (isCropThumb) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isCropThumb) 0.dp else 4.dp)
                )
            }
        } else {
            val specialKind = remember(item.lowerName, item.isDirectory) {
                if (item.isDirectory) SpecialGlyphKind.NONE else detectSpecialGlyphKind(item.lowerName)
            }
            if (specialKind != SpecialGlyphKind.NONE) {
                SpecialFormatSandIcon(
                    kind = specialKind,
                    isStarred = item.isStarred,
                    size = size
                )
            } else {
                SandyFileIcon(
                    category = item.category,
                    isStarred = item.isStarred,
                    size = size
                )
            }
        }

        val activeBadge = if (bmp != null) {
            primaryBadgeText
        } else {
            primaryBadgeText ?: item.extensionLabel.takeIf { it.length in 1..4 }
        }

        if (activeBadge != null && !isSelected) {
            Surface(
                color = badgeColorForLabel(activeBadge),
                shape = RoundedCornerShape(5.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 1.dp, bottom = 1.dp)
            ) {
                Text(
                    text = activeBadge,
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 3.5.dp, vertical = 0.5.dp)
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.42f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(size * 0.28f)
                )
            }
        }
    }
}

@Composable
fun SmartArchiveEntryIcon(
    entryName: String,
    isDirectory: Boolean,
    isSelected: Boolean = false,
    size: Dp = 44.dp
) {
    val category = remember(entryName, isDirectory) {
        FileCategory.fromExtensionOrMime(entryName, null, isDirectory)
    }
    val fakeItem = remember(entryName, isDirectory, category) {
        BeachFileItem(
            key = "archive_entry:$entryName",
            name = entryName,
            pathOrUri = "",
            parentPathOrUri = "",
            isDirectory = isDirectory,
            sizeBytes = 0L,
            createdAtMillis = 0L,
            lastModifiedMillis = 0L,
            category = category,
            mimeType = "",
            isSafDocument = false,
            requiresSafGrantDocId = "skip_io"
        )
    }
    SmartFileIcon(item = fakeItem, size = size, isSelected = isSelected)
}

private enum class SpecialGlyphKind {
    NONE,
    MINECRAFT,
    WINDOWS_PE,
    ANDROID_PKG,
    ARCHIVE_BOX
}

private val MINECRAFT_GLYPH_EXTS = setOf("mcpack", "mcaddon", "mcworld")
private val WINDOWS_PE_GLYPH_EXTS = setOf("exe", "dll")
private val ANDROID_PKG_GLYPH_EXTS = setOf("apk", "apks", "xapk", "aab")
private val ARCHIVE_GLYPH_EXTS = setOf("zip", "7z", "tar", "gz", "tgz", "rar", "xz")

private fun detectSpecialGlyphKind(lowerName: String): SpecialGlyphKind {
    val ext = lowerName.substringAfterLast('.', "")
    return when {
        ext in MINECRAFT_GLYPH_EXTS -> SpecialGlyphKind.MINECRAFT
        ext in WINDOWS_PE_GLYPH_EXTS -> SpecialGlyphKind.WINDOWS_PE
        ext in ANDROID_PKG_GLYPH_EXTS -> SpecialGlyphKind.ANDROID_PKG
        lowerName.endsWith(".tar.gz") || ext in ARCHIVE_GLYPH_EXTS -> SpecialGlyphKind.ARCHIVE_BOX
        else -> SpecialGlyphKind.NONE
    }
}

private fun resolveBadgeLabel(lowerName: String): String? {
    if (lowerName.endsWith(".tar.gz")) return "TGZ"
    return when (val ext = lowerName.substringAfterLast('.', "")) {
        "mcpack" -> "MCP"
        "mcaddon" -> "MCA"
        "mcworld" -> "MCW"
        "apk", "apks", "xapk" -> ext.uppercase(Locale.ROOT)
        "exe", "dll" -> ext.uppercase(Locale.ROOT)
        "zip", "7z", "tar", "gz", "tgz", "rar" -> ext.uppercase(Locale.ROOT)
        "pdf", "json", "md", "flac", "mp3", "wav", "ogg", "mp4", "mkv" -> ext.uppercase(Locale.ROOT)
        else -> null
    }
}

private fun badgeColorForLabel(label: String): Color = when (label) {
    "MCP", "MCA", "MCW" -> Color(0xFF2E7D32)
    "APK", "APKS", "XAPK" -> Color(0xFF00897B)
    "EXE", "DLL" -> Color(0xFF1565C0)
    "7Z" -> Color(0xFF6A1B9A)
    "ZIP" -> Color(0xFFD84315)
    "TAR", "GZ", "TGZ" -> Color(0xFF455A64)
    "RAR" -> Color(0xFFAD1457)
    "JPG", "JPEG", "PNG", "WEBP", "GIF", "HEIC" -> Color(0xFFC6533B)
    "MP4", "MKV", "MOV", "WEBM" -> Color(0xFF8E24AA)
    "MP3", "WAV", "FLAC", "OGG", "M4A" -> Color(0xFFC27D1A)
    else -> Color(0xFF0D7D8C)
}

@Composable
private fun SpecialFormatSandIcon(
    kind: SpecialGlyphKind,
    isStarred: Boolean,
    size: Dp
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.25f
    val sandBase = if (isDark) Color(0xFF3E342A) else Color(0xFFEBD7B2)
    val sandHighlight = if (isDark) Color(0xFF594B3C) else Color(0xFFF9EED8)
    val sandShadow = if (isDark) Color(0xFF241E18) else Color(0xFFC9AE82)
    val domePath = remember { Path() }

    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        drawRoundRect(
            color = sandShadow,
            topLeft = Offset(w * 0.06f, h * 0.10f),
            size = Size(w * 0.88f, h * 0.86f),
            cornerRadius = CornerRadius(w * 0.30f, w * 0.30f)
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(sandHighlight, sandBase),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            ),
            topLeft = Offset(w * 0.06f, h * 0.06f),
            size = Size(w * 0.88f, h * 0.84f),
            cornerRadius = CornerRadius(w * 0.30f, w * 0.30f)
        )

        when (kind) {
            SpecialGlyphKind.MINECRAFT -> {
                val dirtColor = Color(0xFF795548)
                val grassColor = Color(0xFF43A047)
                drawRoundRect(
                    color = dirtColor,
                    topLeft = Offset(w * 0.25f, h * 0.34f),
                    size = Size(w * 0.50f, h * 0.40f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f)
                )
                drawRoundRect(
                    color = grassColor,
                    topLeft = Offset(w * 0.25f, h * 0.24f),
                    size = Size(w * 0.50f, h * 0.18f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f)
                )
                for (i in 0..3) {
                    drawRect(
                        color = grassColor,
                        topLeft = Offset(w * (0.28f + i * 0.12f), h * 0.40f),
                        size = Size(w * 0.07f, h * 0.07f)
                    )
                }
            }

            SpecialGlyphKind.WINDOWS_PE -> {
                val winBlue = Color(0xFF1E88E5)
                val gap = w * 0.04f
                val pane = w * 0.21f
                val startX = w * 0.27f
                val startY = h * 0.26f
                drawRoundRect(winBlue, Offset(startX, startY), Size(pane, pane), CornerRadius(3f, 3f))
                drawRoundRect(winBlue, Offset(startX + pane + gap, startY), Size(pane, pane), CornerRadius(3f, 3f))
                drawRoundRect(winBlue, Offset(startX, startY + pane + gap), Size(pane, pane), CornerRadius(3f, 3f))
                drawRoundRect(winBlue, Offset(startX + pane + gap, startY + pane + gap), Size(pane, pane), CornerRadius(3f, 3f))
            }

            SpecialGlyphKind.ANDROID_PKG -> {
                val droidGreen = Color(0xFF26A69A)
                domePath.reset()
                domePath.moveTo(w * 0.26f, h * 0.58f)
                domePath.cubicTo(w * 0.26f, h * 0.28f, w * 0.74f, h * 0.28f, w * 0.74f, h * 0.58f)
                domePath.close()
                drawPath(domePath, color = droidGreen)
                drawLine(droidGreen, Offset(w * 0.36f, h * 0.36f), Offset(w * 0.29f, h * 0.24f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
                drawLine(droidGreen, Offset(w * 0.64f, h * 0.36f), Offset(w * 0.71f, h * 0.24f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
                drawCircle(Color.White, radius = w * 0.035f, center = Offset(w * 0.40f, h * 0.46f))
                drawCircle(Color.White, radius = w * 0.035f, center = Offset(w * 0.60f, h * 0.46f))
            }

            SpecialGlyphKind.ARCHIVE_BOX -> {
                val crateColor = Color(0xFFD97724)
                drawRoundRect(
                    color = crateColor,
                    topLeft = Offset(w * 0.24f, h * 0.26f),
                    size = Size(w * 0.52f, h * 0.46f),
                    cornerRadius = CornerRadius(w * 0.10f, w * 0.10f)
                )
                for (t in 0..3) {
                    val xOff = if (t % 2 == 0) 0.46f else 0.50f
                    drawRect(
                        color = sandHighlight,
                        topLeft = Offset(w * xOff, h * (0.30f + t * 0.09f)),
                        size = Size(w * 0.06f, h * 0.06f)
                    )
                }
            }

            SpecialGlyphKind.NONE -> Unit
        }

        if (isStarred) {
            drawCircle(Color(0xFFF5B400), radius = w * 0.11f, center = Offset(w * 0.84f, h * 0.18f))
        }
    }
}
