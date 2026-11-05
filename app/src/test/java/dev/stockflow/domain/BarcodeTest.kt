package dev.stockflow.domain

import org.junit.Assert.*
import org.junit.Test

class BarcodeTest {
    private val first = InventoryItem(1, 1, "Cable", "00123", 10, null)
    private val anotherTask = first.copy(id = 2, taskId = 2)

    @Test
    fun `scanner matches exact barcode only inside selected task`() {
        assertEquals(BarcodeMatch.Found(first), findByBarcode(listOf(first, anotherTask), 1, "00123"))
        assertEquals(BarcodeMatch.NotFound, findByBarcode(listOf(anotherTask), 1, "00123"))
    }

    @Test
    fun `leading zeroes are preserved while surrounding whitespace is ignored`() {
        assertEquals(BarcodeMatch.Found(first), findByBarcode(listOf(first), 1, " 00123\n"))
        assertEquals(BarcodeMatch.NotFound, findByBarcode(listOf(first), 1, "123"))
    }

    @Test
    fun `empty unknown and ambiguous barcodes never select an arbitrary product`() {
        assertEquals(BarcodeMatch.Empty, findByBarcode(listOf(first), 1, " "))
        assertEquals(BarcodeMatch.NotFound, findByBarcode(listOf(first), 1, "unknown"))
        assertEquals(BarcodeMatch.Ambiguous, findByBarcode(listOf(first, first.copy(id = 3)), 1, "00123"))
    }
}
