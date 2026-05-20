package eu.kanade.tachiyomi.data.ocr

import chimahon.ocr.LensClient
import chimahon.ocr.MergeConfig
import chimahon.ocr.OcrLanguage
import chimahon.ocr.OcrResult
import chimahon.ocr.OwOCRMerger
import logcat.LogPriority
import logcat.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

suspend fun recognizePage(
    bytes: ByteArray,
    language: OcrLanguage,
): List<OcrResult> {
    val dictPrefs = Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>()
    val engineType = dictPrefs.ocrEngine().get()

    if (engineType == "local") {
        val localOcrBridge = Injekt.get<LocalOcrBridge>()
        val modelDownloader = Injekt.get<ModelDownloader>()
        if (!modelDownloader.isDownloaded) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local selected but models not downloaded, triggering download" }
            modelDownloader.triggerDownload()
            return emptyList()
        }
        if (!localOcrBridge.isAvailable) return emptyList()
        if (!localOcrBridge.isInitialized) {
            localOcrBridge.init()
        }
        if (!localOcrBridge.isInitialized) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local engine failed to initialize" }
            return emptyList()
        }
        val lines = localOcrBridge.recognize(bytes, language)
        if (lines.isEmpty()) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local engine returned no results" }
            return emptyList()
        }
        return OwOCRMerger.merge(lines, MergeConfig(language = language))
    }

    val lensClient = Injekt.get<LensClient>()
    val debugResult = lensClient.getDebugOcrData(bytes = bytes, language = language)
    return debugResult.mergedResults
}
