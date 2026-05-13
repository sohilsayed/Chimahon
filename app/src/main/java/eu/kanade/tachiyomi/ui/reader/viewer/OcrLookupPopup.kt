package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.alpha
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.theme.colorscheme.CustomColorScheme
import chimahon.DictionaryRepository
import chimahon.LookupResult
import chimahon.MediaInfo
import chimahon.anki.AnkiCardCreator
import chimahon.anki.AnkiResult
import chimahon.util.ImageEncoder
import eu.kanade.tachiyomi.ui.dictionary.DictionaryEntryWebView
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryColorScheme
import eu.kanade.tachiyomi.ui.dictionary.TabInfo
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths
import eu.kanade.tachiyomi.util.system.toast
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID
import kotlin.math.roundToInt

/** One entry in the recursive-lookup history stack. */
private data class LookupFrame(
    val id: String = UUID.randomUUID().toString(),
    val query: String,
    val results: List<LookupResult>,
    val styles: List<chimahon.DictionaryStyle>,
    val mediaDataUris: Map<String, String>,
    val existingExpressions: Set<String>,
)


@Composable
fun OcrLookupPopup(
    lookupString: String,
    fullText: String,
    charOffset: Int,
    onDismiss: () -> Unit,
    webView: WebView,
    repository: DictionaryRepository,
    anchorX: Float,
    anchorY: Float,
    anchorWidth: Float = 0f,
    anchorHeight: Float = 0f,
    isVertical: Boolean,
    activeProfile: chimahon.anki.AnkiProfile,
    mediaInfo: MediaInfo? = null,
    screenshot: Bitmap? = null,
    onRequestScreenshot: (() -> Bitmap?)? = null,
    onCropTriggered: ((Long, Int?) -> Unit)? = null,
    initialLookupDeferred: kotlinx.coroutines.Deferred<chimahon.DictionaryRepository.LookupResult2>? = null,
    usePopup: Boolean = true,
    onTermMatched: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember {
        mutableStateOf(initialLookupDeferred != null && !initialLookupDeferred.isCompleted)
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ── Lookup history stack ──────────────────────────────────────────────
    val lookupStack = remember { mutableStateListOf<LookupFrame>() }
    var activeTabIndex by remember { mutableIntStateOf(0) }

    val currentFrame: LookupFrame? = lookupStack.getOrNull(activeTabIndex)
    val results = currentFrame?.results ?: emptyList()
    val styles = currentFrame?.styles ?: emptyList()
    val mediaDataUris = currentFrame?.mediaDataUris ?: emptyMap()
    val existingExpressions = currentFrame?.existingExpressions ?: emptySet()
    var contentReady by remember { mutableStateOf(false) }
    var hasRenderedContent by remember { mutableStateOf(false) }
    var lookupGeneration by remember { mutableIntStateOf(0) }

    /** Build the [TabInfo] list that is passed to the JS tab bar. */
    fun buildTabs(): List<TabInfo> = lookupStack.mapIndexed { i, frame ->
        TabInfo(
            label = frame.query.take(16),
            active = i == activeTabIndex,
        )
    }


    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
    val popupWidthPref by dictionaryPreferences.popupWidth().collectAsState()
    val popupHeightPref by dictionaryPreferences.popupHeight().collectAsState()
    val popupModePref by dictionaryPreferences.popupMode().collectAsState()
    val popupFontSizePref by dictionaryPreferences.fontSize().collectAsState()

    val ankiEnabled = activeProfile.ankiEnabled
    val ankiDeck = activeProfile.ankiDeck
    val ankiModel = activeProfile.ankiModel
    val ankiFieldMap = activeProfile.ankiFieldMap
    val ankiDupCheck = activeProfile.ankiDupCheck
    val ankiDupScope = activeProfile.ankiDupScope
    val ankiDupAction = activeProfile.ankiDupAction
    val ankiTags = activeProfile.ankiTags
    val cropMode = activeProfile.ankiCropMode

    val showFreqHarmonic by dictionaryPreferences.showFrequencyHarmonic().collectAsState()
    val groupTerms by dictionaryPreferences.groupTerms().collectAsState()
    val showPitchDiagram by dictionaryPreferences.showPitchDiagram().collectAsState()
    val showPitchNumber by dictionaryPreferences.showPitchNumber().collectAsState()
    val showPitchText by dictionaryPreferences.showPitchText().collectAsState()
    val customCss by dictionaryPreferences.customCss().collectAsState()
    val wordAudioEnabled by dictionaryPreferences.wordAudioEnabled().collectAsState()
    val groupPitches by dictionaryPreferences.groupPitches().collectAsState()

    val systemIsDark = isSystemInDarkTheme()
    val amoled by dictionaryPreferences.themeDarkAmoled().collectAsState()
    val customColor by dictionaryPreferences.customColor().collectAsState()

    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val seedColor = if (customColor == 0) uiPreferences.colorTheme().get() else customColor
    val isDark = remember(seedColor, customColor, systemIsDark) {
        if (customColor != 0) Color(seedColor).luminance() < 0.5f else systemIsDark
    }
    val colorScheme = remember(isDark, amoled, seedColor) {
        getDictionaryColorScheme(isDark, amoled, seedColor)
    }
    val BgColor = remember(isDark, amoled, seedColor, colorScheme) {
        if (amoled && isDark) Color.Black else colorScheme.surface
    }

    /** Perform a dictionary lookup and push a new frame onto the stack. */
    fun pushLookup(query: String, isRecursive: Boolean = false, deferredResult: kotlinx.coroutines.Deferred<chimahon.DictionaryRepository.LookupResult2>? = null) {
        val cleanQuery = if (isRecursive) {
            query.replace(Regex("[\\s\\p{Punct}「」『』【】（）〔〕［］｛｝〈〉《》…、。！？!?]+"), "").trim()
        } else {
            query.trim()
        }

        if (isRecursive) {
            if (cleanQuery.isBlank()) return
            // Ignore if entirely ascii/english letters and numbers
            if (cleanQuery.all { it.code <= 127 }) return
        }

        val finalQuery = if (isRecursive) cleanQuery else query
        val generation = ++lookupGeneration
        val shouldShowLoading = !isRecursive && !hasRenderedContent

        fun handleResult(result: chimahon.DictionaryRepository.LookupResult2, phaseStart: Long) {
            if (generation != lookupGeneration) return
            if (isRecursive && result.results.isEmpty()) {
                if (shouldShowLoading) isLoading = false
                return
            }

            // Create frame and push immediately — popup shows NOW
            val frame = LookupFrame(
                id = UUID.randomUUID().toString(),
                query = finalQuery,
                results = result.results,
                styles = result.styles,
                mediaDataUris = result.mediaDataUris,
                existingExpressions = emptySet(),
            )

            // Truncate any forward history past the current index, then push
            while (lookupStack.size > activeTabIndex + 1) lookupStack.removeAt(lookupStack.size - 1)
            lookupStack.add(frame)
            activeTabIndex = lookupStack.size - 1
            errorMessage = result.error

            // Hide loading spinner — popup is visible
            isLoading = false

            if (!isRecursive && result.results.isNotEmpty()) {
                val firstMatched = result.results.firstOrNull()?.matched
                if (firstMatched != null) {
                    val charCount = firstMatched.codePointCount(0, firstMatched.length)
                    scope.launch(Dispatchers.Main) {
                        onTermMatched?.invoke(charCount)
                    }
                }
            }

            // Anki duplicate check runs in background, doesn't block UI
            if (ankiEnabled && result.results.isNotEmpty()) {
                val uniqueExpressions = result.results.map { it.term.expression }.distinct()
                scope.launch(Dispatchers.IO) {
                    val existing = AnkiCardCreator.checkExistingCards(
                        context = context,
                        expressions = uniqueExpressions,
                        deckName = ankiDeck,
                        dupScope = ankiDupScope,
                    )
                    withContext(Dispatchers.Main) {
                        val frameIndex = lookupStack.indexOfFirst { it.id == frame.id }
                        if (frameIndex >= 0) {
                            lookupStack[frameIndex] = lookupStack[frameIndex].copy(existingExpressions = existing)
                        }
                        Log.i(
                            "DictionaryPopup",
                            "anki_check_ms=${android.os.SystemClock.elapsedRealtime() - phaseStart} expressions=${uniqueExpressions.size}",
                        )
                    }
                }
            }

            // Load media in background
            scope.launch(Dispatchers.IO) {
                val media = repository.loadMediaAsync(finalQuery, result.results)
                if (media.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val frameIndex = lookupStack.indexOfFirst { it.id == frame.id }
                        if (frameIndex >= 0) {
                            lookupStack[frameIndex] = lookupStack[frameIndex].copy(mediaDataUris = media)
                        }
                    }
                }
            }
        }

        val deferred = deferredResult ?: scope.async(Dispatchers.IO) {
            val termPaths = getDictionaryPaths(context, activeProfile)
            repository.lookup(finalQuery, termPaths, activeProfile.languageCode)
        }

        if (!deferred.isCompleted) {
            // Must await in a coroutine
            scope.launch {
                if (shouldShowLoading) {
                    isLoading = true
                }
                errorMessage = null
                val phaseStart = android.os.SystemClock.elapsedRealtime()
                try {
                    val result = deferred.await()
                    handleResult(result, phaseStart)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (generation != lookupGeneration) return@launch
                    errorMessage = e.message ?: "Lookup failed"
                    isLoading = false
                }
            }
        } else {
            // Synchronous lookup (no nested coroutine)
            // Session is warm, lookup is fast (~10-20ms) — no need for Dispatchers.IO hop
            errorMessage = null
            val phaseStart = android.os.SystemClock.elapsedRealtime()
            try {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                val result = deferred.getCompleted()
                handleResult(result, phaseStart)
            } catch (e: Exception) {
                if (generation != lookupGeneration) return
                errorMessage = e.message ?: "Lookup failed"
                isLoading = false
            }
        }
    }
    val recursiveNavMode by dictionaryPreferences.recursiveLookupMode().collectAsState()

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val maxWidthDp = popupWidthPref.dp.coerceIn(280.dp, configuration.screenWidthDp.dp * 0.9f)
    val maxHeightDp = popupHeightPref.dp.coerceIn(200.dp, configuration.screenHeightDp.dp * 0.8f)

    val popupWidthPx = with(density) { maxWidthDp.toPx() }
    val popupHeightPx = with(density) { maxHeightDp.toPx() }

    val paddingPx = with(density) { 8.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }

    val screenshotFieldMapped = remember(ankiFieldMap) {
        try {
            val fieldMap = org.json.JSONObject(ankiFieldMap)
            fieldMap.keys().asSequence().any { key ->
                val value = fieldMap.getString(key)
                value.contains(chimahon.anki.Marker.SCREENSHOT)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun performAnkiLookup(
        index: Int,
        glossaryIndex: Int?,
        selectedDict: String? = null,
        popupSelection: String? = null,
        forceOpen: Boolean = false,
    ) {
        val result = results.getOrNull(index) ?: return

        // Local helper to update the state, which triggers the optimized JS call via DictionaryEntryWebView
        fun updateStatus(expression: String) {
            val frame = currentFrame ?: return
            val frameIndex = lookupStack.indexOfFirst { it.id == frame.id }
            if (frameIndex >= 0) {
                val newExisting = frame.existingExpressions + expression
                lookupStack[frameIndex] = lookupStack[frameIndex].copy(existingExpressions = newExisting)
            }
        }

        val shouldUseCropMode = screenshotFieldMapped && cropMode == "crop" && onCropTriggered != null

        if (shouldUseCropMode) {
            scope.launch {
                val ankiResult = AnkiCardCreator.addToAnki(
                    context = context,
                    result = result,
                    deck = ankiDeck,
                    model = ankiModel,
                    fieldMapJson = ankiFieldMap,
                    tags = ankiTags,
                    dupCheck = ankiDupCheck,
                    dupScope = ankiDupScope,
                    dupAction = ankiDupAction,
                    sentence = fullText,
                    offset = charOffset,
                    media = mediaInfo,
                    glossaryIndex = glossaryIndex,
                    selection = result.matched,
                    selectedDict = selectedDict,
                    popupSelection = popupSelection,
                    styles = styles,
                    forceOpen = forceOpen,
                )
                if (ankiResult is AnkiResult.Success || ankiResult is AnkiResult.CardExists || ankiResult is AnkiResult.OpenCard) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        updateStatus(result.term.expression)
                        onDismiss()
                        if (ankiResult is AnkiResult.Success) {
                            onCropTriggered.invoke(ankiResult.noteId, glossaryIndex)
                        }
                    }
                } else {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        when (ankiResult) {
                            is AnkiResult.PermissionDenied -> context.toast(MR.strings.pref_anki_permission_denied)
                            is AnkiResult.Error -> context.toast(
                                context.stringResource(MR.strings.anki_card_error, ankiResult.message),
                            )
                            is AnkiResult.NotConfigured -> context.toast(MR.strings.anki_not_configured)
                            else -> {}
                        }
                    }
                }
            }
        } else {
            scope.launch {
                val encoding = onRequestScreenshot?.invoke()?.let { ImageEncoder.encode(it) }
                val ankiResult = AnkiCardCreator.addToAnki(
                    context = context,
                    result = result,
                    deck = ankiDeck,
                    model = ankiModel,
                    fieldMapJson = ankiFieldMap,
                    tags = ankiTags,
                    dupCheck = ankiDupCheck,
                    dupScope = ankiDupScope,
                    dupAction = ankiDupAction,
                    sentence = fullText,
                    offset = charOffset,
                    media = mediaInfo,
                    glossaryIndex = glossaryIndex,
                    screenshotBytes = encoding?.bytes,
                    selection = result.matched,
                    selectedDict = selectedDict,
                    popupSelection = popupSelection,
                    styles = styles,
                    forceOpen = forceOpen,
                )
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    when (ankiResult) {
                        is AnkiResult.Success -> {
                            updateStatus(result.term.expression)
                            context.toast(MR.strings.anki_card_added)
                        }
                        is AnkiResult.CardExists -> {
                            updateStatus(result.term.expression)
                            context.toast(MR.strings.anki_card_exists)
                        }
                        is AnkiResult.OpenCard -> {
                            updateStatus(result.term.expression)
                            chimahon.anki.AnkiDroidBridge(context).guiEditNote(ankiResult.noteId)
                        }
                        is AnkiResult.PermissionDenied -> context.toast(MR.strings.pref_anki_permission_denied)
                        is AnkiResult.Error -> context.toast(
                            context.stringResource(MR.strings.anki_card_error, ankiResult.message),
                        )
                        is AnkiResult.NotConfigured -> context.toast(MR.strings.anki_not_configured)
                    }
                }
            }
        }
    }

    val onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = if (ankiEnabled) {
        { index, glossaryIndex, selectedDict, popupSelection, forceOpen ->
            performAnkiLookup(index, glossaryIndex, selectedDict, popupSelection, forceOpen)
        }
    } else {
        null
    }

    // === Popup Positioning: priority-based 4-direction ===
    data class PopupLayoutResult(val x: Float, val y: Float, val widthPx: Float, val heightPx: Float)

    val layoutResult = remember(
        anchorX, anchorY, anchorWidth, anchorHeight,
        screenWidthPx, screenHeightPx, popupWidthPx, popupHeightPx, isVertical, popupModePref,
    ) {
        val w: Float
        val h: Float
        val bestX: Float
        val bestY: Float

        when (popupModePref) {
            "full_width" -> {
                w = screenWidthPx
                h = minOf(popupHeightPx, screenHeightPx)
                bestX = 0f
                val expH = anchorHeight
                val bottomY = screenHeightPx - h
                val overlapsWord = anchorWidth > 0f && anchorHeight > 0f &&
                    bottomY < anchorY + expH
                bestY = if (overlapsWord) 0f else bottomY
            }
            "full_height" -> {
                w = minOf(popupWidthPx, screenWidthPx * 0.5f, screenWidthPx - paddingPx * 2)
                h = screenHeightPx - paddingPx * 2
                val acx = anchorX + anchorWidth / 2f
                bestY = paddingPx
                bestX = if (acx < screenWidthPx / 2f) {
                    (screenWidthPx - w - paddingPx).coerceAtLeast(paddingPx)
                } else {
                    paddingPx
                }
            }
            else -> {
            w = minOf(popupWidthPx, screenWidthPx)
            h = minOf(popupHeightPx, screenHeightPx)

            val ax = anchorX
            val ay = anchorY
            val aw = anchorWidth
            val ah = anchorHeight
            val acx = ax + aw / 2f
            val acy = ay + ah / 2f

            val expW = aw
            val expH = ah

            // 4 candidate positions (top-left corner of popup)
            data class Pos(val x: Float, val y: Float)

            val right  = Pos(ax + expW + gapPx, acy - h / 2f) // Right of full term
            val left   = Pos(ax - w - gapPx, acy - h / 2f) // Left of anchor
            val below  = Pos(acx - w / 2f, ay + expH + gapPx) // Below full term
            val above  = Pos(acx - w / 2f, ay - h - gapPx) // Above anchor

            val all = listOf(right, left, below, above)

            // Priority order: 0=Right, 1=Left, 2=Below, 3=Above
            val order = if (isVertical) {
                if (acx < screenWidthPx / 2f) listOf(0, 1, 2, 3) else listOf(1, 0, 2, 3)
            } else {
                if (acy < screenHeightPx / 2f) listOf(2, 3, 0, 1) else listOf(3, 2, 0, 1)
            }

            var bx = paddingPx
            var by = paddingPx
            var found = false

            for (idx in order) {
                val p = all[idx]
                val cx = p.x.coerceIn(paddingPx, screenWidthPx - w - paddingPx)
                val cy = p.y.coerceIn(paddingPx, screenHeightPx - h - paddingPx)

                val overlaps = aw > 0f && ah > 0f &&
                    cx < ax + expW && cx + w > ax &&
                    cy < ay + expH && cy + h > ay

                if (!overlaps) {
                    bx = cx
                    by = cy
                    found = true
                    break
                }
            }

            if (!found) {
                val pref = all[order[0]]
                bestX = pref.x.coerceIn(paddingPx, screenWidthPx - w - paddingPx)
                bestY = pref.y.coerceIn(paddingPx, screenHeightPx - h - paddingPx)
            } else {
                bestX = bx
                bestY = by
            }
            }
        }

        PopupLayoutResult(bestX, bestY, w, h)
    }

    val actualWidthDp = with(density) { layoutResult.widthPx.toDp() }
    val actualHeightDp = with(density) { layoutResult.heightPx.toDp() }


    LaunchedEffect(lookupString, ankiEnabled, ankiModel) {
        if (lookupString.isBlank()) {
            lookupGeneration++
            lookupStack.clear()
            activeTabIndex = 0
            isLoading = false
            contentReady = false
            hasRenderedContent = false
            return@LaunchedEffect
        }
        // Reset the stack and load the initial term
        lookupStack.clear()
        activeTabIndex = 0
        isLoading = initialLookupDeferred != null && !initialLookupDeferred.isCompleted
        contentReady = false
        hasRenderedContent = false
        pushLookup(lookupString, deferredResult = initialLookupDeferred)
    }

    // Callbacks forwarded from the WebView bridge
    val onRecursiveLookup: (String) -> Unit = { word -> pushLookup(word, isRecursive = true) }
    val onTabSelect: (Int) -> Unit = { idx ->
        if (idx in lookupStack.indices) activeTabIndex = idx
    }
    val onBack: () -> Unit = {
        if (activeTabIndex > 0) activeTabIndex--
    }

    val outsideTapInteraction = remember { MutableInteractionSource() }

    @Composable
    fun PopupContent() {
        val swipeThreshold = with(density) { 56.dp.toPx() }
        Surface(
            modifier = modifier
                .width(actualWidthDp)
                .height(actualHeightDp)
                .alpha(if (contentReady || errorMessage != null) 1f else 0f)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val ignoreHeight = with(density) { 48.dp.toPx() }
                            if (down.position.y < ignoreHeight) continue

                            var totalDragX = 0f
                            var totalDragY = 0f
                            try {
                                drag(down.id) { change ->
                                    val delta = change.positionChange()
                                    totalDragX += delta.x
                                    totalDragY += delta.y
                                    // If horizontal swipe is dominant and exceeds threshold, dismiss
                                    if (kotlin.math.abs(totalDragX) > swipeThreshold && 
                                        kotlin.math.abs(totalDragX) > kotlin.math.abs(totalDragY) * 1.5f
                                    ) {
                                        onDismiss()
                                        throw CancellationException("Dismissed by swipe")
                                    }
                                }
                            } catch (e: CancellationException) {
                                if (e.message != "Dismissed by swipe") throw e
                            }
                        }
                    }
                }
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    // Consume taps on the popup itself to prevent them from falling through to the reader
                },
            shape = RoundedCornerShape(8.dp),
            color = BgColor,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (currentFrame != null) {
                    DictionaryEntryWebView(
                    results = results,
                    styles = styles,
                    mediaDataUris = mediaDataUris,
                    placeholder = if (isLoading) "" else "No results found",
                    headerText = lookupString.take(20) + if (lookupString.length > 20) "…" else "",
                    fontSize = popupFontSizePref,
                    showFrequencyHarmonic = showFreqHarmonic,
                    groupTerms = groupTerms,
                    showPitchDiagram = showPitchDiagram,
                    showPitchNumber = showPitchNumber,
                    showPitchText = showPitchText,
                    activeProfile = activeProfile,
                    existingExpressions = existingExpressions,
                    tabs = buildTabs(),
                    recursiveNavMode = recursiveNavMode,
                    customCss = customCss,
                    wordAudioEnabled = wordAudioEnabled,
                    groupPitches = groupPitches,
                    webViewProvider = { webView },
                    onAnkiLookup = onAnkiLookup,
                    onRecursiveLookup = onRecursiveLookup,
                    onTabSelect = onTabSelect,
                    onBack = onBack,
                        hideOnContentInvalidated = !hasRenderedContent,
                        isLoading = isLoading,
                        onContentReadyChange = { ready ->
                            if (ready) {
                                contentReady = true
                                hasRenderedContent = true
                            } else if (!hasRenderedContent) {
                                contentReady = false
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp).align(Alignment.Center)
                    )
                }
            }
        }
    }

    if (usePopup) {
        Popup(
            offset = IntOffset(layoutResult.x.roundToInt(), layoutResult.y.roundToInt()),
            onDismissRequest = { onDismiss() },
            properties = PopupProperties(
                focusable = false,
                dismissOnClickOutside = false, // Handled by scrim in ChimaReaderActivity
            ),
        ) {
            PopupContent()
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(
                    x = with(LocalDensity.current) { layoutResult.x.toDp() },
                    y = with(LocalDensity.current) { layoutResult.y.toDp() },
                )
        ) {
            PopupContent()
        }
    }
}
