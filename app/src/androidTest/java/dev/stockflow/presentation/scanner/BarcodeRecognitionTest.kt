package dev.stockflow.presentation.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BarcodeRecognitionTest {
    @Test
    fun bundledModelRecognizesInventoryEan13() {
        val bitmap = inventoryBarcode()
        val scanner = BarcodeScanning.getClient()
        try {
            val result = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS)
            assertTrue("Expected inventory EAN-13 in $result", result.any { it.rawValue == "2000000000015" })
        } finally {
            scanner.close()
            bitmap.recycle()
        }
    }

    /** Independent EAN-13 fixture for the first catalog item, with quiet zones and 4px modules. */
    private fun inventoryBarcode(): Bitmap {
        val left = listOf("0001101", "0011001", "0010011", "0111101", "0100011",
            "0110001", "0101111", "0111011", "0110111", "0001011")
        val even = listOf("0100111", "0110011", "0011011", "0100001", "0011101",
            "0111001", "0000101", "0010001", "0001001", "0010111")
        val digits = "2000000000015"
        val parity = "LLGGLG" // First EAN digit is 2.
        val bars = buildString {
            append("101")
            digits.substring(1, 7).forEachIndexed { index, digit ->
                append((if (parity[index] == 'L') left else even)[digit.digitToInt()])
            }
            append("01010")
            digits.substring(7).forEach { digit ->
                append(left[digit.digitToInt()].map { if (it == '0') '1' else '0' }.joinToString(""))
            }
            append("101")
        }
        return Bitmap.createBitmap((bars.length + 24) * 4, 240, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            bars.forEachIndexed { index, bit ->
                if (bit == '1') canvas.drawRect((index + 12) * 4f, 20f, (index + 13) * 4f, 220f, paint)
            }
        }
    }
}
