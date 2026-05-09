package eu.kanade.tachiyomi.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.player.mpv.MPVView
import eu.kanade.tachiyomi.ui.player.mpv.PlayerObserver
import eu.kanade.tachiyomi.ui.player.setting.PlayerOrientation
import eu.kanade.tachiyomi.ui.player.setting.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PlayerActivity : ComponentActivity() {

    private val viewModel by viewModels<PlayerViewModel>()
    private val playerPreferences: PlayerPreferences by lazy { Injekt.get() }

    private var mpvView: MPVView? = null
    private var playerObserver: PlayerObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupImmersiveMode()
        setupScreenFlags()
        setupOrientation()

        setContent {
            PlayerScreen(
                viewModel = viewModel,
                onMPVViewCreated = ::onMPVViewReady,
                onPlayPause = ::togglePlayPause,
                onSeek = ::seekToPercent,
                onSeekRelative = ::seekRelative,
                onBack = { finish() },
            )
        }

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    is PlayerViewModel.Event.Error -> {
                        logcat(LogPriority.ERROR) { "Player error: ${event.message}" }
                        finish()
                    }
                    is PlayerViewModel.Event.PlaybackCompleted -> {}
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun onMPVViewReady(view: MPVView) {
        mpvView = view

        val configDir = filesDir.resolve("mpv").absolutePath
        val cacheDir = cacheDir.resolve("mpv").absolutePath
        logcat(LogPriority.DEBUG, tag = "Player") { "Initializing MPV" }
        view.initialize(configDir, cacheDir)
        logcat(LogPriority.DEBUG, tag = "Player") { "MPV initialized" }

        val observer = PlayerObserver(
            onPositionChanged = { pos -> viewModel.updatePosition(pos) },
            onDurationChanged = { dur -> viewModel.updateDuration(dur) },
            onPauseChanged = { paused -> viewModel.updatePlaybackState(!paused) },
            onEofReached = { viewModel.onPlaybackCompleted() },
        )
        playerObserver = observer
        MPVLib.addObserver(observer)

        val animeId = intent.getLongExtra(PlayerViewModel.KEY_ANIME_ID, -1L)
        val episodeId = intent.getLongExtra(PlayerViewModel.KEY_EPISODE_ID, -1L)
        val videoUrl = resolveVideoUrl(intent)

        lifecycleScope.launchIO {
            viewModel.init(animeId, episodeId, videoUrl)

            val state = viewModel.state.value
            val episode = state.episode
            if (episode == null) {
                logcat(LogPriority.ERROR, tag = "Player") { "Episode not loaded after init" }
                return@launchIO
            }
            val url = episode.url
            logcat(LogPriority.DEBUG, tag = "Player") { "Episode URL: $url" }
            if (url.isNotBlank()) {
                val resumePos = state.currentPositionSec
                    .takeIf { it > 0 } ?: episode.lastSecondSeen
                val playUrl = resolvePlaybackUrl(url)
                logcat(LogPriority.DEBUG, tag = "Player") { "Playing: $playUrl (resume=$resumePos)" }
                withContext(Dispatchers.Main) {
                    view.playFile(playUrl)
                    logcat(LogPriority.DEBUG, tag = "Player") { "playFile called" }
                    if (resumePos > 0) {
                        view.seekTo(resumePos.toInt())
                    }
                }
            }
        }
    }

    private fun togglePlayPause() {
        mpvView?.cyclePause()
    }

    private fun seekToPercent(percent: Float) {
        val duration = mpvView?.duration ?: return
        mpvView?.seekTo((percent * duration).toInt())
    }

    private fun seekRelative(deltaSec: Int) {
        mpvView?.seekRelative(deltaSec)
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) viewModel.saveProgress()
    }

    override fun onDestroy() {
        playerObserver?.let { MPVLib.removeObserver(it) }
        mpvView?.destroy()
        mpvView = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun setupImmersiveMode() {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupScreenFlags() {
        if (playerPreferences.keepScreenOn().get()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupOrientation() {
        val orientation = PlayerOrientation.fromPreference(
            playerPreferences.defaultPlayerOrientation().get(),
        )
        requestedOrientation = orientation.flag
    }

    private fun resolvePlaybackUrl(url: String): String {
        if (url.startsWith("content://")) {
            val uri = Uri.parse(url)
            val fd = contentResolver.openFileDescriptor(uri, "r")?.detachFd()
                ?: return url
            return "fdclose://$fd"
        }
        return url
    }

    private fun resolveVideoUrl(intent: Intent): String? {
        if (intent.action == Intent.ACTION_VIEW) {
            return intent.data?.toString()
        }
        return intent.getStringExtra(EXTRA_VIDEO_URL)
    }

    companion object {
        private const val EXTRA_VIDEO_URL = "video_url"

        fun newIntent(context: Context, animeId: Long, episodeId: Long): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(PlayerViewModel.KEY_ANIME_ID, animeId)
                putExtra(PlayerViewModel.KEY_EPISODE_ID, episodeId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        fun newIntent(context: Context, videoUrl: String): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}
