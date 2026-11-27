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
 *
 * v3.0.8 safe-mode startup: in the MAIN process, [startEngineServer]
 * (BlackBoxCore.doCreate()) no longer runs in [onCreate]. The main
 * process shows MainActivity's loading screen first; MainActivity's
 * deferred init calls [startEngineServer] after the first frame.
 * Non-main processes (:black server, :pN clone stubs) never show
 * MainActivity, so they still start the engine in [onCreate] exactly
 * as before — doCreate() installs their system hooks and brings up
 * the :black daemon, and skipping it there would break clones.
 * (Upstream BlackBox's own app does the same: a loader screen starts
 * the engine after UI. See NewBlackbox CLAUDE.md "Startup sequence".)
 */
class ZeeCloneApplication : Application() {

    /** Guard for [startEngineServer]: the engine server starts once. */
    @Volatile
    private var engineServerStarted = false

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
        // Crash handler FIRST: anything that fails after this point is
        // recorded to filesDir/crash-last.txt for Device Info.
        installCrashHandler()
        if (isMainProcess()) {
            // v3.0.8 safe-mode: the engine server start is deferred
            // until MainActivity has shown its first frame.
            // MainActivity calls startEngineServer() from its deferred
            // init. (v3.0.7 died on a Samsung Note 10 Plus right here,
            // before any UI existed.)
            Log.i(TAG, "main process: engine server start deferred to MainActivity")
        } else {
            // :black server and :pN clone stubs never show MainActivity,
            // so they start the engine here exactly as before.
            startEngineServer()
        }
    }

    /**
     * Start the engine server (BlackBoxCore.doCreate()). Idempotent.
     * Called from MainActivity's deferred init in the main process
     * (after the first frame), and from [onCreate] in every other
     * process. Any Java failure is recorded and the app runs degraded.
     */
    fun startEngineServer() {
        if (engineServerStarted) return
        engineServerStarted = true
        try {
            BlackBoxCore.get().doCreate()
        } catch (e: Throwable) {
            recordEngineFailure("onCreate", e)
            Log.e(TAG, "engine init failed in onCreate", e)
        }
        if (engineInitError == null) {
            engineInitOk = true
        }
    }

    /**
     * True only when this process is positively identified as the main
     * process (process name == package name). Unknown (null) counts as
     * NOT main, so an unidentifiable process keeps today's behaviour
     * (engine starts in onCreate) instead of silently never starting.
     */
    private fun isMainProcess(): Boolean {
        val processName: String? = try {
            if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else null
        } catch (e: Exception) {
            null
        } ?: try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        } catch (e: Exception) {
            null
        }
        return processName == packageName
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
                .append("Zee Clone 3.0.8\n")
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
                        .append("Zee Clone 3.0.8\n")
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
