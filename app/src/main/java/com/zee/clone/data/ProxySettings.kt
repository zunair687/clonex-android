package com.zee.clone.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One proxy endpoint (v3.1.1: a clone can hold alternates for "New IP").
 * The ACTIVE endpoint is the top-level host/port of ProxySettings (the
 * native hook reads those); alternates live in the "alternates" array.
 */
data class ProxyEndpoint(
    val type: String = "socks5",
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val pass: String = "",
    val country: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("host", host)
        put("port", port)
        put("user", user)
        put("pass", pass)
        put("country", country)
    }

    companion object {
        fun fromJson(json: JSONObject): ProxyEndpoint = ProxyEndpoint(
            type = json.optString("type", "socks5").takeIf { it == "http" } ?: "socks5",
            host = json.optString("host", ""),
            port = json.optInt("port", 0),
            user = json.optString("user", ""),
            pass = json.optString("pass", ""),
            country = json.optString("country", "")
        )
    }
}

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
 *
 * v3.1.1: alternates — up to 3 extra endpoints per clone. "New IP" rotates
 * the active endpoint to the next alternate (USER-triggered only — never
 * automatic, because changing IP can flag the account). The native hook
 * only ever reads the ACTIVE endpoint (top-level fields); alternates are
 * app-side only.
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
    val country: String = "",
    /** Extra endpoints for "New IP" rotation (max 3). */
    val alternates: List<ProxyEndpoint> = emptyList()
) {
    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("type", type)
        put("host", host)
        put("port", port)
        put("user", user)
        put("pass", pass)
        put("country", country)
        if (alternates.isNotEmpty()) {
            val arr = org.json.JSONArray()
            for (alt in alternates) arr.put(alt.toJson())
            put("alternates", arr)
        }
    }.toString()

    /** The endpoint the native hook actually uses. */
    fun activeEndpoint(): ProxyEndpoint = ProxyEndpoint(type, host, port, user, pass, country)

    /**
     * Rotates to the next alternate: the first alternate becomes active,
     * the old active goes to the end of the list. Null when there is
     * nothing to rotate to. NEVER called automatically — only the user's
     * "New IP" tap.
     */
    fun rotated(): ProxySettings? {
        if (alternates.isEmpty()) return null
        val next = alternates.first()
        val rest = alternates.drop(1) + activeEndpoint()
        return copy(
            type = next.type,
            host = next.host,
            port = next.port,
            user = next.user,
            pass = next.pass,
            country = next.country,
            alternates = rest
        )
    }

    /** Adds the current dialog values as an alternate (max 3). */
    fun withAlternateAdded(endpoint: ProxyEndpoint): ProxySettings {
        if (alternates.size >= 3) return this
        if (endpoint.host.isBlank() || endpoint.port !in 1..65535) return this
        if (alternates.any { it.host == endpoint.host && it.port == endpoint.port }) return this
        return copy(alternates = alternates + endpoint)
    }

    fun withAlternateRemoved(index: Int): ProxySettings {
        if (index !in alternates.indices) return this
        return copy(alternates = alternates.filterIndexed { i, _ -> i != index })
    }

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
                val alternates = mutableListOf<ProxyEndpoint>()
                val arr = json.optJSONArray("alternates")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        try {
                            alternates += ProxyEndpoint.fromJson(arr.getJSONObject(i))
                        } catch (e: Exception) {
                            // Skip a bad alternate, keep the rest.
                        }
                    }
                }
                ProxySettings(
                    enabled = json.optBoolean("enabled", true),
                    type = json.optString("type", "socks5").takeIf { it == "http" } ?: "socks5",
                    host = json.optString("host", ""),
                    port = json.optInt("port", 0),
                    user = json.optString("user", ""),
                    pass = json.optString("pass", ""),
                    country = json.optString("country", ""),
                    alternates = alternates.take(3)
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
