package eu.kanade.presentation.entries.manga.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.recommendations.RelatedManga
import eu.kanade.tachiyomi.data.recommendations.RelatedMangaState

/** Kept above chapter lists in both phone and two-panel layouts; never blocks reading. */
@Composable
fun RelatedMangaSection(
    state: RelatedMangaState,
    onRetry: () -> Unit,
    onChoose: (Int) -> Unit,
    onOpen: (RelatedManga) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("연관 추천작", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onRetry, enabled = state !is RelatedMangaState.Loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "추천작 새로고침")
            }
        }
        when (state) {
            RelatedMangaState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            RelatedMangaState.Empty -> Text(
                "연결된 작품이나 등록된 추천작을 찾지 못했습니다.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            RelatedMangaState.Error -> Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("추천 목록을 불러오지 못했습니다.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text("다시 시도") }
            }
            is RelatedMangaState.Choose -> {
                Text(
                    "추천 기준이 될 작품을 선택해 주세요.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                RelatedCovers(state.candidates) { onChoose(it.id) }
            }
            is RelatedMangaState.Ready -> {
                Text(
                    if (state.sameGenre) "비슷한 장르 · AniList" else "함께 읽을 작품 · AniList",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RelatedCovers(state.items, onOpen)
            }
        }
    }
}

@Composable
private fun RelatedCovers(items: List<RelatedManga>, onClick: (RelatedManga) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val coverWidth = if (maxWidth >= 600.dp) 116.dp else 100.dp
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.id }) { item ->
                Card(
                    onClick = { onClick(item) },
                    modifier = Modifier.width(coverWidth),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                        Text(item.title.take(1), Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineMedium)
                        AsyncImage(
                            model = item.cover,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Text(
                            item.title,
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
