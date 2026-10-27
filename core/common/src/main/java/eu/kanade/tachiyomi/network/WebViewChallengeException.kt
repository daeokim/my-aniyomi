package eu.kanade.tachiyomi.network

import okhttp3.Request
import java.io.IOException

/** A failed normal site check that the user can open explicitly; never exposes tokens in its message. */
class WebViewChallengeException private constructor(
    message: String,
    val url: String,
    val headers: Map<String, String>,
    cause: Throwable,
) : IOException(message, cause) {
    companion object {
        private val allowedHeaders = setOf("user-agent", "referer", "origin", "accept", "accept-language")

        fun safeHeaders(headers: Map<String, String>): Map<String, String> =
            headers.filterKeys { it.lowercase() in allowedHeaders }

        fun forRequest(message: String, request: Request, cause: Throwable): IOException {
            // A WebView GET cannot safely replay a POST, and credentials must not appear in a URL handoff.
            if (request.method !in setOf("GET", "HEAD") || !request.url.isHttps ||
                request.url.username.isNotEmpty() || request.url.password.isNotEmpty()
            ) return IOException(message, cause)
            return WebViewChallengeException(
                message, request.url.toString(),
                safeHeaders(request.headers.toMultimap().mapValues { it.value.first() }), cause,
            )
        }

        fun find(error: Throwable): WebViewChallengeException? =
            generateSequence(error) { it.cause }.take(12).filterIsInstance<WebViewChallengeException>().firstOrNull()
    }
}
