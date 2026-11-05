package dev.stockflow.presentation.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.stockflow.R
import dev.stockflow.domain.InventoryItem
import dev.stockflow.domain.parseQuantity

@Composable
internal fun QuantityEditor(
    item: InventoryItem, draft: String, saving: Boolean, error: String?, canSave: Boolean,
    onDraft: (String) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("По учёту: ${item.expectedQuantity} шт.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = draft, onValueChange = onDraft, enabled = !saving && canSave,
                    label = { Text("Фактическое количество") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = draft.isNotEmpty() && parseQuantity(draft) == null,
                    supportingText = { Text(stringResource(R.string.quantity_hint)) })
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = parseQuantity(draft) != null && !saving && canSave, onClick = onSave) {
                Text(if (saving) "Сохранение…" else "Сохранить")
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Отмена") } },
    )
}
