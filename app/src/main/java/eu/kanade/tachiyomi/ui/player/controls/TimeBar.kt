package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.formatTime

@Composable
fun TimeBar(
    currentPositionSec: Long,
    durationSec: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var seekingProgress by remember { mutableFloatStateOf(-1f) }
    val progress = when {
        seekingProgress >= 0f -> seekingProgress
        durationSec > 0 -> currentPositionSec.toFloat() / durationSec
        else -> 0f
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTime(
                if (seekingProgress >= 0f) (seekingProgress * durationSec).toLong() else currentPositionSec,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Slider(
            value = progress,
            onValueChange = { seekingProgress = it },
            onValueChangeFinished = {
                if (seekingProgress >= 0f) {
                    onSeek(seekingProgress)
                    seekingProgress = -1f
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatTime(durationSec),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
