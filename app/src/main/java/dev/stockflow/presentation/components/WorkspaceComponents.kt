package dev.stockflow.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stockflow.domain.ConnectionState
import dev.stockflow.domain.QueueStatus

@Composable
fun StatusPill(text: String, color: Color = MaterialTheme.colorScheme.primary,
    background: Color = MaterialTheme.colorScheme.primaryContainer) {
    Surface(color = background, shape = CircleShape) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun WorkspaceCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { content() }
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, placeholder = { Text(label) },
        leadingIcon = { Icon(StockFlowIcons.Search, null, Modifier.size(20.dp)) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onQuery("") }) { Icon(StockFlowIcons.Close, "Очистить поиск", Modifier.size(18.dp)) } }
        } else null,
        singleLine = true, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ErrorBanner(message: String?, onDismiss: () -> Unit) {
    if (message != null) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) { Icon(StockFlowIcons.Close, "Закрыть сообщение", Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun SyncBanner(status: LoadState<QueueStatus>, connection: ConnectionState, onClick: (() -> Unit)? = null) {
    val queue = (status as? LoadState.Ready)?.value
    val accent = if ((queue?.conflicts ?: 0) > 0 || (queue?.blocked ?: 0) > 0) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary
    WorkspaceCard(onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = accent.copy(alpha = 0.09f), shape = MaterialTheme.shapes.small) {
                Icon(if ((queue?.blocked ?: 0) > 0) StockFlowIcons.Conflict else StockFlowIcons.Sync,
                    null, Modifier.padding(10.dp).size(20.dp), tint = accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(when {
                    status is LoadState.Failed -> "Не удалось прочитать очередь"
                    queue == null -> "Проверяем изменения…"
                    queue.conflicts > 0 -> "Конфликтов: ${queue.conflicts}"
                    queue.blocked > 0 -> "Отправка требует внимания"
                    queue.pending > 0 -> "Ожидают отправки: ${queue.pending}"
                    else -> "Нет изменений к отправке"
                }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).background(when (connection) {
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                        ConnectionState.OFFLINE -> MaterialTheme.colorScheme.tertiary
                        ConnectionState.UNKNOWN -> MaterialTheme.colorScheme.outline
                    }, CircleShape))
                    Text(when (connection) {
                        ConnectionState.CONNECTED -> "Сеть подключена"
                        ConnectionState.OFFLINE -> "Нет сети · можно продолжать учёт"
                        ConnectionState.UNKNOWN -> "Проверяем подключение"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onClick != null) Icon(StockFlowIcons.Next, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, description: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(20.dp).size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun LoadingState() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
}

@Composable
fun LoadError(onRetry: () -> Unit) {
    EmptyState(StockFlowIcons.Conflict, "Не удалось загрузить данные", "Попробуйте ещё раз. Сохранённые данные остаются на устройстве.") {
        TextButton(onClick = onRetry) { Text("Повторить") }
    }
}
