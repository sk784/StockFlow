package dev.stockflow.presentation.tasks

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stockflow.domain.InventoryItem
import dev.stockflow.presentation.theme.StockFlowTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuantityDialogTest {
    @get:Rule val compose = createComposeRule()
    private val item = InventoryItem(1, 1, "Товар", "123", 10, null)
    @Test fun emptyInputCannotSaveAndZeroCanBeSaved() {
        var saved: String? = null
        compose.setContent {
            var draft by remember { mutableStateOf("") }
            StockFlowTheme { QuantityEditor(item, draft, false, null, true, { draft = it }, {}, { saved = draft }) }
        }
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("0")
        compose.onNodeWithText("Сохранить").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("0", saved) }
    }
    @Test fun overflowingQuantityCannotSave() {
        compose.setContent {
            var draft by remember { mutableStateOf("") }
            StockFlowTheme { QuantityEditor(item, draft, false, null, true, { draft = it }, {}, {}) }
        }
        compose.onNode(hasSetTextAction()).performTextInput("2147483648")
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
    }
    @Test fun cancelDoesNotSendDraft() {
        var saved: String? = null
        var dismissed = false
        compose.setContent {
            var draft by remember { mutableStateOf("") }
            StockFlowTheme { QuantityEditor(item, draft, false, null, true, { draft = it }, { dismissed = true }, { saved = draft }) }
        }
        compose.onNode(hasSetTextAction()).performTextInput("12")
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertNull(saved); assertTrue(dismissed) }
    }
}
