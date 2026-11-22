package com.zee.clone

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zee Clone v3.0 application — the NewBlackbox virtual engine
 * (BlackBoxCore) is initialized here:
 *
 *  - attachBaseContext: BlackBoxCore.get().doAttachBaseContext(base,
 *    ClientConfiguration) — hooks the process into the virtual space.
 *    Must run in EVERY process of this APK (main, :black server, and
 *    the :pN stub processes that host cloned apps).
 *  - onCreate: BlackBoxCore.get().doCreate() — brings up the engine
 *    server (the :black process, DaemonService keep-alive).
 *
 * v3.0 replaces the MultiApp opensdk engine (v2.0–v2.0.10): the opensdk
 * server rejected every clone launch on the user's phone (engine code
 * 102) and sometimes reported success while the clone's :agent process
 * died at once. NewBlackbox is actively maintained (Android 13–16),
 * its launch is a single boolean (no int codes), and its hidden-API
 * bypass is 3-layer (generated accessors + FreeReflection + native)
 * instead of the opensdk's bare reflection.
 *
 * Hardening (kept from v2.0.1):
 *  - If the engine init throws, the app must NOT die at startup. The
 *    failure is recorded in [engineInitError] and the app continues in
 *    degraded mode (RealVirtualEngine refuses every op with a clear
 *    message instead of calling into a dead engine).
 *  - A global uncaught-exception handler writes the last crash to
 *    filesDir/crash-last.txt so a crash is diagnosable without logcat
 *    (Device Info shows it).
 */
class ZeeCloneApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Fresh failure record per launch: the first failure in this
        // process is the root cause — later ones (e.g. the
        // NoClassDefFoundError in onCreate after a failed class init)
        // must not overwrite it.
        try {
            File(base.filesDir, ENGINE_FAILURE_FILE).delete()
        } catch (ignored: Exception) {
        }
        try {
            BlackBoxCore.get().doAttachBaseContext(
                base,
                object : ClientConfiguration() {
                    override fun getHostPackageName(): String = base.packageName
                }
            )
        } catch (e: Throwable) {
            recordEngineFailure("attachBaseContext", e)
            Log.e(TAG, "engine init failed in attachBaseContext", e)
        }
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        try {
            BlackBoxCore.get().doCreate()
        } catch (e: Throwable) {
            recordEngineFailure("onCreate", e)
            Log.e(TAG, "engine init failed in onCreate", e)
        }
        if (engineInitError == null) {
            engineInitOk = true
        }
        installCrashHandler()
    }

    /**
     * Record the FULL engine-init failure (type + message + whole stack
     * trace with causes + device) to filesDir/engine-init-failure.txt,
     * so the exact cause is readable from Device Info without logcat.
     * The one-line banner alone cannot identify the cause: the engine
     * swallows most failures internally, so whatever escapes doCreate()
     * is usually an Error from native init or class loading.
     */
    private fun recordEngineFailure(stage: String, e: Throwable) {
        // Keep the FIRST failure only: when class init fails, every later
        // use throws NoClassDefFoundError and would hide the root cause.
        if (engineInitError != null) return
        engineInitError = "$stage: ${e.message}"
        try {
            val stamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US
            ).format(Date())
            val text = StringBuilder()
                .append("Zee Clone engine init failure at $stamp\n")
                .append("Stage: $stage\n")
                .append("Android ${Build.VERSION.RELEASE} ")
                .append("(SDK ${Build.VERSION.SDK_INT}) ")
                .append("${Build.MANUFACTURER} ${Build.MODEL}\n")
                .append("Zee Clone 3.0.5\n")
                .append("Exception: ${e.javaClass.name}: ${e.message}\n")
                .append(Log.getStackTraceString(e))
                .toString()
            File(filesDir, ENGINE_FAILURE_FILE).writeText(text)
        } catch (ignored: Exception) {
            // Never let the recorder itself break startup.
        }
    }

    /** Record the last uncaught crash (stack trace + device) for Device Info. */
    private fun installCrashHandler() {
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
                        .append("Zee Clone 3.0.5\n")
                        .append(Log.getStackTraceString(throwable))
                        .toString()
                    File(filesDir, CRASH_FILE).writeText(text)
                } catch (ignored: Exception) {
                    // Never let the handler itself crash the crash path.
                }
                previous?.uncaughtException(thread, throwable)
            }
        } catch (e: Exception) {
            Log.w(TAG, "installCrashHandler failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ZeeCloneApplication"

        private const val CRASH_FILE = "crash-last.txt"

        private const val ENGINE_FAILURE_FILE = "engine-init-failure.txt"

        @Volatile
        var instance: ZeeCloneApplication? = null
            private set

        /** Non-null when the engine init threw; the app runs degraded. */
        @Volatile
        var engineInitError: String? = null
            private set

        @Volatile
        var engineInitOk: Boolean = false
            private set

        fun engineStatus(): String =
            if (engineInitError == null) "OK" else "failed to start: $engineInitError"

        /** Last recorded crash, or null when none was recorded. */
        fun readLastCrash(context: Context): String? {
            return try {
                val file = File(context.filesDir, CRASH_FILE)
                if (file.exists()) file.readText().trim().takeIf { it.isNotEmpty() } else null
            } catch (e: Exception) {
                null
            }
        }

        /** Full recorded engine-init failure, or null when none was recorded. */
        fun readEngineFailure(context: Context): String? {
            return try {
                val file = File(context.filesDir, ENGINE_FAILURE_FILE)
                if (file.exists()) file.readText().trim().takeIf { it.isNotEmpty() } else null
            } catch (e: Exception) {
                null
            }
        }
    }
}
