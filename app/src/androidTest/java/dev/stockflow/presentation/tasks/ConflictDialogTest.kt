package dev.stockflow.presentation.tasks

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stockflow.domain.ConflictChoice
import dev.stockflow.domain.InventoryConflict
import dev.stockflow.presentation.conflicts.ConflictDetailsScreen
import dev.stockflow.presentation.theme.StockFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConflictDialogTest {
    @get:Rule val compose = createComposeRule()
    private val conflict = InventoryConflict("operation", 1, "Товар", 12, 7, 1)
    @Test fun keepLocalIsAnExplicitChoice() {
        var choice: ConflictChoice? = null
        compose.setContent { StockFlowTheme { ConflictDetailsScreen(conflict, false, false, null, {}, { choice = it }) } }
        compose.onNodeWithText("На устройстве").assertExists()
        compose.onNodeWithText("На сервере").assertExists()
        compose.onAllNodesWithText("Оставить моё").filter(hasClickAction()).onFirst().performClick()
        compose.runOnIdle { assertEquals(ConflictChoice.KEEP_LOCAL, choice) }
    }
    @Test fun useServerIsAnExplicitChoice() {
        var choice: ConflictChoice? = null
        compose.setContent { StockFlowTheme { ConflictDetailsScreen(conflict, false, false, null, {}, { choice = it }) } }
        compose.onAllNodesWithText("Принять серверное").filter(hasClickAction()).onFirst().performClick()
        compose.runOnIdle { assertEquals(ConflictChoice.USE_SERVER, choice) }
    }
    @Test fun cannotSubmitTwiceWhileResolving() {
        compose.setContent { StockFlowTheme { ConflictDetailsScreen(conflict, false, true, null, {}, {}) } }
        compose.onNodeWithText("Сохранение…").assertIsNotEnabled()
        compose.onAllNodesWithText("Принять серверное").filter(hasClickAction()).onFirst().assertIsNotEnabled()
    }
}
