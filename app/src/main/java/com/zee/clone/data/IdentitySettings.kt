package com.zee.clone.data

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Per-clone identity (v3.1.1) — everything that makes one clone look like
 * a different device in a different country:
 *   timezone + locale — applied inside the clone process at bind time
 *                       (ZeeIdentity.java, patched BActivityThread)
 *   GPS lat/lng       — applied through the ENGINE's own fake-location
 *                       system (BLocationManager, OWN_MODE per app;
 *                       see FakeLocationBridge)
 *   androidId         — stable per-clone fake ANDROID_ID, seeded into
 *                       Settings.Secure's cache at bind time
 *
 * Stored as one JSON file per clone:
 *   <filesDir>/identity/<packageName>_<userId>.json
 * The engine's native proxy config (<filesDir>/proxy/...) is separate and
 * untouched — this file is the identity half. A `.status` file next to it
 * records what ZeeIdentity actually applied at the last clone start.
 */
data class IdentitySettings(
    /** IANA id, e.g. "Europe/Berlin"; "" = the host phone's timezone. */
    val timezone: String = "",
    /** BCP-47 tag, e.g. "de-DE"; "" = the host phone's locale. */
    val locale: String = "",
    val gpsEnabled: Boolean = false,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** Stable per-clone fake ANDROID_ID (16 lowercase hex chars). */
    val androidId: String = ""
) {
    fun toJson(): String = JSONObject().apply {
        put("timezone", timezone)
        put("locale", locale)
        put("gpsEnabled", gpsEnabled)
        put("lat", lat)
        put("lng", lng)
        put("androidId", androidId)
    }.toString()

    companion object {
        fun identityDir(context: Context): File = File(context.filesDir, "identity")

        fun configFile(context: Context, packageName: String, userId: Int): File =
            File(identityDir(context), "${packageName}_${userId}.json")

        /** Written by ZeeIdentity at clone bind time (one line per item). */
        fun statusFile(context: Context, packageName: String, userId: Int): File =
            File(identityDir(context), "${packageName}_${userId}.status")

        /** Null when this clone has no identity configured. */
        fun read(context: Context, packageName: String, userId: Int): IdentitySettings? {
            return try {
                val file = configFile(context, packageName, userId)
                if (!file.isFile) return null
                val json = JSONObject(file.readText())
                IdentitySettings(
                    timezone = json.optString("timezone", ""),
                    locale = json.optString("locale", ""),
                    gpsEnabled = json.optBoolean("gpsEnabled", false),
                    lat = json.optDouble("lat", 0.0),
                    lng = json.optDouble("lng", 0.0),
                    androidId = json.optString("androidId", "")
                )
            } catch (e: Exception) {
                null
            }
        }

        /** Atomic write (temp + rename). Also clears any stale status file. */
        fun save(context: Context, packageName: String, userId: Int, settings: IdentitySettings) {
            val dir = identityDir(context).apply { mkdirs() }
            val tmp = File(dir, "${packageName}_${userId}.json.tmp")
            val dst = configFile(context, packageName, userId)
            tmp.writeText(settings.toJson())
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            statusFile(context, packageName, userId).delete()
        }

        fun remove(context: Context, packageName: String, userId: Int) {
            configFile(context, packageName, userId).delete()
            statusFile(context, packageName, userId).delete()
        }

        fun readStatus(context: Context, packageName: String, userId: Int): String? {
            return try {
                statusFile(context, packageName, userId)
                    .takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }

        /** The host phone's real ANDROID_ID (never spoofed, only compared). */
        fun hostAndroidId(context: Context): String? {
            return try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Returns this clone's fake ANDROID_ID, generating + persisting one
         * the first time. Never regenerates on its own — a new ID makes
         * the clone look like a NEW device, so it only changes when the
         * user taps "Generate new" in the dialog.
         */
        fun ensureAndroidId(context: Context, packageName: String, userId: Int): String {
            val existing = read(context, packageName, userId)
            if (!existing?.androidId.isNullOrBlank()) return existing!!.androidId
            val hostId = hostAndroidId(context)
            val fresh = generateAndroidId(exclude = hostId)
            val updated = (existing ?: IdentitySettings()).copy(androidId = fresh)
            save(context, packageName, userId, updated)
            return fresh
        }

        /** 16 lowercase hex chars, like a real ANDROID_ID. */
        fun generateAndroidId(exclude: String? = null): String {
            val rnd = SecureRandom()
            while (true) {
                val bytes = ByteArray(8)
                rnd.nextBytes(bytes)
                val hex = bytes.joinToString("") { "%02x".format(it) }
                if (hex != "0000000000000000" && hex != exclude) return hex
            }
        }
    }
}

/**
 * Country defaults for the "Set from country" button: timezone, locale
 * and capital-city coordinates. Capitals are well-known public facts.
 */
object CountryDefaults {
    data class Info(
        val timezone: String,
        val locale: String,
        val lat: Double,
        val lng: Double,
        val capital: String
    )

    val MAP: Map<String, Info> = mapOf(
        "DE" to Info("Europe/Berlin", "de-DE", 52.52, 13.405, "Berlin"),
        "FR" to Info("Europe/Paris", "fr-FR", 48.8566, 2.3522, "Paris"),
        "GB" to Info("Europe/London", "en-GB", 51.5074, -0.1278, "London"),
        "US" to Info("America/New_York", "en-US", 40.7128, -74.006, "New York"),
        "TR" to Info("Europe/Istanbul", "tr-TR", 41.0082, 28.9784, "Istanbul"),
        "NL" to Info("Europe/Amsterdam", "nl-NL", 52.3676, 4.9041, "Amsterdam"),
        "IT" to Info("Europe/Rome", "it-IT", 41.9028, 12.4964, "Rome"),
        "ES" to Info("Europe/Madrid", "es-ES", 40.4168, -3.7038, "Madrid"),
        "PL" to Info("Europe/Warsaw", "pl-PL", 52.2297, 21.0122, "Warsaw"),
        "RO" to Info("Europe/Bucharest", "ro-RO", 44.4268, 26.1025, "Bucharest"),
        "UA" to Info("Europe/Kyiv", "uk-UA", 50.4501, 30.5234, "Kyiv"),
        "RU" to Info("Europe/Moscow", "ru-RU", 55.7558, 37.6173, "Moscow"),
        "SE" to Info("Europe/Stockholm", "sv-SE", 59.3293, 18.0686, "Stockholm"),
        "NO" to Info("Europe/Oslo", "nb-NO", 59.9139, 10.7522, "Oslo"),
        "CH" to Info("Europe/Zurich", "de-CH", 47.3769, 8.5417, "Zurich"),
        "AT" to Info("Europe/Vienna", "de-AT", 48.2082, 16.3738, "Vienna"),
        "BE" to Info("Europe/Brussels", "fr-BE", 50.8503, 4.3517, "Brussels"),
        "GR" to Info("Europe/Athens", "el-GR", 37.9838, 23.7275, "Athens"),
        "CZ" to Info("Europe/Prague", "cs-CZ", 50.0755, 14.4378, "Prague"),
        "HU" to Info("Europe/Budapest", "hu-HU", 47.4979, 19.0402, "Budapest"),
        "IN" to Info("Asia/Kolkata", "hi-IN", 19.076, 72.8777, "Mumbai"),
        "PK" to Info("Asia/Karachi", "ur-PK", 24.8607, 67.0011, "Karachi"),
        "AE" to Info("Asia/Dubai", "ar-AE", 25.2048, 55.2708, "Dubai"),
        "SA" to Info("Asia/Riyadh", "ar-SA", 24.7136, 46.6753, "Riyadh"),
        "JP" to Info("Asia/Tokyo", "ja-JP", 35.6762, 139.6503, "Tokyo"),
        "BR" to Info("America/Sao_Paulo", "pt-BR", -23.5505, -46.6333, "São Paulo"),
        "CA" to Info("America/Toronto", "en-CA", 43.6532, -79.3832, "Toronto"),
        "AU" to Info("Australia/Sydney", "en-AU", -33.8688, 151.2093, "Sydney")
    )

    fun forCode(code: String): Info? = MAP[code.trim().uppercase()]
}
