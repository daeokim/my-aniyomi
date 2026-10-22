package eu.kanade.tachiyomi.util.system

import eu.kanade.tachiyomi.network.HttpException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

object ConnectionRecovery {
    fun isTransient(error: Throwable): Boolean {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        if (causes.any { it is CancellationException || it is SSLException || it is SecurityException || it is java.io.FileNotFoundException }) return false
        if (causes.any { it is HttpException }) return false // Authentication, access and server responses need explicit user action.
        return causes.any {
            it is SocketTimeoutException || it is ConnectException || it is NoRouteToHostException ||
                it is UnknownHostException || it is SocketException
        }
    }

    /** mpv exposes a text log, not a structured HTTP error. Only explicit transport failures qualify. */
    fun isTransientPlayback(message: String): Boolean {
        val text = message.lowercase()
        if (Regex("(?:http(?: error)?|status)[^0-9]{0,8}[45][0-9]{2}").containsMatchIn(text) ||
            listOf("certificate", "tls", "ssl", "permission", "no space", "unauthorized", "forbidden").any(text::contains)
        ) return false
        return listOf("connection reset", "connection refused", "network is unreachable", "no route to host",
            "connection timed out", "connection timeout", "timed out", "temporary failure in name resolution").any(text::contains)
    }
}

/** One claim per failed load; a failed automatic retry does not re-arm itself. */
class ConnectionRetry {
    private var spent = false
    @Synchronized fun reset() { spent = false }
    @Synchronized fun claim(error: Throwable?, busy: Boolean): Boolean {
        if (busy || error == null || spent || !ConnectionRecovery.isTransient(error)) return false
        spent = true
        return true
    }
}
