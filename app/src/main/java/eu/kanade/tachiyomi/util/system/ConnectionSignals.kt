package eu.kanade.tachiyomi.util.system

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

data class ConnectionSignal(val networkId: String?, val online: Boolean)

@OptIn(kotlinx.coroutines.FlowPreview::class)
fun Flow<ConnectionSignal>.afterConnectionSettles(): Flow<Unit> =
    distinctUntilChanged().drop(1).debounce(1200).filter { it.online }.map { Unit }
