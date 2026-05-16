package eu.kanade.tachiyomi.ui.dictionary

import eu.kanade.tachiyomi.R

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.canopus.chimareader.data.FontManager
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import chimahon.DictionaryStyle
import chimahon.LookupResult
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import chimahon.audio.WordAudioService
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.theme.colorscheme.*
import com.materialkolor.PaletteStyle
import tachiyomi.presentation.core.util.collectAsState

private const val ANKI_SCHEME = "anki"
private const val ANKI_PATH_ADD = "add"
private const val ANKI_PATH_OPEN = "open"

private const val CHIMA_SCHEME = "chima"
private const val CHIMA_HOST_LOOKUP = "lookup"
private const val CHIMA_HOST_TAB = "tab"
private const val CHIMA_HOST_BACK = "back"

/** Represents one entry in the scrollable lookup-history tab bar shown inside the WebView. */
data class TabInfo(val label: String, val active: Boolean)

/**
 * Full signature — used as a key for the payload builder.
 * Split into resultsKey (data) + settingsSignature (display) for optimization.
 */
private data class DictionaryRenderSignature(
    val results: List<LookupResult>,
    val styles: List<DictionaryStyle>,
    val placeholder: String,
    val isDark: Boolean,
    val showFrequencyHarmonic: Boolean,
    val groupTerms: Boolean,
    val showPitchDiagram: Boolean,
    val showPitchNumber: Boolean,
    val showPitchText: Boolean,
    val activeProfile: chimahon.anki.AnkiProfile,
    val tabs: List<TabInfo>,
    val recursiveNavMode: String,
    val wordAudioEnabled: Boolean,
    val wordAudioAutoplay: Boolean,
    val showNavigationButtons: Boolean,
    val groupPitches: Boolean,
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DictionaryEntryWebView(
    results: List<LookupResult>,
    styles: List<DictionaryStyle>,
    mediaDataUris: Map<String, String>,
    placeholder: String,
    headerText: String = "",
    fontSize: Int = 16,
    showFrequencyHarmonic: Boolean = false,
    groupTerms: Boolean = true,
    activeProfile: chimahon.anki.AnkiProfile,
    existingExpressions: Set<String> = emptySet(),
    tabs: List<TabInfo> = emptyList(),
    showPitchDiagram: Boolean = true,
    showPitchNumber: Boolean = true,
    showPitchText: Boolean = true,
    recursiveNavMode: String = "tabs",
    wordAudioEnabled: Boolean = true,
    // When non-null, overrides the wordAudioAutoplay preference. Pass false to
    // suppress autoplay (e.g. when the popup is hidden but the warm shell is
    // still in composition and receives a new lookup result).
    wordAudioAutoplayOverride: Boolean? = null,
    customCss: String = "",
    groupPitches: Boolean = false,
    modifier: Modifier = Modifier,
    webViewProvider: ((Context) -> WebView)? = null,
    onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = null,
    onRecursiveLookup: ((String) -> Unit)? = null,
    onTabSelect: ((Int) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onContentReadyChange: ((Boolean) -> Unit)? = null,
    hideOnContentInvalidated: Boolean = true,
    forceDefaultTheme: Boolean = false,
    isLoading: Boolean = false,
) {
    val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
    val amoled by dictionaryPreferences.themeDarkAmoled().collectAsState()
    val customColor by dictionaryPreferences.customColor().collectAsState()

    val context = LocalContext.current
    val prefs = remember { Injekt.get<DictionaryPreferences>() }
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val fontFamily by prefs.fontFamily().collectAsState()
    val seedColor = if (customColor == 0 || forceDefaultTheme) uiPreferences.colorTheme().get() else customColor

    val systemIsDark = isSystemInDarkTheme()
    val isDark = remember(seedColor, customColor, systemIsDark, forceDefaultTheme) {
        if (customColor != 0 && !forceDefaultTheme) Color(seedColor).luminance() < 0.5f else systemIsDark
    }
    val colorScheme = remember(isDark, amoled, seedColor) {
        getDictionaryColorScheme(isDark, amoled, seedColor)
    }
    val BgColor = remember(isDark, amoled, seedColor, colorScheme) {
        if (amoled && isDark) Color.Black else colorScheme.surface
    }
    val wordAudioAutoplay by prefs.wordAudioAutoplay().collectAsState()
    val effectiveWordAudioAutoplay = wordAudioAutoplayOverride ?: wordAudioAutoplay
    val showNavigationButtons by prefs.showNavigationButtons().collectAsState()
    val eInkMode by prefs.eInkMode().collectAsState()

    val renderSignature = remember(
        results,
        styles,
        placeholder,
        isDark,
        showFrequencyHarmonic,
        groupTerms,
        showPitchDiagram,
        showPitchNumber,
        showPitchText,
        activeProfile,
        tabs,
        recursiveNavMode,
        wordAudioEnabled,
        effectiveWordAudioAutoplay,
        showNavigationButtons,
        groupPitches,
    ) {
        DictionaryRenderSignature(
            results = results,
            styles = styles,
            placeholder = placeholder,
            isDark = isDark,
            showFrequencyHarmonic = showFrequencyHarmonic,
            groupTerms = groupTerms,
            showPitchDiagram = showPitchDiagram,
            showPitchNumber = showPitchNumber,
            showPitchText = showPitchText,
            activeProfile = activeProfile,
            tabs = tabs,
            recursiveNavMode = recursiveNavMode,
            wordAudioEnabled = wordAudioEnabled,
            wordAudioAutoplay = effectiveWordAudioAutoplay,
            showNavigationButtons = showNavigationButtons,
            groupPitches = groupPitches,
        )
    }

    // B1: Build payload off the main thread, track which signature it was built for.
    var payloadPair by remember { mutableStateOf<Pair<String, DictionaryRenderSignature>?>(null) }
    // Read during composition so Compose registers the dependency (update lambda reads it later)
    val currentPayloadPair = payloadPair
    LaunchedEffect(renderSignature) {
        val buildStart = SystemClock.elapsedRealtime()
        val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildRenderPayload(
                context, results, styles, emptyMap(), placeholder, isDark,
                showFrequencyHarmonic, groupTerms, showPitchDiagram, showPitchNumber, showPitchText,
                effectiveWordAudioAutoplay, activeProfile, emptySet(), tabs, recursiveNavMode,
                wordAudioEnabled = wordAudioEnabled,
                showNavigationButtons = showNavigationButtons,
                groupPitches = groupPitches,
            ).toString()
        }
        Log.i(
            "DictionaryRender",
            "payload_build_ms=${SystemClock.elapsedRealtime() - buildStart} results=${results.size} tabs=${tabs.size}",
        )
        payloadPair = p to renderSignature
    }

    val bootstrapHtml = remember(context, isDark, amoled, seedColor, colorScheme, fontFamily, eInkMode, activeProfile.languageCode) {
        getDictionaryBootstrapHtml(
            context = context,
            colorScheme = colorScheme,
            isDark = isDark,
            isAmoled = amoled,
            seedColor = seedColor,
            fontFamily = fontFamily,
            eInkMode = eInkMode,
            languageCode = activeProfile.languageCode,
        )
    }
    
    Box(modifier = modifier.background(BgColor)) {
        AndroidView<WebView>(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: Context ->
                val webView = webViewProvider?.invoke(ctx) ?: WebView(ctx)
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)

                if (webView.tag !is DictionaryWebViewState) {
                    prepareDictionaryWebViewShell(ctx, webView, bootstrapHtml, BgColor.toArgb())
                }
                (webView.tag as? DictionaryWebViewState)?.let { state ->
                    state.onContentInvalidated = {
                        onContentReadyChange?.invoke(false)
                        if (hideOnContentInvalidated) {
                            webView.alpha = 0f
                        }
                    }
                    state.onContentReady = {
                        onContentReadyChange?.invoke(true)
                        webView.alpha = 1f
                    }
                    if (state.pageReady) {
                        state.flush(webView)
                    }
                }

                webView.isClickable = true
                webView.isLongClickable = true
                webView.isFocusable = true
                webView.isFocusableInTouchMode = true
                webView.requestFocus()
                webView.setOnLongClickListener { false }

                webView.setOnKeyListener { v, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                                (v as? WebView)?.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.navigate(-1);", null)
                                true
                            }
                            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                (v as? WebView)?.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.navigate(1);", null)
                                true
                            }
                            else -> false
                        }
                    } else false
                }

                webView
            },
            update = { webView: WebView ->
                val state = webView.tag as? DictionaryWebViewState ?: return@AndroidView
                if (state.bootstrapHtml != bootstrapHtml) {
                    state.reloadShell(webView, bootstrapHtml)
                }
                state.onAnkiLookup = onAnkiLookup
                state.onRecursiveLookup = onRecursiveLookup
                state.onTabSelect = onTabSelect
                state.onBack = onBack
                state.customCss = customCss
                state.fontSize = fontSize
                state.onContentInvalidated = {
                    onContentReadyChange?.invoke(false)
                    if (hideOnContentInvalidated) {
                        webView.alpha = 0f
                    }
                }
                state.onContentReady = {
                    onContentReadyChange?.invoke(true)
                    webView.alpha = 1f
                }

                webView.setBackgroundColor(BgColor.toArgb())

                // Efficiently push new data to existing page
                if (state.pageReady) {
                    val enableRecursive = onRecursiveLookup != null
                    webView.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.setRecursiveLookupEnabled($enableRecursive);", null)
                    state.injectCustomCss(webView)
                    state.injectFontSize(webView)
                    if (isLoading) {
                        state.clear(webView)
                    } else {
                        val (payload, payloadSig) = currentPayloadPair ?: (null to null)
                        if (payload != null && payloadSig == renderSignature) {
                            state.flush(webView, results, existingExpressions, mediaDataUris, renderSignature, payload)
                        }
                    }
                } else {
                    val (pendingPayloadVal, pendingPayloadSig) = currentPayloadPair ?: (null to null)
                    if (pendingPayloadVal != null && pendingPayloadSig == renderSignature) {
                        state.pendingPayload = pendingPayloadVal
                        state.pendingResults = results
                        state.pendingExistingExpressions = existingExpressions
                        state.pendingMediaDataUris = mediaDataUris
                        state.pendingRenderSignature = renderSignature
                    }
                }
            },
            onRelease = { webView ->
                val state = webView.tag as? DictionaryWebViewState
                state?.clear(webView)
                state?.onContentInvalidated = null
                state?.onContentReady = null
                state?.lastPayload = null
                state?.lastResults = null
                state?.lastExistingExpressions = null
                state?.lastMediaDataUris = null
                state?.lastRenderSignature = null
                state?.pendingPayload = null
                state?.pendingResults = null
                state?.pendingExistingExpressions = null
                state?.pendingMediaDataUris = null
                state?.pendingRenderSignature = null
            },
        )
    }
}

private class DictionaryReadyBridge(
    private val webViewProvider: () -> WebView?,
) {
    @JavascriptInterface
    fun contentReady() {
        val webView = webViewProvider() ?: return
        webView.post {
            (webView.tag as? DictionaryWebViewState)?.onContentReady?.invoke()
        }
    }
}

/**
 * Bridge for passing large payloads (JSON, HTML) from Kotlin to JS without
 * embedding them in evaluateJavascript() strings.  The JS side calls
 * PayloadBridge.getPayloadJson() or PayloadBridge.getEntryHtml() to pull
 * the data natively — avoids the overhead of encoding 300KB+ as a JS
 * string literal.
 */
private class PayloadBridge {
    @Volatile
    var rawPayloadJson: String = ""

    @Volatile
    var rawEntryHtml: String = ""

    @JavascriptInterface
    fun getPayloadJson(): String = rawPayloadJson

    @JavascriptInterface
    fun getEntryHtml(): String = rawEntryHtml
}

private class WordAudioBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?
) {
    private val wordAudioService: WordAudioService by Injekt.injectLazy()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.Job())
    private var mediaPlayer: android.media.MediaPlayer? = null

    @JavascriptInterface
    fun fetchAudio(term: String, reading: String, callbackId: String) {
        scope.launch {
            val results = wordAudioService.findWordAudio(term, reading)
            val jsonArray = org.json.JSONArray().apply {
                results.forEach { result ->
                    put(org.json.JSONObject().apply {
                        put("name", result.name)
                        put("url", result.url)
                    })
                }
            }.toString()
            
            webViewProvider()?.evaluateJavascript(
                "window.DictionaryRenderer && window.DictionaryRenderer.onAudioResults('$callbackId', $jsonArray);",
                null
            )
        }
    }
    
    @JavascriptInterface
    fun playAudio(url: String) {
        scope.launch {
            try {
                stopAudio()
                
                val player = android.media.MediaPlayer()
                mediaPlayer = player
                
                if (url.startsWith("chimahon-local://")) {
                    // Extract term and file from local URL: chimahon-local://sourceId/file
                    val uri = Uri.parse(url)
                    val sourceId = uri.host ?: return@launch
                    val filePath = uri.path?.substring(1) ?: return@launch // remove leading slash
                    
                    val data = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        wordAudioService.getAudioData(filePath, sourceId)
                    } ?: return@launch
                    
                    val extension = "." + (filePath.substringAfterLast('.', "mp3"))
                    val tempFile = File.createTempFile("word_audio", extension, context.cacheDir)
                    tempFile.writeBytes(data)
                    
                    player.setDataSource(tempFile.absolutePath)
                    tempFile.deleteOnExit()
                } else {
                    player.setDataSource(url)
                }
                
                player.prepareAsync()
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { 
                    it.release()
                    if (mediaPlayer == it) mediaPlayer = null
                }
            } catch (e: Exception) {
                Log.e("WordAudioBridge", "Error playing audio: $url", e)
            }
        }
    }

    fun stopAudio() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun prepareDictionaryWebViewShell(
    context: Context,
    webView: WebView = WebView(context),
    bootstrapHtml: String? = null,
    backgroundColor: Int = android.graphics.Color.TRANSPARENT,
    languageCode: String = "",
): WebView {
    val effectiveBootstrap = bootstrapHtml ?: getDictionaryBootstrapHtml(context, languageCode = languageCode)
    (webView.tag as? DictionaryWebViewState)?.let { state ->
        if (state.bootstrapHtml != effectiveBootstrap) {
            state.reloadShell(webView, effectiveBootstrap)
        }
        return webView
    }

    val state = DictionaryWebViewState(context, webViewProvider = { webView }, bootstrapHtml = effectiveBootstrap)
    webView.apply {
        alpha = 0f
        setBackgroundColor(backgroundColor)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkLoads = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.setSupportZoom(true)
        settings.displayZoomControls = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.forceDark = WebSettings.FORCE_DARK_OFF
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.isAlgorithmicDarkeningAllowed = false
        }

        disableSafeBrowsingForDictionary(this)

        addJavascriptInterface(state.wordAudioBridge, "WordAudioBridge")
        addJavascriptInterface(state.readyBridge, "DictionaryReadyBridge")
        addJavascriptInterface(state.payloadBridge, "PayloadBridge")

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val s = view?.tag as? DictionaryWebViewState ?: return
                s.pageReady = true
                view.setTag(R.id.chima_webview_warm, true)
                val enableRecursive = s.onRecursiveLookup != null
                view.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.setRecursiveLookupEnabled($enableRecursive);", null)
                s.injectFontSize(view)
                s.injectCustomCss(view)
                s.flush(view)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url ?: return false
                val s = view?.tag as? DictionaryWebViewState

                if (url.scheme == CHIMA_SCHEME) {
                    when (url.host) {
                        CHIMA_HOST_LOOKUP -> {
                            val q = url.getQueryParameter("q") ?: return true
                            if (q.isNotBlank()) s?.onRecursiveLookup?.invoke(q)
                            return true
                        }
                        CHIMA_HOST_TAB -> {
                            val idx = url.getQueryParameter("index")?.toIntOrNull()
                            if (idx != null) s?.onTabSelect?.invoke(idx)
                            return true
                        }
                        CHIMA_HOST_BACK -> {
                            s?.onBack?.invoke()
                            return true
                        }
                    }
                    return true
                }

                if (url.scheme == ANKI_SCHEME) {
                    val host = url.host ?: ""
                    val isAdd = host.equals(ANKI_PATH_ADD, ignoreCase = true)
                    val isOpen = host.equals(ANKI_PATH_OPEN, ignoreCase = true)

                    if (isAdd || isOpen) {
                        val index = url.getQueryParameter("index")?.toIntOrNull()
                        val glossary = url.getQueryParameter("glossary")?.toIntOrNull()
                        val selectedDict = url.getQueryParameter("selected_dict")
                        val popupSelection = url.getQueryParameter("popup_selection")

                        android.util.Log.d("DictionaryEntryWebView", "onAnkiLookup: host=$host, index=$index, isOpen=$isOpen")

                        if (index != null && index >= 0) {
                            s?.onAnkiLookup?.invoke(index, glossary, selectedDict, popupSelection, isOpen)
                        }
                        return true
                    }
                }
                return false
            }
        }

        tag = state
        state.reloadShell(this, effectiveBootstrap)
    }
    return webView
}

private class DictionaryWebViewState(
    val context: Context,
    webViewProvider: () -> WebView?,
    var bootstrapHtml: String = "",
) {
    val wordAudioBridge: WordAudioBridge = WordAudioBridge(context, webViewProvider)
    val readyBridge: DictionaryReadyBridge = DictionaryReadyBridge(webViewProvider)
    val payloadBridge: PayloadBridge = PayloadBridge()
    var pageReady: Boolean = false
    var fontSize: Int = 16
    var onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = null
    var onRecursiveLookup: ((String) -> Unit)? = null
    var onTabSelect: ((Int) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onContentInvalidated: (() -> Unit)? = null
    var onContentReady: (() -> Unit)? = null
    var lastPayload: String? = null
    var lastResults: List<LookupResult>? = null
    var lastExistingExpressions: Set<String>? = null
    var lastMediaDataUris: Map<String, String>? = null
    var lastRenderSignature: DictionaryRenderSignature? = null
    var pendingPayload: String? = null
    var pendingResults: List<LookupResult>? = null
    var pendingExistingExpressions: Set<String>? = null
    var pendingMediaDataUris: Map<String, String>? = null
    var pendingRenderSignature: DictionaryRenderSignature? = null
    var customCss: String = ""

    fun injectCustomCss(webView: WebView) {
        if (customCss.isEmpty()) {
            webView.evaluateJavascript(
                "var el = document.getElementById('chima-custom-css'); if (el) el.textContent = '';",
                null
            )
            return
        }
        webView.evaluateJavascript(
            "(function(v) {" +
                "var el = document.getElementById('chima-custom-css');" +
                "if (el) el.textContent = v;" +
                "})(decodeURIComponent('${java.net.URLEncoder.encode(customCss, "UTF-8").replace("+", "%20")}'));",
            null,
        )
    }

    fun injectFontSize(webView: WebView) {
        webView.evaluateJavascript(
            "(function(v) {" +
                "v = v + 'px';" +
                "document.documentElement.style.fontSize = v;" +
                "document.body.style.fontSize = v;" +
                "document.documentElement.style.setProperty('--font-size-no-units', '$fontSize');" +
                "document.documentElement.dataset.theme = '${if (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}';" +
                "})('$fontSize');",
            null
        )
    }

    fun clear(webView: WebView) {
        wordAudioBridge.stopAudio()
        onContentInvalidated?.invoke()
        webView.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.clear();", null)
    }

    fun reloadShell(webView: WebView, html: String) {
        bootstrapHtml = html
        pageReady = false
        lastPayload = null
        lastResults = null
        lastExistingExpressions = null
        lastMediaDataUris = null
        lastRenderSignature = null
        clearPendingPayload()
        onContentInvalidated?.invoke()
        webView.loadDataWithBaseURL(
            "https://chima.local/popup/",
            html,
            "text/html",
            "UTF-8",
            null,
        )
    }

    fun flush(
        webView: WebView,
        results: List<LookupResult>? = null,
        existingExpressions: Set<String>? = null,
        mediaDataUris: Map<String, String>? = null,
        renderSignature: DictionaryRenderSignature? = null,
        payload: String? = null,
    ) {
        val p = payload ?: pendingPayload ?: return
        val renderResults = results ?: pendingResults
        val renderExistingExpressions = existingExpressions ?: pendingExistingExpressions
        val renderMediaDataUris = mediaDataUris ?: pendingMediaDataUris
        val signature = renderSignature ?: pendingRenderSignature

        val canPatchExistingRender = lastResults != null &&
            renderResults != null &&
            lastResults === renderResults &&
            lastRenderSignature == signature
        if (canPatchExistingRender) {
            if (lastExistingExpressions != renderExistingExpressions && renderExistingExpressions != null) {
                val json = org.json.JSONArray(renderExistingExpressions).toString()
                webView.evaluateJavascript("DictionaryRenderer.updateAnkiStatus(${json.toJavascriptExpression()})", null)
                lastExistingExpressions = renderExistingExpressions
            }
            if (lastMediaDataUris != renderMediaDataUris && renderMediaDataUris != null) {
                val json = org.json.JSONObject(renderMediaDataUris).toString()
                webView.evaluateJavascript("DictionaryRenderer.updateMediaDataUris(${json.toJavascriptExpression()})", null)
                lastMediaDataUris = renderMediaDataUris
            }
            if (p != lastPayload) {
                lastPayload = p
            }
            clearPendingPayload()
            return
        }

        if (p == lastPayload && lastRenderSignature == signature) {
            if (lastExistingExpressions != renderExistingExpressions && renderExistingExpressions != null) {
                // Optimized path: Only Anki status changed
                val json = org.json.JSONArray(renderExistingExpressions).toString()
                webView.evaluateJavascript("DictionaryRenderer.updateAnkiStatus(${json.toJavascriptExpression()})", null)
                lastExistingExpressions = renderExistingExpressions
            }
            if (lastMediaDataUris != renderMediaDataUris && renderMediaDataUris != null) {
                val json = org.json.JSONObject(renderMediaDataUris).toString()
                webView.evaluateJavascript("DictionaryRenderer.updateMediaDataUris(${json.toJavascriptExpression()})", null)
                lastMediaDataUris = renderMediaDataUris
            }
            clearPendingPayload()
            return
        }

        lastPayload = p
        lastResults = renderResults
        lastExistingExpressions = renderExistingExpressions
        lastMediaDataUris = renderMediaDataUris
        lastRenderSignature = signature
        clearPendingPayload()

        val renderStart = SystemClock.elapsedRealtime()

        onContentInvalidated?.invoke()
        injectFontSize(webView)

        // Store the payload in the bridge and have JS pull it natively.
        // This avoids embedding 300KB+ JSON in an evaluateJavascript() string —
        // the bridge delivers the string via @JavascriptInterface which is
        // faster for large payloads.
        payloadBridge.rawPayloadJson = p

        val ankiPatch = renderExistingExpressions?.let {
            "window.DictionaryRenderer.updateAnkiStatus(${org.json.JSONArray(it).toString().toJavascriptExpression()});"
        }.orEmpty()
        val mediaPatch = renderMediaDataUris?.takeIf { it.isNotEmpty() }?.let {
            "window.DictionaryRenderer.updateMediaDataUris(${org.json.JSONObject(it).toString().toJavascriptExpression()});"
        }.orEmpty()
        webView.evaluateJavascript(
            "if (window.DictionaryRenderer) { window.DictionaryRenderer.renderFromBridge(); $ankiPatch $mediaPatch }",
            null,
        )

        Log.i(
            "DictionaryRender",
            "webview_dispatch_ms=${SystemClock.elapsedRealtime() - renderStart}",
        )
    }

    private fun clearPendingPayload() {
        pendingPayload = null
        pendingResults = null
        pendingExistingExpressions = null
        pendingMediaDataUris = null
        pendingRenderSignature = null
    }
}

private fun String.toJavascriptExpression(): String =
    replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

private fun buildRenderPayload(
    context: Context,
    results: List<LookupResult>,
    styles: List<DictionaryStyle>,
    mediaDataUris: Map<String, String>,
    placeholder: String,
    isDark: Boolean,
    showFrequencyHarmonic: Boolean,
    groupTerms: Boolean,
    showPitchDiagram: Boolean,
    showPitchNumber: Boolean,
    showPitchText: Boolean,
    wordAudioAutoplay: Boolean,
    activeProfile: chimahon.anki.AnkiProfile,
    existingExpressions: Set<String> = emptySet(),
    tabs: List<TabInfo> = emptyList(),
    recursiveNavMode: String = "tabs",
    wordAudioEnabled: Boolean = true,
    showNavigationButtons: Boolean = true,
    groupPitches: Boolean = false,
): JsonObject = buildJsonObject {
    // Dictionary Priority Order (Titles)
    val orderedTitles = activeProfile.dictionaryOrder
        .map { getDictionaryTitle(context, it) }
    val displayModesByTitle = activeProfile.dictionaryDisplayModes
        .mapKeys { (dirName, _) -> getDictionaryTitle(context, dirName) }

    putJsonArray("dictionaryOrder") {
        for (title in orderedTitles) {
            add(JsonPrimitive(title))
        }
    }

    put("ankiEnabled", activeProfile.ankiEnabled)
    put("ankiDupAction", activeProfile.ankiDupAction)
    put("dictionaryCollapseMode", activeProfile.dictionaryCollapseMode)
    put("dictionaryDisplayModes", buildJsonObject {
        for ((title, mode) in displayModesByTitle) {
            put(title, mode)
        }
    })

    put("placeholder", placeholder)
    put("isDark", isDark)
    put("showFrequencyHarmonic", showFrequencyHarmonic)
    put("groupTerms", groupTerms)
    put("showPitchDiagram", showPitchDiagram)
    put("showPitchNumber", showPitchNumber)
    put("showPitchText", showPitchText)
    put("wordAudioAutoplay", wordAudioAutoplay)
    put("wordAudioEnabled", wordAudioEnabled)
    put("recursiveNavMode", recursiveNavMode)
    put("showNavigationButtons", showNavigationButtons)

    // Tabs for recursive lookup navigation
    putJsonArray("tabs") {
        for (tab in tabs) {
            add(buildJsonObject {
                put("label", tab.label)
                put("active", tab.active)
            })
        }
    }

    putJsonArray("existingExpressions") {
        for (expr in existingExpressions) {
            add(JsonPrimitive(expr))
        }
    }

    // Styles array
    putJsonArray("styles") {
        for (style in styles) {
            add(buildJsonObject {
                put("dictName", style.dictName)
                put("styles", style.styles)
            })
        }
    }

    // Media data URIs
    val mediaObj = buildJsonObject {
        for ((key, value) in mediaDataUris) {
            put(key, value)
        }
    }
    put("mediaDataUris", mediaObj)

    // Results array
    putJsonArray("results") {
        for ((index, result) in results.withIndex()) {
            add(buildJsonObject {
                put("index", index)
                put("matched", result.matched)
                put("deinflected", result.deinflected)

                // Process array
                putJsonArray("process") {
                    for (p in result.process) {
                        add(JsonPrimitive(p))
                    }
                }

                // Term object
                put("term", buildJsonObject {
                    put("expression", result.term.expression)
                    put("reading", result.term.reading)
                    put("rules", result.term.rules)

                    // Glossaries
                    putJsonArray("glossaries") {
                        for (g in result.term.glossaries) {
                            add(buildJsonObject {
                                put("dictName", g.dictName)
                                put("glossary", g.glossary)
                                put("definitionTags", g.definitionTags)
                                put("termTags", g.termTags)
                            })
                        }
                    }

                    // Frequencies
                    putJsonArray("frequencies") {
                        for (group in result.term.frequencies) {
                            add(buildJsonObject {
                                put("dictName", group.dictName)
                                putJsonArray("frequencies") {
                                    for (item in group.frequencies) {
                                        add(buildJsonObject {
                                            put("value", item.value)
                                            put("displayValue", item.displayValue)
                                        })
                                    }
                                }
                            })
                        }
                    }

                    // Pitches
                    putJsonArray("pitches") {
                        val allPitches = result.term.pitches
                        val priorityList = activeProfile.dictionaryOrder

                        if (groupPitches) {
                            // Full Union approach: merge all pitches and all dictionaries into a single block
                            // Order is determined by dictionary priority
                            val orderedPitches = LinkedHashSet<Int>()
                            
                            // 1. Process dictionaries in priority order to establish pitch sequence
                            for (dictId in priorityList) {
                                allPitches.filter { it.dictName == dictId }
                                    .forEach { group -> 
                                        orderedPitches.addAll(group.pitchPositions.toList())
                                    }
                            }
                            
                            // 2. Catch remaining pitches from dictionaries not in priority list
                            for (group in allPitches) {
                                if (group.dictName !in priorityList) {
                                    orderedPitches.addAll(group.pitchPositions.toList())
                                }
                            }

                            val allDictIds = allPitches.map { it.dictName }.distinct()

                            if (orderedPitches.isNotEmpty()) {
                                // Sort dictionary IDs by priority and convert to Titles
                                val sortedTitles = allDictIds.sortedBy { 
                                    val idx = priorityList.indexOf(it)
                                    if (idx == -1) Int.MAX_VALUE else idx
                                }.map { getDictionaryTitle(context, it) }

                                add(buildJsonObject {
                                    put("dictName", sortedTitles.joinToString(", "))
                                    putJsonArray("pitchPositions") {
                                        for (pos in orderedPitches) {
                                            add(JsonPrimitive(pos))
                                        }
                                    }
                                })
                            }
                        } else {
                            // Classic Hoshi-style display: one block per dictionary
                            for (group in allPitches) {
                                val title = getDictionaryTitle(context, group.dictName)
                                add(buildJsonObject {
                                    put("dictName", title)
                                    putJsonArray("pitchPositions") {
                                        for (pos in group.pitchPositions.distinct()) {
                                            add(JsonPrimitive(pos))
                                        }
                                    }
                                })
                            }
                        }
                    }
                })
            })
        }
    }
}



private fun disableSafeBrowsingForDictionary(webView: WebView) {
    val settings = webView.settings

    val disabledViaAndroidX = runCatching {
        val featureClass = Class.forName("androidx.webkit.WebViewFeature")
        val featureValue = featureClass.getField("SAFE_BROWSING_ENABLE").get(null) as String
        val isSupportedMethod = featureClass.getMethod("isFeatureSupported", String::class.java)
        val supported = isSupportedMethod.invoke(null, featureValue) as? Boolean ?: false
        if (!supported) return@runCatching false

        val compatClass = Class.forName("androidx.webkit.WebSettingsCompat")
        val setter = compatClass.getMethod(
            "setSafeBrowsingEnabled",
            WebSettings::class.java,
            Boolean::class.javaPrimitiveType,
        )
        setter.invoke(null, settings, false)
        true
    }.getOrDefault(false)

    if (!disabledViaAndroidX && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = false
    }
}


internal fun getDictionaryBootstrapHtml(
    context: Context,
    colorScheme: androidx.compose.material3.ColorScheme? = null,
    isDark: Boolean? = null,
    seedColor: Int? = null,
    isAmoled: Boolean = false,
    fontFamily: String = "",
    eInkMode: Boolean = false,
    languageCode: String = "",
): String {
    val css = dictionaryBaseCss.getOrPut(Unit) {
        readTextAsset(context.applicationContext, "dictionary/base.css")
    }
    val js = dictionaryRendererJs.getOrPut(Unit) {
        readTextAsset(context.applicationContext, "dictionary/renderer.js").replace("</script", "<\\/script")
    }

    val fontUrl = FontManager.getFontUri(context, fontFamily)
    val fontFaceCss = if (fontUrl != null) {
        """
          @font-face {
            font-family: 'HoshiCustomFont';
            src: url('$fontUrl');
          }
          :root, body, #entries {
            font-family: 'HoshiCustomFont' !important;
          }
        """.trimIndent()
    } else if (fontFamily.isNotBlank()) {
        var ff = fontFamily
        if (ff == "System Serif") ff = "serif"
        else if (ff == "System Sans-Serif") ff = "sans-serif"
        """
          :root, body, #entries {
            font-family: '$ff' !important;
          }
        """.trimIndent()
    } else ""

    val dynamicThemeCss = if (colorScheme != null) {
        val accentHex = "#%06X".format(0xFFFFFF and colorScheme.primary.toArgb())
        val onAccentHex = "#%06X".format(0xFFFFFF and colorScheme.onPrimary.toArgb())
        val fgHex = "#%06X".format(0xFFFFFF and colorScheme.onSurface.toArgb())
        val bgHex = if (isAmoled && isDark == true) {
            "#000000"
        } else {
            "#%06X".format(0xFFFFFF and colorScheme.surface.toArgb())
        }
        val secondaryHex = "#%06X".format(0xFFFFFF and colorScheme.onSurfaceVariant.toArgb())
        // In AMOLED dark mode, surfaceVariant and outlineVariant are NOT overridden by
        // getColorScheme() (overrideDarkSurfaceContainers=false), so they stay tinted.
        // Force them to pure-black derivatives so the popup is truly black, not grey.
        val borderHex = if (isAmoled && isDark == true) {
            "rgba(255,255,255,0.10)"
        } else {
            "#%06X".format(0xFFFFFF and colorScheme.outlineVariant.toArgb())
        }
        val hoverHex = if (isAmoled && isDark == true) {
            "rgba(255,255,255,0.07)"
        } else {
            "#%06X".format(0xFFFFFF and colorScheme.surfaceVariant.toArgb())
        }
        val tabBgHex = if (isAmoled && isDark == true) {
            "#0d0d0d"
        } else {
            "#%06X".format(0xFFFFFF and colorScheme.surfaceContainer.toArgb())
        }
        """
          <style id="dynamic-theme">
            :root, :root[data-theme="dark"], :root[data-theme="light"] {
                --accent: $accentHex;
                --on-accent: $onAccentHex;
                --fg: $fgHex;
                --bg: $bgHex;
                --secondary: $secondaryHex;
                --border: $borderHex;
                --hover-bg: $hoverHex;
                --tab-bg: $tabBgHex;
                --pronunciation-annotation-color: $fgHex;
            }
          </style>
        """
    } else ""
    
    val eInkAttr = if (eInkMode) "true" else "false"

    val themeAttr = if (isDark == true) "dark" else "light"
    val langAttr = if (languageCode.isNotEmpty()) """lang="$languageCode" """ else ""

    return """
        <!doctype html>
        <html $langAttr data-theme="$themeAttr" data-chima-eink-mode="$eInkAttr">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
          <style>$css</style>$dynamicThemeCss
          <style>$fontFaceCss</style>
          <style id="dictionary-styles"></style>
          <style id="chima-custom-css"></style>
          <script>
            window.AnkiBridge = {
              addToAnki: function(index, glossary, selectedDict, popupSelection) {
                var url = "anki://add?index=" + index;
                if (glossary !== undefined && glossary !== null) url += "&glossary=" + glossary;
                if (selectedDict) url += "&selected_dict=" + encodeURIComponent(selectedDict);
                if (popupSelection) url += "&popup_selection=" + encodeURIComponent(popupSelection);
                window.location.href = url;
              },
              openInAnki: function(index, glossary, selectedDict, popupSelection) {
                var url = "anki://open?index=" + index;
                if (glossary !== undefined && glossary !== null) url += "&glossary=" + glossary;
                if (selectedDict) url += "&selected_dict=" + encodeURIComponent(selectedDict);
                if (popupSelection) url += "&popup_selection=" + encodeURIComponent(popupSelection);
                window.location.href = url;
              }
            };
          </script>
        </head>
        <body>
          <main id="entries" class="entries"></main>
          <script>$js</script>
        </body>
        </html>
    """.trimIndent()
}


private fun readTextAsset(context: Context, assetPath: String): String {
    return context.assets.open(assetPath).use { input ->
        input.readBytes().toString(Charsets.UTF_8)
    }
}

private val dictionaryBaseCss = java.util.concurrent.ConcurrentHashMap<Unit, String>()
private val dictionaryRendererJs = java.util.concurrent.ConcurrentHashMap<Unit, String>()

private val dictionaryTitleCache = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun getDictionaryTitle(context: Context, dirName: String): String {
    return dictionaryTitleCache.getOrPut(dirName) {
        val dictionariesDir = File(context.getExternalFilesDir(null), "dictionaries")
        val dictDir = File(dictionariesDir, dirName)
        val indexFile = File(dictDir, "index.json")
        if (!indexFile.exists()) return@getOrPut dirName

        try {
            val json = indexFile.readText()
            org.json.JSONObject(json).optString("title", dirName)
        } catch (e: Exception) {
            dirName
        }
    }
}

fun getDictionaryColorScheme(
    isDark: Boolean,
    isAmoled: Boolean,
    seedColor: Int,
): ColorScheme {
    val uiPreferences = Injekt.get<UiPreferences>()
    val baseScheme = CustomColorScheme(
        seed = Color(seedColor),
        style = PaletteStyle.TonalSpot // Low-key, subtle gradient
    )
    return baseScheme.getColorScheme(isDark, isAmoled, false)
}

internal fun stopDictionaryAudio(webView: WebView) {
    (webView.tag as? DictionaryWebViewState)?.wordAudioBridge?.stopAudio()
}
