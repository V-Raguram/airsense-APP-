package com.airsense.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airsense.companion.Page
import com.airsense.companion.data.ReadingStatus

@Composable
fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp))
        .border(1.dp, Border, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun PageTitle(eyebrow: String, title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow.uppercase(), color = Mint, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(description, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun StatusPill(label: String, color: Color = Mint) {
    Row(Modifier.background(color.copy(alpha = .1f), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(5.dp)) { drawCircle(color) }
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

fun ReadingStatus.color(): Color = when (this) {
    ReadingStatus.NORMAL -> Mint
    ReadingStatus.CAUTION -> Amber
    ReadingStatus.HIGH -> Coral
    ReadingStatus.OFFLINE -> Muted
}

@Composable
fun EmptyPanel(title: String, detail: String, action: String? = null, onClick: () -> Unit = {}) {
    PanelCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
        if (action != null) OutlinedButton(onClick) { Text(action) }
    }
}

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(34.dp)) {
        val scale = size.width / 32f
        val path = Path().apply {
            moveTo(3 * scale, 11 * scale); lineTo(21 * scale, 11 * scale)
            cubicTo(30 * scale, 11 * scale, 29 * scale, 2 * scale, 23 * scale, 3 * scale)
            moveTo(3 * scale, 17 * scale); lineTo(25 * scale, 17 * scale)
            cubicTo(34 * scale, 17 * scale, 29 * scale, 29 * scale, 23 * scale, 25 * scale)
            moveTo(3 * scale, 23 * scale); lineTo(15 * scale, 23 * scale)
        }
        drawPath(path, Mint, style = Stroke(2 * scale, cap = StrokeCap.Round))
    }
}

@Composable
fun PageIcon(page: Page, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.width / 24
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), 1.7f * s, StrokeCap.Round)
        when (page) {
            Page.LIVE -> {
                line(2f, 13f, 6f, 13f); line(6f, 13f, 9f, 5f); line(9f, 5f, 14f, 20f)
                line(14f, 20f, 17f, 11f); line(17f, 11f, 22f, 11f)
            }
            Page.LOGS -> {
                drawRoundRect(color, Offset(5*s, 2*s), Size(14*s, 20*s), CornerRadius(2*s), style = Stroke(1.6f*s))
                line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f)
            }
            Page.ANALYSIS -> {
                line(3f, 3f, 3f, 21f); line(3f, 21f, 22f, 21f)
                line(7f, 16f, 11f, 11f); line(11f, 11f, 15f, 14f); line(15f, 14f, 21f, 5f)
            }
            Page.SCREEN -> {
                drawRoundRect(color, Offset(2*s, 3*s), Size(20*s, 14*s), CornerRadius(2*s), style = Stroke(1.6f*s))
                line(8f, 21f, 16f, 21f); line(12f, 17f, 12f, 21f)
                line(6f, 7f, 9f, 10f); line(9f, 10f, 6f, 13f); line(12f, 13f, 17f, 13f)
            }
            Page.SETTINGS -> {
                drawCircle(color, 8*s, style = Stroke(1.7f*s))
                drawCircle(color, 2.6f*s, style = Stroke(1.7f*s))
                line(12f, 1f, 12f, 4f); line(12f, 20f, 12f, 23f)
                line(1f, 12f, 4f, 12f); line(20f, 12f, 23f, 12f)
            }
        }
    }
}
