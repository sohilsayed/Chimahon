package chimahon.dictionary

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private val checkerClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

private val remoteJsonParser = Json { ignoreUnknownKeys = true }

@Serializable
private data class RemoteIndex(
    val revision: String? = null,
    val downloadUrl: String? = null,
)

fun checkDictionaryUpdates(dictionariesDir: File): List<DictionaryUpdateInfo> {
    if (!dictionariesDir.isDirectory) return emptyList()

    return dictionariesDir.listFiles()
        ?.filter { it.isDirectory }
        ?.map { dir -> checkSingleDictionary(dir) }
        .orEmpty()
}

private fun checkSingleDictionary(dictDir: File): DictionaryUpdateInfo {
    val index = readDictionaryIndex(dictDir)
    if (index == null) {
        return DictionaryUpdateInfo(
            dictName = dictDir.name,
            currentRevision = null,
            isUpdatable = false,
            indexUrl = null,
            downloadUrl = null,
            error = "missing index.json",
        )
    }

    val info = DictionaryUpdateInfo(
        dictName = index.title.ifBlank { dictDir.name },
        currentRevision = index.revision,
        isUpdatable = index.isUpdatable,
        indexUrl = index.indexUrl,
        downloadUrl = index.downloadUrl,
    )

    if (!index.isUpdatable || index.indexUrl.isNullOrBlank()) return info

    return try {
        val remote = fetchRemoteIndex(index.indexUrl)
        val latestRev = remote?.revision
        val latestDl = remote?.downloadUrl ?: index.downloadUrl
        info.copy(
            latestRevision = latestRev,
            latestDownloadUrl = latestDl,
            hasUpdate = hasNewerRevision(info.currentRevision, latestRev),
        )
    } catch (e: Exception) {
        info.copy(error = e.message)
    }
}

private fun fetchRemoteIndex(indexUrl: String): RemoteIndex? {
    val request = Request.Builder().url(indexUrl).build()
    val response = checkerClient.newCall(request).execute()
    if (!response.isSuccessful) return null
    val body = response.body?.string() ?: return null
    return remoteJsonParser.decodeFromString<RemoteIndex>(body)
}
