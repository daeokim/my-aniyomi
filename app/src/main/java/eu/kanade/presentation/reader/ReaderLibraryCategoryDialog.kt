package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderLibraryCategoryDialog(
    categories: List<Category>,
    selected: List<Long>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    var selection by remember(categories, selected) { mutableStateOf(selected.toSet()) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.action_move_category)) },
        text = {
            LazyColumn {
                items(categories, key = { it.id }) { category ->
                    val toggle = {
                        selection = if (category.id in selection) selection - category.id else selection + category.id
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = toggle),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = category.id in selection, onCheckedChange = { toggle() })
                        Text(category.name, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selection.toList()) }) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
