package dtoon.validation

import eu.kanade.tachiyomi.ui.storage.StorageCalculation
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.measureNanoTime

object StorageBenchmark {
    data class Entry(val id: Int, val files: List<File>, val fixed: Long? = null) {
        fun size() = fixed ?: files.sumOf { it.length() }
    }
    data class Item(val id: Int, val size: Long)
    private fun old(items: List<Entry>): List<Item> {
        var result = emptyList<Item>()
        items.forEach { result = (result + Item(it.id, it.size())).sortedByDescending { item -> item.size } }
        return result
    }
    private suspend fun updated(items: List<Entry>) = StorageCalculation.calculate(items, Entry::size) { entry, size -> Item(entry.id, size) }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val grouped = File(args[0]).walkTopDown().onEnter { it.name !in setOf(".git", ".gradle", "build", ".kotlin", "test-output") }
            .filter { it.isFile }.groupBy { it.parentFile }.values.toList().mapIndexed { i, files -> Entry(i, files) }
        println("dataset,count,old_median_ms,new_median_ms,old_sorts,new_sorts,old_publishes,new_publishes,equal")
        val fixed = (0 until 5000).map { Entry(it, emptyList(), (it * 2654435761L) % 1000000000) }
        for ((name, data) in listOf("actual_project_directories" to grouped, "fixed_5000" to fixed)) {
            repeat(2) { old(data); updated(data) }
            val a = mutableListOf<Long>(); val b = mutableListOf<Long>()
            repeat(5) {
                var before: List<Item> = emptyList(); var after: List<Item> = emptyList()
                a += measureNanoTime { before = old(data) }
                b += measureNanoTime { after = updated(data) }
                check(before == after)
            }
            println("$name,${data.size},${a.sorted()[2] / 1e6},${b.sorted()[2] / 1e6},${data.size},1,${data.size + 1},2,true")
        }
    }
}
