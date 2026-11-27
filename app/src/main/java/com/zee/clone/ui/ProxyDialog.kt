package com.zee.clone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zee.clone.MainActivity
import com.zee.clone.data.CloneEntry
import com.zee.clone.data.ProxySettings
import com.zee.clone.data.ProxyTestResult
import com.zee.clone.data.ProxyTester
import com.zee.clone.engine.VirtualEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-clone proxy dialog (v3.1.0). One clone = one country/IP.
 *
 * The proxy is NOT bought or provided by Zee Clone — the user types their
 * own proxy details. The Test button checks the proxy first (plain-socket
 * SOCKS5/HTTP CONNECT + exit-IP lookup), Save writes the per-clone config
 * file the engine's native hook reads at the next clone launch.
 */
@Composable
fun ProxyDialog(
    entry: CloneEntry,
    activity: MainActivity,
    engine: VirtualEngine,
    onStatus: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(entry) {
        ProxySettings.read(context, entry.packageName, entry.userId)
    }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var type by remember { mutableStateOf(existing?.type ?: "socks5") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf(existing?.port?.takeIf { it > 0 }?.toString() ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var pass by remember { mutableStateOf(existing?.pass ?: "") }
    var country by remember { mutableStateOf(existing?.country ?: "") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }

    fun currentSettings() = ProxySettings(
        enabled = enabled,
        type = type,
        host = host.trim(),
        port = port.trim().toIntOrNull() ?: 0,
        user = user.trim(),
        pass = pass,
        country = country.trim()
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Proxy — ${entry.label} (Clone ${entry.cloneIndex + 1})") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use a proxy for this clone", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Type", modifier = Modifier.weight(1f))
                    TextButton(onClick = { typeMenu = true }) {
                        Text(if (type == "http") "HTTP" else "SOCKS5")
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        DropdownMenuItem(text = { Text("SOCKS5") }, onClick = { type = "socks5"; typeMenu = false })
                        DropdownMenuItem(text = { Text("HTTP") }, onClick = { type = "http"; typeMenu = false })
                    }
                }
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("Proxy host (IP or domain)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("Username (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("Password (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = country, onValueChange = { country = it },
                    label = { Text("Country label (e.g. DE)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        testing = true
                        testResult = null
                        val s = currentSettings()
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { ProxyTester.test(s) }
                            testResult = when (result) {
                                is ProxyTestResult.Success ->
                                    "Working — exit IP ${result.exitIp} (${result.country})."
                                is ProxyTestResult.Failed ->
                                    "Failed — ${result.reason}"
                            }
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (testing) "Testing…" else "Test proxy")
                }
                testResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "One clone = one country. The proxy applies on the NEXT launch — " +
                        "restart the clone after saving. Use a static residential IP and " +
                        "never rotate it for the same account; keep the IP country, the " +
                        "clone's timezone and language the same. UDP/QUIC is blocked for " +
                        "proxied clones (no leaks around the proxy), and the clone has no " +
                        "internet at all if the proxy is down — that is the safe failure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = {
                        ProxySettings.remove(context, entry.packageName, entry.userId)
                        onStatus("Proxy removed for ${entry.label} — restart the clone to apply.")
                        onDismiss()
                    }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        scope.launch {
                            activity.killClone(entry, engine)
                            val outcome = activity.launchClone(entry, engine)
                            onStatus(outcome.message)
                            onDismiss()
                        }
                    }
                ) { Text("Restart clone") }
                TextButton(onClick = {
                    val s = currentSettings()
                    if (s.enabled && (s.host.isBlank() || s.port !in 1..65535)) {
                        testResult = "Save first: a host and a port (1–65535) are needed."
                        return@TextButton
                    }
                    ProxySettings.save(context, entry.packageName, entry.userId, s)
                    onStatus(
                        if (s.enabled) "Proxy saved for ${entry.label} — restart the clone to apply."
                        else "Proxy disabled for ${entry.label} — restart the clone to apply."
                    )
                    onDismiss()
                }) { Text("Save") }
            }
        }
    )
}
