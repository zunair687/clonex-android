package com.zee.clone

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zee.clone.data.CloneDao
import com.zee.clone.data.CloneDatabase
import com.zee.clone.data.CloneEntry
import com.zee.clone.data.IdentitySettings
import com.zee.clone.data.ProxyHealth
import com.zee.clone.data.ProxySettings
import com.zee.clone.data.SettingsStore
import com.zee.clone.engine.EngineResult
import com.zee.clone.engine.FakeLocationBridge
import com.zee.clone.engine.RealVirtualEngine
import com.zee.clone.engine.VirtualEngine
import com.zee.clone.ui.ProxyDialog
import com.zee.clone.ui.ZeeCloneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Zee Clone v3.0 — light-mode UI, on the NewBlackbox virtual engine
 * (RealVirtualEngine / BlackBoxCore). Based on CloneX v3.0.
 *
 *  - Home (v2.0.6): blank — only the clone grid and the teal circular
 *    + FAB bottom-right. Everything else lives in the + menu.
 *  - Import Apps: its own screen — back arrow, search, "Import from file
 *    HOT section, A-Z list with a right-side alphabet index.
 *  - Long-press a clone: Launch / Clear data / Clear cache /
 *    Kill app / Remove.
 *  - Permissions (v2.0.9): declared in the manifest, NEVER asked on
 *    their own — not at app open (v2.0.7) and not at clone tap
 *    (v2.0.8 fired five dialogs in a row). A clone launches at once,
 *    no dialogs. Settings → "Grant app permissions" is manual only.
 *
 * Room stays the UI cache; the engine is the source of truth for installs
 * (reconciled once at startup: engine-installed clones missing from the
 * library are added back).
 *
 * v3.0 replaces the MultiApp opensdk engine: its server rejected every
 * clone launch on the user's phone (engine code 102). NewBlackbox's
 * launch is a single boolean and it handles split APKs (Instagram etc.)
 * via the MaxCloner patch applied to Bcore in CI. No assist package:
 * NewBlackbox runs clones in the host's own :pN stub processes.
 *
 * v3.0.9 (Claude diagnosis, chat b2300a15): breadcrumbs at every
 * startup step, a crash-loop guard (Boot), engine profiles
 * FULL → COMPAT → OFF, a safe-mode error screen with Retry (compat) /
 * Copy report / Clear engine data, and a report screen (Device Info)
 * with exit reasons + full device details. See Boot.kt.
 */
class MainActivity : ComponentActivity() {

    /** v3.0.8 safe-mode startup states (v3.0.9: Failed/Ready carry
     *  the engine-disabled reason + the full startup report). */
    private sealed interface StartupState {
        data object Loading : StartupState
        data class Ready(
            val dao: CloneDao,
            val settings: SettingsStore,
            val engine: VirtualEngine,
            /** Non-null when the engine is disabled/failed (home banner). */
            val engineDisabledReason: String?
        ) : StartupState
        data class Failed(
            val message: String,
            val engineDisabledReason: String?,
            val report: String
        ) : StartupState
    }

    private var startupState by mutableStateOf<StartupState>(StartupState.Loading)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZeeCloneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (val state = startupState) {
                        StartupState.Loading -> {
                            StartupLoadingScreen()
                            // Runs after the first frame: the loading
                            // screen is already on screen when the heavy
                            // init below starts. The F1 breadcrumb proves
                            // the process survived attachBaseContext.
                            LaunchedEffect(Unit) {
                                Boot.mark(
                                    applicationContext.filesDir,
                                    "F1 first frame"
                                )
                                startupState = runDeferredInit()
                            }
                        }
                        is StartupState.Ready -> ZeeCloneApp(
                            dao = state.dao,
                            settings = state.settings,
                            engine = state.engine,
                            engineBanner = state.engineDisabledReason,
                            activity = this
                        )
                        is StartupState.Failed -> StartupErrorScreen(
                            message = state.message,
                            engineDisabledReason = state.engineDisabledReason,
                            report = state.report,
                            onRetry = { startupState = StartupState.Loading },
                            onRetryCompat = { retryWithCompat() },
                            onClearEngineData = { clearEngineDataAndRestart() }
                        )
                    }
                }
            }
        }
    }

    /**
     * v3.0.8 safe-mode: the heavy startup work, off the main thread.
     * Room open, DataStore, engine server start and the engine wrapper
     * are each inside the one try/catch — a failure returns [Failed]
     * with a readable message instead of crashing before any UI.
     * (The engine server start itself can still throw inside native
     * code in ways Java cannot catch — see the v3.0.8 build note.)
     *
     * v3.0.9: every step writes a breadcrumb (M1/M2/M3) so the last
     * marker shows where a death happened; a DataStore corruption is
     * repaired by deleting the prefs file once; success marks the boot
     * OK (crash-loop guard), failure marks it FAILED and carries the
     * full startup report for the safe-mode screen.
     */
    private suspend fun runDeferredInit(): StartupState = withContext(Dispatchers.Default) {
        val filesDir = applicationContext.filesDir
        try {
            Boot.mark(filesDir, "M1 Room start")
            val db = CloneDatabase.get(applicationContext)
            val dao = db.cloneDao()
            Boot.mark(filesDir, "M1 Room done")
            Boot.mark(filesDir, "M2 DataStore start")
            val settings = loadSettingsWithRepair()
            Boot.mark(filesDir, "M2 DataStore done")
            Boot.mark(filesDir, "M3 engine start")
            // Engine server start, deferred from Application.onCreate.
            (application as ZeeCloneApplication).startEngineServer()
            val engine: VirtualEngine = RealVirtualEngine(applicationContext, dao)
            Boot.mark(filesDir, "M3 engine done")
            Boot.markOk(filesDir)
            val reason = ZeeCloneApplication.engineDisabledReason()
            StartupState.Ready(dao, settings, engine, reason)
        } catch (t: Throwable) {
            Log.e("MainActivity", "startup init failed", t)
            Boot.mark(filesDir, "M-init FAILED: ${t.javaClass.simpleName}: ${t.message}")
            Boot.markFailed(filesDir, "${t.javaClass.simpleName}: ${t.message ?: ""}")
            val reason = ZeeCloneApplication.engineDisabledReason()
            StartupState.Failed(startupFailureMessage(t), reason, loadDeviceInfo())
        }
    }

    /**
     * v3.0.9: DataStore corruption handler. A corrupt preferences file
     * (torn write, bad update) throws IOException on first read — the
     * app used to die on it. Delete the prefs file once and rebuild
     * the store; settings are non-critical (max clones defaults to 10).
     */
    private suspend fun loadSettingsWithRepair(): SettingsStore {
        val store = SettingsStore(applicationContext)
        return try {
            // Force the first read: construction alone is lazy.
            store.maxClones.first()
            store
        } catch (e: IOException) {
            Log.w("MainActivity", "DataStore corrupt, resetting settings", e)
            try {
                val prefsDir = File(applicationContext.filesDir, "datastore")
                File(prefsDir, "zee_clone_settings.preferences_pb").delete()
                File(prefsDir, "zee_clone_settings.preferences_pb.bak").delete()
            } catch (ignored: Exception) {
            }
            SettingsStore(applicationContext)
        }
    }

    /** "Retry (compat)": force the COMPAT profile next launch, restart. */
    private fun retryWithCompat() {
        try {
            Boot.setProfile(applicationContext.filesDir, Boot.EngineProfile.COMPAT)
        } catch (ignored: Exception) {
        }
        restartApp()
    }

    /** "Clear engine data": reset boot diagnostics + guard, restart. */
    private fun clearEngineDataAndRestart() {
        try {
            Boot.resetAll(applicationContext.filesDir)
        } catch (ignored: Exception) {
        }
        restartApp()
    }

    /** Restart the app process via its launcher intent. */
    private fun restartApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (intent != null) startActivity(intent)
        } catch (ignored: Exception) {
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun startupFailureMessage(t: Throwable): String {
        val detail = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return "Zee Clone could not start ($detail). Your clones are not " +
            "deleted. Try again — or clear the app's storage in Android " +
            "Settings if this keeps happening."
    }

    // ------------------------------------------------------------------
    // Engine-backed actions (internal so the composables in this file can
    // call them). Every one returns a user-facing message.
    // ------------------------------------------------------------------

    internal suspend fun importApkFile(
        uri: Uri,
        engine: VirtualEngine,
        dao: CloneDao,
        maxClones: Int
    ): InstallOutcome = withContext(Dispatchers.IO) {
        try {
            val fileName = "import-${System.currentTimeMillis()}.apk"
            val dir = File(cacheDir, "zeeclone-imports").apply { mkdirs() }
            val outFile = File(dir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext InstallOutcome("Could not read the picked file.")
            val parsed = parseApkPackage(outFile.absolutePath)
                ?: return@withContext InstallOutcome("Could not read this APK file.")
            val (packageName, label) = parsed
            if (dao.countForPackage(packageName) >= maxClones) {
                return@withContext InstallOutcome(
                    "$label already has $maxClones clones (max $maxClones per app)."
                )
            }
            val userId = engine.availableUser(packageName)
            val result = engine.installPackageAsUser(outFile.absolutePath, userId)
            InstallOutcome(
                message = messageOf(result),
                success = result is EngineResult.Ok
            )
        } catch (e: Exception) {
            InstallOutcome("Import failed: ${e.message}")
        }
    }

    /**
     * Import a directory of split APKs (one app, e.g. WhatsApp from Play).
     * The SAF tree is copied into a cache dir and handed to the engine's
     * installApkFiles, which accepts a directory.
     */
    internal suspend fun importApkDirectory(
        treeUri: Uri,
        engine: VirtualEngine,
        dao: CloneDao,
        maxClones: Int
    ): InstallOutcome = withContext(Dispatchers.IO) {
        try {
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Non-persistable grant still works for this session.
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val dir = File(cacheDir, "zeeclone-split-${System.currentTimeMillis()}")
                .apply { mkdirs() }
            var copied = 0
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol =
                    cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idCol < 0 || nameCol < 0) return@use
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        cursor.getString(idCol)
                    )
                    contentResolver.openInputStream(docUri)?.use { input ->
                        File(dir, name).outputStream().use { output -> input.copyTo(output) }
                        copied++
                    }
                }
            }
            if (copied == 0) {
                return@withContext InstallOutcome("No APK files found in the picked folder.")
            }
            val firstApk = dir.listFiles()
                ?.filter { it.name.endsWith(".apk", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.firstOrNull()
                ?: return@withContext InstallOutcome("No APK files found in the picked folder.")
            val parsed = parseApkPackage(firstApk.absolutePath)
                ?: return@withContext InstallOutcome("Could not read the APK set.")
            val (packageName, label) = parsed
            if (dao.countForPackage(packageName) >= maxClones) {
                return@withContext InstallOutcome(
                    "$label already has $maxClones clones (max $maxClones per app)."
                )
            }
            val userId = engine.availableUser(packageName)
            val result = engine.installApkFiles(dir.absolutePath, userId)
            InstallOutcome(
                message = messageOf(result),
                success = result is EngineResult.Ok
            )
        } catch (e: Exception) {
            InstallOutcome("Split-APK import failed: ${e.message}")
        }
    }

    internal suspend fun addInstalledApp(
        packageName: String,
        label: String,
        engine: VirtualEngine,
        dao: CloneDao,
        maxClones: Int
    ): InstallOutcome = withContext(Dispatchers.IO) {
        try {
            if (dao.countForPackage(packageName) >= maxClones) {
                return@withContext InstallOutcome(
                    "$label already has $maxClones clones (max $maxClones per app)."
                )
            }
            val userId = engine.availableUser(packageName)
            val result = engine.installFromHost(packageName, userId)
            InstallOutcome(
                message = messageOf(result),
                success = result is EngineResult.Ok
            )
        } catch (e: Exception) {
            InstallOutcome("Could not add app: ${e.message}")
        }
    }

    internal suspend fun launchClone(entry: CloneEntry, engine: VirtualEngine): InstallOutcome {
        return try {
            // v3.1.1: re-apply the clone's GPS (engine fake location) on
            // every launch — the engine's fake-location state is
            // server-side and can be lost when the engine restarts. The
            // bind-time identity (timezone / locale / Android ID) is
            // applied by ZeeIdentity inside the clone process. Fail-soft:
            // a GPS failure never blocks the launch, it is just reported.
            var gpsNote = ""
            val ident = IdentitySettings.read(applicationContext, entry.packageName, entry.userId)
            if (ident != null) {
                val gps = withContext(Dispatchers.IO) {
                    FakeLocationBridge.apply(
                        entry.packageName, entry.userId,
                        ident.lat, ident.lng, ident.gpsEnabled
                    )
                }
                if (!gps.ok) gpsNote = " (GPS: ${gps.message})"
            }
            // v2.0.9 — the engine's IPC calls can block forever when its
            // server is unresponsive (v2.0.8: second tap stuck on
            // "Launching Instagram…" for 20s+). Bound it: 15s, then a
            // plain failure instead of a hang. (withTimeoutOrNull cancels
            // the wait; a stuck Binder thread may linger, but the UI moves on.)
            val result = withTimeoutOrNull(15_000) {
                engine.launchApp(entry.packageName, entry.userId)
            } ?: EngineResult.Failed("Launch timed out — the engine did not respond.")
            if (result !is EngineResult.Ok) {
                return InstallOutcome(messageOf(result))
            }
            // v2.0.3 — the engine reported success, but on the user's
            // phone v2.0.1/v2.0.2 both showed "Launching…" forever with
            // nothing opening. A clone runs in a ":pN" stub process
            // under Zee Clone's own UID; if no :pN process is alive 5s
            // after a reported success, the clone's process died on
            // start (engine hooks failing on this Android version) —
            // say that plainly instead of "Launching…" forever.
            delay(5000)
            if (isAgentProcessAlive()) {
                InstallOutcome(messageOf(result) + gpsNote)
            } else {
                InstallOutcome(
                    "Clone could not start — its process did not stay alive. " +
                    "The engine may not support this Android version."
                )
            }
        } catch (e: Exception) {
            // Never let a launch failure escape into the click handler —
            // an uncaught throw in the coroutine would kill the app.
            InstallOutcome("Launch failed: ${e.message}")
        }
    }

    private fun isAgentProcessAlive(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.any {
                it.processName.startsWith("$packageName:p")
            } == true
        } catch (e: Exception) {
            true // inconclusive — do not false-alarm
        }
    }

    internal suspend fun removeClone(
        entry: CloneEntry,
        engine: VirtualEngine,
        dao: CloneDao
    ): String = withContext(Dispatchers.IO) {
        when (val result = engine.uninstallPackage(entry.packageName, entry.userId)) {
            is EngineResult.Ok -> {
                dao.delete(entry.packageName, entry.cloneIndex)
                "${entry.label} removed."
            }
            is EngineResult.Failed ->
                "Could not remove ${entry.label}: ${result.reason} (entry kept in the library)"
            is EngineResult.NotImplemented -> result.reason
        }
    }

    internal suspend fun removeFromLibraryOnly(entry: CloneEntry, dao: CloneDao): String =
        withContext(Dispatchers.IO) {
            dao.delete(entry.packageName, entry.cloneIndex)
            "${entry.label} removed from the library only (virtual install untouched)."
        }

    internal suspend fun clearCloneData(entry: CloneEntry, engine: VirtualEngine): String =
        messageOf(engine.clearCloneData(entry.packageName, entry.userId))

    internal suspend fun clearCloneCache(entry: CloneEntry, engine: VirtualEngine): String =
        messageOf(engine.clearCloneCache(entry.packageName, entry.userId))

    internal suspend fun killClone(entry: CloneEntry, engine: VirtualEngine): String =
        messageOf(engine.killClone(entry.packageName, entry.userId))

    /**
     * Google Suite (v2.0.6): services ONLY — the host's Google packages
     * (GMS / GSF) when present on the phone. The Play Store client
     * (bundled com.waxmoon.ma.gp.apk) and the vending package are NOT
     * installed anymore: the store is what showed "MultiApp" branding
     * and is what clones got redirected to. removeGoogleSuite still
     * uninstalls all four, so an older install gets cleaned up.
     */
    /** v3.0 — Google Suite is now the engine's own GMS install
     *  (installGms), not host packages cloned one by one. The Play
     *  Store app itself crashes inside NewBlackbox (open upstream issue:
     *  SecurityException on LocaleManager.setApplicationLocales), so the
     *  Suite is GMS-only (gms + gsf) — no Play Store app, no vending. */
    internal suspend fun installGoogleSuite(engine: VirtualEngine): String =
        withContext(Dispatchers.IO) {
            try {
                if (engine.isGmsInstalled(0)) {
                    return@withContext "Google Suite ready."
                }
                when (val result = engine.installGms(0)) {
                    is EngineResult.Ok -> "Google Suite ready."
                    else -> "Google Suite: ${messageOf(result)}"
                }
            } catch (e: Exception) {
                "Google Suite install failed: ${e.message}"
            }
        }

    /** v3.0 — remove Google Suite (the user asked for a Remove
     *  option: the + menu kept offering Install even after the Suite
     *  was already installed). Uninstalls GMS from the virtual space. */
    internal suspend fun removeGoogleSuite(engine: VirtualEngine): String =
        withContext(Dispatchers.IO) {
            try {
                if (!engine.isGmsInstalled(0)) {
                    return@withContext "Google Suite removed."
                }
                when (val result = engine.uninstallGms(0)) {
                    is EngineResult.Ok -> "Google Suite removed."
                    else -> "Google Suite: ${messageOf(result)}"
                }
            } catch (e: Exception) {
                "Google Suite remove failed: ${e.message}"
            }
        }

    /**
     * v3.0.9 report screen: Device Info now carries the full startup
     * report — engine profile + boot state, breadcrumbs, last exit
     * reasons (CRASH vs CRASH_NATIVE + signal + tombstone head) and
     * full device details — so a phone-only user can copy-paste the
     * exact cause without logcat. The dialog's Copy button copies all
     * of it.
     */
    internal suspend fun loadDeviceInfo(): String =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            sb.appendLine("Zee Clone: 3.1.1 (targetSdk 35, sideload-only)")
            val app = ZeeCloneApplication.instance
            sb.appendLine(
                "Engine profile: ${app?.engineProfile ?: Boot.currentProfile(applicationContext.filesDir)}"
            )
            sb.appendLine("Boot state: ${ZeeCloneApplication.bootStateSummary(applicationContext)}")
            sb.appendLine(
                "Engine: NewBlackbox (BlackBoxCore) — ${ZeeCloneApplication.engineStatus()}"
            )
            val engineFailure = ZeeCloneApplication.readEngineFailure(applicationContext)
            if (engineFailure == null) {
                sb.appendLine("Engine init failure: none recorded")
            } else {
                sb.appendLine("Engine init failure:")
                sb.appendLine(engineFailure)
            }
            val lastCrash = ZeeCloneApplication.readLastCrash(applicationContext)
            if (lastCrash == null) {
                sb.appendLine("Last crash: none recorded")
            } else {
                sb.appendLine("Last crash:")
                sb.appendLine(lastCrash)
            }
            sb.appendLine()
            sb.appendLine("Breadcrumbs (last markers — the last one shows where it died):")
            sb.appendLine(ZeeCloneApplication.readBreadcrumbs(applicationContext))
            sb.appendLine()
            sb.appendLine("Exit reasons:")
            sb.appendLine(Boot.exitReasonsReport(applicationContext))
            sb.appendLine()
            sb.appendLine("Device details:")
            sb.appendLine(Boot.deviceReport())
            sb.appendLine()
            sb.appendLine("Clones — proxy + identity (v3.1.1):")
            try {
                val entries = CloneDatabase.get(applicationContext).cloneDao().getAllOnce()
                if (entries.isEmpty()) {
                    sb.appendLine("(no clones yet)")
                }
                for (entry in entries) {
                    sb.appendLine("- ${entry.label} (Clone ${entry.cloneIndex + 1}, ${entry.packageName}, user ${entry.userId}):")
                    val proxy = ProxySettings.read(applicationContext, entry.packageName, entry.userId)
                    if (proxy == null) {
                        sb.appendLine("  proxy: not configured")
                    } else if (!proxy.enabled) {
                        sb.appendLine("  proxy: disabled")
                    } else {
                        val active = proxy.activeEndpoint()
                        val health = ProxyHealth.readFor(applicationContext, entry.packageName, entry.userId, active)
                        val healthText = when {
                            health == null -> "not tested yet"
                            health.alive -> "ALIVE, exit ${health.exitIp} (${health.country})"
                            else -> "DEAD: ${health.reason}"
                        }
                        sb.appendLine("  proxy: ${active.type} ${active.host}:${active.port} (${active.country.ifBlank { "no country" }}) — health: $healthText")
                        if (proxy.alternates.isNotEmpty()) {
                            sb.appendLine("  alternates: ${proxy.alternates.size} (${proxy.alternates.joinToString { "${it.host}:${it.port}" }})")
                        }
                    }
                    val proxyStatus = ProxySettings.readStatus(applicationContext, entry.packageName, entry.userId)
                    if (proxyStatus != null) {
                        sb.appendLine("  proxy hook status: ${proxyStatus.replace("\n", " | ")}")
                    }
                    val identity = IdentitySettings.read(applicationContext, entry.packageName, entry.userId)
                    if (identity == null) {
                        sb.appendLine("  identity: not configured (host timezone/locale/GPS/ID)")
                    } else {
                        sb.appendLine(
                            "  identity: tz=${identity.timezone.ifBlank { "(host)" }} " +
                                "locale=${identity.locale.ifBlank { "(host)" }} " +
                                "gps=${if (identity.gpsEnabled) "${identity.lat},${identity.lng}" else "off"} " +
                                "androidId=${identity.androidId.ifBlank { "(real ID)" }}"
                        )
                    }
                    val identityStatus = IdentitySettings.readStatus(applicationContext, entry.packageName, entry.userId)
                    if (identityStatus != null) {
                        sb.appendLine("  identity apply status: ${identityStatus.replace("\n", " | ")}")
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("(could not list clones: ${e.message})")
            }
            sb.appendLine()
            sb.appendLine("Device IDs the engine already separates per clone (no app work needed):")
            sb.appendLine("  Advertising ID: engine hook (VirtualAdvertisingIdService) — distinct per clone.")
            sb.appendLine("  App Set ID: distinct naturally (GMS data is isolated per clone).")
            sb.appendLine("  Build serial / IMEI: not readable by normal apps on Android 10+")
            sb.appendLine("  (SecurityException without READ_PRIVILEGED_PHONE_STATE).")
            sb.appendLine("  Build model/fingerprint: the REAL phone for every clone (consistent).")
            sb.toString().trim()
        }


    /** Launchable apps installed on the phone (for the Import Apps screen). */
    internal suspend fun listInstalledApps(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val pm = packageManager
            @Suppress("DEPRECATION")
            val infos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                pm.queryIntentActivities(intent, 0)
            }
            infos.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null // never clone Zee Clone itself
                val label = info.loadLabel(pm)?.toString() ?: pkg
                pkg to label
            }.sortedBy { it.second.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
        }


    internal fun hostLabel(packageName: String): String {
        return try {
            val pm = packageManager
            @Suppress("DEPRECATION")
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                pm.getApplicationInfo(packageName, 0)
            }
            info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
        } catch (e: Exception) {
            packageName
        }
    }

    /** (packageName, label) parsed out of an APK file, or null. */
    internal fun parseApkPackage(apkPath: String): Pair<String, String>? {
        return try {
            val pm = packageManager
            @Suppress("DEPRECATION")
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(
                    apkPath,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                pm.getPackageArchiveInfo(apkPath, 0)
            } ?: return null
            val appInfo = info.applicationInfo ?: return null
            val label = appInfo.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                ?: info.packageName
            info.packageName to label
        } catch (e: Exception) {
            null
        }
    }
}

/** An install/launch outcome: a message for the status card. */
data class InstallOutcome(
    val message: String,
    /** True when the install really succeeded — the UI auto-returns home. */
    val success: Boolean = false
)

private fun messageOf(result: EngineResult): String = when (result) {
    is EngineResult.Ok -> result.message
    is EngineResult.Failed -> result.reason
    is EngineResult.NotImplemented -> result.reason
}

private enum class Screen { HOME, IMPORT }

/**
 * Real app icons, loaded from PackageManager and cached in memory.
 *
 * - Installed app: the live launcher icon via getApplicationIcon().
 * - Imported APK that is NOT installed: the icon embedded in the APK file
 *   via getPackageArchiveInfo() (sourceDir must point at the file or
 *   loadIcon() returns the default icon).
 * - Anything else: null, and the UI falls back to a letter tile.
 */
object AppIconLoader {
    private val cache = ConcurrentHashMap<String, ImageBitmap?>()

    suspend fun load(pm: PackageManager, entry: CloneEntry): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val key = entry.packageName + "#" + entry.cloneIndex
            cache.getOrPut(key) {
                try {
                    val drawable: Drawable? = try {
                        pm.getApplicationIcon(entry.packageName)
                    } catch (e: PackageManager.NameNotFoundException) {
                        val apkPath = entry.apkPath ?: return@getOrPut null
                        @Suppress("DEPRECATION")
                        val info = if (Build.VERSION.SDK_INT >= 33) {
                            pm.getPackageArchiveInfo(
                                apkPath,
                                PackageManager.PackageInfoFlags.of(0)
                            )?.applicationInfo
                        } else {
                            pm.getPackageArchiveInfo(apkPath, 0)?.applicationInfo
                        } ?: return@getOrPut null
                        info.sourceDir = apkPath
                        info.publicSourceDir = apkPath
                        info.loadIcon(pm)
                    }
                    drawable?.toImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
        }

    suspend fun loadForPackage(pm: PackageManager, packageName: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            cache.getOrPut("pkg:$packageName") {
                try {
                    pm.getApplicationIcon(packageName).toImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
        }
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    val w = intrinsicWidth.takeIf { it > 0 } ?: 192
    val h = intrinsicHeight.takeIf { it > 0 } ?: 192
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bitmap.asImageBitmap()
}

// ---------------------------------------------------------------------------
// v3.0.8 safe-mode startup screens
// ---------------------------------------------------------------------------

@Composable
private fun StartupLoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text("Starting Zee Clone…", fontSize = 16.sp)
    }
}

@Composable
private fun StartupErrorScreen(
    message: String,
    engineDisabledReason: String?,
    report: String,
    onRetry: () -> Unit,
    onRetryCompat: () -> Unit,
    onClearEngineData: () -> Unit
) {
    // v3.0.9 safe-mode UI: the app always opens and says WHY the
    // engine is disabled, with Retry (compat), Copy report and Clear
    // engine data — instead of dying silently before any UI.
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Zee Clone could not start",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center)
        if (engineDisabledReason != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(engineDisabledReason, textAlign = TextAlign.Center)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRetryCompat, modifier = Modifier.fillMaxWidth()) {
            Text("Retry with COMPAT engine")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                clipboardManager.setText(AnnotatedString(report))
                Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy report")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Try again")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onClearEngineData) { Text("Clear engine data") }
    }
}

// Root UI — screen state machine
// ---------------------------------------------------------------------------

@Composable
private fun ZeeCloneApp(
    dao: CloneDao,
    settings: SettingsStore,
    engine: VirtualEngine,
    /** v3.0.9: non-null when the engine is disabled/failed (home banner). */
    engineBanner: String?,
    activity: MainActivity
) {
    val clones by dao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val maxClones by settings.maxClones.collectAsStateWithLifecycle(
        initialValue = SettingsStore.DEFAULT_MAX_CLONES
    )
    val notifications by settings.notificationsEnabled.collectAsStateWithLifecycle(
        initialValue = true
    )
    val googleSuiteInstalled by settings.googleSuiteInstalled.collectAsStateWithLifecycle(
        initialValue = false
    )

    // v3.1.1: periodic proxy health re-check while Zee Clone is open.
    // Every 15 min (only while the app is resumed) each configured proxy
    // is re-tested and its health file updated; a dead proxy shows as a
    // warning in the clone menu, the Proxy dialog and Device Info. No
    // background service: when the app is closed, health is re-checked
    // on the next open / dialog / Device Info load instead.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        while (true) {
            delay(15 * 60 * 1000)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                withContext(Dispatchers.IO) {
                    for (entry in dao.getAllOnce()) {
                        val ps = ProxySettings.read(activity, entry.packageName, entry.userId)
                        if (ps?.enabled == true && ps.host.isNotBlank() && ps.port in 1..65535) {
                            ProxyHealth.testEndpoint(activity, entry.packageName, entry.userId, ps.activeEndpoint())
                        }
                    }
                }
            }
        }
    }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var status by remember { mutableStateOf<String?>(null) }
    var cloneOptions by remember { mutableStateOf<CloneEntry?>(null) }
    // v3.1.0: per-clone proxy dialog target.
    var proxyEntry by remember { mutableStateOf<CloneEntry?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showDeviceInfo by remember { mutableStateOf(false) }
    var deviceInfoText by remember { mutableStateOf<String?>(null) }
    var installedApps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val scope = rememberCoroutineScope()

    // v2.0.9 — permissions: Zee Clone declares camera / mic / location /
    // contacts / photos in the manifest (a clone can only use what the
    // host is granted), but NEVER asks for them on its own — not at app
    // open (v2.0.7) and not when a clone is tapped (v2.0.8: the tap
    // fired FIVE permission dialogs in a row, which no real app does).
    // A clone is launched immediately on tap, no dialogs. When a clone
    // itself needs camera/photos (e.g. Instagram), it asks inside the
    // virtual space and the engine handles it. Settings → "Grant app
    // permissions" still lets the user grant them manually any time.
    val clonePermissions: Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
    val requestClonePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.count { it.value }
        status = if (granted == results.size) {
            "All ${results.size} permissions granted."
        } else {
            "$granted of ${results.size} permissions granted. Denied ones stay off for clones — re-grant them from Settings → Grant app permissions, or system Settings → Apps → Zee Clone → Permissions."
        }
    }
    /** Tap a clone → launch it immediately. No permission dialogs, ever:
     *  v2.0.8 asked at tap time and the user got five dialogs in a row. */
    val launchCloneNow: (CloneEntry) -> Unit = { entry ->
        scope.launch {
            val outcome = activity.launchClone(entry, engine)
            status = outcome.message
        }
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    // v2.0.1 two-way reconciliation: the ENGINE is the source of truth.
    // Engine-installed clones missing from the library are added back,
    // and library entries the engine does NOT report are removed — the
    // v1.x stub entries (never real installs) must not survive as
    // phantom clones. Runs ONLY while the engine is up: when the engine
    // is down (or its call throws) the library is left untouched, so a
    // transient engine failure can never wipe the library.
    LaunchedEffect(Unit) {
        try {
            if (!engine.engineReady()) return@LaunchedEffect
            val engineClones = engine.listClones()
            val existing = dao.getAllOnce()
            for (ec in engineClones) {
                if (existing.none {
                        it.packageName == ec.packageName && it.cloneIndex == ec.cloneIndex
                    }
                ) {
                    dao.insert(ec)
                }
            }
            for (old in existing) {
                if (engineClones.none {
                        it.packageName == old.packageName && it.cloneIndex == old.cloneIndex
                    }
                ) {
                    dao.delete(old.packageName, old.cloneIndex)
                }
            }
        } catch (e: Exception) {
            // Engine not ready yet — the library still works.
        }
    }

    LaunchedEffect(screen) {
        if (screen == Screen.IMPORT && installedApps == null) {
            installedApps = activity.listInstalledApps()
        }
    }

    LaunchedEffect(showDeviceInfo) {
        if (showDeviceInfo) {
            deviceInfoText = activity.loadDeviceInfo()
        }
    }

    val apkFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val outcome = activity.importApkFile(uri, engine, dao, maxClones)
                status = outcome.message
                if (outcome.success) screen = Screen.HOME
            }
        }
    }
    val apkDirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val outcome = activity.importApkDirectory(uri, engine, dao, maxClones)
                status = outcome.message
                if (outcome.success) screen = Screen.HOME
            }
        }
    }

    // Shared overflow-menu actions.
    val onInstallGoogleSuite: () -> Unit = {
        scope.launch {
            status = activity.installGoogleSuite(engine)
            if (status == "Google Suite ready.") {
                settings.setGoogleSuiteInstalled(true)
            }
        }
    }
    // v2.0.5 — the user asked for a Remove option: the + menu kept
    // offering Install even after the Suite was already installed.
    val onRemoveGoogleSuite: () -> Unit = {
        scope.launch {
            status = activity.removeGoogleSuite(engine)
            if (status == "Google Suite removed.") {
                settings.setGoogleSuiteInstalled(false)
            }
        }
    }
    val onDeviceInfo: () -> Unit = { showDeviceInfo = true }
    val onSettings: () -> Unit = { showSettings = true }
    // v2.0.10 fix — Run #72 failed: the long-press dialog called
    // onAddAnotherClone, but it only existed as ZeeCloneHomeScreen's
    // parameter. One shared lambda here, used by the home screen AND
    // the long-press "Add another clone" option.
    val onAddAnotherClone: (CloneEntry) -> Unit = { entry ->
        scope.launch {
            val outcome = activity.addInstalledApp(
                entry.packageName, entry.label, engine, dao, maxClones
            )
            status = outcome.message
            if (outcome.success) screen = Screen.HOME
        }
    }

    when (screen) {
        Screen.HOME -> ZeeCloneHomeScreen(
            clones = clones,
            status = status,
            onStatusDismiss = { status = null },
            engineBanner = engineBanner,
            onEngineBannerDetails = onDeviceInfo,
            onOpenImport = { screen = Screen.IMPORT },
            onInstallGoogleSuite = onInstallGoogleSuite,
            onRemoveGoogleSuite = onRemoveGoogleSuite,
            googleSuiteInstalled = googleSuiteInstalled,
            onDeviceInfo = onDeviceInfo,
            onSettings = onSettings,
            onLaunchClone = launchCloneNow,
            onCloneOptions = { cloneOptions = it },
        )
        Screen.IMPORT -> ImportAppsScreen(
            installedApps = installedApps,
            clones = clones,
            status = status,
            onStatusDismiss = { status = null },
            onBack = { screen = Screen.HOME },
            onInstallGoogleSuite = onInstallGoogleSuite,
            onDeviceInfo = onDeviceInfo,
            onSettings = onSettings,
            onPickApkFile = {
                apkFilePicker.launch(arrayOf("*/*"))
            },
            onPickApkFolder = { apkDirPicker.launch(null) },
            onAddApp = { pkg, label ->
                scope.launch {
                    val outcome = activity.addInstalledApp(pkg, label, engine, dao, maxClones)
                    status = outcome.message
                    if (outcome.success) screen = Screen.HOME
                }
            }
        )
    }

    // --- dialogs ------------------------------------------------------------

    cloneOptions?.let { entry ->
        // v3.1.0: show the configured proxy country on the button, if any.
        // v3.1.1: append a DEAD warning when the last health check failed.
        val proxyLabel = remember(entry) {
            val ps = ProxySettings.read(activity, entry.packageName, entry.userId)
                ?.takeIf { it.enabled }
            val base = ps?.let { "Proxy… (${it.country.ifBlank { it.type }})" } ?: "Proxy…"
            val dead = ps?.let {
                ProxyHealth.readFor(activity, entry.packageName, entry.userId, it.activeEndpoint())
            }?.takeIf { !it.alive }
            if (dead != null) "$base ⚠ DEAD" else base
        }
        AlertDialog(
            onDismissRequest = { cloneOptions = null },
            title = { Text(entry.label) },
            text = {
                Column {
                    CloneOptionButton("Launch") {
                        cloneOptions = null
                        launchCloneNow(entry)
                    }
                    CloneOptionButton("Add another clone") {
                        cloneOptions = null
                        onAddAnotherClone(entry)
                    }
                    CloneOptionButton(proxyLabel) {
                        cloneOptions = null
                        proxyEntry = entry
                    }
                    CloneOptionButton("Clear data") {
                        cloneOptions = null
                        scope.launch { status = activity.clearCloneData(entry, engine) }
                    }
                    CloneOptionButton("Clear cache") {
                        cloneOptions = null
                        scope.launch { status = activity.clearCloneCache(entry, engine) }
                    }
                    CloneOptionButton("Kill app") {
                        cloneOptions = null
                        scope.launch { status = activity.killClone(entry, engine) }
                    }
                    CloneOptionButton("Remove") {
                        cloneOptions = null
                        scope.launch { status = activity.removeClone(entry, engine, dao) }
                    }
                    CloneOptionButton("Remove from library only") {
                        cloneOptions = null
                        scope.launch { status = activity.removeFromLibraryOnly(entry, dao) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { cloneOptions = null }) { Text("Cancel") }
            }
        )
    }

    // v3.1.0: per-clone proxy dialog.
    proxyEntry?.let { entry ->
        ProxyDialog(
            entry = entry,
            activity = activity,
            engine = engine,
            onStatus = { status = it },
            onDismiss = { proxyEntry = null }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Max clones per app", modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch { settings.setMaxClones(maxClones - 1) }
                        }) { Text("−", fontSize = 20.sp) }
                        Text("$maxClones", fontWeight = FontWeight.Bold)
                        TextButton(onClick = {
                            scope.launch { settings.setMaxClones(maxClones + 1) }
                        }) { Text("+", fontSize = 20.sp) }
                    }
                    Text(
                        "Already PRO — all features unlocked, up to 10 clones per app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Clone notifications", modifier = Modifier.weight(1f))
                        Switch(
                            checked = notifications,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.setNotificationsEnabled(enabled) }
                            }
                        )
                    }
                    TextButton(
                        onClick = {
                            showSettings = false
                            requestClonePermissions.launch(clonePermissions)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Grant app permissions",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                    }
                    Text(
                        "Camera, photos, location, contacts — a clone can only use what Zee Clone itself is granted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { showSettings = false; showLicenses = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Open source licenses",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                    }
                    Text(
                        "Engine: NewBlackbox (BlackBoxCore) • targetSdk 35 • sideload-only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("Done") }
            }
        )
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open source licenses") },
            text = {
                Text(
                    "Zee Clone is based on CloneX v3.0 and uses the NewBlackbox virtual engine by ALEX5402 " +
                        "(https://github.com/ALEX5402/NewBlackbox), licensed under " +
                        "the Apache License 2.0. CloneX source code is published " +
                        "at https://github.com/XyronixDev/clonex-android. The " +
                        "engine is used with the MaxCloner split-APK patch " +
                        "(https://github.com/ashiquesheriefofficial-max/MaxCloner, " +
                        "also Apache-2.0), applied at build time."
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) { Text("Close") }
            }
        )
    }

    if (showDeviceInfo) {
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showDeviceInfo = false },
            title = { Text("Device Info") },
            text = { Text(deviceInfoText ?: "Loading…") },
            dismissButton = {
                TextButton(onClick = {
                    deviceInfoText?.let {
                        clipboardManager.setText(AnnotatedString(it))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Copy") }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceInfo = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun CloneOptionButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 0.dp)
    ) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )
    }
}

// ---------------------------------------------------------------------------
// Home screen (light)
// ---------------------------------------------------------------------------

@Composable
private fun ZeeCloneHomeScreen(
    clones: List<CloneEntry>,
    status: String?,
    onStatusDismiss: () -> Unit,
    /** v3.0.9: engine-disabled reason, shown as a banner (null = engine up). */
    engineBanner: String?,
    onEngineBannerDetails: () -> Unit,
    onOpenImport: () -> Unit,
    onInstallGoogleSuite: () -> Unit,
    onRemoveGoogleSuite: () -> Unit,
    googleSuiteInstalled: Boolean,
    onDeviceInfo: () -> Unit,
    onSettings: () -> Unit,
    onLaunchClone: (CloneEntry) -> Unit,
    onCloneOptions: (CloneEntry) -> Unit
) {
    var showFabMenu by remember { mutableStateOf(false) }
    var bannerDismissed by remember { mutableStateOf(false) }

    val visibleClones = remember(clones) { clones }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { showFabMenu = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Text("+", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(
                    expanded = showFabMenu,
                    onDismissRequest = { showFabMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Import Apps") },
                        onClick = { showFabMenu = false; onOpenImport() }
                    )
                    DropdownMenuItem(
                        // v2.0.5 — once installed, the menu offers Remove
                        // instead of Install (the user asked for it).
                        text = {
                            Text(
                                if (googleSuiteInstalled) "Remove Google Suite"
                                else "Install Google Suite"
                            )
                        },
                        onClick = {
                            showFabMenu = false
                            if (googleSuiteInstalled) onRemoveGoogleSuite()
                            else onInstallGoogleSuite()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Device Info") },
                        onClick = { showFabMenu = false; onDeviceInfo() }
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = { showFabMenu = false; onSettings() }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            status?.let { message ->
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onStatusDismiss) { Text("Dismiss") }
                        }
                    }
                }
            }

            // v3.0.9: engine-disabled banner — the UI always opens and
            // says why the engine is off, with Details opening the full
            // report (Device Info). Dismiss is per-screen only.
            if (engineBanner != null && !bannerDismissed) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(engineBanner, style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onEngineBannerDetails) { Text("Details") }
                            TextButton(onClick = { bannerDismissed = true }) { Text("Dismiss") }
                        }
                    }
                }
            }

            // v2.0.6 — blank home: no title, no feature card, no
            // search, no empty-state text. Only the clone cards (the
            // app's purpose — without them a clone could never be
            // launched) and the + button. The status card above stays:
            // it is the only place a launch/install result is shown.
            if (visibleClones.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(visibleClones, key = { it.packageName + "#" + it.cloneIndex }) { entry ->
                        CloneTile(
                            entry = entry,
                            // v2.0.10 — the number rides on the NAME, only
                            // when this app has more than one clone.
                            cloneCount = visibleClones.count { it.packageName == entry.packageName },
                            onClick = { onLaunchClone(entry) },
                            onLongClick = { onCloneOptions(entry) }
                        )
                    }
                }
            }
        }
    }
}

/** Real app icon with a letter tile only as a loading fallback. */
@Composable
private fun CloneAppIcon(entry: CloneEntry, size: androidx.compose.ui.unit.Dp) {
    val pm = LocalContext.current.packageManager
    var bitmap by remember(entry.packageName, entry.cloneIndex) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(entry.packageName, entry.cloneIndex) {
        bitmap = AppIconLoader.load(pm, entry)
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = entry.label,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(14.dp))
        )
    } else {
        val letter = entry.label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val hue = (entry.packageName.hashCode() and 0xFF) / 255f
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.hsv(hue * 360f, 0.55f, 0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Text(letter, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloneTile(
    entry: CloneEntry,
    cloneCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // v2.0.10 — launcher-style tile, NOT a card: no white card, no
    // number badge, no "+" button, no "Clone N" pill, no "Tap to
    // launch". Just the real app icon and its name, like a launcher.
    // When one app has more than one clone, the number rides on the
    // name ("Instagram 1", "Instagram 2"); a single clone shows the
    // plain name. Long-press opens the options (Launch / Add another
    // clone / Clear data / Clear cache / Kill / Remove).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CloneAppIcon(entry = entry, size = 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (cloneCount > 1) "${entry.label} ${entry.cloneIndex + 1}" else entry.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF14141A),
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------------------
// Import Apps screen
// ---------------------------------------------------------------------------

private sealed interface ImportItem {
    data class Header(val letter: Char) : ImportItem
    data class Row(val packageName: String, val label: String) : ImportItem
}

private val HOT_APPS = listOf(
    "com.instagram.android" to "Instagram",
    "com.google.android.youtube" to "YouTube",
    "org.telegram.messenger" to "Telegram",
    "com.whatsapp" to "WhatsApp",
    "com.facebook.katana" to "Facebook"
)

@Composable
private fun ImportAppsScreen(
    installedApps: List<Pair<String, String>>?,
    clones: List<CloneEntry>,
    status: String?,
    onStatusDismiss: () -> Unit,
    onBack: () -> Unit,
    onInstallGoogleSuite: () -> Unit,
    onDeviceInfo: () -> Unit,
    onSettings: () -> Unit,
    onPickApkFile: () -> Unit,
    onPickApkFolder: () -> Unit,
    onAddApp: (String, String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showImportChoice by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val cloneCountByPkg = remember(clones) {
        clones.groupingBy { it.packageName }.eachCount()
    }

    // A-Z flat list of rows, grouped by first letter.
    val flatItems = remember(installedApps, query) {
        val list = installedApps ?: emptyList()
        val filtered = if (query.isBlank()) list
        else list.filter {
            it.second.contains(query, ignoreCase = true) ||
                it.first.contains(query, ignoreCase = true)
        }
        val groups = filtered.groupBy { (_, label) ->
            label.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
        }.toSortedMap(compareBy { if (it == '#') Char.MAX_VALUE else it })
        val items = mutableListOf<ImportItem>()
        for ((letter, rows) in groups) {
            items += ImportItem.Header(letter)
            for ((pkg, label) in rows) items += ImportItem.Row(pkg, label)
        }
        items
    }
    val sectionIndex = remember(flatItems) {
        val map = mutableMapOf<Char, Int>()
        flatItems.forEachIndexed { index, item ->
            if (item is ImportItem.Header) map[item.letter] = index
        }
        map
    }
    val indexLetters = remember(sectionIndex) { sectionIndex.keys.sorted() }

    val hotList = remember(installedApps) {
        val installed = installedApps ?: emptyList()
        HOT_APPS.mapNotNull { (pkg, _) ->
            installed.firstOrNull { it.first == pkg }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("‹", fontSize = 32.sp, color = Color(0xFF14141A))
            }
            Text(
                "Import Apps",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF14141A)
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showSearch = !showSearch }) {
                Text("⌕", fontSize = 22.sp, color = Color(0xFF14141A))
            }
        }

        if (showSearch) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps…") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
        }

        status?.let { message ->
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onStatusDismiss) { Text("Dismiss") }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Import from file
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showImportChoice = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE0F2F1)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📁", fontSize = 22.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Import from file",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = Color(0xFF14141A)
                )
                Spacer(Modifier.width(8.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        when (installedApps) {
            null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Loading installed apps…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(end = 24.dp, bottom = 16.dp)
                    ) {
                        if (query.isBlank() && hotList.isNotEmpty()) {
                            item {
                                Text(
                                    "HOT",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6B6B70),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            items(hotList, key = { it.first }) { (pkg, label) ->
                                ImportAppRow(
                                    packageName = pkg,
                                    label = label,
                                    cloneCount = cloneCountByPkg[pkg] ?: 0,
                                    onAdd = { onAddApp(pkg, label) }
                                )
                            }
                        }
                        if (query.isBlank()) {
                            items(flatItems, key = { item ->
                                when (item) {
                                    is ImportItem.Header -> "header:${item.letter}"
                                    is ImportItem.Row -> "row:${item.packageName}"
                                }
                            }) { item ->
                                when (item) {
                                    is ImportItem.Header -> Text(
                                        item.letter.toString(),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF6B6B70),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.background)
                                            .padding(vertical = 8.dp)
                                    )
                                    is ImportItem.Row -> ImportAppRow(
                                        packageName = item.packageName,
                                        label = item.label,
                                        cloneCount = cloneCountByPkg[item.packageName] ?: 0,
                                        onAdd = { onAddApp(item.packageName, item.label) }
                                    )
                                }
                            }
                        } else {
                            // Search mode: flat filtered rows, no sections/index.
                            val filtered = (installedApps ?: emptyList()).filter {
                                it.second.contains(query, ignoreCase = true) ||
                                    it.first.contains(query, ignoreCase = true)
                            }
                            items(filtered, key = { it.first }) { (pkg, label) ->
                                ImportAppRow(
                                    packageName = pkg,
                                    label = label,
                                    cloneCount = cloneCountByPkg[pkg] ?: 0,
                                    onAdd = { onAddApp(pkg, label) }
                                )
                            }
                        }
                    }
                    // Right-side alphabet index.
                    if (query.isBlank() && indexLetters.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            for (letter in indexLetters) {
                                Text(
                                    letter.toString(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF6B6B70),
                                    modifier = Modifier
                                        .clickable {
                                            val target = sectionIndex[letter] ?: return@clickable
                                            scope.launch { listState.scrollToItem(target) }
                                        }
                                        .padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showImportChoice) {
        AlertDialog(
            onDismissRequest = { showImportChoice = false },
            title = { Text("Import from file") },
            text = {
                Text(
                    "Pick a single APK file, or a folder containing all split APKs " +
                        "of one app (WhatsApp, Facebook, Instagram from Play are split APKs)."
                )
            },
            confirmButton = {
                TextButton(onClick = { showImportChoice = false; onPickApkFile() }) {
                    Text("APK file")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportChoice = false; onPickApkFolder() }) {
                    Text("APK folder")
                }
            }
        )
    }
}

@Composable
private fun ImportAppRow(
    packageName: String,
    label: String,
    cloneCount: Int,
    onAdd: () -> Unit
) {
    val pm = LocalContext.current.packageManager
    var bitmap by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        bitmap = AppIconLoader.loadForPackage(pm, packageName)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = label,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            val letter = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(letter, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFF14141A)
            )
            Text(
                if (cloneCount > 0) "$cloneCount clone${if (cloneCount == 1) "" else "s"} added"
                else packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onAdd, contentPadding = PaddingValues(0.dp)) {
            Text(
                "+",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ---------------------------------------------------------------------------
