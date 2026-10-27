package eu.kanade.tachiyomi.network

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** Shared by source status and retries; Chromium's network errors are IOExceptions, not SocketExceptions. */
object TransportErrors {
    enum class Kind { DNS, TIMEOUT, CONNECTION, SECURITY, OTHER }

    fun kind(error: Throwable): Kind {
        val chain = generateSequence(error) { it.cause }.take(12).toList()
        if (chain.any { it is SSLException || it is CertificateException || it is SecurityException }) return Kind.SECURITY
        if (chain.any { it is CancellationException || it is HttpException || it is FileNotFoundException }) return Kind.OTHER
        if (chain.any { HTTP_ERROR.containsMatchIn(it.message.orEmpty()) || it.message.orEmpty().contains("cloudflare", true) ||
                it.message.orEmpty().contains("challenge", true) }) return Kind.OTHER
        val codes = chain.flatMap { item -> CODE.findAll(item.message.orEmpty()).map { it.groupValues[1].uppercase() }.toList() }
        if (codes.any { it.startsWith("ERR_CERT_") || it.startsWith("ERR_SSL_") }) return Kind.SECURITY
        return when {
            chain.any { it is UnknownHostException } || "ERR_NAME_NOT_RESOLVED" in codes -> Kind.DNS
            chain.any { it is SocketTimeoutException } || codes.any { it in TIMEOUTS } -> Kind.TIMEOUT
            chain.any { it is ConnectException || it is NoRouteToHostException || it is SocketException } ||
                codes.any { it in CONNECTIONS } -> Kind.CONNECTION
            else -> Kind.OTHER
        }
    }

    fun isRetryable(error: Throwable): Boolean {
        if (generateSequence(error) { it.cause }.take(12).any {
                it.message.orEmpty().contains("Retryable=false", ignoreCase = true)
            }
        ) return false
        return kind(error) in setOf(Kind.DNS, Kind.TIMEOUT, Kind.CONNECTION)
    }

    private val CODE = Regex("\\bnet::(ERR_[A-Z0-9_]+)\\b", RegexOption.IGNORE_CASE)
    private val HTTP_ERROR = Regex("\\bHTTP(?: error)?\\s*[:=]?\\s*[45]\\d\\d\\b", RegexOption.IGNORE_CASE)
    private val TIMEOUTS = setOf("ERR_TIMED_OUT", "ERR_CONNECTION_TIMED_OUT")
    private val CONNECTIONS = setOf(
        "ERR_CONNECTION_RESET", "ERR_CONNECTION_CLOSED", "ERR_CONNECTION_REFUSED", "ERR_CONNECTION_ABORTED",
        "ERR_ADDRESS_UNREACHABLE", "ERR_INTERNET_DISCONNECTED", "ERR_NETWORK_CHANGED",
    )
}
