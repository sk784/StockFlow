package dev.stockflow.domain

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    @Test
    fun `refresh never replaces pending local count or its base revision`() {
        val local = QuantitySnapshot(12, 1)
        assertEquals(local, mergeRemoteCount(local, QuantitySnapshot(7, 4), hasPending = true))
    }

    @Test
    fun `late catalog response cannot roll back a newer upload acknowledgement`() {
        val local = QuantitySnapshot(12, 5)
        assertEquals(local, mergeRemoteCount(local, QuantitySnapshot(7, 4), hasPending = false))
    }

    @Test
    fun `clean count accepts newer server state including zero and uncounted`() {
        val local = QuantitySnapshot(12, 1)
        assertEquals(QuantitySnapshot(0, 2), mergeRemoteCount(local, QuantitySnapshot(0, 2), false))
        assertEquals(QuantitySnapshot(null, 3), mergeRemoteCount(local, QuantitySnapshot(null, 3), false))
    }

    @Test
    fun `duplicate ids barcodes and missing parent are rejected before persistence`() {
        val task = InventoryTask(1, "Task", "Warehouse")
        val item = CatalogItem(1, 1, "Item", "00123", 10, QuantitySnapshot(null, 0))
        listOf(
            InventoryCatalog(listOf(task, task), listOf(item)),
            InventoryCatalog(listOf(task), listOf(item, item)),
            InventoryCatalog(listOf(task), listOf(item, item.copy(id = 2))),
            InventoryCatalog(listOf(task), listOf(item.copy(taskId = 99))),
            InventoryCatalog(listOf(task), listOf(item.copy(expectedQuantity = -1))),
        ).forEach { catalog ->
            try { catalog.validate(); fail("Invalid catalog accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun `same barcode in different tasks is valid`() {
        val tasks = listOf(InventoryTask(1, "First", "A"), InventoryTask(2, "Second", "B"))
        val first = CatalogItem(1, 1, "Item", "00123", 10, QuantitySnapshot(null, 0))
        val catalog = InventoryCatalog(tasks, listOf(first, first.copy(id = 2, taskId = 2)))
        assertEquals(catalog, catalog.validate())
    }

    @Test
    fun `invalid server quantity and revision are rejected`() {
        try { QuantitySnapshot(-1, 0); fail("Negative quantity accepted") }
        catch (_: IllegalArgumentException) { }
        try { QuantitySnapshot(1, -1); fail("Negative revision accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
