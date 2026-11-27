package com.zee.clone

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.0.9 — boot diagnostics + crash-loop guard + engine profiles.
 * (Claude diagnosis, chat b2300a15, 2026-11-28: the Samsung Note 10 Plus
 * instant launch crash is most likely an engine native crash in
 * doAttachBaseContext/doCreate on Samsung Android 12, or a Java Error
 * that catch (Exception) misses. A native crash cannot be caught in
 * Java — so instead of catching it, this file CONTAINS it: breadcrumbs
 * show where it died, and the crash-loop guard makes a bad device cost
 * at most ONE crash before the app opens in safe mode.)
 *
 * Everything here is plain file I/O on filesDir — no DataStore, no Room
 * — because it must work in attachBaseContext, before anything else
 * exists. All methods swallow their own failures: diagnostics must
 * never break startup.
 *
 * Boot-state file (engine-boot-state.txt), three lines:
 *   lastResult=TRYING|OK|FAILED   (TRYING = died during engine init)
 *   profile=FULL|COMPAT|OFF
 *   failures=N                    (consecutive TRYING deaths)
 *
 * Crash-loop guard: the MAIN process writes TRYING before engine init
 * and OK after the first successful init. If the next launch finds
 * TRYING, the previous launch died mid-init: failures++, profile steps
 * down FULL → COMPAT → OFF, and the engine is skipped that launch.
 * Non-main processes (:black, :pN stubs) never touch the state file —
 * a dead clone stub must not poison the main app's next launch.
 *
 * Engine profiles (seeded from real behaviour, never guessed):
 *   FULL   — v3.0.8 behaviour: attach in attachBaseContext, doCreate
 *            deferred until after the first frame.
 *   COMPAT — Samsung, or SDK <= 32: doCreate is NOT run at startup at
 *            all; the engine server starts lazily on the first clone
 *            operation, and no daemon is started until then. (The
 *            "minimal native hooks" half of COMPAT is engine-internal
 *            and cannot be toggled from the app — BlackBox exposes no
 *            hook-profile API. COMPAT here is the lazy-start half plus
 *            the step-down. See the v3.0.9 build note.)
 *   OFF    — engine skipped entirely this launch; the UI opens and
 *            says why, with Retry (compat) / Copy report / Clear data.
 */
object Boot {

    private const val TAG = "ZeeBoot"

    const val BOOT_STATE_FILE = "engine-boot-state.txt"
    const val BREADCRUMB_FILE = "boot-breadcrumbs.txt"

    private const val MAX_BREADCRUMB_BYTES = 64 * 1024
    private const val MAX_BREADCRUMB_LINES = 200
    private const val MAX_REPORT_BREADCRUMBS = 40

    enum class EngineProfile { FULL, COMPAT, OFF }

    data class BootState(
        val lastResult: String,
        val profile: EngineProfile,
        val failures: Int
    )

    data class BeginDecision(
        val profile: EngineProfile,
        val skipEngine: Boolean,
        val steppedDown: Boolean,
        val previousFailures: Int
    )

    private val lock = Any()

    @Volatile
    private var crashHandlerInstalled = false

    // ------------------------------------------------------------------
    // Crash handler (installed FIRST, in attachBaseContext, before any
    // engine work — v3.0.8 installed it in onCreate, too late for an
    // attach crash). Writes filesDir/crash-last.txt. Idempotent.
    // ------------------------------------------------------------------

    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val stamp = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.US
                    ).format(Date())
                    val text = StringBuilder()
                        .append("Zee Clone crash at $stamp\n")
                        .append("Android ${Build.VERSION.RELEASE} ")
                        .append("(SDK ${Build.VERSION.SDK_INT}) ")
                        .append("${Build.MANUFACTURER} ${Build.MODEL}\n")
                        .append("Zee Clone 3.0.9\n")
                        .append(Log.getStackTraceString(throwable))
                        .toString()
                    File(context.filesDir, "crash-last.txt").writeText(text)
                } catch (ignored: Exception) {
                    // Never let the handler itself crash the crash path.
                }
                previous?.uncaughtException(thread, throwable)
            }
        } catch (e: Exception) {
            Log.w(TAG, "installCrashHandler failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Boot state (crash-loop guard)
    // ------------------------------------------------------------------

    fun readState(filesDir: File): BootState {
        return try {
            val file = File(filesDir, BOOT_STATE_FILE)
            if (!file.exists()) {
                return BootState("OK", resolveInitialProfile(), 0)
            }
            var lastResult = "OK"
            var profile = resolveInitialProfile()
            var failures = 0
            file.readLines().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@forEach
                when (parts[0].trim()) {
                    "lastResult" -> lastResult = parts[1].trim()
                    "profile" -> {
                        try {
                            profile = EngineProfile.valueOf(parts[1].trim())
                        } catch (ignored: IllegalArgumentException) {
                        }
                    }
                    "failures" -> failures = parts[1].trim().toIntOrNull() ?: 0
                }
            }
            BootState(lastResult, profile, failures)
        } catch (e: Exception) {
            BootState("OK", resolveInitialProfile(), 0)
        }
    }

    private fun writeState(filesDir: File, state: BootState) {
        try {
            val text = "lastResult=${state.lastResult}\n" +
                "profile=${state.profile}\n" +
                "failures=${state.failures}\n"
            FileOutputStream(File(filesDir, BOOT_STATE_FILE), false).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeState failed: ${e.message}")
        }
    }

    /** Fresh-install seed: Samsung, or SDK <= 32, starts in COMPAT. */
    fun resolveInitialProfile(): EngineProfile {
        return try {
            val isSamsung = Build.MANUFACTURER?.contains("samsung", ignoreCase = true) == true
            if (isSamsung || Build.VERSION.SDK_INT <= 32) EngineProfile.COMPAT
            else EngineProfile.FULL
        } catch (e: Exception) {
            EngineProfile.FULL
        }
    }

    private fun stepDown(profile: EngineProfile): EngineProfile = when (profile) {
        EngineProfile.FULL -> EngineProfile.COMPAT
        EngineProfile.COMPAT -> EngineProfile.OFF
        EngineProfile.OFF -> EngineProfile.OFF
    }

    /**
     * Main process only. Installs the crash handler, evaluates the
     * crash-loop guard, and arms TRYING for this launch. Must be called
     * FIRST in attachBaseContext, before any engine work.
     */
    fun beginMain(context: Context): BeginDecision {
        installCrashHandler(context)
        val filesDir = context.filesDir
        synchronized(lock) {
            val state = readState(filesDir)
            if (state.lastResult == "TRYING") {
                // Previous launch died during engine init: step down.
                val next = stepDown(state.profile)
                val failures = state.failures + 1
                writeState(filesDir, BootState("FAILED", next, failures))
                markLocked(filesDir, "BOOT previous launch died during engine init — stepping down to $next (failures=$failures)")
                return BeginDecision(next, next == EngineProfile.OFF, true, failures)
            }
            if (state.profile == EngineProfile.OFF) {
                markLocked(filesDir, "BOOT engine OFF (stepped down after ${state.failures} crash(es)) — skipping engine this launch")
                return BeginDecision(EngineProfile.OFF, true, false, state.failures)
            }
            writeState(filesDir, BootState("TRYING", state.profile, state.failures))
            markLocked(filesDir, "BOOT begin launch profile=${state.profile}")
            return BeginDecision(state.profile, false, false, state.failures)
        }
    }

    /** Call after the first successful init (engine up, UI ready). */
    fun markOk(filesDir: File) {
        synchronized(lock) {
            val state = readState(filesDir)
            writeState(filesDir, BootState("OK", state.profile, 0))
            markLocked(filesDir, "BOOT OK profile=${state.profile}")
        }
    }

    /** Call when the app survived but init failed in Java (error screen). */
    fun markFailed(filesDir: File, reason: String) {
        synchronized(lock) {
            val state = readState(filesDir)
            writeState(filesDir, BootState("FAILED", state.profile, state.failures))
            markLocked(filesDir, "BOOT FAILED: $reason")
        }
    }

    /**
     * A Java engine failure (doCreate/doAttach threw): step the profile
     * down for the NEXT launch, so a device that fails in FULL retries
     * in COMPAT, then OFF — instead of dying the same way every time.
     */
    fun noteJavaEngineFailure(filesDir: File) {
        synchronized(lock) {
            val state = readState(filesDir)
            val next = stepDown(state.profile)
            writeState(filesDir, BootState("FAILED", next, state.failures))
            markLocked(filesDir, "BOOT Java engine failure — next launch profile=$next")
        }
    }

    /** "Retry (compat)": force COMPAT for the next launch, clear FAILED. */
    fun setProfile(filesDir: File, profile: EngineProfile) {
        synchronized(lock) {
            val state = readState(filesDir)
            writeState(filesDir, BootState("OK", profile, 0))
            markLocked(filesDir, "BOOT profile manually set to $profile")
        }
    }

    /** "Clear engine data": reset all boot diagnostics + guard state. */
    fun resetAll(filesDir: File) {
        synchronized(lock) {
            try {
                File(filesDir, BOOT_STATE_FILE).delete()
                File(filesDir, BREADCRUMB_FILE).delete()
                File(filesDir, "crash-last.txt").delete()
                File(filesDir, "engine-init-failure.txt").delete()
            } catch (e: Exception) {
                Log.w(TAG, "resetAll failed: ${e.message}")
            }
        }
    }

    fun currentProfile(filesDir: File): EngineProfile = readState(filesDir).profile

    fun bootStateSummary(filesDir: File): String {
        val s = readState(filesDir)
        return "lastResult=${s.lastResult} profile=${s.profile} failures=${s.failures}"
    }

    // ------------------------------------------------------------------
    // Breadcrumbs — fsync'd stage markers; the last one shows where it
    // died. Trimmed so the file never grows past ~64 KB.
    // ------------------------------------------------------------------

    fun mark(filesDir: File, stage: String) {
        synchronized(lock) { markLocked(filesDir, stage) }
    }

    private fun markLocked(filesDir: File, stage: String) {
        try {
            val file = File(filesDir, BREADCRUMB_FILE)
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val line = "$stamp $stage\n"
            if (file.exists() && file.length() > MAX_BREADCRUMB_BYTES) {
                val lines = file.readLines()
                val kept = lines.takeLast(MAX_BREADCRUMB_LINES)
                FileOutputStream(file, false).use { out ->
                    kept.forEach { out.write("$it\n".toByteArray(Charsets.UTF_8)) }
                    out.fd.sync()
                }
            }
            FileOutputStream(file, true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "breadcrumb failed: ${e.message}")
        }
    }

    fun readBreadcrumbs(filesDir: File): String {
        return try {
            val file = File(filesDir, BREADCRUMB_FILE)
            if (!file.exists()) return "(no breadcrumbs recorded)"
            file.readLines().takeLast(MAX_REPORT_BREADCRUMBS).joinToString("\n")
        } catch (e: Exception) {
            "(breadcrumbs unreadable: ${e.message})"
        }
    }

    /**
     * Guard: mark the stage, run the block, catch Throwable (not just
     * Exception — a Java Error such as NoSuchMethodError below Android
     * 15 must be recorded, not escape). Returns true on success.
     */
    fun guard(
        filesDir: File,
        stage: String,
        onError: (Throwable) -> Unit,
        block: () -> Unit
    ): Boolean {
        mark(filesDir, "$stage start")
        return try {
            block()
            mark(filesDir, "$stage done")
            true
        } catch (t: Throwable) {
            mark(filesDir, "$stage FAILED: ${t.javaClass.simpleName}: ${t.message}")
            try {
                onError(t)
            } catch (ignored: Exception) {
            }
            false
        }
    }

    // ------------------------------------------------------------------
    // Exit reasons + device report (for the report screen).
    // getHistoricalProcessExitReasons is API 30+; guarded by SDK check.
    // ------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.R)
    fun exitReasonsReport(context: Context): String {
        if (Build.VERSION.SDK_INT < 30) {
            return "Exit reasons: not available below API 30 (SDK ${Build.VERSION.SDK_INT})"
        }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val reasons = am.getHistoricalProcessExitReasons(null, 0, 5)
            if (reasons.isNullOrEmpty()) return "Exit reasons: none recorded"
            val sb = StringBuilder()
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            for (info in reasons) {
                sb.appendLine("- ${exitReasonName(info.reason)} at ${fmt.format(Date(info.timestamp))} process=${info.processName} pid=${info.pid} status=${info.status}")
                val desc = info.description
                if (!desc.isNullOrBlank()) sb.appendLine("  $desc")
                if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    sb.appendLine("  tombstone: ${readTombstoneHead(info)}")
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "Exit reasons unreadable: ${e.message}"
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "REASON_$reason"
    }

    /** First ~1500 chars of the native tombstone, if one is available. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTombstoneHead(info: ApplicationExitInfo): String {
        return try {
            val stream = info.traceInputStream ?: return "(no tombstone stream)"
            stream.use { input ->
                val bytes = ByteArray(1500)
                val n = input.read(bytes)
                if (n <= 0) "(empty tombstone)" else String(bytes, 0, n, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "(tombstone unreadable: ${e.message})"
        }
    }

    fun deviceReport(): String {
        return try {
            val sb = StringBuilder()
            sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
            sb.appendLine("Model: ${Build.MODEL}")
            sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            sb.appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
            sb.appendLine("Kernel: ${System.getProperty("os.version")}")
            sb.appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            sb.appendLine("Fingerprint: ${Build.FINGERPRINT}")
            sb.toString().trim()
        } catch (e: Exception) {
            "Device info unreadable: ${e.message}"
        }
    }
}
