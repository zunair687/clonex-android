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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zee.clone.MainActivity
import com.zee.clone.data.CloneEntry
import com.zee.clone.data.CountryDefaults
import com.zee.clone.data.IdentitySettings
import com.zee.clone.data.ProxyEndpoint
import com.zee.clone.data.ProxyHealth
import com.zee.clone.data.ProxySettings
import com.zee.clone.data.ProxyTestResult
import com.zee.clone.engine.FakeLocationBridge
import com.zee.clone.engine.VirtualEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-clone proxy + identity dialog (v3.1.0 proxy, v3.1.1 identity).
 * One clone = one country/IP + one timezone/language/GPS/device-ID.
 *
 * The proxy is NOT bought or provided by Zee Clone — the user types their
 * own proxy details. The Test button checks the proxy first (plain-socket
 * SOCKS5/HTTP CONNECT + exit-IP lookup), Save writes the per-clone config
 * file the engine's native hook reads at the next clone launch.
 *
 * Identity (v3.1.1): timezone + locale are applied inside the clone
 * process at bind time (ZeeIdentity); GPS goes through the engine's own
 * fake-location system (BLocationManager, OWN_MODE per app); ANDROID_ID
 * is a stable per-clone fake ID seeded into Settings.Secure's cache.
 * "Set from country" fills timezone/locale/GPS from the proxy country.
 * "New IP" rotates to the next alternate proxy — USER-triggered only,
 * never automatic, because changing IP can flag the account.
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
    val existingIdentity = remember(entry) {
        IdentitySettings.read(context, entry.packageName, entry.userId)
    }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var type by remember { mutableStateOf(existing?.type ?: "socks5") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf(existing?.port?.takeIf { it > 0 }?.toString() ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var pass by remember { mutableStateOf(existing?.pass ?: "") }
    var country by remember { mutableStateOf(existing?.country ?: "") }
    var alternates by remember { mutableStateOf(existing?.alternates ?: emptyList()) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var rotateConfirm by remember { mutableStateOf(false) }

    // --- identity state (v3.1.1) ---
    var timezone by remember { mutableStateOf(existingIdentity?.timezone ?: "") }
    var locale by remember { mutableStateOf(existingIdentity?.locale ?: "") }
    var gpsEnabled by remember { mutableStateOf(existingIdentity?.gpsEnabled ?: false) }
    var lat by remember { mutableStateOf(existingIdentity?.lat?.takeIf { it != 0.0 }?.toString() ?: "") }
    var lng by remember { mutableStateOf(existingIdentity?.lng?.takeIf { it != 0.0 }?.toString() ?: "") }
    var androidId by remember {
        mutableStateOf(
            existingIdentity?.androidId?.takeIf { it.isNotBlank() }
                ?: IdentitySettings.ensureAndroidId(context, entry.packageName, entry.userId)
        )
    }
    var healthText by remember { mutableStateOf<String?>(null) }

    fun currentSettings() = ProxySettings(
        enabled = enabled,
        type = type,
        host = host.trim(),
        port = port.trim().toIntOrNull() ?: 0,
        user = user.trim(),
        pass = pass,
        country = country.trim(),
        alternates = alternates
    )

    fun currentIdentity() = IdentitySettings(
        timezone = timezone.trim(),
        locale = locale.trim(),
        gpsEnabled = gpsEnabled,
        lat = lat.trim().toDoubleOrNull() ?: 0.0,
        lng = lng.trim().toDoubleOrNull() ?: 0.0,
        androidId = androidId
    )

    fun refreshHealthLine() {
        val health = ProxyHealth.readFor(context, entry.packageName, entry.userId, currentSettings().activeEndpoint())
        healthText = when {
            health == null -> "Proxy health: not tested yet."
            health.alive -> "Proxy health: ALIVE — exit ${health.exitIp} (${health.country}), " +
                "tested ${android.text.format.DateFormat.format("dd MMM HH:mm", health.lastTestMs)}."
            else -> "Proxy health: DEAD — ${health.reason}, " +
                "tested ${android.text.format.DateFormat.format("dd MMM HH:mm", health.lastTestMs)}. " +
                "The clone has no internet until the proxy works again."
        }
    }

    // Auto-test on open when the last check is stale (older than 5 min).
    LaunchedEffect(entry) {
        refreshHealthLine()
        val s = currentSettings()
        if (s.enabled && s.host.isNotBlank() && s.port in 1..65535) {
            val last = ProxyHealth.readFor(context, entry.packageName, entry.userId, s.activeEndpoint())
            if (last == null || System.currentTimeMillis() - last.lastTestMs > 5 * 60 * 1000) {
                withContext(Dispatchers.IO) {
                    ProxyHealth.testEndpoint(context, entry.packageName, entry.userId, s.activeEndpoint())
                }
                refreshHealthLine()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Proxy + Identity — ${entry.label} (Clone ${entry.cloneIndex + 1})") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Proxy", fontWeight = FontWeight.Bold)
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
                            val result = withContext(Dispatchers.IO) {
                                ProxyHealth.testEndpoint(context, entry.packageName, entry.userId, s.activeEndpoint())
                            }
                            testResult = when (result) {
                                is ProxyTestResult.Success ->
                                    "Working — exit IP ${result.exitIp} (${result.country})."
                                is ProxyTestResult.Failed ->
                                    "Failed — ${result.reason}"
                            }
                            refreshHealthLine()
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
                healthText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                // --- alternates + rotation (v3.1.1) ---
                if (alternates.isNotEmpty()) {
                    Text("Alternate proxies (${alternates.size}/3):", style = MaterialTheme.typography.bodySmall)
                    alternates.forEachIndexed { index, alt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${alt.country.ifBlank { alt.type }} — ${alt.host}:${alt.port}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = {
                                // Make this alternate the active proxy.
                                val current = currentSettings()
                                val newActive = alt
                                val newAlternates = alternates.filterIndexed { i, _ -> i != index } +
                                    current.activeEndpoint()
                                type = newActive.type
                                host = newActive.host
                                port = newActive.port.toString()
                                user = newActive.user
                                pass = newActive.pass
                                country = newActive.country
                                alternates = newAlternates
                                testResult = "Switched to ${newActive.country.ifBlank { newActive.host }} — Save to apply."
                            }) { Text("Use") }
                            TextButton(onClick = {
                                alternates = alternates.filterIndexed { i, _ -> i != index }
                            }) { Text("Delete") }
                        }
                    }
                }
                Row {
                    TextButton(
                        onClick = {
                            val added = currentSettings().withAlternateAdded(currentSettings().activeEndpoint())
                            if (added.alternates.size == alternates.size) {
                                testResult = "Cannot add: need a host + port, max 3 alternates, no duplicates."
                            } else {
                                alternates = added.alternates
                                testResult = "Added as alternate — the active proxy stays as it is."
                            }
                        },
                        enabled = alternates.size < 3
                    ) { Text("Add current as alternate") }
                    TextButton(
                        onClick = {
                            if (!rotateConfirm) {
                                rotateConfirm = true
                                return@TextButton
                            }
                            rotateConfirm = false
                            val rotated = currentSettings().rotated()
                            if (rotated != null) {
                                type = rotated.type
                                host = rotated.host
                                port = rotated.port.toString()
                                user = rotated.user
                                pass = rotated.pass
                                country = rotated.country
                                alternates = rotated.alternates
                                ProxySettings.save(context, entry.packageName, entry.userId, rotated)
                                testResult = "Rotated to ${rotated.country.ifBlank { rotated.host }} — restart the clone to apply."
                                onStatus("Proxy rotated to ${rotated.country.ifBlank { rotated.host }} for ${entry.label} — restart the clone to apply.")
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        ProxyHealth.testEndpoint(context, entry.packageName, entry.userId, rotated.activeEndpoint())
                                    }
                                    refreshHealthLine()
                                }
                            }
                        },
                        enabled = alternates.isNotEmpty()
                    ) {
                        Text(if (rotateConfirm) "Tap again to confirm rotate" else "New IP (rotate)")
                    }
                }
                if (rotateConfirm) {
                    Text(
                        "Warning: changing IP can flag the account — only rotate when the " +
                            "current IP is dead or burned. The new IP applies on the next launch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // --- identity (v3.1.1) ---
                Text("Identity — timezone, language, GPS, device ID", fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = {
                        val info = CountryDefaults.forCode(country)
                        if (info == null) {
                            testResult = "Set the country label first (e.g. DE) to fill from country."
                        } else {
                            timezone = info.timezone
                            locale = info.locale
                            lat = info.lat.toString()
                            lng = info.lng.toString()
                            gpsEnabled = true
                            testResult = "Filled from ${info.capital}: ${info.timezone}, ${info.locale}, GPS ${info.lat}, ${info.lng}."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Set timezone, language + GPS from country") }
                OutlinedTextField(
                    value = timezone, onValueChange = { timezone = it },
                    label = { Text("Timezone (e.g. Europe/Berlin)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = locale, onValueChange = { locale = it },
                    label = { Text("Language (e.g. de-DE)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fake GPS for this clone", modifier = Modifier.weight(1f))
                    Switch(checked = gpsEnabled, onCheckedChange = { gpsEnabled = it })
                }
                OutlinedTextField(
                    value = lat, onValueChange = { lat = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    label = { Text("Latitude (e.g. 52.52)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = gpsEnabled
                )
                OutlinedTextField(
                    value = lng, onValueChange = { lng = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    label = { Text("Longitude (e.g. 13.405)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = gpsEnabled
                )
                Text(
                    "Android ID (fake, per clone): $androidId",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = {
                    val fresh = IdentitySettings.generateAndroidId(
                        exclude = IdentitySettings.hostAndroidId(context)
                    )
                    androidId = fresh
                    testResult = "New Android ID generated — only use this BEFORE the first login " +
                        "in this clone. Changing it later makes the clone look like a NEW device."
                }) { Text("Generate new Android ID") }
                Text(
                    "One clone = one country. The proxy applies on the NEXT launch — " +
                        "restart the clone after saving. Use a static residential IP and " +
                        "never rotate it for the same account; keep the IP country, the " +
                        "clone's timezone and language the same. UDP/QUIC is blocked for " +
                        "proxied clones (no leaks around the proxy), and the clone has no " +
                        "internet at all if the proxy is down — that is the safe failure. " +
                        "Timezone, language and Android ID apply at the next launch; GPS " +
                        "is set through the engine's fake location and applies immediately.",
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
                        onStatus("Proxy removed for ${entry.label} — restart the clone to apply. (Identity kept.)")
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
                    val ident = currentIdentity()
                    if (ident.gpsEnabled &&
                        (ident.lat !in -90.0..90.0 || ident.lng !in -180.0..180.0 ||
                            (ident.lat == 0.0 && ident.lng == 0.0))
                    ) {
                        testResult = "GPS is on but the coordinates look wrong — check latitude/longitude."
                        return@TextButton
                    }
                    ProxySettings.save(context, entry.packageName, entry.userId, s)
                    IdentitySettings.save(context, entry.packageName, entry.userId, ident)
                    scope.launch {
                        val gps = withContext(Dispatchers.IO) {
                            FakeLocationBridge.apply(
                                entry.packageName, entry.userId,
                                ident.lat, ident.lng, ident.gpsEnabled
                            )
                        }
                        onStatus(
                            if (s.enabled) "Proxy + identity saved for ${entry.label} — restart the clone to apply. GPS: ${gps.message}"
                            else "Proxy disabled, identity saved for ${entry.label} — restart the clone to apply. GPS: ${gps.message}"
                        )
                    }
                    onDismiss()
                }) { Text("Save") }
            }
        }
    )
}
