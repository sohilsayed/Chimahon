package eu.kanade.tachiyomi.data.dictionary

import android.app.Service
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import chimahon.HoshiDicts
import chimahon.dictionary.checkDictionaryUpdates
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit

class DictionaryUpdateJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = DictionaryUpdateNotifier(context)
    private val prefs: DictionaryPreferences = Injekt.get()

    override suspend fun doWork(): Result {
        if (!prefs.autoUpdateEnabled().get()) {
            return Result.success()
        }

        val dictionariesDir = File(applicationContext.getExternalFilesDir(null), "dictionaries")
        if (!dictionariesDir.isDirectory) return Result.success()

        setForegroundSafely()

        return try {
            val updates = withContext(Dispatchers.IO) {
                checkDictionaryUpdates(dictionariesDir)
                    .filter { it.hasUpdate && !it.downloadUrl.isNullOrBlank() }
            }

            if (updates.isEmpty()) {
                Log.d(TAG, "No dictionary updates found")
                Result.success()
            } else {
                var successCount = 0
                updates.forEachIndexed { index, update ->
                    notifier.showProgress(update.dictName, index, updates.size)
                    val ok = applyUpdate(update.dictName, update.downloadUrl!!, dictionariesDir)
                    if (ok) successCount++
                }

                notifier.showComplete(successCount)
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary update failed", e)
            notifier.showError("", e.message ?: "Unknown error")
            Result.failure()
        }
    }

    private suspend fun applyUpdate(
        dictName: String,
        downloadUrl: String,
        dictionariesDir: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = applicationContext.cacheDir
        val tempZip = File(cacheDir, "dict_update_${System.currentTimeMillis()}.zip")
        val tempImportDir = File(cacheDir, "dict_import_tmp_${System.currentTimeMillis()}")

        try {
            Log.d(TAG, "Downloading $dictName from $downloadUrl")
            downloadFile(downloadUrl, tempZip)

            Log.d(TAG, "Importing $dictName to temp dir")
            tempImportDir.mkdirs()
            val result = HoshiDicts.importDictionary(
                zipPath = tempZip.absolutePath,
                outputDir = tempImportDir.absolutePath,
            )

            if (!result.success) {
                Log.e(TAG, "Import failed for $dictName")
                notifier.showError(dictName, "Import failed")
                return@withContext false
            }

            val importedSubdir = tempImportDir.listFiles()
                ?.firstOrNull { it.isDirectory }

            if (importedSubdir == null) {
                Log.e(TAG, "No imported directory found for $dictName")
                notifier.showError(dictName, "No imported directory found")
                return@withContext false
            }

            val oldDir = File(dictionariesDir, dictName)
            if (oldDir.exists()) {
                oldDir.deleteRecursively()
            }

            importedSubdir.renameTo(oldDir)

            Log.d(TAG, "Successfully updated $dictName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update $dictName", e)
            notifier.showError(dictName, e.message ?: "Unknown error")
            false
        } finally {
            if (tempZip.exists()) tempZip.delete()
            if (tempImportDir.exists()) tempImportDir.deleteRecursively()
        }
    }

    private fun downloadFile(url: String, output: File) {
        val request = Request.Builder().url(url).build()
        val response = downloadClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Download failed: HTTP ${response.code}")
        }
        response.body?.byteStream()?.use { input ->
            output.outputStream().use { outputStream ->
                input.copyTo(outputStream)
            }
        }
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val notification = notifier.run {
            androidx.core.app.NotificationCompat.Builder(
                applicationContext,
                Notifications.CHANNEL_DICTIONARY_UPDATE,
            )
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(
                    applicationContext.stringResource(MR.strings.dict_update_progress_title),
                )
                .setOngoing(true)
                .build()
        }
        return androidx.work.ForegroundInfo(
            Notifications.ID_DICT_UPDATE_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Service.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG = "DictUpdateJob"
        private const val UNIQUE_WORK_NAME = "DictionaryUpdate-auto"

        private val downloadClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun setupTask(context: Context, enabled: Boolean) {
            if (enabled) {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = PeriodicWorkRequestBuilder<DictionaryUpdateJob>(
                    24, TimeUnit.HOURS,
                    1, TimeUnit.HOURS,
                )
                    .addTag(TAG)
                    .setConstraints(constraints)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
                Log.d(TAG, "Scheduled daily dictionary update check")
            } else {
                context.workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                Log.d(TAG, "Cancelled dictionary update check")
            }
        }
    }
}
