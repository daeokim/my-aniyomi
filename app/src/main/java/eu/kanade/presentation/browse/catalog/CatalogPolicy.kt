package eu.kanade.presentation.browse.catalog

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneId

/** Stable values, shared by manga and video catalogs. */
enum class CatalogTab(val label: String) { POPULAR("인기"), LATEST("최신"), WEEKDAY("요일별"), COMPLETED("완결") }

data class CatalogRequest(val tab: CatalogTab, val weekday: Int, val filters: String, val search: String = "")

data class CatalogCapability(val weekday: Boolean, val completed: Boolean, val latest: Boolean) {
    /** Personal fork: weekday lists are offered for Naver Webtoon only. */
    fun forSource(sourceId: Long, naverId: Long) = if (sourceId == naverId) this else copy(weekday = false)

    /** Tabs to show: the weekday tab disappears entirely where it is not offered. */
    val tabs: List<CatalogTab> get() = CatalogTab.entries.filter { it != CatalogTab.WEEKDAY || weekday }

    /** Saved tabs are stored by [CatalogTab.ordinal] so hiding a tab never shifts the others. */
    fun pageOf(savedTab: Int) = tabs.indexOfFirst { it.ordinal == savedTab }.coerceAtLeast(0)

    fun supports(tab: CatalogTab) = when (tab) {
        CatalogTab.POPULAR -> true
        CatalogTab.LATEST -> latest
        CatalogTab.WEEKDAY -> weekday
        CatalogTab.COMPLETED -> completed
    }
}

object CatalogPolicy {
    fun isSort(name: String) = name.trim().lowercase() in setOf("정렬", "정렬순", "정렬 기준", "sort", "order")
    fun sortNames(tab: CatalogTab) = when (tab) {
        CatalogTab.POPULAR -> setOf("인기순", "인기", "조회순", "조회수순", "popular", "popularity", "views")
        CatalogTab.LATEST -> setOf("최신순", "최신", "업데이트순", "최근 업데이트순", "latest", "updated", "recently updated")
        else -> emptySet()
    }
    fun sortCombinationAllowed(tab: CatalogTab, native: Boolean, searchSort: Boolean) =
        tab !in setOf(CatalogTab.POPULAR, CatalogTab.LATEST) || native || searchSort

    fun today() = LocalDate.now(ZoneId.of("Asia/Seoul")).dayOfWeek.value
    fun dayIndex(values: List<String>, day: Int): Int = values.indexOfFirst { value ->
        val text = value.trim().lowercase()
        val ko = listOf("월", "화", "수", "목", "금", "토", "일")[day.coerceIn(1, 7) - 1]
        val en = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")[day.coerceIn(1, 7) - 1]
        text == ko || text == "${ko}요일" || text.startsWith(en)
    }
    fun isDay(name: String) = name.trim().lowercase() in setOf("요일", "방영요일", "방영 요일", "연재요일", "연재 요일", "day", "weekday", "요일별")
    fun completedIndex(values: List<String>) = values.indexOfFirst { it.trim().lowercase() in setOf("완결", "완결작", "완결작품", "완료", "completed", "finished") }
    fun isStatus(name: String) = name.trim().lowercase() in setOf("상태", "연재", "연재 상태", "연재상태", "방영 상태", "방영상태", "status", "연재/완결")
    fun encode(values: Map<String, String>): String = JsonObject(values.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()
    fun decode(value: String): Map<String, String> = runCatching {
        Json.parseToJsonElement(value).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())
    fun title(raw: String): Pair<String, String> {
        // Preserve the source title; split only trailing display annotations.
        val match = Regex("\\s*[（(]([^()（）]+)[）)]\\s*$").find(raw) ?: return raw to ""
        val title = raw.substring(0, match.range.first).trim()
        return if (title.isEmpty()) raw to "" else title to match.groupValues[1].trim()
    }
    fun columns(widthDp: Float): Int {
        val min = if (widthDp < 600) 96f else 120f
        return ((widthDp - 24 + 8) / (min + 8)).toInt().coerceAtLeast(1)
    }
}
