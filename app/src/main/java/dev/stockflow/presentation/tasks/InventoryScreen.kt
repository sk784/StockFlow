package dev.stockflow.presentation.tasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stockflow.domain.*
import dev.stockflow.presentation.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    task: TaskProgress?, products: LoadState<List<InventoryItem>>, queue: LoadState<QueueStatus>,
    connection: ConnectionState, conflictedItems: Set<Long>, pendingItems: Set<Long>,
    editingAllowed: Boolean, saving: Boolean, error: String?,
    onBack: () -> Unit, onScan: () -> Unit, onItem: (InventoryItem) -> Unit,
    onSync: () -> Unit, onRetry: () -> Unit, onDismissError: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf(ItemFilter.ALL.name) }
    val filter = ItemFilter.entries.firstOrNull { it.name == selectedFilter } ?: ItemFilter.ALL
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Инвентаризация", style = MaterialTheme.typography.titleLarge)
                    Text(task?.title ?: "Товары задания", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }, navigationIcon = { IconButton(onClick = onBack) { Icon(StockFlowIcons.Back, "Назад") } })
        },
        bottomBar = {
            if (products is LoadState.Ready && editingAllowed && !saving) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                    androidx.compose.material3.Button(onClick = onScan,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                            .semantics { contentDescription = "Сканировать товар" }) {
                        Icon(StockFlowIcons.Scan, null, Modifier.size(20.dp))
                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                        Text("Сканировать товар")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { SyncBanner(queue, connection, onSync) }
            if (error != null) item { ErrorBanner(error, onDismissError) }
            when (products) {
                LoadState.Loading -> item { LoadingState() }
                LoadState.Failed -> item { LoadError(onRetry) }
                is LoadState.Ready -> {
                    val all = products.value
                    val counted = all.count { it.countedQuantity != null }
                    val differences = all.count { it.countedQuantity != null && it.countedQuantity != it.expectedQuantity }
                    item {
                        Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.large) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("Прогресс задания", style = MaterialTheme.typography.labelMedium)
                                        Text("$counted / ${all.size}", style = MaterialTheme.typography.headlineLarge)
                                    }
                                    Column {
                                        Text("Расхождения", style = MaterialTheme.typography.labelMedium)
                                        Text(differences.toString(), style = MaterialTheme.typography.headlineMedium)
                                    }
                                }
                                LinearProgressIndicator(progress = { if (all.isEmpty()) 0f else counted.toFloat() / all.size },
                                    modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onPrimary,
                                    trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f))
                                Text(if (all.isNotEmpty() && counted == all.size) "Все товары посчитаны" else "Осталось посчитать: ${all.size - counted}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item { SearchField(query, { query = it }, "Название или штрихкод") }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ItemFilter.entries.forEach { value ->
                                FilterChip(selected = filter == value, onClick = { selectedFilter = value.name }, label = { Text(value.label()) })
                            }
                        }
                    }
                    val filtered = filterItems(all, query, filter)
                    item { Text("Товары · ${filtered.size} из ${all.size}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (filtered.isEmpty()) item {
                        EmptyState(StockFlowIcons.Search, if (all.isEmpty()) "В задании нет товаров" else "Товары не найдены",
                            if (all.isEmpty()) "Обновите задания на главном экране." else "Измените запрос или фильтр.")
                    }
                    items(filtered, key = { it.id }) { item ->
                        InventoryItemCard(item, item.id in conflictedItems, item.id in pendingItems,
                            onClick = if (editingAllowed && !saving) ({ onItem(item) }) else null)
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryItemCard(item: InventoryItem, conflict: Boolean, pending: Boolean, onClick: (() -> Unit)?) {
    WorkspaceCard(onClick = onClick) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(item.barcode, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    conflict -> StatusPill("Конфликт", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                    pending -> StatusPill("К отправке", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer)
                    item.countedQuantity != null -> StatusPill("Посчитано")
                    else -> StatusPill("Не посчитано", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainer)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("По учёту: ${item.expectedQuantity}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Факт: ${item.countedQuantity?.toString() ?: "—"}", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            val difference = item.countedQuantity?.minus(item.expectedQuantity)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(when {
                    conflict -> "Разрешите конфликт количества"
                    difference == null -> "Внести количество"
                    difference == 0 -> "Количество совпадает"
                    else -> "Расхождение: ${if (difference > 0) "+" else ""}$difference"
                }, style = MaterialTheme.typography.labelMedium, color = when {
                    conflict -> MaterialTheme.colorScheme.error
                    difference != null && difference != 0 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                })
                Icon(if (difference == 0 && !conflict) StockFlowIcons.Check else StockFlowIcons.Next,
                    null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun ItemFilter.label() = when (this) {
    ItemFilter.ALL -> "Все"
    ItemFilter.UNCOUNTED -> "Не посчитаны"
    ItemFilter.COUNTED -> "Посчитаны"
    ItemFilter.DIFFERENCES -> "С расхождениями"
}
