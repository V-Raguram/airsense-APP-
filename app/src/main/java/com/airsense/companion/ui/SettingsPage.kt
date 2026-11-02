package com.airsense.companion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airsense.companion.AppState
import com.airsense.companion.data.DeviceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsPage(state: AppState, onSave: (DeviceSettings) -> Unit, onTest: () -> Unit, onDemo: () -> Unit, onForget: () -> Unit, onMessage: (String) -> Unit) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    var sshPort by remember(state.settings) { mutableStateOf(state.settings.sshPort.toString()) }
    var mqttPort by remember(state.settings) { mutableStateOf(state.settings.mqttPort.toString()) }
    var useKey by remember(state.settings) { mutableStateOf(state.settings.privateKey.isNotBlank()) }
    var showPassword by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val key = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        val bytes = ByteArray(65537)
                        var offset = 0
                        while (offset < bytes.size) { val n = input.read(bytes, offset, bytes.size - offset); if (n < 0) break; offset += n }
                        require(offset <= 65536) { "The private key file is too large." }
                        String(bytes, 0, offset, Charsets.UTF_8).also { require(it.contains("PRIVATE KEY")) { "Select a PEM or OpenSSH private key." } }
                    }
                }
                draft = draft.copy(privateKey = key)
                useKey = true
                onMessage("Private key loaded. Save settings to keep it encrypted on this phone.")
            } catch (error: Exception) { onMessage(error.message ?: "Could not import the private key.") }
        }
    }
    val transform = if (showPassword) VisualTransformation.None else PasswordVisualTransformation()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageTitle("Your Airsense connection", "Meet your device.", "Join the Raspberry Pi hotspot using the Wi-Fi button, then enter its existing SSH details.") }
        item {
            PanelCard {
                Text("Raspberry Pi", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(draft.host, { draft = draft.copy(host = it.trim()) }, label = { Text("Pi address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.username, { draft = draft.copy(username = it) }, label = { Text("SSH username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!useKey, { useKey = false }, label = { Text("Password") })
                    FilterChip(useKey, { useKey = true }, label = { Text("Private key") })
                }
                if (useKey) {
                    OutlinedButton({ keyPicker.launch(arrayOf("*/*")) }) { Text("Import existing private key") }
                    Text(if (draft.privateKey.isBlank()) "No key loaded" else "Private key loaded · ${draft.privateKey.lineSequence().firstOrNull().orEmpty()}", color = Muted, fontSize = 11.sp)
                    OutlinedTextField(draft.keyPassphrase, { draft = draft.copy(keyPassphrase = it) }, label = { Text("Key passphrase, if encrypted") },
                        visualTransformation = transform, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(draft.password, { draft = draft.copy(password = it) }, label = { Text("SSH password") },
                        visualTransformation = transform, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Row { Checkbox(showPassword, { showPassword = it }); Text("Show password", modifier = Modifier.padding(top = 13.dp), color = Muted, fontSize = 12.sp) }
                OutlinedTextField(draft.logDirectory, { draft = draft.copy(logDirectory = it) }, label = { Text("Existing log folder on Pi") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide advanced connection settings" else "Advanced connection settings") }
            if (advanced) PanelCard {
                OutlinedTextField(sshPort, { sshPort = it }, label = { Text("SSH port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mqttPort, { mqttPort = it }, label = { Text("MQTT port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.mqttTopic, { draft = draft.copy(mqttTopic = it) }, label = { Text("MQTT sensor topic") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.mqttUsername, { draft = draft.copy(mqttUsername = it) }, label = { Text("MQTT username · optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.mqttPassword, { draft = draft.copy(mqttPassword = it) }, label = { Text("MQTT password · optional") }, visualTransformation = transform, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Leave MQTT credentials empty if your existing broker allows anonymous access.", color = Muted, fontSize = 11.sp)
            }
        }
        item {
            Button(onClick = {
                if (useKey && draft.privateKey.isBlank()) onMessage("Import your existing SSH private key first.")
                else onSave(draft.copy(host = draft.host.trim(), username = draft.username.trim(), logDirectory = draft.logDirectory.trim(),
                    sshPort = sshPort.toIntOrNull() ?: -1, mqttPort = mqttPort.toIntOrNull() ?: -1,
                    password = if (useKey) "" else draft.password,
                    privateKey = if (useKey) draft.privateKey else "", keyPassphrase = if (useKey) draft.keyPassphrase else ""))
            }, modifier = Modifier.fillMaxWidth()) { Text("Save & connect") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onTest, modifier = Modifier.fillMaxWidth(), enabled = state.settings.configured) { Text("Test saved SSH settings") }
            Text(state.sshStatus, modifier = Modifier.padding(top = 8.dp), color = Muted, fontSize = 11.sp)
        }
        item {
            PanelCard {
                Text("Explore before connecting", style = MaterialTheme.typography.titleMedium)
                Text("Try the dashboard and a one-hour sample CSV. Sample data is always labelled.", color = Muted, fontSize = 12.sp)
                OutlinedButton(onDemo) { Text("Explore sample data") }
            }
        }
        item {
            Text("Credentials are encrypted using Android Keystore. The app reads existing files, MQTT messages, and tmux panes; it installs nothing on the Pi.", color = Muted, fontSize = 11.sp)
            TextButton({ forget = true }) { Text("Forget credentials & trusted Pi keys", color = Coral, fontSize = 12.sp) }
            Text("Airsense Companion · 0.1.0", color = Muted, fontSize = 10.sp)
        }
    }
    if (forget) AlertDialog(onDismissRequest = { forget = false }, title = { Text("Forget this connection?") },
        text = { Text("Remove saved credentials and SSH fingerprints from this phone. Downloaded logs stay available.") },
        confirmButton = { TextButton({ forget = false; onForget() }) { Text("Forget", color = Coral) } },
        dismissButton = { TextButton({ forget = false }) { Text("Cancel") } })
}
