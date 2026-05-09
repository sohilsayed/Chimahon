package eu.kanade.tachiyomi.ui.player.mpv

import `is`.xyz.mpv.MPVLib
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class PlayerObserver(
    private val onPositionChanged: (positionSec: Long) -> Unit = {},
    private val onDurationChanged: (durationSec: Long) -> Unit = {},
    private val onPauseChanged: (paused: Boolean) -> Unit = {},
    private val onEofReached: () -> Unit = {},
) : MPVLib.EventObserver {

    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {
        dispatchNumericProperty(property, value)
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            MPVView.PROP_PAUSE -> onPauseChanged(value)
            MPVView.PROP_EOF_REACHED -> if (value) onEofReached()
        }
    }

    override fun eventProperty(property: String, value: Double) {
        dispatchNumericProperty(property, value.toLong())
    }

    override fun eventProperty(property: String, value: String) {}

    override fun event(eventId: Int) {}

    override fun efEvent(err: String?) {
        logcat(LogPriority.ERROR) { err ?: "Playback error" }
    }

    private fun dispatchNumericProperty(property: String, value: Long) {
        when (property) {
            MPVView.PROP_TIME_POS -> onPositionChanged(value)
            MPVView.PROP_DURATION -> onDurationChanged(value)
        }
    }
}
