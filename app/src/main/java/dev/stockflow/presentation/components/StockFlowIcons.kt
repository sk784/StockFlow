package dev.stockflow.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object StockFlowIcons {
    private fun icon(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(
        name, 24.dp, 24.dp, 24f, 24f,
    ).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()

    val Tasks = icon("Tasks") {
        moveTo(7f, 4f); lineTo(19f, 4f); lineTo(19f, 21f); lineTo(5f, 21f); lineTo(5f, 4f); lineTo(7f, 4f)
        moveTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f); lineTo(9f, 6f); close()
        moveTo(9f, 11f); lineTo(15f, 11f); moveTo(9f, 15f); lineTo(15f, 15f)
    }
    val Sync = icon("Sync") {
        moveTo(20f, 10f); curveTo(19f, 5f, 13f, 2f, 8f, 5f); lineTo(5f, 7f)
        moveTo(5f, 3f); lineTo(5f, 7f); lineTo(9f, 7f)
        moveTo(4f, 14f); curveTo(5f, 19f, 11f, 22f, 16f, 19f); lineTo(19f, 17f)
        moveTo(19f, 21f); lineTo(19f, 17f); lineTo(15f, 17f)
    }
    val Conflict = icon("Conflict") {
        moveTo(12f, 3f); lineTo(22f, 20f); lineTo(2f, 20f); close()
        moveTo(12f, 9f); lineTo(12f, 13f); moveTo(12f, 16.5f); lineTo(12f, 16.6f)
    }
    val Search = icon("Search") {
        moveTo(17f, 10f); curveTo(17f, 14f, 14f, 17f, 10f, 17f)
        curveTo(6f, 17f, 3f, 14f, 3f, 10f); curveTo(3f, 6f, 6f, 3f, 10f, 3f)
        curveTo(14f, 3f, 17f, 6f, 17f, 10f); moveTo(15.5f, 15.5f); lineTo(21f, 21f)
    }
    val Back = icon("Back") { moveTo(14f, 5f); lineTo(7f, 12f); lineTo(14f, 19f) }
    val Next = icon("Next") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
    val Check = icon("Check") { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 6f) }
    val Close = icon("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Scan = icon("Scan") {
        moveTo(3f, 8f); lineTo(3f, 3f); lineTo(8f, 3f)
        moveTo(16f, 3f); lineTo(21f, 3f); lineTo(21f, 8f)
        moveTo(21f, 16f); lineTo(21f, 21f); lineTo(16f, 21f)
        moveTo(8f, 21f); lineTo(3f, 21f); lineTo(3f, 16f)
        moveTo(7f, 8f); lineTo(7f, 16f); moveTo(10f, 8f); lineTo(10f, 16f)
        moveTo(14f, 8f); lineTo(14f, 16f); moveTo(17f, 8f); lineTo(17f, 16f)
    }
    val Box = icon("Box") {
        moveTo(3f, 7f); lineTo(12f, 3f); lineTo(21f, 7f); lineTo(21f, 17f); lineTo(12f, 21f); lineTo(3f, 17f); close()
        moveTo(3f, 7f); lineTo(12f, 11f); lineTo(21f, 7f)
        moveTo(12f, 11f); lineTo(12f, 21f); moveTo(7.5f, 5f); lineTo(16.5f, 9f)
    }
    val Clock = icon("Clock") {
        moveTo(21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f); curveTo(7f, 21f, 3f, 17f, 3f, 12f)
        curveTo(3f, 7f, 7f, 3f, 12f, 3f); curveTo(17f, 3f, 21f, 7f, 21f, 12f)
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15f, 14f)
    }
}
