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
 *
 * v3.0.9 (Claude diagnosis, chat b2300a15): the Samsung Note 10 Plus
 * instant launch crash is most likely an engine NATIVE crash in
 * doAttach/doCreate on Samsung Android 12, or a Java Error that
 * catch (Exception) misses. A native crash cannot be caught — so this
 * version CONTAINS it instead, via [Boot]: the crash handler +
 * breadcrumbs install FIRST in attachBaseContext, every engine call
 * is guarded and catches Throwable, a crash-loop guard steps the
 * engine profile down FULL → COMPAT → OFF after each death (a bad
 * device costs at most one crash), and Samsung / SDK <= 32 start in
 * COMPAT, where the engine server starts lazily on the first clone
 * operation instead of at startup.
 */
class ZeeCloneApplication : Application() {

    /** Guard for [startEngineServerNow]: the engine server starts once. */
    @Volatile
    private var engineServerStarted = false

    /**
     * v3.0.9: true when the engine server start was deferred (COMPAT
     * profile) and has not run yet. RealVirtualEngine starts it lazily
     * on the first clone operation.
     */
    @Volatile
    var engineServerDeferred = false
        private set

    /** v3.0.9: true when the crash-loop guard skipped the engine (OFF). */
    @Volatile
    var engineSkipped = false
        private set

    /** The engine profile this launch is running under. */
    @Volatile
    var engineProfile: Boot.EngineProfile = Boot.EngineProfile.FULL
        private set

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
        if (isMainProcess()) {
            // v3.0.9: crash handler + breadcrumbs + crash-loop guard
            // FIRST, before any engine work. beginMain() arms TRYING
            // for this launch and steps the profile down if the last
            // launch died mid-init.
            val decision = Boot.beginMain(this)
            engineProfile = decision.profile
            if (decision.skipEngine) {
                // OFF: a previous launch died starting the engine.
                // The app opens in safe mode; the UI explains why.
                engineSkipped = true
                engineInitError = "Engine disabled on this phone (profile OFF after a crash during startup)"
                Log.w(TAG, "engine skipped this launch (profile OFF)")
            } else {
                Boot.mark(filesDir, "A0 attach")
                Boot.guard(
                    filesDir,
                    "A1 doAttach",
                    { t -> recordEngineFailure("attachBaseContext", t) }
                ) {
                    BlackBoxCore.get().doAttachBaseContext(
                        base,
                        object : ClientConfiguration() {
                            override fun getHostPackageName(): String = base.packageName
                        }
                    )
                }
            }
        } else {
            // :black server and :pN clone stubs: the crash-loop guard
            // does not apply (a dead stub must not poison the main
            // app's next launch) — attach exactly as before.
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
        }
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        // Crash handler FIRST (idempotent — Boot.beginMain already
        // installed it in the main process; this covers the case where
        // attachBaseContext took another path).
        Boot.installCrashHandler(this)
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
     *
     * v3.0.9: profile-aware. In COMPAT the server is NOT started here
     * — it starts lazily on the first clone operation (see
     * [ensureEngineServer]), so a device that cannot start the daemon
     * at boot can still open the UI. In OFF the server never starts.
     */
    fun startEngineServer() {
        if (engineSkipped) {
            Log.w(TAG, "startEngineServer skipped (engine OFF)")
            return
        }
        if (engineProfile == Boot.EngineProfile.COMPAT && isMainProcess()) {
            // COMPAT: no daemon at startup. The server starts lazily
            // on the first clone operation (RealVirtualEngine).
            engineServerDeferred = true
            Boot.mark(filesDir, "C1 doCreate deferred (COMPAT — lazy start on first clone use)")
            Log.i(TAG, "COMPAT: engine server start deferred until first clone operation")
            return
        }
        startEngineServerNow()
    }

    /**
     * The actual doCreate() call. Called by [startEngineServer] in
     * FULL (and in non-main processes), and lazily by
     * [ensureEngineServer] in COMPAT. Idempotent.
     */
    fun startEngineServerNow() {
        if (engineServerStarted) return
        engineServerStarted = true
        engineServerDeferred = false
        Boot.mark(filesDir, "C1 doCreate start")
        try {
            BlackBoxCore.get().doCreate()
            Boot.mark(filesDir, "C1 doCreate done")
        } catch (e: Throwable) {
            Boot.mark(filesDir, "C1 doCreate FAILED: ${e.javaClass.simpleName}: ${e.message}")
            recordEngineFailure("onCreate", e)
            Log.e(TAG, "engine init failed in onCreate", e)
        }
        if (engineInitError == null) {
            engineInitOk = true
        }
    }

    /**
     * COMPAT lazy start: called by RealVirtualEngine before the first
     * engine operation. No-op in FULL (server already started) and in
     * OFF (engine skipped). Safe to call from Dispatchers.IO — every
     * engine op already runs there.
     */
    fun ensureEngineServer() {
        if (!engineServerDeferred || engineSkipped) return
        if (engineInitError != null) return
        Log.i(TAG, "COMPAT lazy start: starting engine server on first clone use")
        startEngineServerNow()
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
     *
     * v3.0.9: a Java engine failure also steps the profile down for the
     * NEXT launch (Boot.noteJavaEngineFailure) — a device that fails in
     * FULL retries in COMPAT, then OFF, instead of dying the same way
     * every time. Runs only on the FIRST failure (the guard above).
     */
    private fun recordEngineFailure(stage: String, e: Throwable) {
        // Keep the FIRST failure only: when class init fails, every later
        // use throws NoClassDefFoundError and would hide the root cause.
        if (engineInitError != null) return
        engineInitError = "$stage: ${e.message}"
        try {
            Boot.noteJavaEngineFailure(filesDir)
        } catch (ignored: Exception) {
        }
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
                .append("Zee Clone 3.0.9\n")
                .append("Exception: ${e.javaClass.name}: ${e.message}\n")
                .append(Log.getStackTraceString(e))
                .toString()
            File(filesDir, ENGINE_FAILURE_FILE).writeText(text)
        } catch (ignored: Exception) {
            // Never let the recorder itself break startup.
        }
    }

    companion object {
        private const val TAG = "ZeeCloneApplication"

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

        /**
         * v3.0.9: one-line reason when the engine is disabled/failed, or
         * null when it is up. Shown as the home banner and on the
         * safe-mode error screen.
         */
        fun engineDisabledReason(): String? {
            val app = instance
            if (app?.engineSkipped == true) {
                return "Engine disabled on this phone (profile OFF after a crash during startup). " +
                    "Your clones are not deleted — retry with COMPAT or clear engine data below."
            }
            val err = engineInitError
            if (err != null) return "Engine failed to start: $err"
            if (app?.engineServerDeferred == true) {
                return "Engine in COMPAT mode — it starts when you use a clone."
            }
            return null
        }

        /** Last recorded crash, or null when none was recorded. */
        fun readLastCrash(context: Context): String? {
            return try {
                val file = File(context.filesDir, "crash-last.txt")
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

        /** Breadcrumb markers from this and previous launches. */
        fun readBreadcrumbs(context: Context): String =
            Boot.readBreadcrumbs(context.filesDir)

        /** Boot-state summary line (lastResult/profile/failures). */
        fun bootStateSummary(context: Context): String =
            Boot.bootStateSummary(context.filesDir)
    }
}
