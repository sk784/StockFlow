package dev.stockflow.presentation.conflicts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stockflow.domain.ConflictChoice
import dev.stockflow.domain.InventoryConflict
import dev.stockflow.presentation.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(conflicts: LoadState<List<InventoryConflict>>, error: String?,
    onConflict: (String) -> Unit, onRetry: () -> Unit, onDismissError: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Конфликты", style = MaterialTheme.typography.headlineMedium) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (error != null) item { ErrorBanner(error, onDismissError) }
            when (conflicts) {
                LoadState.Loading -> item { LoadingState() }
                LoadState.Failed -> item { LoadError(onRetry) }
                is LoadState.Ready -> {
                    if (conflicts.value.isEmpty()) item {
                        EmptyState(StockFlowIcons.Check, "Конфликтов нет", "Одновременные изменения одного товара на разных устройствах появятся здесь.")
                    } else {
                        item {
                            Text("Нужно выбрать количество", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("Ваши изменения сохранены. Уточните данные, чтобы продолжить отправку.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        items(conflicts.value, key = { it.operationId }) { conflict ->
                            WorkspaceCard(onClick = { onConflict(conflict.operationId) }) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    StatusPill("Изменён на другом устройстве", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                                    Text(conflict.itemName, style = MaterialTheme.typography.titleMedium)
                                    Comparison(conflict)
                                    TextButton(onClick = { onConflict(conflict.operationId) }) { Text("Разрешить конфликт") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictDetailsScreen(conflict: InventoryConflict?, loading: Boolean, saving: Boolean, error: String?,
    onBack: () -> Unit, onChoice: (ConflictChoice) -> Unit, onDismissError: () -> Unit = {},
    loadFailed: Boolean = false, onRetry: () -> Unit = {}) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Разрешение конфликта", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = onBack, enabled = !saving) { Icon(StockFlowIcons.Back, "Назад") } }) },
        bottomBar = {
            if (conflict != null && !loading && !loadFailed) Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onChoice(ConflictChoice.KEEP_LOCAL) }, enabled = !saving,
                        modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Сохранение…" else "Оставить моё") }
                    OutlinedButton(onClick = { onChoice(ConflictChoice.USE_SERVER) }, enabled = !saving,
                        modifier = Modifier.fillMaxWidth()) { Text("Принять серверное") }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (error != null) item { ErrorBanner(error, onDismissError) }
            when {
                loading -> item { LoadingState() }
                loadFailed -> item { LoadError(onRetry) }
                conflict == null -> item { EmptyState(StockFlowIcons.Check, "Конфликт уже разрешён", "Можно продолжить инвентаризацию.") }
                else -> {
                    item {
                        StatusPill("Требуется решение", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                        Spacer(Modifier.height(14.dp))
                        Text(conflict.itemName, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("На сервере есть другое количество этого товара. Выберите, какие данные использовать.",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { Comparison(conflict) }
                    item {
                        WorkspaceCard {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("Оставить моё", style = MaterialTheme.typography.titleMedium)
                                    Text("Отправим ваше количество. Если данные снова изменятся на другом устройстве, появится новый конфликт.",
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("Принять серверное", style = MaterialTheme.typography.titleMedium)
                                    Text("Ваше значение и неотправленные изменения только этого товара будут заменены последними данными сервера.",
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Comparison(conflict: InventoryConflict) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        QuantityPanel("На устройстве", conflict.localQuantity, Modifier.weight(1f), true)
        QuantityPanel("На сервере", conflict.remoteQuantity, Modifier.weight(1f), false)
    }
}

@Composable
private fun QuantityPanel(label: String, value: Int?, modifier: Modifier, local: Boolean) {
    Surface(modifier, shape = MaterialTheme.shapes.medium,
        color = if (local) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium)
            Text(if (value == null) "Не посчитано" else "шт.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
