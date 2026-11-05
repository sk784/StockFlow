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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stockflow.domain.ConnectionState
import dev.stockflow.domain.QueueStatus
import dev.stockflow.domain.TaskFilter
import dev.stockflow.domain.TaskProgress
import dev.stockflow.domain.TaskStage
import dev.stockflow.domain.filterTasks
import dev.stockflow.presentation.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksDashboard(
    tasks: LoadState<List<TaskProgress>>, queue: LoadState<QueueStatus>, connection: ConnectionState,
    refreshing: Boolean, saving: Boolean, error: String?,
    onRefresh: () -> Unit, onDemo: () -> Unit, onTask: (Long) -> Unit,
    onSync: () -> Unit, onRetry: () -> Unit, onDismissError: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf(TaskFilter.ALL.name) }
    val filter = TaskFilter.entries.firstOrNull { it.name == selectedFilter } ?: TaskFilter.ALL
    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text("STOCKFLOW", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), fontWeight = FontWeight.Bold)
                Text("Задания", style = MaterialTheme.typography.headlineMedium)
            }
        }, actions = {
            IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(StockFlowIcons.Sync, "Обновить задания") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("tasks-list"), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SyncBanner(queue, connection, onSync) }
            if (error != null) item { ErrorBanner(error, onDismissError) }
            when (tasks) {
                LoadState.Loading -> item { LoadingState() }
                LoadState.Failed -> item { LoadError(onRetry) }
                is LoadState.Ready -> {
                    val all = tasks.value
                    if (all.isEmpty()) {
                        item {
                            EmptyState(StockFlowIcons.Tasks, "Первое задание", "Загрузите задания с сервера или попробуйте учёт на учебном складе.") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = onRefresh, enabled = !refreshing) { Text(if (refreshing) "Загрузка…" else "Загрузить задания") }
                                    TextButton(onClick = onDemo, enabled = !saving) { Text("Создать демо-задание") }
                                }
                            }
                        }
                    } else {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OverviewNumber(all.count { it.stage != TaskStage.COUNTED }.toString(), "Открыто", Modifier.weight(1f))
                                OverviewNumber(all.sumOf { it.countedItems }.toString(), "Посчитано", Modifier.weight(1f))
                                OverviewNumber(all.sumOf { it.totalItems }.toString(), "Товаров", Modifier.weight(1f))
                            }
                        }
                        item { SearchField(query, { query = it }, "Задание или место хранения") }
                        item {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TaskFilter.entries.forEach { value ->
                                    FilterChip(selected = filter == value, onClick = { selectedFilter = value.name }, label = { Text(value.label()) })
                                }
                            }
                        }
                        val filtered = filterTasks(all, query, filter)
                        item {
                            Text("На устройстве · ${filtered.size} из ${all.size}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (filtered.isEmpty()) item {
                            EmptyState(StockFlowIcons.Search, "Заданий не найдено", "Измените запрос или выберите другой статус.")
                        }
                        items(filtered, key = { it.id }) { task -> TaskCard(task) { onTask(task.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewNumber(value: String, label: String, modifier: Modifier) {
    WorkspaceCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TaskCard(task: TaskProgress, onClick: () -> Unit) {
    WorkspaceCard(onClick = onClick) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ЗАДАНИЕ ${task.id.toString().padStart(3, '0')}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                val label = if (task.conflicts > 0) "Конфликт" else when (task.stage) {
                    TaskStage.NEW -> "Не начато"
                    TaskStage.IN_PROGRESS -> "В работе"
                    TaskStage.COUNTED -> "Посчитано"
                }
                if (task.conflicts > 0) StatusPill(label, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                else StatusPill(label)
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(task.location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${task.countedItems} / ${task.totalItems} товаров", style = MaterialTheme.typography.labelMedium)
                    Text("${(task.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceContainer)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(when {
                    task.conflicts > 0 -> "Разрешите конфликт количества"
                    task.blockedOperations > 0 -> "Отправка требует внимания"
                    task.differences > 0 -> "Расхождения: ${task.differences}"
                    task.pendingOperations > 0 -> "К отправке: ${task.pendingOperations}"
                    else -> "Открыть инвентаризацию"
                }, style = MaterialTheme.typography.labelMedium,
                    color = if (task.needsAttention) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                Icon(StockFlowIcons.Next, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun TaskFilter.label() = when (this) {
    TaskFilter.ALL -> "Все"
    TaskFilter.NEW -> "Не начаты"
    TaskFilter.IN_PROGRESS -> "В работе"
    TaskFilter.COUNTED -> "Посчитаны"
    TaskFilter.ATTENTION -> "Требуют внимания"
}
