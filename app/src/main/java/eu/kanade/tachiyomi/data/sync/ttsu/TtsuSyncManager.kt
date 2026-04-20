package eu.kanade.tachiyomi.data.sync.ttsu

import android.content.Context
import com.canopus.chimareader.data.BookMetadata
import com.canopus.chimareader.data.BookStorage
import com.canopus.chimareader.data.Bookmark
import com.canopus.chimareader.data.Statistics
import com.canopus.chimareader.data.StatisticsSyncMode
import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import eu.kanade.domain.sync.SyncPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

@Serializable
data class TtsuBookmarkData(
    val dataId: Int = 1,
    val exploredCharCount: Int,
    val progress: Double,
    val lastBookmarkModified: Long,
)

class TtsuSyncManager(private val context: Context) {
    private val syncPreferences: SyncPreferences = Injekt.get()
    private val ttuOAuthService = TtsuOAuthService(context)
    private val json = Json { ignoreUnknownKeys = true }

    private val exporterVersion = 1
    private val dbVersion = 6

    private data class TimestampedRemoteFile(
        val fileId: String,
        val fileName: String,
        val timestamp: Long,
    )

    private data class FolderSnapshot(
        val folderId: String,
        val progressFiles: List<TimestampedRemoteFile>,
        val statisticsFiles: List<TimestampedRemoteFile>,
    ) {
        val latestProgress: TimestampedRemoteFile?
            get() = progressFiles.maxByOrNull { it.timestamp }

        val latestStatistics: TimestampedRemoteFile?
            get() = statisticsFiles.maxByOrNull { it.timestamp }
    }

    private data class StatisticsFetchResult(
        val fileId: String,
        val statistics: List<Statistics>,
        val lastStatisticModified: Long,
    )

    private data class ProgressFingerprint(
        val exploredCharCount: Int,
        val progressBits: Long,
    )

    private data class CachedPushState(
        val progressFingerprint: ProgressFingerprint? = null,
        val statistics: List<Statistics>? = null,
    )

    companion object {
        private val syncMutex = Mutex()
        @Volatile
        private var cachedRootFolderId: String? = null

        private val cachedBookFolderIds = ConcurrentHashMap<String, String>()
        private val cachedPushStates = ConcurrentHashMap<String, CachedPushState>()
    }

    fun isSyncEnabled(): Boolean {
        return syncPreferences.ttuSyncEnabled().get() &&
            ttuOAuthService.hasClientId &&
            ttuOAuthService.isAuthenticated
    }

    private fun bookCacheKey(bookTitle: String): String {
        return sanitizeForFilename(bookTitle)
    }

    private fun getCachedPushState(bookTitle: String): CachedPushState? {
        return cachedPushStates[bookCacheKey(bookTitle)]
    }

    private fun cacheProgressPush(bookTitle: String, exploredCharCount: Int, progress: Double) {
        val key = bookCacheKey(bookTitle)
        val state = cachedPushStates[key] ?: CachedPushState()
        cachedPushStates[key] = state.copy(
            progressFingerprint = ProgressFingerprint(exploredCharCount, progress.toBits()),
        )
    }

    private fun cacheStatisticsPush(bookTitle: String, statistics: List<Statistics>) {
        val key = bookCacheKey(bookTitle)
        val state = cachedPushStates[key] ?: CachedPushState()
        cachedPushStates[key] = state.copy(statistics = normalizeStatistics(statistics))
    }

    private fun normalizeStatistics(statistics: List<Statistics>): List<Statistics> {
        return statistics.sortedWith(
            compareBy<Statistics> { it.dateKey }
                .thenBy { it.lastStatisticModified }
                .thenBy { it.title },
        )
    }

    private suspend inline fun <T> runWithSyncLock(
        allowSkipIfBusy: Boolean,
        crossinline block: suspend () -> T,
    ): T? {
        if (allowSkipIfBusy) {
            if (!syncMutex.tryLock()) {
                return null
            }
            return try {
                block()
            } finally {
                syncMutex.unlock()
            }
        }

        syncMutex.lock()
        return try {
            block()
        } finally {
            syncMutex.unlock()
        }
    }

    suspend fun pushBookData(
        bookTitle: String,
        exploredCharCount: Int,
        progress: Double,
        lastModified: Long,
        statistics: List<Statistics>,
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext runWithSyncLock(allowSkipIfBusy = false) {
            if (!isSyncEnabled()) return@runWithSyncLock false
            val drive = getAuthorizedDriveService() ?: return@runWithSyncLock false
            val folderSnapshot = getBookFolderSnapshot(drive, bookTitle, createIfMissing = true) ?: return@runWithSyncLock false

            var changed = false
            if (syncPreferences.ttuSyncProgress().get()) {
                changed = pushProgressToGoogleDrive(
                    drive = drive,
                    folderSnapshot = folderSnapshot,
                    bookTitle = bookTitle,
                    exploredCharCount = exploredCharCount,
                    progress = progress,
                    lastModified = lastModified,
                ) || changed
            }
            if (syncPreferences.ttuSyncStatistics().get()) {
                changed = pushStatisticsToGoogleDrive(
                    drive = drive,
                    folderSnapshot = folderSnapshot,
                    bookTitle = bookTitle,
                    statistics = statistics,
                ) || changed
            }
            changed
        } ?: false
    }

    suspend fun pushProgressToGoogleDrive(
        bookTitle: String,
        exploredCharCount: Int,
        progress: Double,
        lastModified: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext runWithSyncLock(allowSkipIfBusy = false) {
            if (!isSyncEnabled() || !syncPreferences.ttuSyncProgress().get()) return@runWithSyncLock false
            val drive = getAuthorizedDriveService() ?: return@runWithSyncLock false
            val folderSnapshot = getBookFolderSnapshot(drive, bookTitle, createIfMissing = true) ?: return@runWithSyncLock false
            val changed = pushProgressToGoogleDrive(
                drive = drive,
                folderSnapshot = folderSnapshot,
                bookTitle = bookTitle,
                exploredCharCount = exploredCharCount,
                progress = progress,
                lastModified = lastModified,
            )
            changed
        } ?: false
    }

    private fun pushProgressToGoogleDrive(
        drive: Drive,
        folderSnapshot: FolderSnapshot,
        bookTitle: String,
        exploredCharCount: Int,
        progress: Double,
        lastModified: Long,
    ): Boolean {
        return try {
            val fingerprint = ProgressFingerprint(exploredCharCount, progress.toBits())
            if (getCachedPushState(bookTitle)?.progressFingerprint == fingerprint) {
                return false
            }

            val latestRemote = folderSnapshot.latestProgress
            if (latestRemote != null && latestRemote.timestamp > lastModified) {
                logcat(LogPriority.INFO, tag = "TTSU-SYNC") {
                    "Skip progress push for '$bookTitle': remote is newer"
                }
                return false
            }

            val bookmarkData = TtsuBookmarkData(
                exploredCharCount = exploredCharCount,
                progress = progress,
                lastBookmarkModified = lastModified,
            )
            val fileName = getProgressFileName(bookmarkData)
            val content = ByteArrayContent(
                "application/json",
                json.encodeToString(bookmarkData).toByteArray(),
            )

            if (latestRemote != null) {
                val metadata = File().apply { name = fileName }
                drive.files().update(latestRemote.fileId, metadata, content).execute()
                cacheProgressPush(bookTitle, exploredCharCount, progress)
            } else {
                val metadata = File().apply {
                    name = fileName
                    mimeType = "application/json"
                    parents = listOf(folderSnapshot.folderId)
                }
                drive.files().create(metadata, content).execute()
                cacheProgressPush(bookTitle, exploredCharCount, progress)
            }

            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e, tag = "TTSU-SYNC") {
                "Failed to push TTU/Hoshi progress for '$bookTitle'"
            }
            false
        }
    }

    suspend fun pullBookProgressById(bookId: String): Boolean {
        val books = BookStorage.loadAllBooks(context)
        val book = books.find { it.id == bookId } ?: return false
        return pullBookFromCloud(book)
    }

    suspend fun pullBookFromCloud(book: BookMetadata): Boolean = withContext(Dispatchers.IO) {
        return@withContext runWithSyncLock(allowSkipIfBusy = false) {
            if (!isSyncEnabled()) return@runWithSyncLock false
            val drive = getAuthorizedDriveService() ?: return@runWithSyncLock false
            pullBookFromCloud(drive, book)
        } ?: false
    }

    private fun pullBookFromCloud(
        drive: Drive,
        book: BookMetadata,
        includeStatistics: Boolean = true,
    ): Boolean {
        val title = book.title ?: return false
        val folderSnapshot = getBookFolderSnapshot(drive, title, createIfMissing = false) ?: return false

        var changed = false
        if (syncPreferences.ttuSyncProgress().get()) {
            changed = pullBookProgress(drive, folderSnapshot, book) || changed
        }
        if (includeStatistics && syncPreferences.ttuSyncStatistics().get()) {
            changed = pullStatistics(drive, folderSnapshot, book) || changed
        }
        return changed
    }

    suspend fun pullBookProgress(
        book: BookMetadata,
        allowSkipIfBusy: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val title = book.title ?: return@withContext false
        val folderName = book.folder ?: return@withContext false
        val bookDir = BookStorage.getBookDirectory(context, folderName)
        return@withContext runWithSyncLock(allowSkipIfBusy = allowSkipIfBusy) {
            if (!isSyncEnabled() || !syncPreferences.ttuSyncProgress().get()) return@runWithSyncLock false

            val drive = getAuthorizedDriveService() ?: return@runWithSyncLock false
            val folderSnapshot = getBookFolderSnapshot(drive, title, createIfMissing = false) ?: return@runWithSyncLock false
            pullBookProgress(drive, folderSnapshot, book)
        } ?: false
    }

    private fun pullBookProgress(
        drive: Drive,
        folderSnapshot: FolderSnapshot,
        book: BookMetadata,
    ): Boolean {
        val title = book.title ?: return false
        val folderName = book.folder ?: return false

        return try {
            val latestRemote = folderSnapshot.latestProgress ?: return false

            val bookDir = BookStorage.getBookDirectory(context, folderName)
            val localBookmark = BookStorage.loadBookmark(bookDir)
            val localTimestamp = localBookmark?.lastModified ?: 0L
            if (latestRemote.timestamp <= localTimestamp) {
                return false
            }

            val bookmarkData = downloadJson<TtsuBookmarkData>(drive, latestRemote.fileId) ?: return false
            val epubBook = BookStorage.loadEpub(bookDir)
            val (newIndex, newProgress) = epubBook.convertCharsToProgress(bookmarkData.exploredCharCount)

            BookStorage.saveBookmark(
                bookmark = Bookmark(
                    chapterIndex = newIndex,
                    progress = newProgress,
                    characterCount = bookmarkData.exploredCharCount,
                    lastModified = bookmarkData.lastBookmarkModified,
                ),
                directory = bookDir,
            )
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e, tag = "TTSU-SYNC") {
                "Failed to pull TTU/Hoshi progress for '$title'"
            }
            false
        }
    }

    suspend fun pullAllFromCloud(): Boolean = withContext(Dispatchers.IO) {
        return@withContext runWithSyncLock(allowSkipIfBusy = false) {
            if (!isSyncEnabled()) return@runWithSyncLock false

            try {
                val localBooks = BookStorage.loadAllBooks(context)
                if (localBooks.isEmpty()) return@runWithSyncLock false

                val drive = getAuthorizedDriveService() ?: return@runWithSyncLock false

                var anyUpdated = false
                localBooks.forEach { book ->
                    anyUpdated = pullBookFromCloud(drive, book) || anyUpdated
                }
                anyUpdated
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, throwable = e, tag = "TTSU-SYNC") {
                    "Failed to pull all TTU/Hoshi novel data"
                }
                false
            }
        } ?: false
    }

    suspend fun pushStatisticsToGoogleDrive(
        bookTitle: String,
        statistics: List<Statistics>,
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext runWithSyncLock(allowSkipIfBusy = false) {
            if (!isSyncEnabled() || !syncPreferences.ttuSyncStatistics().get() || statistics.isEmpty()) {
                return@runWithSyncLock false
            }

            val drive = getAuthorizedDriveService() ?: return@runWithSyncLock false
            val folderSnapshot = getBookFolderSnapshot(drive, bookTitle, createIfMissing = true) ?: return@runWithSyncLock false
            pushStatisticsToGoogleDrive(drive, folderSnapshot, bookTitle, statistics)
        } ?: false
    }

    private fun pushStatisticsToGoogleDrive(
        drive: Drive,
        folderSnapshot: FolderSnapshot,
        bookTitle: String,
        statistics: List<Statistics>,
    ): Boolean {
        return try {
            val normalizedStatistics = normalizeStatistics(statistics)
            if (getCachedPushState(bookTitle)?.statistics == normalizedStatistics) {
                return false
            }

            val latestRemote = folderSnapshot.latestStatistics
            val remoteStatistics = latestRemote?.let {
                downloadJson<List<Statistics>>(drive, it.fileId)
            }
            val finalStatistics = when {
                remoteStatistics == null -> normalizedStatistics
                getStatisticsSyncMode() == StatisticsSyncMode.REPLACE -> normalizedStatistics
                else -> mergeStatistics(normalizedStatistics, remoteStatistics)
            }

            val lastModified = finalStatistics.maxOfOrNull { it.lastStatisticModified } ?: System.currentTimeMillis()
            val fileName = getStatisticsFileName(finalStatistics, lastModified)
            val content = ByteArrayContent(
                "application/json",
                json.encodeToString(finalStatistics).toByteArray(),
            )

            if (latestRemote != null) {
                val metadata = File().apply { name = fileName }
                drive.files().update(latestRemote.fileId, metadata, content).execute()
                cacheStatisticsPush(bookTitle, finalStatistics)
            } else {
                val metadata = File().apply {
                    name = fileName
                    mimeType = "application/json"
                    parents = listOf(folderSnapshot.folderId)
                }
                drive.files().create(metadata, content).execute()
                cacheStatisticsPush(bookTitle, finalStatistics)
            }

            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e, tag = "TTSU-SYNC") {
                "Failed to push TTU/Hoshi statistics for '$bookTitle'"
            }
            false
        }
    }

    suspend fun pullStatistics(
        book: BookMetadata,
        allowSkipIfBusy: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val title = book.title ?: return@withContext false
        val folderName = book.folder ?: return@withContext false
        val bookDir = BookStorage.getBookDirectory(context, folderName)
        val localTimestamp = BookStorage.loadStatistics(bookDir).orEmpty()
            .maxOfOrNull { it.lastStatisticModified } ?: 0L

        return@withContext runWithSyncLock(allowSkipIfBusy = allowSkipIfBusy) {
            if (!isSyncEnabled() || !syncPreferences.ttuSyncStatistics().get()) return@runWithSyncLock false

            val drive = getAuthorizedDriveService() ?: return@runWithSyncLock false
            val folderSnapshot = getBookFolderSnapshot(drive, title, createIfMissing = false) ?: return@runWithSyncLock false
            pullStatistics(drive, folderSnapshot, book)
        } ?: false
    }

    private fun pullStatistics(
        drive: Drive,
        folderSnapshot: FolderSnapshot,
        book: BookMetadata,
    ): Boolean {
        val title = book.title ?: return false
        val folderName = book.folder ?: return false

        return try {
            val remoteData = pullStatisticsData(drive, folderSnapshot) ?: return false

            val bookDir = BookStorage.getBookDirectory(context, folderName)
            val localStats = BookStorage.loadStatistics(bookDir).orEmpty()
            val localTimestamp = localStats.maxOfOrNull { it.lastStatisticModified } ?: 0L
            if (remoteData.lastStatisticModified <= localTimestamp) {
                return false
            }

            val finalStatistics = when (getStatisticsSyncMode()) {
                StatisticsSyncMode.MERGE -> mergeStatistics(localStats, remoteData.statistics)
                StatisticsSyncMode.REPLACE -> remoteData.statistics
            }
            BookStorage.saveStatistics(finalStatistics, bookDir)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e, tag = "TTSU-SYNC") {
                "Failed to pull TTU/Hoshi statistics for '$title'"
            }
            false
        }
    }

    private suspend fun getAuthorizedDriveService(): Drive? {
        if (!ttuOAuthService.ensureValidToken()) {
            return null
        }
        return ttuOAuthService.getDriveService()
    }

    private fun getStatisticsSyncMode(): StatisticsSyncMode {
        return StatisticsSyncMode.fromValue(syncPreferences.ttuStatisticsSyncMode().get())
    }

    private fun getRootFolderId(drive: Drive): String? {
        cachedRootFolderId?.let { return it }

        val query = "mimeType='application/vnd.google-apps.folder' and name = 'ttu-reader-data' and trashed=false"
        val rootId = drive.files().list()
            .setQ(query)
            .setFields("files(id)")
            .execute()
            .files
            ?.firstOrNull()
            ?.id

        if (rootId != null) {
            cachedRootFolderId = rootId
        }
        return rootId
    }

    private fun getOrCreateRootFolder(drive: Drive): String {
        return getRootFolderId(drive) ?: drive.files().create(
            File().apply {
                name = "ttu-reader-data"
                mimeType = "application/vnd.google-apps.folder"
            },
        ).setFields("id").execute().id.also {
            cachedRootFolderId = it
        }
    }

    private fun findBookFolderId(drive: Drive, bookTitle: String): String? {
        val sanitizedTitle = sanitizeForFilename(bookTitle)
        cachedBookFolderIds[sanitizedTitle]?.let { return it }

        val rootId = getRootFolderId(drive) ?: return null
        val query = "mimeType='application/vnd.google-apps.folder' and name = \"$sanitizedTitle\" and '$rootId' in parents and trashed=false"
        val folderId = drive.files().list()
            .setQ(query)
            .setFields("files(id)")
            .execute()
            .files
            ?.firstOrNull()
            ?.id

        if (folderId != null) {
            cachedBookFolderIds[sanitizedTitle] = folderId
        }
        return folderId
    }

    private fun getOrCreateBookFolder(drive: Drive, bookTitle: String): String {
        val sanitizedTitle = sanitizeForFilename(bookTitle)
        cachedBookFolderIds[sanitizedTitle]?.let { return it }

        val rootId = getOrCreateRootFolder(drive)
        val query = "mimeType='application/vnd.google-apps.folder' and name = \"$sanitizedTitle\" and '$rootId' in parents and trashed=false"
        val existingId = drive.files().list()
            .setQ(query)
            .setFields("files(id)")
            .execute()
            .files
            ?.firstOrNull()
            ?.id
        if (existingId != null) {
            cachedBookFolderIds[sanitizedTitle] = existingId
            return existingId
        }

        return drive.files().create(
            File().apply {
                name = sanitizedTitle
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(rootId)
            },
        ).setFields("id").execute().id.also {
            cachedBookFolderIds[sanitizedTitle] = it
        }
    }

    private fun getBookFolderSnapshot(
        drive: Drive,
        bookTitle: String,
        createIfMissing: Boolean,
    ): FolderSnapshot? {
        val folderId = if (createIfMissing) {
            getOrCreateBookFolder(drive, bookTitle)
        } else {
            findBookFolderId(drive, bookTitle)
        } ?: return null

        return listBookFolderFiles(drive, folderId)
    }

    private fun listBookFolderFiles(drive: Drive, folderId: String): FolderSnapshot {
        val query = "'$folderId' in parents and trashed=false"
        val files = drive.files().list()
            .setQ(query)
            .setFields("files(id, name, mimeType)")
            .execute()
            .files
            .orEmpty()

        val progressFiles = files.asSequence()
            .filter { it.mimeType == "application/json" && it.name.startsWith("progress_") }
            .map { TimestampedRemoteFile(it.id, it.name, parseTimestamp(it.name)) }
            .toList()

        val statisticsFiles = files.asSequence()
            .filter { it.mimeType == "application/json" && it.name.startsWith("statistics_") }
            .map { TimestampedRemoteFile(it.id, it.name, parseTimestamp(it.name)) }
            .toList()

        return FolderSnapshot(
            folderId = folderId,
            progressFiles = progressFiles,
            statisticsFiles = statisticsFiles,
        )
    }

    private fun pullStatisticsData(
        drive: Drive,
        folderSnapshot: FolderSnapshot,
    ): StatisticsFetchResult? {
        val latest = folderSnapshot.latestStatistics ?: return null
        val stats = downloadJson<List<Statistics>>(drive, latest.fileId) ?: return null
        return StatisticsFetchResult(
            fileId = latest.fileId,
            statistics = stats,
            lastStatisticModified = latest.timestamp,
        )
    }

    private inline fun <reified T> downloadJson(drive: Drive, fileId: String): T? {
        return try {
            val outputStream = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            json.decodeFromString(outputStream.toString(Charsets.UTF_8.name()))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e, tag = "TTSU-SYNC") {
                "Failed to decode TTU/Hoshi JSON file $fileId"
            }
            null
        }
    }

    private fun parseTimestamp(fileName: String): Long {
        return fileName.split("_")
            .getOrNull(3)
            ?.substringBefore('.')
            ?.toLongOrNull()
            ?: 0L
    }

    private fun getProgressFileName(bookmark: TtsuBookmarkData): String {
        return "progress_${exporterVersion}_${dbVersion}_${bookmark.lastBookmarkModified}_${bookmark.progress}.json"
    }

    private fun getStatisticsFileName(stats: List<Statistics>, lastStatisticModified: Long): String {
        var readingTime = 0.0
        var charactersRead = 0
        var minReadingSpeed = 0
        var altMinReadingSpeed = 0
        var maxReadingSpeed = 0
        var weightedSum = 0
        var validReadingDays = 0
        var finishDate = "na"

        stats.forEach { stat ->
            readingTime += stat.readingTime
            charactersRead += stat.charactersRead
            minReadingSpeed = if (minReadingSpeed > 0) {
                minOf(minReadingSpeed, stat.minReadingSpeed)
            } else {
                stat.minReadingSpeed
            }
            altMinReadingSpeed = if (altMinReadingSpeed > 0) {
                minOf(altMinReadingSpeed, stat.altMinReadingSpeed)
            } else {
                stat.altMinReadingSpeed
            }
            maxReadingSpeed = maxOf(maxReadingSpeed, stat.lastReadingSpeed)
            weightedSum += stat.readingTime.toInt() * stat.charactersRead

            if (stat.readingTime > 0) {
                validReadingDays += 1
            }

            stat.completedData?.let {
                finishDate = if (finishDate == "na") {
                    stat.dateKey
                } else {
                    maxOf(finishDate, it.dateKey)
                }
            }
        }

        val averageReadingTime = if (validReadingDays > 0) ceil(readingTime / validReadingDays).toInt() else 0
        val averageWeightedReadingTime = if (charactersRead > 0) ceil(weightedSum.toDouble() / charactersRead).toInt() else 0
        val averageCharactersRead = if (validReadingDays > 0) ceil(charactersRead.toDouble() / validReadingDays).toInt() else 0
        val averageWeightedCharactersRead = if (readingTime > 0) ceil(weightedSum / readingTime).toInt() else 0
        val lastReadingSpeed = if (readingTime > 0) ceil((3600.0 * charactersRead) / readingTime).toInt() else 0
        val averageReadingSpeed = if (averageReadingTime > 0) {
            ceil((3600.0 * averageCharactersRead) / averageReadingTime).toInt()
        } else {
            0
        }
        val averageWeightedReadingSpeed = if (averageWeightedReadingTime > 0) {
            ceil((3600.0 * averageWeightedCharactersRead) / averageWeightedReadingTime).toInt()
        } else {
            0
        }

        return "statistics_${exporterVersion}_${dbVersion}_${lastStatisticModified}_${charactersRead}_${readingTime}_${minReadingSpeed}_${altMinReadingSpeed}_${lastReadingSpeed}_${maxReadingSpeed}_${averageReadingTime}_${averageWeightedReadingTime}_${averageCharactersRead}_${averageWeightedCharactersRead}_${averageReadingSpeed}_${averageWeightedReadingSpeed}_${finishDate}.json"
    }

    private fun mergeStatistics(local: List<Statistics>, remote: List<Statistics>): List<Statistics> {
        return (local + remote)
            .groupBy { it.dateKey }
            .values
            .map { entries -> entries.maxBy { it.lastStatisticModified } }
            .sortedBy { it.dateKey }
    }

    private fun sanitizeForFilename(title: String): String {
        return title
            .replace(Regex("[ ]$"), "~ttu-spc~")
            .replace(Regex("[.]$"), "~ttu-dend~")
            .replace("*", "~ttu-star~")
            .replace(Regex("[/?<>\\\\:*|%\"]")) { matchResult ->
                matchResult.value.map { character ->
                    "%%%02X".format(character.code)
                }.joinToString("")
            }
    }
}
