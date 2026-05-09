package eu.kanade.tachiyomi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.tachiyomi.ui.player.controls.PlayerControlsOverlay
import eu.kanade.tachiyomi.ui.player.mpv.MPVView

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onMPVViewCreated: (MPVView) -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekRelative: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { context ->
                MPVView(context).also(onMPVViewCreated)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.isLoading) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        PlayerControlsOverlay(
            isPlaying = state.isPlaying,
            animeTitle = state.anime?.title,
            episodeName = state.episode?.name,
            currentPositionSec = state.currentPositionSec,
            durationSec = state.durationSec,
            doubleTapSeekSec = state.doubleTapSeekSec,
            onPlayPause = onPlayPause,
            onSeek = onSeek,
            onSeekRelative = onSeekRelative,
            onBack = onBack,
        )
    }
}
