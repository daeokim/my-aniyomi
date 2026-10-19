package eu.kanade.presentation.browse.catalog

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/** Snapshot values, never share mutable extension filter objects between pages. */
object AnimeCatalogFilters {
    private fun flatten(filters: List<*>): List<Pair<String, AnimeFilter<*>>> = buildList {
        fun visit(items: List<*>, parent: String) {
            items.forEachIndexed { i, item ->
                if (item !is AnimeFilter<*>) return@forEachIndexed
                val key = "$parent/$i:${item.name}"
                if (item is AnimeFilter.Group<*>) visit(item.state, key) else add(key to item)
            }
        }
        visit(filters, "")
    }
    fun snapshot(filters: AnimeFilterList): String = CatalogPolicy.encode(flatten(filters).mapNotNull { (key, f) ->
        when (f) {
            is AnimeFilter.Select<*> -> key to f.state.toString()
            is AnimeFilter.Text -> key to f.state
            is AnimeFilter.CheckBox -> key to f.state.toString()
            is AnimeFilter.TriState -> key to f.state.toString()
            is AnimeFilter.Sort -> f.state?.let { key to "${it.index}:${it.ascending}" }
            else -> null
        }
    }.toMap())
    fun restore(filters: AnimeFilterList, snapshot: String): AnimeFilterList {
        val saved = CatalogPolicy.decode(snapshot)
        flatten(filters).forEach { (key, f) ->
            val value = saved[key] ?: return@forEach
            when (f) {
                is AnimeFilter.Select<*> -> value.toIntOrNull()?.takeIf { it in f.values.indices }?.let { f.state = it }
                is AnimeFilter.Text -> f.state = value
                is AnimeFilter.CheckBox -> f.state = value.toBooleanStrictOrNull() ?: f.state
                is AnimeFilter.TriState -> value.toIntOrNull()?.takeIf { it in 0..2 }?.let { f.state = it }
                is AnimeFilter.Sort -> {
                    val parts = value.split(':')
                    val index = parts.firstOrNull()?.toIntOrNull()
                    if (index != null && index in f.values.indices) f.state = AnimeFilter.Sort.Selection(index, parts.getOrNull(1) == "true")
                }
                else -> Unit
            }
        }
        return filters
    }
    fun capabilities(filters: AnimeFilterList, latest: Boolean) = CatalogCapability(
        weekday = flatten(filters).any { (_, f) -> f is AnimeFilter.Select<*> && CatalogPolicy.isDay(f.name) && (1..7).all { CatalogPolicy.dayIndex(f.values.map { it.toString() }, it) >= 0 } },
        completed = flatten(filters).any { (_, f) ->
            (f is AnimeFilter.Select<*> && CatalogPolicy.isStatus(f.name) && CatalogPolicy.completedIndex(f.values.map { it.toString() }) >= 0) ||
                (f is AnimeFilter.CheckBox && f.name.trim() in setOf("완결", "완결만", "완결 작품"))
        },
        latest = latest,
    )
    fun apply(filters: AnimeFilterList, request: CatalogRequest): AnimeFilterList {
        restore(filters, request.filters)
        flatten(filters).forEach { (_, f) ->
            val sortNames = when (request.tab) {
                CatalogTab.POPULAR -> setOf("인기순", "인기", "조회순", "조회수순", "popular", "popularity", "views")
                CatalogTab.LATEST -> setOf("최신순", "최신", "업데이트순", "최근 업데이트순", "latest", "updated", "recently updated")
                else -> emptySet()
            }
            if (f is Filter.Select<*> && f.name.trim().lowercase() in setOf("정렬", "정렬순", "정렬 기준", "sort", "order")) {
                val i = f.values.indexOfFirst { it.toString().trim().lowercase() in sortNames }
                if (i >= 0) f.state = i
            }
            if (f is Filter.Sort) {
                val i = f.values.indexOfFirst { it.trim().lowercase() in sortNames }
                if (i >= 0) f.state = Filter.Sort.Selection(i, false)
            }
            if (request.tab == CatalogTab.WEEKDAY && f is AnimeFilter.Select<*> && CatalogPolicy.isDay(f.name)) {
                val i = CatalogPolicy.dayIndex(f.values.map { it.toString() }, request.weekday)
                if (i >= 0) f.state = i
            }
            if (request.tab == CatalogTab.COMPLETED) {
                if (f is AnimeFilter.Select<*> && CatalogPolicy.isStatus(f.name)) {
                    val i = CatalogPolicy.completedIndex(f.values.map { it.toString() }); if (i >= 0) f.state = i
                }
                if (f is AnimeFilter.CheckBox && f.name.trim() in setOf("완결", "완결만", "완결 작품")) f.state = true
            }
        }
        return filters
    }
}
