package eu.kanade.tachiyomi.ui.player.mpv

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import `is`.xyz.mpv.MPVLib
import java.io.File
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class MPVView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private var isInitialized = false
    private var lastSurfaceWidth = -1
    private var lastSurfaceHeight = -1

    init {
        holder.addCallback(this)
    }

    fun initialize(configDir: String, cacheDir: String) {
        if (isInitialized) return

        File(configDir).mkdirs()
        File(cacheDir).mkdirs()

        MPVLib.create(context, if (isDebugBuildType) "v" else "warn")
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("ao", "audiotrack")
        MPVLib.setOptionString("hwdec", "auto")
        MPVLib.setOptionString("hwdec-codecs", "all")
        MPVLib.setOptionString("save-position-on-quit", "no")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("keep-open", "always")
        MPVLib.setOptionString("demux-max-bytes", "32MiB")
        MPVLib.setOptionString("demux-max-back-bytes", "16MiB")
        MPVLib.setOptionString("sub-auto", "fuzzy")
        MPVLib.setOptionString("sub-font-size", "55")
        MPVLib.setOptionString("sub-border-size", "3")

        MPVLib.init()
        isInitialized = true

        MPVLib.observeProperty(PROP_TIME_POS, MPVLib.mpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty(PROP_DURATION, MPVLib.mpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty(PROP_PAUSE, MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty(PROP_EOF_REACHED, MPVLib.mpvFormat.MPV_FORMAT_FLAG)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        logcat(LogPriority.DEBUG, tag = "Player") { "surfaceCreated, isInitialized=$isInitialized" }
        if (isInitialized) {
            MPVLib.attachSurface(holder.surface)
            MPVLib.setOptionString("force-window", "yes")
            logcat(LogPriority.DEBUG, tag = "Player") { "Surface attached" }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width == lastSurfaceWidth && height == lastSurfaceHeight) return
        lastSurfaceWidth = width
        lastSurfaceHeight = height
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
    }

    fun playFile(url: String) {
        if (!isInitialized) {
            logcat(LogPriority.ERROR) { "playFile called but MPV not initialized" }
            return
        }
        logcat(LogPriority.DEBUG, tag = "Player") { "MPV loadfile: $url" }
        MPVLib.command(arrayOf("loadfile", url))
        logcat(LogPriority.DEBUG, tag = "Player") { "MPV loadfile command sent" }
    }

    val timePos: Int?
        get() = MPVLib.getPropertyInt(PROP_TIME_POS)

    val duration: Int?
        get() = MPVLib.getPropertyInt(PROP_DURATION)

    val paused: Boolean
        get() = MPVLib.getPropertyBoolean(PROP_PAUSE) ?: false

    fun cyclePause() {
        MPVLib.command(arrayOf("cycle", "pause"))
    }

    fun seekTo(positionSec: Int) {
        if (!isInitialized) return
        MPVLib.command(arrayOf("seek", positionSec.toString(), "absolute"))
    }

    fun seekRelative(deltaSec: Int) {
        if (!isInitialized) return
        MPVLib.command(arrayOf("seek", deltaSec.toString(), "relative"))
    }

    fun destroy() {
        if (!isInitialized) return
        isInitialized = false
        try {
            MPVLib.destroy()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error destroying MPV" }
        }
    }

    companion object {
        const val PROP_TIME_POS = "time-pos"
        const val PROP_DURATION = "duration"
        const val PROP_PAUSE = "pause"
        const val PROP_EOF_REACHED = "eof-reached"
    }
}
