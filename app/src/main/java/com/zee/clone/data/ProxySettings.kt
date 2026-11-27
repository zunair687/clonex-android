package com.zee.clone.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Per-clone proxy settings (v3.1.0). One clone = one country/IP.
 *
 * Stored as one small JSON file per clone so the engine's native hook can
 * read it directly from inside the clone process:
 *   <filesDir>/proxy/<packageName>_<userId>.json
 * The file is written atomically (temp file + rename) so the native reader
 * never sees a torn write. Removing the file (or enabled=false) means the
 * clone gets no proxy and its traffic is untouched.
 *
 * The proxy itself is NOT bought or provided by Zee Clone — the user enters
 * their own proxy details (host, port, optional user/pass). Until then the
 * Test button is the way to check a proxy works.
 */
data class ProxySettings(
    val enabled: Boolean = true,
    /** "socks5" or "http" (HTTP CONNECT). */
    val type: String = "socks5",
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val pass: String = "",
    /** Free label shown in the UI, e.g. "DE" — the country the IP is in. */
    val country: String = ""
) {
    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("type", type)
        put("host", host)
        put("port", port)
        put("user", user)
        put("pass", pass)
        put("country", country)
    }.toString()

    companion object {
        fun proxyDir(context: Context): File = File(context.filesDir, "proxy")

        fun configFile(context: Context, packageName: String, userId: Int): File =
            File(proxyDir(context), "${packageName}_${userId}.json")

        /** Written by the native hook at clone start (enabled/type/error). */
        fun statusFile(context: Context, packageName: String, userId: Int): File =
            File(proxyDir(context), "${packageName}_${userId}.status")

        /** Null when this clone has no proxy configured. */
        fun read(context: Context, packageName: String, userId: Int): ProxySettings? {
            return try {
                val file = configFile(context, packageName, userId)
                if (!file.isFile) return null
                val json = JSONObject(file.readText())
                ProxySettings(
                    enabled = json.optBoolean("enabled", true),
                    type = json.optString("type", "socks5").takeIf { it == "http" } ?: "socks5",
                    host = json.optString("host", ""),
                    port = json.optInt("port", 0),
                    user = json.optString("user", ""),
                    pass = json.optString("pass", ""),
                    country = json.optString("country", "")
                )
            } catch (e: Exception) {
                null
            }
        }

        /** Atomic write (temp + rename). Also clears any stale status file. */
        fun save(context: Context, packageName: String, userId: Int, settings: ProxySettings) {
            val dir = proxyDir(context).apply { mkdirs() }
            val tmp = File(dir, "${packageName}_${userId}.json.tmp")
            val dst = configFile(context, packageName, userId)
            tmp.writeText(settings.toJson())
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            statusFile(context, packageName, userId).delete()
        }

        fun remove(context: Context, packageName: String, userId: Int): Boolean {
            val ok = configFile(context, packageName, userId).delete()
            statusFile(context, packageName, userId).delete()
            return ok
        }

        fun readStatus(context: Context, packageName: String, userId: Int): String? {
            return try {
                statusFile(context, packageName, userId)
                    .takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
    }
}
