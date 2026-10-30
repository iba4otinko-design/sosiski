package com.example

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioPlayerManager
import com.example.data.local.BeachDatabase
import com.example.data.local.PreferencesRepository
import com.example.data.model.BeachFileItem
import com.example.data.model.ClipboardMode
import com.example.data.model.ConflictDecision
import com.example.data.model.ConflictResolution
import com.example.data.model.DrawerCategoryType
import com.example.data.model.FileCategory
import com.example.data.model.InstalledAppInfo
import com.example.data.model.MainDestination
import com.example.data.model.MoveDestinationOption
import com.example.data.model.RepeatPlayMode
import com.example.data.model.StorageRoot
import com.example.data.model.SupportedArchiveFormat
import com.example.data.repository.AppLauncherAndApkManager
import com.example.data.repository.ArchiveRepository
import com.example.data.repository.CloudSyncRepository
import com.example.data.repository.FileLaunchOutcome
import com.example.data.repository.FileOperationsManager
import com.example.data.repository.FileStorageRepository
import com.example.ui.icons.PeIconExtractor
import com.example.ui.icons.SmartIconLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: BeachDatabase
    private lateinit var fileRepository: FileStorageRepository
    private lateinit var fileOpsManager: FileOperationsManager
    private lateinit var archiveRepository: ArchiveRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, BeachDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fileRepository = FileStorageRepository(context, database.metadataDao())
        fileOpsManager = FileOperationsManager(context, database.metadataDao())
        archiveRepository = ArchiveRepository(context)
        SmartIconLoader.clearAll()
    }

    @After
    fun tearDown() {
        database.close()
        SmartIconLoader.clearAll()
    }

    @Test
    fun appNameAndPrimaryTabsIncludeDownloads() {
        val appName = context.getString(R.string.app_name)
        assertEquals("БоброПляж", appName)
        assertEquals("Завантаження", context.getString(R.string.nav_downloads))

        val primaryTabs = MainDestination.entries.filter { it.isPrimaryTab }
        assertEquals(
            listOf(
                MainDestination.BEACH,
                MainDestination.SHELLS,
                MainDestination.DOWNLOADS,
                MainDestination.DEVICE
            ),
            primaryTabs
        )
    }

    @Test
    fun workspaceDownloadsDrawerCategoriesAndProvidersWorkCleanly(): Unit = runBlocking {
        // 1. Verify legacy fake demo files are automatically purged and NO fake files are created
        File(context.filesDir, ".demo_purged_v2").delete()
        val legacyDownloadFake = File(fileRepository.appDownloadsDir.apply { mkdirs() }, "довідник_завантажень_бобра.txt").apply {
            writeText("legacy fake")
        }
        val legacyCoveFake = File(fileRepository.beaverCoveDir.apply { mkdirs() }, "креслення_греблі.txt").apply {
            writeText("legacy fake")
        }

        fileRepository.ensureWorkspaceInitialized()
        assertFalse("Legacy demo file in Downloads must be purged", legacyDownloadFake.exists())
        assertFalse("Legacy demo file in Beaver Cove must be purged", legacyCoveFake.exists())

        val initialMetaMap = fileRepository.observeAllMeta().first().associateBy { it.fileKey }
        val coveRoot = StorageRoot.BeaverCove()
        val initialCoveItems = fileRepository.listDirectory(
            coveRoot,
            fileRepository.rootPathFor(coveRoot),
            initialMetaMap
        )
        assertTrue("Clean workspace must contain zero imaginary/demo files", initialCoveItems.isEmpty())

        // 2. Create real user files in public Download, app Downloads, hidden .dotfile, and loose root /storage/emulated/0
        fileRepository.publicDownloadsDir.mkdirs()
        fileRepository.deviceSharedRootDir.mkdirs()
        val realReport = File(fileRepository.publicDownloadsDir, "real_report.txt").apply {
            writeText("Справжній документ користувача")
        }
        val hiddenEnv = File(fileRepository.publicDownloadsDir, ".hidden_config.env").apply {
            writeText("SECRET_MODE=1")
        }
        val realWav = File(fileRepository.appDownloadsDir, "real_wave.wav")
        writeMinimalWav(realWav)
        val looseRootFile = File(fileRepository.deviceSharedRootDir, "loose_root_readme.md").apply {
            writeText("# Корінь пам'яті")
        }

        try {
            val metaMap = fileRepository.observeAllMeta().first().associateBy { it.fileKey }

            // Downloads bottom tab ("Завантаження") — verify real files + hidden files toggle
            val dlCrumb = fileRepository.initialDownloadsBreadcrumb(emptyList())
            val dlVisibleOnly = fileRepository.listDownloadsBreadcrumb(
                dlCrumb,
                emptyList(),
                metaMap,
                showHidden = false
            )
            assertTrue(dlVisibleOnly.any { it.name == "real_report.txt" })
            assertTrue(dlVisibleOnly.any { it.name == "real_wave.wav" })
            assertFalse(dlVisibleOnly.any { it.name == ".hidden_config.env" })
            assertFalse(dlVisibleOnly.any { it.name == "довідник_завантажень_бобра.txt" })

            val dlWithHidden = fileRepository.listDownloadsBreadcrumb(
                dlCrumb,
                emptyList(),
                metaMap,
                showHidden = true
            )
            assertTrue("Hidden .dot file must appear when showHidden = true", dlWithHidden.any { it.name == ".hidden_config.env" })

            // Device root ("Пристрій") — verify loose top-level files on /storage/emulated/0 are shown alongside folders
            val devCrumb = fileRepository.initialDeviceBreadcrumb()
            val devItems = fileRepository.listDeviceBreadcrumb(devCrumb, emptyList(), metaMap, showHidden = false)
            assertTrue("Loose root file on shared storage must be visible", devItems.any { it.name == "loose_root_readme.md" })

            // 3. Left Drawer non-duplicated categories (Recent, Audio, Documents, Starred, Trash + Restore)
            val audioItems = fileRepository.listDrawerCategoryItems(
                DrawerCategoryType.AUDIO,
                emptyList(),
                metaMap
            )
            assertTrue("Expected real wav in Audio category", audioItems.any { it.name == "real_wave.wav" })

            val docItems = fileRepository.listDrawerCategoryItems(
                DrawerCategoryType.DOCUMENTS,
                emptyList(),
                metaMap
            )
            assertTrue("Expected real document in Drawer Documents", docItems.any { it.name == "real_report.txt" })

            // Move one item to Trash and restore it via Drawer Trash workflow
            val sampleDoc = docItems.first { it.name == "real_report.txt" }
            fileRepository.categorizeByGesture(sampleDoc, targetCategory = null, star = false, trash = true)
            val afterTrashMeta = fileRepository.observeAllMeta().first().associateBy { it.fileKey }
            val trashItems = fileRepository.listDrawerCategoryItems(
                DrawerCategoryType.TRASH,
                emptyList(),
                afterTrashMeta
            )
            assertTrue(trashItems.any { it.key == sampleDoc.key })

            fileRepository.restoreFromTrash(trashItems.first { it.key == sampleDoc.key })
            val afterRestoreMeta = fileRepository.observeAllMeta().first().associateBy { it.fileKey }
            val trashAfterRestore = fileRepository.listDrawerCategoryItems(
                DrawerCategoryType.TRASH,
                emptyList(),
                afterRestoreMeta
            )
            assertFalse(trashAfterRestore.any { it.key == sampleDoc.key })
        } finally {
            realReport.delete()
            hiddenEnv.delete()
            realWav.delete()
            looseRootFile.delete()
        }

        // 4. Verify installed DocumentsProviders exclude bottom-bar duplicates and include external providers
        val spm = shadowOf(context.packageManager)

        fun buildMockDocProviderResolveInfo(
            pkg: String,
            authority: String,
            appLabel: String
        ): android.content.pm.ResolveInfo {
            val appInfo = ApplicationInfo().apply {
                packageName = pkg
                name = appLabel
                nonLocalizedLabel = appLabel
            }
            val provInfo = ProviderInfo().apply {
                packageName = pkg
                this.authority = authority
                name = "$pkg.DocProvider"
                applicationInfo = appInfo
                nonLocalizedLabel = appLabel
            }
            return android.content.pm.ResolveInfo().apply {
                providerInfo = provInfo
                nonLocalizedLabel = appLabel
            }
        }

        spm.addResolveInfoForIntent(
            android.content.Intent("android.content.action.DOCUMENTS_PROVIDER"),
            listOf(
                buildMockDocProviderResolveInfo(
                    "com.android.providers.downloads",
                    "com.android.providers.downloads.documents",
                    "Downloads"
                ),
                buildMockDocProviderResolveInfo(
                    "com.google.android.apps.docs",
                    "com.google.android.apps.docs.storage",
                    "Диск"
                ),
                buildMockDocProviderResolveInfo(
                    "com.termux",
                    "com.termux.documents",
                    "Termux"
                ),
                buildMockDocProviderResolveInfo(
                    "com.android.shell",
                    "com.android.shell.documents",
                    "Звіти про помилки"
                )
            )
        )

        val discovered = fileRepository.queryInstalledDocumentProviders(
            connectedFolders = emptyList(),
            freeDeviceBytes = 10L * 1024L * 1024L * 1024L
        )
        assertFalse(discovered.any { it.authority == "com.android.providers.downloads.documents" })
        assertTrue(discovered.any { it.authority == "com.google.android.apps.docs.storage" && it.title == "Диск" })
        assertTrue(discovered.any { it.authority == "com.termux.documents" && it.title == "Termux" })
        assertTrue(discovered.any { it.authority == "com.android.shell.documents" && it.title == "Звіти про помилки" })

        // 5. Verify CloudSyncRepository gracefully stays offline when cloud is not configured
        val prefsRepo = PreferencesRepository(context)
        val cloudRepo = CloudSyncRepository(context, fileRepository, prefsRepo, database.metadataDao())
        val syncResult = cloudRepo.performBackupNow()
        assertFalse(syncResult.isSuccess)
    }

    @Test
    fun totalCommanderClipboardFormatAndConflictPasteWorkCleanly(): Unit = runBlocking {
        val srcDir = File(context.cacheDir, "tc_src_${System.nanoTime()}").apply { mkdirs() }
        val dstDir = File(context.cacheDir, "tc_dst_${System.nanoTime()}").apply { mkdirs() }

        val modsFolder = File(srcDir, "Моди").apply { mkdirs() }
        val innerMod = File(modsFolder, "мод бобра.txt").apply { writeText("Привіт з греблі!") }
        val rootNote = File(srcDir, "нотатка з пробілами.txt").apply { writeText("Вміст 12345") }

        val folderItem = BeachFileItem(
            key = "file:${modsFolder.absolutePath}",
            name = "Моди",
            pathOrUri = modsFolder.absolutePath,
            parentPathOrUri = srcDir.absolutePath,
            isDirectory = true,
            sizeBytes = 0L,
            createdAtMillis = 1720065359000L,
            lastModifiedMillis = 1720065359000L,
            category = FileCategory.FOLDER,
            mimeType = "vnd.android.document/directory",
            isSafDocument = false
        )
        val fileItem = BeachFileItem(
            key = "file:${rootNote.absolutePath}",
            name = rootNote.name,
            pathOrUri = rootNote.absolutePath,
            parentPathOrUri = srcDir.absolutePath,
            isDirectory = false,
            sizeBytes = rootNote.length(),
            createdAtMillis = 1720065359000L,
            lastModifiedMillis = 1720065359000L,
            category = FileCategory.DOCUMENTS,
            mimeType = "text/plain",
            isSafDocument = false
        )

        val tcCopyText = fileOpsManager.copyListAsTextToSystemClipboard(
            items = listOf(folderItem, fileItem),
            mode = ClipboardMode.COPY,
            currentFolderDisplay = "/storage/emulated/0/Download"
        )
        val lines = tcCopyText.lines()
        assertEquals("Total Commander file list (copy)", lines[0])
        assertEquals("Total files: 2", lines[1])
        assertEquals("/storage/emulated/0/Download", lines[2])
        assertTrue(lines[3].startsWith("Моди/    0    "))
        assertTrue(lines[4].startsWith("нотатка з пробілами.txt    ${rootNote.length()}    "))

        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(tcCopyText, cm.primaryClip?.getItemAt(0)?.text?.toString())

        // Execute recursive COPY to dstDir
        val copySummary = fileOpsManager.executePasteOrMove(
            items = listOf(folderItem, fileItem),
            mode = ClipboardMode.COPY,
            isDestSaf = false,
            destFolderPathOrUri = dstDir.absolutePath,
            onProgress = { _, _, _ -> },
            onConflict = { _, _ -> ConflictDecision(ConflictResolution.OVERWRITE, true) }
        )
        assertEquals(2, copySummary.succeededCount)
        assertTrue(File(dstDir, "Моди/мод бобра.txt").exists())
        assertTrue(innerMod.exists()) // Source preserved on COPY

        // Execute second COPY with ConflictResolution.RENAME -> creates "(1)" copy
        val renameSummary = fileOpsManager.executePasteOrMove(
            items = listOf(fileItem),
            mode = ClipboardMode.COPY,
            isDestSaf = false,
            destFolderPathOrUri = dstDir.absolutePath,
            onProgress = { _, _, _ -> },
            onConflict = { _, _ -> ConflictDecision(ConflictResolution.RENAME, false) }
        )
        assertEquals(1, renameSummary.succeededCount)
        assertTrue(File(dstDir, "нотатка з пробілами (1).txt").exists())

        srcDir.deleteRecursively()
        dstDir.deleteRecursively()
    }

    @Test
    fun archiverZip7zTarGzPreviewPartialExtractAndZipSlipDefense(): Unit = runBlocking {
        val workDir = File(context.cacheDir, "arch_work_${System.nanoTime()}").apply { mkdirs() }
        val srcFolder = File(workDir, "Скарби").apply { mkdirs() }
        val innerTxt = File(srcFolder, "мапа_берега.txt").apply { writeText("Секретна бухта бобра 42") }
        val secondTxt = File(workDir, "мушля.txt").apply { writeText("Перлина") }

        val itemsToPack = listOf(
            BeachFileItem(
                key = "file:${srcFolder.absolutePath}",
                name = srcFolder.name,
                pathOrUri = srcFolder.absolutePath,
                parentPathOrUri = workDir.absolutePath,
                isDirectory = true,
                sizeBytes = 0L,
                createdAtMillis = srcFolder.lastModified(),
                lastModifiedMillis = srcFolder.lastModified(),
                category = FileCategory.FOLDER,
                mimeType = "vnd.android.document/directory",
                isSafDocument = false
            ),
            BeachFileItem(
                key = "file:${secondTxt.absolutePath}",
                name = secondTxt.name,
                pathOrUri = secondTxt.absolutePath,
                parentPathOrUri = workDir.absolutePath,
                isDirectory = false,
                sizeBytes = secondTxt.length(),
                createdAtMillis = secondTxt.lastModified(),
                lastModifiedMillis = secondTxt.lastModified(),
                category = FileCategory.DOCUMENTS,
                mimeType = "text/plain",
                isSafDocument = false
            )
        )

        for (fmt in listOf(SupportedArchiveFormat.ZIP, SupportedArchiveFormat.SEVEN_Z, SupportedArchiveFormat.TAR_GZ)) {
            val outDir = File(workDir, "out_${fmt.name}").apply { mkdirs() }
            val createdName = archiveRepository.createArchive(
                items = itemsToPack,
                isDestSaf = false,
                destDirPathOrUri = outDir.absolutePath,
                archiveBaseName = "тест_архів",
                format = fmt,
                onProgress = { _, _, _ -> }
            ).getOrThrow()

            val archFile = File(outDir, createdName)
            assertTrue("Expected archive ${archFile.name} to exist", archFile.exists() && archFile.length() > 0L)

            val archItem = BeachFileItem(
                key = "file:${archFile.absolutePath}",
                name = archFile.name,
                pathOrUri = archFile.absolutePath,
                parentPathOrUri = outDir.absolutePath,
                isDirectory = false,
                sizeBytes = archFile.length(),
                createdAtMillis = archFile.lastModified(),
                lastModifiedMillis = archFile.lastModified(),
                category = FileCategory.ARCHIVES_ANDROID,
                mimeType = "application/octet-stream",
                isSafDocument = false
            )

            // Virtual folder listing
            val allEntries = archiveRepository.listArchiveEntries(archItem, fmt)
            assertTrue(allEntries.any { it.entryPath == "Скарби/мапа_берега.txt" })

            // Single-entry preview extraction without unpacking full archive
            val mapEntry = allEntries.first { it.entryPath == "Скарби/мапа_берега.txt" }
            val previewCached = archiveRepository.extractSingleEntryToPreviewCache(archItem, fmt, mapEntry)
            assertNotNull(previewCached)
            assertEquals("Секретна бухта бобра 42", File(previewCached!!.pathOrUri).readText())

            // Partial extraction of ONLY мушля.txt
            val partialExtractDir = File(outDir, "unpacked_partial").apply { mkdirs() }
            val extractSummary = archiveRepository.extractArchive(
                archiveItem = archItem,
                format = fmt,
                isDestSaf = false,
                destDirPathOrUri = partialExtractDir.absolutePath,
                selectedPaths = setOf("мушля.txt"),
                onProgress = { _, _, _ -> },
                onConflict = { _, _ -> ConflictDecision(ConflictResolution.OVERWRITE, true) }
            )
            assertEquals(1, extractSummary.succeededCount)
            assertTrue(File(partialExtractDir, "мушля.txt").exists())
            assertFalse(File(partialExtractDir, "Скарби/мапа_берега.txt").exists())
        }

        // Strict Zip-Slip Path Traversal Protection Check
        assertThrows(SecurityException::class.java) {
            archiveRepository.resolveSafeExtractionTarget(workDir, "../../escaped_evil.txt")
        }
        assertThrows(SecurityException::class.java) {
            archiveRepository.sanitizeArchiveEntryPath("..\\..\\system\\evil.dll")
        }

        val maliciousZip = File(workDir, "malicious_slip.zip")
        ZipOutputStream(FileOutputStream(maliciousZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../outside_escape.txt"))
            zos.write("hacked".toByteArray())
            zos.closeEntry()
        }
        val maliciousItem = BeachFileItem(
            key = "file:${maliciousZip.absolutePath}",
            name = maliciousZip.name,
            pathOrUri = maliciousZip.absolutePath,
            parentPathOrUri = workDir.absolutePath,
            isDirectory = false,
            sizeBytes = maliciousZip.length(),
            createdAtMillis = maliciousZip.lastModified(),
            lastModifiedMillis = maliciousZip.lastModified(),
            category = FileCategory.ARCHIVES_ANDROID,
            mimeType = "application/zip",
            isSafDocument = false
        )
        val safeTargetDir = File(workDir, "safe_box").apply { mkdirs() }
        val slipSummary = archiveRepository.extractArchive(
            archiveItem = maliciousItem,
            format = SupportedArchiveFormat.ZIP,
            isDestSaf = false,
            destDirPathOrUri = safeTargetDir.absolutePath,
            onProgress = { _, _, _ -> },
            onConflict = { _, _ -> ConflictDecision(ConflictResolution.OVERWRITE, true) }
        )
        assertEquals(0, slipSummary.succeededCount)
        assertTrue(slipSummary.failedCount > 0)
        assertFalse(File(workDir.parentFile, "outside_escape.txt").exists())

        workDir.deleteRecursively()
    }

    @Test
    fun smartIconsExtractPeExeIconMcpackIconAndInvalidateCache(): Unit = runBlocking {
        // 1. Verify .mcpack embedded pack_icon.png extraction & caching using a temporary test archive
        val mcpackFile = File(context.cacheDir, "test_pack_${System.nanoTime()}.mcpack")
        ZipOutputStream(FileOutputStream(mcpackFile)).use { zos ->
            zos.putNextEntry(ZipEntry("pack_icon.png"))
            val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.CYAN)
            }
            bmp.compress(Bitmap.CompressFormat.PNG, 100, zos)
            zos.closeEntry()
        }

        try {
            val mcpackItem = BeachFileItem(
                key = "file:${mcpackFile.absolutePath}",
                name = mcpackFile.name,
                pathOrUri = mcpackFile.absolutePath,
                parentPathOrUri = context.cacheDir.absolutePath,
                isDirectory = false,
                sizeBytes = mcpackFile.length(),
                createdAtMillis = mcpackFile.lastModified(),
                lastModifiedMillis = mcpackFile.lastModified(),
                category = FileCategory.ARCHIVES_ANDROID,
                mimeType = "application/octet-stream",
                isSafDocument = false
            )
            val loadedMcIcon = SmartIconLoader.loadSmartBitmap(context, mcpackItem)
            assertNotNull("Expected embedded pack_icon.png bitmap from .mcpack", loadedMcIcon)
            val cacheKey = SmartIconLoader.buildCacheKey(mcpackItem.pathOrUri, mcpackItem.lastModifiedMillis, mcpackItem.sizeBytes)
            assertNotNull(SmartIconLoader.getCached(cacheKey))

            SmartIconLoader.invalidatePath(mcpackItem.pathOrUri)
            assertNull("Cache must clear after invalidatePath", SmartIconLoader.getCached(cacheKey))
        } finally {
            mcpackFile.delete()
        }

        // 2. Verify pure-Kotlin PE32 (.exe) RT_GROUP_ICON + RT_ICON binary extraction (zero execution)
        val syntheticExeBytes = buildMinimalPe32WithIcon()
        val extractedExeBmp = PeIconExtractor.extractFromBytes(syntheticExeBytes)
        assertNotNull("Expected PeIconExtractor to decode embedded 4x4 32-bit DIB icon", extractedExeBmp)
        assertEquals(4, extractedExeBmp!!.width)
        assertEquals(4, extractedExeBmp.height)
    }

    @Test
    fun audioPlayerQueueTransportRepeatAndShuffleWorkCleanly(): Unit = runBlocking {
        val wavFile = File(context.cacheDir, "track_one_${System.nanoTime()}.wav")
        val secondWav = File(context.cacheDir, "track_two_${System.nanoTime()}.wav")
        writeMinimalWav(wavFile)
        writeMinimalWav(secondWav)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val item1 = BeachFileItem(
                key = "file:${wavFile.absolutePath}",
                name = wavFile.name,
                pathOrUri = wavFile.absolutePath,
                parentPathOrUri = context.cacheDir.absolutePath,
                isDirectory = false,
                sizeBytes = wavFile.length(),
                createdAtMillis = wavFile.lastModified(),
                lastModifiedMillis = wavFile.lastModified(),
                category = FileCategory.AUDIO,
                mimeType = "audio/wav",
                isSafDocument = false
            )
            val item2 = item1.copy(
                key = "file:${secondWav.absolutePath}",
                name = secondWav.name,
                pathOrUri = secondWav.absolutePath
            )

            val playerManager = AudioPlayerManager(context, scope, connectToService = false)

            playerManager.playFileWithContextQueue(item1, listOf(item1, item2))
            var attempts = 0
            while (!playerManager.state.value.isActive && attempts < 40) {
                delay(25L)
                attempts++
            }

            assertTrue(playerManager.state.value.isActive)
            assertEquals(2, playerManager.state.value.queue.size)
            assertEquals(0, playerManager.state.value.currentIndex)

            playerManager.skipToNext()
            assertEquals(1, playerManager.state.value.currentIndex)

            playerManager.skipToPrevious()
            assertEquals(0, playerManager.state.value.currentIndex)

            playerManager.cycleRepeatMode()
            assertEquals(RepeatPlayMode.ALL, playerManager.state.value.repeatMode)
            playerManager.cycleRepeatMode()
            assertEquals(RepeatPlayMode.ONE, playerManager.state.value.repeatMode)
            playerManager.cycleRepeatMode()
            assertEquals(RepeatPlayMode.OFF, playerManager.state.value.repeatMode)

            playerManager.toggleShuffle()
            assertTrue(playerManager.state.value.shuffleEnabled)

            playerManager.removeFromQueue(0)
            assertEquals(1, playerManager.state.value.queue.size)

            playerManager.stopAndClosePlayer()
            assertFalse(playerManager.state.value.isActive)
        } finally {
            wavFile.delete()
            secondWav.delete()
            scope.cancel()
        }
    }

    @Test
    fun persistentRoomCacheAntiPhantomPurgeGalleryVideosAndApkLauncherWorkCleanly(): Unit = runBlocking {
        fileRepository.ensureWorkspaceInitialized()
        val coveRoot = StorageRoot.BeaverCove()
        val coveDir = fileRepository.beaverCoveDir.apply { mkdirs() }

        val realPhoto = File(coveDir, "sunset_shell.png").apply {
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
            FileOutputStream(this).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val realVideo = File(coveDir, "waves_clip.mp4").apply {
            writeBytes(ByteArray(128) { it.toByte() })
        }
        val soonDeletedPhantom = File(coveDir, "ephemeral_ghost.txt").apply {
            writeText("Цей файл буде видалено ззовні")
        }

        val apkExportDir = File(context.cacheDir, "apk_export_${System.nanoTime()}").apply { mkdirs() }
        val fakeInstalledApk = File(context.cacheDir, "mock_base_${System.nanoTime()}.apk").apply {
            ZipOutputStream(FileOutputStream(this)).use { zos ->
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write("manifest".toByteArray())
                zos.closeEntry()
            }
        }

        try {
            val metaMap = fileRepository.getAllMetaSnapshot()

            // 1. Gallery must return BOTH photos (IMAGES) and videos (VIDEO) together
            val galleryMedia = fileRepository.queryGalleryMediaItems(metaMap, showHidden = false)
            assertTrue("Gallery must include photo", galleryMedia.any { it.name == "sunset_shell.png" && it.category == FileCategory.IMAGES })
            assertTrue("Gallery must include video", galleryMedia.any { it.name == "waves_clip.mp4" && it.category == FileCategory.VIDEO })

            // 2. Scan folder and persist into Room L2 cache
            val scopeKey = fileRepository.beachScopeKey(coveRoot, coveDir.absolutePath, showHidden = false)
            val freshItems = fileRepository.listDirectory(coveRoot, coveDir.absolutePath, metaMap, showHidden = false)
            assertEquals(3, freshItems.size)
            val (_, didChangeFirst) = fileRepository.commitReconciledScope(scopeKey, freshItems, coveDir.lastModified())
            assertTrue(didChangeFirst)

            // Re-committing identical list should report didChange == false (prevents unnecessary UI recompositions)
            val (_, didChangeSecond) = fileRepository.commitReconciledScope(scopeKey, freshItems, coveDir.lastModified())
            assertFalse(didChangeSecond)

            // 3. Simulate external file deletion while app was closed, then cold-start a new repository over the same Room DB
            assertTrue(soonDeletedPhantom.delete())
            val coldStartRepo = FileStorageRepository(context, database.metadataDao())
            val hydratedFromRoom = coldStartRepo.getCachedScopeWithAntiPhantom(scopeKey, metaMap)
            assertNotNull("Expected persisted scope from Room", hydratedFromRoom)
            assertTrue(hydratedFromRoom!!.any { it.name == "sunset_shell.png" })
            assertTrue(hydratedFromRoom.any { it.name == "waves_clip.mp4" })
            assertFalse(
                "Externally deleted file must NEVER appear as a phantom entry",
                hydratedFromRoom.any { it.name == "ephemeral_ghost.txt" }
            )
            // Verify phantom row was also purged from Room SQLite table
            val remainingDbRows = database.metadataDao().getCachedEntriesForScope(scopeKey)
            assertFalse(remainingDbRows.any { it.name == "ephemeral_ghost.txt" })

            // 4. Verify AppLauncherAndApkManager: phantom detection + Total Commander APK staging + folder copy
            val apkManager = AppLauncherAndApkManager(context)
            val ghostItem = freshItems.first { it.name == "ephemeral_ghost.txt" }
            val launchGhostOutcome = apkManager.openOrInstallFile(ghostItem)
            assertEquals(FileLaunchOutcome.FileMissing, launchGhostOutcome)

            val mockInstalledApp = InstalledAppInfo(
                packageName = "com.example.mocktool",
                appName = "Бобровий Інструмент",
                versionName = "2.4",
                apkFilePath = fakeInstalledApk.absolutePath,
                sizeBytes = fakeInstalledApk.length(),
                lastUpdatedMillis = fakeInstalledApk.lastModified(),
                isSystemApp = false
            )
            assertEquals("Бобровий Інструмент_v2.4.apk", mockInstalledApp.apkExportFileName)

            val stagedForTcClipboard = apkManager.exportAppsToBeachFileItems(listOf(mockInstalledApp))
            assertEquals(1, stagedForTcClipboard.size)
            assertTrue(File(stagedForTcClipboard.first().pathOrUri).exists())

            val copiedCount = apkManager.copyInstalledAppsToDestination(
                apps = listOf(mockInstalledApp),
                destination = MoveDestinationOption(
                    id = "local_test_dest",
                    title = "Тестова папка",
                    subtitle = apkExportDir.absolutePath,
                    isSaf = false,
                    targetPathOrUri = apkExportDir.absolutePath
                )
            )
            assertEquals(1, copiedCount)
            assertTrue(File(apkExportDir, "Бобровий Інструмент_v2.4.apk").exists())
        } finally {
            realPhoto.delete()
            realVideo.delete()
            soonDeletedPhantom.delete()
            fakeInstalledApk.delete()
            apkExportDir.deleteRecursively()
        }
    }

    @Test
    fun totalCommanderSmoothListPriorityCancellationDedupAndThousandFilesWorkCleanly() = runBlocking {
        SmartIconLoader.clearAll()
        val bigDir = File(context.filesDir, "tc_thousand_files_${System.nanoTime()}").apply { mkdirs() }

        try {
            // 1. Create 2,500 files with diverse extensions + 20 subdirectories (with nested files)
            val extensions = arrayOf("jpg", "tar.gz", "apk", "mp4", "txt")
            for (sub in 0 until 20) {
                val subDir = File(bigDir, "folder_%02d".format(sub)).apply { mkdirs() }
                File(subDir, "nested_a.txt").writeText("a")
                File(subDir, "nested_b.txt").writeText("b")
            }
            for (i in 0 until 2500) {
                val ext = extensions[i % extensions.size]
                File(bigDir, "item_%04d.$ext".format(i)).writeBytes(ByteArray((i % 32) + 4) { 0x2A })
            }

            val metaMap = fileRepository.getAllMetaSnapshot()
            val root = StorageRoot.BeaverCove()
            val scopeKey = fileRepository.beachScopeKey(root, bigDir.absolutePath, showHidden = false)

            // List 2,520 entries and reconcile in-memory without blocking on icons or SQLite writes
            val listed = fileRepository.listDirectory(root, bigDir.absolutePath, metaMap, showHidden = false)
            assertEquals("All 2,500 files + 20 folders must list immediately", 2520, listed.size)

            val (reconciled, didChange) = fileRepository.reconcileMemoryScopeFast(scopeKey, listed)
            assertTrue(didChange)
            assertEquals(2520, reconciled.size)

            val sampleTarGz = reconciled.first { it.name.endsWith(".tar.gz") }
            assertEquals("TAR.GZ", sampleTarGz.extensionLabel)
            assertEquals(sampleTarGz.name.lowercase(), sampleTarGz.lowerName)

            val sampleApk = reconciled.first { it.name.endsWith(".apk") }
            assertEquals("APK", sampleApk.extensionLabel)
            assertTrue("APK must be eligible for async custom icon extraction", SmartIconLoader.canAttemptCustomIcon(sampleApk))

            val sampleTxt = reconciled.first { it.name.endsWith(".txt") }
            assertEquals("TXT", sampleTxt.extensionLabel)
            assertFalse("Plain text files should skip async icon worker scheduling", SmartIconLoader.canAttemptCustomIcon(sampleTxt))

            val sampleFolder = reconciled.first { it.isDirectory }
            assertEquals("", sampleFolder.extensionLabel)
            assertEquals(
                "Large directories (>64 items) must skip nested subfolder readdir syscalls for O(N) speed",
                -1,
                sampleFolder.childCount
            )

            // 2. Verify LIFO viewport priority + off-screen cancellation during fast scroll
            SmartIconLoader.clearAll()
            val workersBusyCount = AtomicInteger(0)
            val allFourWorkersOccupied = CompletableDeferred<Unit>()
            val releaseWorkersGate = CompletableDeferred<Unit>()

            val blockerJobs = (0 until 4).map { idx ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    SmartIconLoader.loadPrioritizedForTest(
                        cacheKey = "blocker_worker_$idx",
                        recordNegativeOnNull = false
                    ) {
                        if (workersBusyCount.incrementAndGet() == 4) {
                            allFourWorkersOccupied.complete(Unit)
                        }
                        releaseWorkersGate.await()
                        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    }
                }
            }

            allFourWorkersOccupied.await()

            val startedOrder = Collections.synchronizedList(mutableListOf<Int>())
            // Enqueue 20 scroll items synchronously in order 0..19 (so 19 is at LIFO head, 0 at tail)
            val scrollJobs = (0 until 20).map { idx ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    SmartIconLoader.loadPrioritizedForTest(
                        cacheKey = "scroll_row_$idx",
                        recordNegativeOnNull = false
                    ) {
                        startedOrder.add(idx)
                        delay(25L)
                        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(Color.CYAN)
                        }
                    }
                }
            }

            // Simulate user flinging past rows 0..13 so they leave the viewport before workers free up
            for (cancelledIdx in 0..13) {
                scrollJobs[cancelledIdx].cancelAndJoin()
            }

            // Release the 4 workers to process remaining visible viewport items (19 down to 14)
            releaseWorkersGate.complete(Unit)
            blockerJobs.forEach { it.join() }
            val visibleBitmaps = (14..19).map { scrollJobs[it].await() }

            assertTrue("All visible items 14..19 must decode non-null icons", visibleBitmaps.all { it != null })
            assertTrue(
                "Cancelled off-screen items 0..13 must be evicted from queue (>=14 evicted)",
                SmartIconLoader.evictedQueueTaskCount() >= 14
            )
            for (cancelledIdx in 0..13) {
                assertFalse(
                    "Cancelled off-screen row $cancelledIdx must NEVER execute decoding",
                    startedOrder.contains(cancelledIdx)
                )
            }
            assertEquals("Exactly 6 visible scroll items (14..19) should execute", 6, startedOrder.size)
            val posNewest19 = startedOrder.indexOf(19)
            val posOlder14 = startedOrder.indexOf(14)
            assertTrue(
                "LIFO priority must start newest visible item (19) before older queued item (14), order=$startedOrder",
                posNewest19 in 0 until posOlder14
            )

            // 3. Verify in-flight deduplication & 0ms cached reuse when scrolling back
            val dedupRuns = AtomicInteger(0)
            val dedupKey = "shared_apk_icon_key"
            val concurrentCallers = (0 until 12).map {
                async(Dispatchers.Default) {
                    SmartIconLoader.loadPrioritizedForTest(cacheKey = dedupKey) {
                        dedupRuns.incrementAndGet()
                        delay(35L)
                        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(Color.GREEN)
                        }
                    }
                }
            }
            val dedupResults = concurrentCallers.awaitAll()
            assertTrue(dedupResults.all { it != null })
            assertEquals("12 concurrent requests for the same file must coalesce into 1 decode", 1, dedupRuns.get())

            // Scroll-back reuse: synchronous L1 cache hit + 0 extra decodes
            assertNotNull("Icon must be immediately available in L1 cache on scroll-back", SmartIconLoader.getCached(dedupKey))
            val cachedAgain = SmartIconLoader.loadPrioritizedForTest(cacheKey = dedupKey) {
                dedupRuns.incrementAndGet()
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            }
            assertNotNull(cachedAgain)
            assertEquals("Scrolling back to cached item must NOT re-decode", 1, dedupRuns.get())

            // Negative cache reuse: corrupt/iconless file should not re-run extractor on scroll-back
            val negativeRuns = AtomicInteger(0)
            val badKey = "corrupt_media_without_thumb"
            val firstNull = SmartIconLoader.loadPrioritizedForTest(cacheKey = badKey, recordNegativeOnNull = true) {
                negativeRuns.incrementAndGet()
                null
            }
            assertNull(firstNull)
            assertTrue(SmartIconLoader.isKnownNegative(badKey))
            val secondNull = SmartIconLoader.loadPrioritizedForTest(cacheKey = badKey, recordNegativeOnNull = true) {
                negativeRuns.incrementAndGet()
                null
            }
            assertNull(secondNull)
            assertEquals("Negative cache must prevent repeated failed decode attempts on scroll-back", 1, negativeRuns.get())
        } finally {
            bigDir.deleteRecursively()
            SmartIconLoader.clearAll()
        }
    }

    private fun writeMinimalWav(target: File) {
        val sampleRate = 8000
        val numSamples = 800
        val dataSize = numSamples * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(1) // Mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (i in 0 until numSamples) {
            buf.putShort(0)
        }
        target.writeBytes(buf.array())
    }

    /**
     * Constructs a self-contained valid PE32 header + .rsrc section containing
     * RT_GROUP_ICON (14) and RT_ICON (3) with a 4x4 32-bit BGRA DIB icon.
     */
    private fun buildMinimalPe32WithIcon(): ByteArray {
        val totalSize = 2048
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // 1. DOS Header at 0x00
        buf.putShort(0, 0x5A4D.toShort()) // 'MZ'
        val peOffset = 0x80
        buf.putInt(0x3C, peOffset)

        // 2. NT Headers at 0x80
        buf.putInt(peOffset, 0x00004550) // "PE\0\0"
        buf.putShort(peOffset + 6, 1) // NumberOfSections = 1
        val optHeaderSize = 224 // Standard PE32 optional header size
        buf.putShort(peOffset + 20, optHeaderSize.toShort())

        // 3. Optional Header (PE32 = 0x010B) at 0x98
        val optStart = peOffset + 24
        buf.putShort(optStart, 0x010B.toShort())
        val rsrcRva = 0x1000
        val rsrcRawOffset = 0x200
        val rsrcRawSize = 0x400
        // DataDirectory[2] (Resource Table) is at optStart + 96 + 16
        buf.putInt(optStart + 96 + 16, rsrcRva)
        buf.putInt(optStart + 96 + 20, rsrcRawSize)

        // 4. Section Header (.rsrc) at optStart + optHeaderSize
        val secStart = optStart + optHeaderSize
        val secName = ".rsrc".toByteArray(Charsets.US_ASCII)
        for (i in secName.indices) buf.put(secStart + i, secName[i])
        buf.putInt(secStart + 8, rsrcRawSize) // VirtualSize
        buf.putInt(secStart + 12, rsrcRva) // VirtualAddress
        buf.putInt(secStart + 16, rsrcRawSize) // SizeOfRawData
        buf.putInt(secStart + 20, rsrcRawOffset) // PointerToRawData

        // 5. .rsrc Directory Tree at rsrcRawOffset (0x200)
        // Root Dir (0x00 in .rsrc): 2 ID entries -> RT_ICON (3) at 0x28, RT_GROUP_ICON (14) at 0x60
        val r = rsrcRawOffset
        buf.putShort(r + 14, 2) // 2 ID entries
        buf.putInt(r + 16, 3) // ID = RT_ICON
        buf.putInt(r + 20, 0x80000000.toInt() or 0x28)
        buf.putInt(r + 24, 14) // ID = RT_GROUP_ICON
        buf.putInt(r + 28, 0x80000000.toInt() or 0x60)

        // Level 2 for RT_ICON at r + 0x28 -> 1 ID entry (Icon ID = 1) -> Level 3 at 0x40
        buf.putShort(r + 0x28 + 14, 1)
        buf.putInt(r + 0x28 + 16, 1)
        buf.putInt(r + 0x28 + 20, 0x80000000.toInt() or 0x40)

        // Level 3 for RT_ICON at r + 0x40 -> 1 lang entry -> DataEntry at 0x98
        buf.putShort(r + 0x40 + 14, 1)
        buf.putInt(r + 0x40 + 16, 1033)
        buf.putInt(r + 0x40 + 20, 0x98)

        // Level 2 for RT_GROUP_ICON at r + 0x60 -> 1 ID entry (Group ID = 100) -> Level 3 at 0x78
        buf.putShort(r + 0x60 + 14, 1)
        buf.putInt(r + 0x60 + 16, 100)
        buf.putInt(r + 0x60 + 20, 0x80000000.toInt() or 0x78)

        // Level 3 for RT_GROUP_ICON at r + 0x78 -> 1 lang entry -> DataEntry at 0xA8
        buf.putShort(r + 0x78 + 14, 1)
        buf.putInt(r + 0x78 + 16, 1033)
        buf.putInt(r + 0x78 + 20, 0xA8)

        // Build 4x4 32-bit DIB payload at r + 0x100 (RVA = 0x1100)
        // BITMAPINFOHEADER (40 bytes) + 16 BGRA pixels (64 bytes) + 4 AND mask rows (16 bytes) = 120 bytes
        val dibOffset = r + 0x100
        val dibSize = 120
        buf.putInt(dibOffset + 0, 40) // biSize
        buf.putInt(dibOffset + 4, 4) // biWidth = 4
        buf.putInt(dibOffset + 8, 8) // biHeight = 8 (2 * 4 for XOR + AND)
        buf.putShort(dibOffset + 12, 1) // biPlanes = 1
        buf.putShort(dibOffset + 14, 32) // biBitCount = 32
        for (px in 0 until 16) {
            val pOff = dibOffset + 40 + px * 4
            buf.put(pOff + 0, 0xCC.toByte()) // Blue
            buf.put(pOff + 1, 0x88.toByte()) // Green
            buf.put(pOff + 2, 0x22.toByte()) // Red
            buf.put(pOff + 3, 0xFF.toByte()) // Alpha
        }

        // Build GRPICONDIR + 1 GRPICONDIRENTRY (6 + 14 = 20 bytes) at r + 0x1A0 (RVA = 0x11A0)
        val grpOffset = r + 0x1A0
        val grpSize = 20
        buf.putShort(grpOffset + 0, 0) // idReserved
        buf.putShort(grpOffset + 2, 1) // idType = 1 (Icon)
        buf.putShort(grpOffset + 4, 1) // idCount = 1
        buf.put(grpOffset + 6, 4) // bWidth = 4
        buf.put(grpOffset + 7, 4) // bHeight = 4
        buf.putShort(grpOffset + 10, 1) // wPlanes = 1
        buf.putShort(grpOffset + 12, 32) // wBitCount = 32
        buf.putInt(grpOffset + 14, dibSize) // dwBytesInRes
        buf.putShort(grpOffset + 18, 1) // nID = 1 (matches RT_ICON ID 1)

        // DataEntry for RT_ICON (at r + 0x98)
        buf.putInt(r + 0x98 + 0, rsrcRva + 0x100)
        buf.putInt(r + 0x98 + 4, dibSize)

        // DataEntry for RT_GROUP_ICON (at r + 0xA8)
        buf.putInt(r + 0xA8 + 0, rsrcRva + 0x1A0)
        buf.putInt(r + 0xA8 + 4, grpSize)

        return buf.array()
    }
}
