package com.zee.clone.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Per-proxy health monitoring (v3.1.1).
 *
 * One health record per proxy endpoint (keyed by "host:port"), stored in
 *   <filesDir>/proxy/<packageName>_<userId>.health.json
 * A record is written every time a proxy is tested — on demand (Test
 * button), when the Proxy dialog opens, when Device Info loads, and by
 * the periodic re-check while Zee Clone is in the foreground. A dead
 * proxy is NEVER silently retried or auto-rotated: it is marked dead and
 * shown as a warning in the dialog, the clone menu and Device Info, and
 * only the user's "New IP" tap switches to another proxy.
 *
 * The native hook itself does no health checking — a dead proxy fails
 * closed (the clone simply has no internet), and this file is how the
 * user finds out why.
 */
object ProxyHealth {

    data class EndpointHealth(
        val lastTestMs: Long,
        val alive: Boolean,
        val exitIp: String,
        val country: String,
        val reason: String
    )

    fun keyFor(endpoint: ProxyEndpoint): String = "${endpoint.host}:${endpoint.port}"

    fun healthFile(context: Context, packageName: String, userId: Int): File =
        File(ProxySettings.proxyDir(context), "${packageName}_${userId}.health.json")

    fun read(context: Context, packageName: String, userId: Int): Map<String, EndpointHealth> {
        return try {
            val file = healthFile(context, packageName, userId)
            if (!file.isFile) return emptyMap()
            val json = JSONObject(file.readText())
            val out = mutableMapOf<String, EndpointHealth>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val o = json.optJSONObject(key) ?: continue
                out[key] = EndpointHealth(
                    lastTestMs = o.optLong("lastTestMs", 0),
                    alive = o.optBoolean("alive", false),
                    exitIp = o.optString("exitIp", ""),
                    country = o.optString("country", ""),
                    reason = o.optString("reason", "")
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun readFor(context: Context, packageName: String, userId: Int, endpoint: ProxyEndpoint): EndpointHealth? =
        read(context, packageName, userId)[keyFor(endpoint)]

    fun record(context: Context, packageName: String, userId: Int, endpoint: ProxyEndpoint, result: ProxyTestResult) {
        try {
            val dir = ProxySettings.proxyDir(context).apply { mkdirs() }
            val all = read(context, packageName, userId).toMutableMap()
            val health = when (result) {
                is ProxyTestResult.Success -> EndpointHealth(
                    lastTestMs = System.currentTimeMillis(),
                    alive = true,
                    exitIp = result.exitIp,
                    country = result.country,
                    reason = ""
                )
                is ProxyTestResult.Failed -> EndpointHealth(
                    lastTestMs = System.currentTimeMillis(),
                    alive = false,
                    exitIp = "",
                    country = "",
                    reason = result.reason
                )
            }
            all[keyFor(endpoint)] = health
            val json = JSONObject()
            for ((k, v) in all) {
                json.put(k, JSONObject().apply {
                    put("lastTestMs", v.lastTestMs)
                    put("alive", v.alive)
                    put("exitIp", v.exitIp)
                    put("country", v.country)
                    put("reason", v.reason)
                })
            }
            val tmp = File(dir, "${packageName}_${userId}.health.json.tmp")
            val dst = healthFile(context, packageName, userId)
            tmp.writeText(json.toString())
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // Health is diagnostics only — never break the UI for it.
        }
    }

    /**
     * Tests one endpoint exactly the way the native hook will use it
     * (ProxyTester) and records the result. BLOCKING — call off the
     * main thread (network I/O, up to ~15s on a dead proxy).
     */
    fun testEndpoint(context: Context, packageName: String, userId: Int, endpoint: ProxyEndpoint): ProxyTestResult {
        val settings = ProxySettings(
            enabled = true,
            type = endpoint.type,
            host = endpoint.host,
            port = endpoint.port,
            user = endpoint.user,
            pass = endpoint.pass,
            country = endpoint.country
        )
        val result = ProxyTester.test(settings)
        record(context, packageName, userId, endpoint, result)
        return result
    }
}
