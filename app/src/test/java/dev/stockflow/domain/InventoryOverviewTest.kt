package dev.stockflow.domain

import org.junit.Assert.*
import org.junit.Test

class InventoryOverviewTest {
    private fun task(id: Long = 1, total: Int = 3, counted: Int = 0, differences: Int = 0, blocked: Int = 0) =
        TaskProgress(id, "Основной склад", "Стеллаж A", total, counted, differences, 0, blocked, 0)
    @Test fun `empty task is not falsely reported as counted`() {
        assertEquals(TaskStage.NEW, task(total = 0).stage)
        assertEquals(0f, task(total = 0).progress)
    }
    @Test fun `progress and stages follow counted items`() {
        assertEquals(TaskStage.NEW, task().stage)
        assertEquals(TaskStage.IN_PROGRESS, task(counted = 1).stage)
        assertEquals(TaskStage.COUNTED, task(counted = 3).stage)
        assertEquals(1f, task(counted = 3).progress)
    }
    @Test fun `task search includes location and combines with status`() {
        val tasks = listOf(task(1), task(2, counted = 1), task(3, counted = 3))
        assertEquals(listOf(2L), filterTasks(tasks, " стеллаж ", TaskFilter.IN_PROGRESS).map { it.id })
        assertTrue(filterTasks(tasks, "unknown", TaskFilter.ALL).isEmpty())
        assertEquals(listOf(3L), filterTasks(tasks, "СКЛАД", TaskFilter.COUNTED).map { it.id })
    }
    @Test fun `attention includes quantity differences and blocked operations`() {
        assertEquals(listOf(2L, 3L), filterTasks(listOf(task(1), task(2, differences = 1), task(3, blocked = 1)), "", TaskFilter.ATTENTION).map { it.id })
    }
    @Test fun `zero counts as counted and difference filter excludes uncounted`() {
        val items = listOf(InventoryItem(1, 1, "Кабель", "00123", 2, null),
            InventoryItem(2, 1, "Кабель", "00124", 2, 0), InventoryItem(3, 1, "Мышь", "00999", 2, 2))
        assertEquals(listOf(2L), filterItems(items, "каБЕЛЬ", ItemFilter.COUNTED).map { it.id })
        assertEquals(listOf(1L), filterItems(items, "", ItemFilter.UNCOUNTED).map { it.id })
        assertEquals(listOf(2L), filterItems(items, "", ItemFilter.DIFFERENCES).map { it.id })
        assertEquals(listOf(3L), filterItems(items, "009", ItemFilter.ALL).map { it.id })
    }
    @Test fun `operation status prioritizes conflicts over retry counters`() {
        val op = SyncOperation("id", 1, "Товар", 1, "Задание", 12, 0, false, null, null, null)
        assertEquals(OperationStatus.QUEUED, op.status)
        assertEquals(OperationStatus.RETRY, op.copy(attempts = 2).status)
        assertEquals(OperationStatus.REJECTED, op.copy(attempts = 2, blocked = true).status)
        assertEquals(OperationStatus.CONFLICT, op.copy(attempts = 2, blocked = true, conflictRevision = 1).status)
    }
}
