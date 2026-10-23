package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.tachiyomi.ui.reader.ReaderTocEntry
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** 목차: jump to any chapter without leaving the reader. */
@Composable
fun ReaderChapterListDialog(
    entries: List<ReaderTocEntry>,
    initialIndex: Int,
    onDismissRequest: () -> Unit,
    onOpenChapter: (Long) -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column {
            Text(
                text = stringResource(MR.strings.chapters),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (initialIndex - 2).coerceAtLeast(0))
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
                items(entries, key = { it.id }) { entry ->
                    ChapterRow(entry, onClick = { onOpenChapter(entry.id) })
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(entry: ReaderTocEntry, onClick: () -> Unit) {
    val dimmed = entry.locked || (entry.read && !entry.current)
    val color = when {
        entry.current -> MaterialTheme.colorScheme.primary
        dimmed -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !entry.locked && !entry.current, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.locked) Icon(Icons.Outlined.Lock, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        if (entry.bookmark) Icon(Icons.Filled.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(
            text = entry.title,
            color = color,
            fontWeight = if (entry.current) FontWeight.Bold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.dateUpload > 0) {
            Text(text = relativeDateText(entry.dateUpload), color = color, style = MaterialTheme.typography.bodySmall)
        }
    }
}
