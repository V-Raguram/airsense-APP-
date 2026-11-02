package com.airsense.companion.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airsense.companion.AppState
import com.airsense.companion.data.*
import kotlinx.coroutines.delay

@Composable
fun LivePage(state: AppState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val now by produceState(SystemClock.elapsedRealtime()) { while (true) { value = SystemClock.elapsedRealtime(); delay(1000) } }
    val hasReading = state.live.receivedElapsedMs > 0
    val age = if (hasReading) ((now - state.live.receivedElapsedMs) / 1000).coerceAtLeast(0) else null
    val fresh = hasReading && (state.demo || (state.mqttConnected && age!! < 15))
    val online = if (fresh) state.live.values.count { it.value != null } else 0
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageTitle("Your environment, in view", "A clearer picture of air.", "Live measurements from your Airsense device.") }
        item {
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Mint.copy(alpha = .13f), Panel)), RoundedCornerShape(24.dp))
                .padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(if (state.demo) "SAMPLE" else if (fresh) "LIVE" else if (hasReading) "STALE DATA" else "AWAITING DATA", if (fresh) Mint else Amber)
                    Text(if (state.demo) "Preview device" else state.settings.host, color = Muted, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("$online", fontSize = 58.sp, fontWeight = FontWeight.Light, color = Mint)
                    Text("/ 7 parameters online", color = Muted, modifier = Modifier.padding(bottom = 12.dp))
                }
                Text(state.mqttStatus, style = MaterialTheme.typography.bodySmall, color = Muted)
                if (!state.demo) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!state.mqttConnected) Button(onConnect) { Text("Connect") }
                        OutlinedButton(onDisconnect) { Text("Disconnect") }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sensor readings", style = MaterialTheme.typography.titleMedium)
                Text(age?.let { "Updated ${it}s ago" } ?: "No data yet", color = Muted, fontSize = 11.sp)
            }
        }
        items(Sensor.entries.chunked(2).size) { index ->
            val sensors = Sensor.entries.chunked(2)[index]
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                sensors.forEach { sensor ->
                    val value = if (fresh) state.live.values[sensor] else null
                    val status = sensor.status(value)
                    Column(Modifier.weight(1f).background(Panel, RoundedCornerShape(20.dp)).padding(17.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(sensor.title, color = Muted, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(sensor.display(value), fontSize = 29.sp, fontWeight = FontWeight.Medium)
                            Text(sensor.unit, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 5.dp))
                        }
                        StatusPill(when (status) { ReadingStatus.NORMAL -> "NORMAL"; ReadingStatus.CAUTION -> "CAUTION"; ReadingStatus.HIGH -> "OUT OF RANGE"; ReadingStatus.OFFLINE -> "OFFLINE" }, status.color())
                    }
                }
                if (sensors.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Text("Device time: ${state.live.deviceTime.ifBlank { "Waiting for RTC time" }}", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text("Colours follow the thresholds configured in your existing Pi dashboard.", color = Muted, fontSize = 11.sp)
            if (state.invalidPackets > 0) Text("Ignored ${state.invalidPackets} invalid sensor packets.", color = Amber, fontSize = 11.sp)
        }
    }
}
