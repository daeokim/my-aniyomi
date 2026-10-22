package eu.kanade.tachiyomi.ui.home.personal

import eu.kanade.tachiyomi.data.personal.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DtoonHomePolicyTest {
    private val webtoon = 850164079243120301L
    private val drama = 850164079243120102L
    private fun row(id: Long, number: Double, complete: Boolean = false, bookmark: Boolean = false) =
        CopyRow(id, "회차 $id", "/e/$id", number, complete, bookmark)

    @Test fun `latest feed skips sources without latest so they cannot block caching`() {
        val sources = listOf(HomeSource(1, "A", true, supportsLatest = false), HomeSource(2, "B", true), HomeSource(3, "C", false))
        assertEquals(listOf(2L, 3L), HomeFeedPolicy.pick(sources, null, latest = true).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), HomeFeedPolicy.pick(sources, null, latest = false).map { it.id })
    }
    @Test fun `feed interleaves kinds before filling six slots`() {
        val manga = (1L..5L).map { HomeSource(it, "M$it", true) }
        val video = listOf(HomeSource(drama, "D", false), HomeSource(webtoon, "W", true))
        val picked = HomeFeedPolicy.pick(manga + video, null, latest = true)
        assertEquals(6, picked.size)
        assertEquals(setOf(MediaKind.MANGA, MediaKind.DRAMA, MediaKind.WEBTOON), picked.take(3).map { it.kind }.toSet())
    }
    @Test fun `feed kind filter keeps only that kind`() {
        val sources = listOf(HomeSource(1, "M", true), HomeSource(drama, "D", false))
        assertEquals(listOf(drama), HomeFeedPolicy.pick(sources, MediaKind.DRAMA, latest = false).map { it.id })
    }
    @Test fun `copy merges completion and bookmarks without clearing target`() {
        val source = listOf(row(1, 1.0, complete = true), row(2, 2.0, bookmark = true), row(3, 3.0, complete = true, bookmark = true))
        val target = listOf(row(11, 1.0, complete = true), row(12, 2.0), row(13, 3.0, bookmark = true))
        val plan = ProgressCopyPolicy.plan(source, target, mapOf(1L to 11L, 2L to 12L, 3L to 13L))!!
        assertEquals(listOf(13L), plan.read)
        assertEquals(listOf(12L), plan.bookmark)
        assertEquals(3.0, plan.trackNumber)
    }
    @Test fun `copy refuses when a confirmed target row disappeared or is reused`() {
        val source = listOf(row(1, 1.0, complete = true), row(2, 2.0, complete = true))
        assertNull(ProgressCopyPolicy.plan(source, listOf(row(11, 1.0)), mapOf(1L to 11L, 2L to 12L)))
        assertNull(ProgressCopyPolicy.plan(source, listOf(row(11, 1.0)), mapOf(1L to 11L, 2L to 11L)))
    }
    @Test fun `bookmark only copy never moves trackers`() {
        val plan = ProgressCopyPolicy.plan(listOf(row(1, 5.0, bookmark = true)), listOf(row(11, 5.0)), mapOf(1L to 11L))!!
        assertNull(plan.trackNumber)
        assertTrue(plan.read.isEmpty())
    }
    @Test fun `unknown chapter numbers are not sent to trackers`() {
        val plan = ProgressCopyPolicy.plan(listOf(row(1, -1.0, complete = true)), listOf(row(11, -1.0)), mapOf(1L to 11L))!!
        assertEquals(listOf(11L), plan.read)
        assertNull(plan.trackNumber)
    }
    @Test fun `numbers unique on both sides are suggested and stored matches win`() {
        val source = listOf(row(1, 1.0, complete = true), row(2, 2.0, complete = true), row(3, 3.0, complete = true))
        val target = listOf(row(11, 1.0), row(12, 2.0), row(13, 3.0))
        assertEquals(mapOf(2L to 12L, 3L to 13L), ProgressCopyPolicy.suggest(source, target, mapOf(1L to 11L)))
    }
    @Test fun `ambiguous or unknown numbers stay manual`() {
        val source = listOf(row(1, 1.0, complete = true), row(2, 2.0, complete = true), row(3, 2.0, complete = true), row(4, -1.0, complete = true))
        val target = listOf(row(11, 1.0), row(12, 1.0), row(13, 2.0), row(14, -1.0))
        assertTrue(ProgressCopyPolicy.suggest(source, target, emptyMap()).isEmpty())
    }
    @Test fun `suggestions never reuse a target already matched`() {
        val source = listOf(row(1, 1.0, complete = true), row(2, 5.0, complete = true))
        val target = listOf(row(11, 1.0), row(15, 5.0))
        assertEquals(mapOf(1L to 11L), ProgressCopyPolicy.suggest(source, target, mapOf(2L to 15L)) - 2L)
        assertTrue(ProgressCopyPolicy.suggest(listOf(row(1, 1.0)), target, mapOf(9L to 11L)).isEmpty())
    }
    @Test fun `recent history keeps the newest row per work up to the limit`() {
        data class H(val work: Long, val at: Long?)
        val history = listOf(H(1, 10), H(2, 30), H(1, 40), H(3, null), H(4, 20))
        assertEquals(listOf(H(1, 40), H(2, 30), H(4, 20)), HomeRecentPolicy.latest(history, { it.work }, { it.at }, limit = 3))
        assertEquals(H(3, null), HomeRecentPolicy.latest(history, { it.work }, { it.at }).last())
    }
    @Test fun `identity lookups use the library first and fetch other works once`() = runTest {
        val fetched = mutableListOf<Long>()
        val resolver = WorkIdentityResolver { id -> fetched += id; WorkIdentity(9, "/w/$id") }
        val library = mapOf(1L to WorkIdentity(5, "/lib"))
        assertEquals(WorkIdentity(5, "/lib"), resolver.resolve(1, library))
        repeat(3) { assertEquals(WorkIdentity(9, "/w/2"), resolver.resolve(2, library)) }
        assertEquals(listOf(2L), fetched)
    }
    @Test fun `missing works are retried instead of cached as absent`() = runTest {
        var calls = 0
        val resolver = WorkIdentityResolver { calls++; null }
        repeat(2) { assertNull(resolver.resolve(7, emptyMap())) }
        assertEquals(2, calls)
    }
    @Test fun `continue cards label the last episode number only when it is known`() {
        assertEquals("12화", HomeRecentPolicy.progressLabel(12.0))
        assertEquals("12.5화", HomeRecentPolicy.progressLabel(12.5))
        assertNull(HomeRecentPolicy.progressLabel(-1.0))
        assertNull(HomeRecentPolicy.progressLabel(0.0))
        assertNull(HomeRecentPolicy.progressLabel(null))
    }
}
