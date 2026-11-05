package dev.stockflow.presentation.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import dev.stockflow.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stockflow.domain.*
import dev.stockflow.presentation.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynchronizationScreen(
    operations: LoadState<List<SyncOperation>>, queue: LoadState<QueueStatus>, connection: ConnectionState,
    scheduling: Boolean, error: String?, onSend: () -> Unit, onConflict: (String) -> Unit,
    onConflicts: () -> Unit, onRetry: () -> Unit, onDismissError: () -> Unit,
    saving: Boolean = false, onDiscard: (String) -> Unit = {},
) {
    val status = (queue as? LoadState.Ready)?.value
    var discardId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = (operations as? LoadState.Ready)?.value?.firstOrNull { it.operationId == discardId }
    val busy = saving || scheduling
    LaunchedEffect(operations, saving) {
        if (!saving && operations is LoadState.Ready && selected == null) discardId = null
    }
    if (selected != null && selected.status == OperationStatus.REJECTED) {
        AlertDialog(
            onDismissRequest = { if (!saving) discardId = null },
            title = { Text(stringResource(R.string.discard_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.discard_explanation, selected.itemName))
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = { onDiscard(selected.operationId) }, enabled = !busy) {
                    Text(stringResource(if (saving) R.string.discard_in_progress else R.string.discard_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { discardId = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Синхронизация", style = MaterialTheme.typography.headlineMedium) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("sync-list"), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SyncBanner(queue, connection, if ((status?.conflicts ?: 0) > 0) onConflicts else null) }
            if (error != null) item { ErrorBanner(error, onDismissError) }
            item {
                WorkspaceCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Изменения под контролем", style = MaterialTheme.typography.titleLarge)
                        Text(if (connection == ConnectionState.OFFLINE)
                            "Данные сохранены на устройстве. Отправка продолжится после подключения."
                        else "Отправка работает в фоне. Записи исчезают из очереди после подтверждения сервера.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            QueueNumber(status?.pending ?: 0, "В очереди")
                            QueueNumber(status?.blocked ?: 0, "Приостановлено")
                        }
                        if ((status?.conflicts ?: 0) > 0) {
                            Text("Сначала разрешите конфликт — отправка очереди приостановлена.", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = onConflicts) { Text("Перейти к конфликтам") }
                        } else {
                            Button(onClick = onSend, enabled = (status?.pending ?: 0) > 0 && !busy,
                                modifier = Modifier.fillMaxWidth()) {
                                Icon(StockFlowIcons.Sync, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (scheduling) "Планирование…" else "Отправить сейчас")
                            }
                        }
                    }
                }
            }
            when (operations) {
                LoadState.Loading -> item { LoadingState() }
                LoadState.Failed -> item { LoadError(onRetry) }
                is LoadState.Ready -> {
                    if (operations.value.isEmpty()) item {
                        EmptyState(StockFlowIcons.Check, "Очередь пуста", "Новые записи появятся здесь после изменения количества товара.")
                    } else {
                        item { Text("Операции · ${operations.value.size}", style = MaterialTheme.typography.titleMedium) }
                        items(operations.value, key = { it.operationId }) { operation ->
                            OperationCard(operation, onConflict, { id -> onDismissError(); discardId = id }, busy)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueNumber(value: Int, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.headlineLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OperationCard(operation: SyncOperation, onConflict: (String) -> Unit,
    onDiscard: (String) -> Unit, busy: Boolean) {
    WorkspaceCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(operation.taskTitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(operation.itemName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(operation.quantity.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            val label = when (operation.status) {
                OperationStatus.QUEUED -> "Ожидает отправки"
                OperationStatus.RETRY -> "Повторим автоматически"
                OperationStatus.REJECTED -> "Отклонено сервером"
                OperationStatus.CONFLICT -> "Конфликт данных"
            }
            if (operation.blocked) StatusPill(label, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
            else StatusPill(label, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer)
            if (operation.attempts > 0) Text("Неудачных попыток: ${operation.attempts}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (operation.lastError != null) Text(operation.lastError, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (operation.status == OperationStatus.CONFLICT) {
                TextButton(onClick = { onConflict(operation.operationId) }) { Text("Разрешить конфликт") }
            } else if (operation.status == OperationStatus.REJECTED) {
                TextButton(onClick = { onDiscard(operation.operationId) }, enabled = !busy) {
                    Text(stringResource(R.string.discard_action))
                }
            }
        }
    }
}
