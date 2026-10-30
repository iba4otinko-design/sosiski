package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sand_item_meta")
data class SandItemMetaEntity(
    @PrimaryKey val fileKey: String,
    val sandX: Float,
    val sandY: Float,
    val customCategory: String? = null,
    val isStarred: Boolean = false,
    val isTrashed: Boolean = false,
    val lastBackedUpAt: Long = 0L
)

@Entity(tableName = "connected_saf_folders")
data class ConnectedFolderEntity(
    @PrimaryKey val treeUri: String,
    val displayName: String,
    val addedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "cloud_backup_logs")
data class BackupLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val providerName: String,
    val syncedFilesCount: Int,
    val syncedBytes: Long,
    val message: String,
    val isSuccess: Boolean
)

/**
 * Persistent directory/gallery index cache entry so folders, downloads, device storage,
 * and gallery media render in 0ms without re-scanning from scratch, while supporting
 * strict delta reconciliation and immediate phantom-file removal.
 */
@Entity(
    tableName = "cached_file_entries",
    primaryKeys = ["scopeKey", "fileKey"],
    indices = [
        Index(value = ["scopeKey"]),
        Index(value = ["pathOrUri"])
    ]
)
data class CachedFileEntryEntity(
    val scopeKey: String,
    val fileKey: String,
    val name: String,
    val pathOrUri: String,
    val parentPathOrUri: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val lastModifiedMillis: Long,
    val categoryName: String,
    val mimeType: String,
    val isSafDocument: Boolean,
    val safTreeUri: String,
    val requiresSafGrantDocId: String?,
    val childCount: Int,
    val durationMillis: Long,
    val sortOrder: Int
)

@Entity(tableName = "cached_dir_stamps")
data class CachedDirStampEntity(
    @PrimaryKey val scopeKey: String,
    val dirLastModified: Long,
    val entryCount: Int,
    val checkedAtMillis: Long
)

@Dao
interface BeachMetadataDao {
    @Query("SELECT * FROM sand_item_meta")
    fun observeAllMeta(): Flow<List<SandItemMetaEntity>>

    @Query("SELECT * FROM sand_item_meta")
    suspend fun getAllMetaSnapshot(): List<SandItemMetaEntity>

    @Query("SELECT * FROM sand_item_meta WHERE fileKey = :key LIMIT 1")
    suspend fun getMeta(key: String): SandItemMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(entity: SandItemMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllMeta(entities: List<SandItemMetaEntity>)

    @Query("DELETE FROM sand_item_meta WHERE fileKey = :key")
    suspend fun deleteMeta(key: String)

    @Query("SELECT * FROM sand_item_meta WHERE isTrashed = 1")
    suspend fun getTrashedMeta(): List<SandItemMetaEntity>

    @Query("SELECT * FROM connected_saf_folders ORDER BY addedAtMillis DESC")
    fun observeConnectedFolders(): Flow<List<ConnectedFolderEntity>>

    @Query("SELECT * FROM connected_saf_folders ORDER BY addedAtMillis DESC")
    suspend fun getConnectedFoldersSnapshot(): List<ConnectedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConnectedFolder(folder: ConnectedFolderEntity)

    @Query("DELETE FROM connected_saf_folders WHERE treeUri = :treeUri")
    suspend fun removeConnectedFolder(treeUri: String)

    @Query("SELECT * FROM cloud_backup_logs ORDER BY timestampMillis DESC LIMIT 20")
    fun observeBackupLogs(): Flow<List<BackupLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackupLog(log: BackupLogEntity)

    // --- Smart Persistent File Index Cache & Anti-Phantom Operations ---

    @Query("SELECT * FROM cached_file_entries WHERE scopeKey = :scopeKey ORDER BY sortOrder ASC")
    suspend fun getCachedEntriesForScope(scopeKey: String): List<CachedFileEntryEntity>

    @Query("SELECT * FROM cached_file_entries WHERE scopeKey = :scopeKey ORDER BY sortOrder ASC")
    fun observeCachedEntriesForScope(scopeKey: String): Flow<List<CachedFileEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedEntries(entries: List<CachedFileEntryEntity>)

    @Query("DELETE FROM cached_file_entries WHERE scopeKey = :scopeKey")
    suspend fun clearCachedScope(scopeKey: String)

    @Query("DELETE FROM cached_file_entries WHERE fileKey = :fileKey OR pathOrUri = :pathOrUri")
    suspend fun purgePhantomEverywhere(fileKey: String, pathOrUri: String)

    @Query("DELETE FROM cached_file_entries WHERE pathOrUri LIKE :pathPrefix || '%'")
    suspend fun purgePhantomSubtree(pathPrefix: String)

    @Query("SELECT * FROM cached_dir_stamps WHERE scopeKey = :scopeKey LIMIT 1")
    suspend fun getDirStamp(scopeKey: String): CachedDirStampEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDirStamp(stamp: CachedDirStampEntity)

    @Query("DELETE FROM cached_dir_stamps WHERE scopeKey = :scopeKey")
    suspend fun deleteDirStamp(scopeKey: String)

    @Transaction
    suspend fun replaceCachedScopeAtomically(
        scopeKey: String,
        entries: List<CachedFileEntryEntity>,
        stamp: CachedDirStampEntity
    ) {
        clearCachedScope(scopeKey)
        if (entries.isNotEmpty()) {
            upsertCachedEntries(entries)
        }
        upsertDirStamp(stamp)
    }
}

@Database(
    entities = [
        SandItemMetaEntity::class,
        ConnectedFolderEntity::class,
        BackupLogEntity::class,
        CachedFileEntryEntity::class,
        CachedDirStampEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class BeachDatabase : RoomDatabase() {
    abstract fun metadataDao(): BeachMetadataDao

    companion object {
        @Volatile
        private var INSTANCE: BeachDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_file_entries` (
                        `scopeKey` TEXT NOT NULL,
                        `fileKey` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `pathOrUri` TEXT NOT NULL,
                        `parentPathOrUri` TEXT NOT NULL,
                        `isDirectory` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `lastModifiedMillis` INTEGER NOT NULL,
                        `categoryName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `isSafDocument` INTEGER NOT NULL,
                        `safTreeUri` TEXT NOT NULL,
                        `requiresSafGrantDocId` TEXT,
                        `childCount` INTEGER NOT NULL,
                        `durationMillis` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`scopeKey`, `fileKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cached_file_entries_scopeKey` ON `cached_file_entries` (`scopeKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cached_file_entries_pathOrUri` ON `cached_file_entries` (`pathOrUri`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_dir_stamps` (
                        `scopeKey` TEXT NOT NULL,
                        `dirLastModified` INTEGER NOT NULL,
                        `entryCount` INTEGER NOT NULL,
                        `checkedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`scopeKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): BeachDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BeachDatabase::class.java,
                    "beaver_beach_storage.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
