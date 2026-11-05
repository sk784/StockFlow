package dev.stockflow.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class UpdateQuantityTest {
    @Test
    fun `zero is a valid counted quantity`() = runBlocking {
        val repository = RecordingRepository()
        UpdateQuantity(repository)(42, "0")
        assertEquals(42L to 0, repository.lastUpdate)
    }

    @Test
    fun `largest supported quantity can be saved`() = runBlocking {
        val repository = RecordingRepository()
        UpdateQuantity(repository)(7, Int.MAX_VALUE.toString())
        assertEquals(7L to Int.MAX_VALUE, repository.lastUpdate)
    }

    @Test
    fun `invalid quantities never reach the repository`() {
        val repository = RecordingRepository()
        val update = UpdateQuantity(repository)
        listOf("", " ", "-1", "+1", "1.5", "1,5", "1 000", "2147483648", "abc").forEach { input ->
            try {
                runBlocking { update(42, input) }
                fail("Accepted invalid input: $input")
            } catch (_: IllegalArgumentException) {
                // Validation must reject the value before calling the repository.
            }
        }
        assertNull(repository.lastUpdate)
    }

    @Test
    fun `leading zeroes preserve numeric value`() {
        assertEquals(12, parseQuantity("0012"))
    }

    private class RecordingRepository : InventoryRepository {
        var lastUpdate: Pair<Long, Int>? = null
        override fun observeTasks(): Flow<List<InventoryTask>> = emptyFlow()
        override fun observeItems(taskId: Long): Flow<List<InventoryItem>> = emptyFlow()
        override suspend fun createDemoTask() = Unit
        override suspend fun discardRejectedChanges(operationId: String) = Unit
        override suspend fun updateQuantity(itemId: Long, quantity: Int) {
            lastUpdate = itemId to quantity
        }
    }
}
