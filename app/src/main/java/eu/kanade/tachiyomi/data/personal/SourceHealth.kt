package eu.kanade.tachiyomi.data.personal

import java.net.*
import javax.net.ssl.SSLException

/** Records the stage of real user requests; never stores URLs, cookies or credentials. */
object SourceHealth {
    private val last = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    fun message(stage: String, error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val message = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        val reason = when {
            chain.any { it is UnknownHostException } -> "주소/DNS 연결 실패"
            chain.any { it is SSLException } -> "보안 연결 실패"
            chain.any { it is SocketTimeoutException } -> "연결 시간 초과"
            "403" in message || "cloudflare" in message || "challenge" in message -> "접근 제한 또는 사이트 확인 필요"
            "401" in message || "로그인" in message -> "로그인 또는 접근 확인 필요"
            "404" in message -> "대상을 찾을 수 없음"
            "이미지" in message || "parse" in message || "json" in message -> "응답 형식 또는 콘텐츠 확인 필요"
            else -> "불러오기 실패"
        }
        return "$stage · $reason"
    }
    fun record(sourceKey: String, stage: String, error: Throwable?) {
        val key = "$sourceKey:$stage"
        val value = if (error == null) "$stage · 확인됨" else message(stage, error)
        val now = System.currentTimeMillis()
        val previous = last[key]
        if (previous?.first == value && now - previous.second < 30_000) return
        last[key] = value to now
        PersonalStore.text("health:$sourceKey:$stage").set(value)
        PersonalStore.long("health_time:$sourceKey:$stage").set(System.currentTimeMillis())
    }
    fun summary(sourceKey: String): String = listOf("목록", "회차", "이미지", "재생").joinToString("\n") { stage ->
        val value = PersonalStore.text("health:$sourceKey:$stage").get()
        val at = PersonalStore.long("health_time:$sourceKey:$stage").get()
        if (value.isBlank()) "$stage · 아직 확인하지 않음" else "$value · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(at))}"
    }
}
