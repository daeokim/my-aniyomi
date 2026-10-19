package eu.kanade.tachiyomi.ui.player

import android.widget.Toast
import eu.kanade.tachiyomi.util.system.toast
import `is`.xyz.mpv.MPVLib
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class PlayerObserver(val activity: PlayerActivity) :
    MPVLib.EventObserver,
    MPVLib.LogObserver {

    override fun eventProperty(property: String) {
        activity.runOnUiThread { activity.onObserverEvent(property) }
    }

    override fun eventProperty(property: String, value: Long) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun eventProperty(property: String, value: Double) {
        activity.runOnUiThread { activity.onObserverEvent(property, value) }
    }

    override fun event(eventId: Int) {
        activity.runOnUiThread { activity.event(eventId) }
    }

    override fun efEvent(err: String?) {
        var errorMessage = err ?: "Error: File ended"
        if (!httpError.isNullOrEmpty()) {
            errorMessage += ": $httpError"
            httpError = null
        }
        logcat(LogPriority.ERROR) { errorMessage }
        activity.runOnUiThread {
            if (activity.recoverStream(errorMessage)) activity.toast("재생 주소를 갱신하고 보던 위치를 복원합니다.", Toast.LENGTH_LONG)
            else activity.toast(eu.kanade.tachiyomi.data.personal.SourceHealth.message("재생", java.io.IOException(errorMessage)), Toast.LENGTH_LONG)
        }
    }

    private var httpError: String? = null

    override fun logMessage(prefix: String, level: Int, text: String) {
        val logPriority = when (level) {
            MPVLib.mpvLogLevel.MPV_LOG_LEVEL_FATAL, MPVLib.mpvLogLevel.MPV_LOG_LEVEL_ERROR -> LogPriority.ERROR
            MPVLib.mpvLogLevel.MPV_LOG_LEVEL_WARN -> LogPriority.WARN
            MPVLib.mpvLogLevel.MPV_LOG_LEVEL_INFO -> LogPriority.INFO
            else -> LogPriority.VERBOSE
        }
        if (text.contains("HTTP error")) httpError = text
        logcat.logcat("mpv/$prefix", logPriority) { text }
    }
}
