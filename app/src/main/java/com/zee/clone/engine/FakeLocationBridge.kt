package com.zee.clone.engine

/**
 * GPS spoofing through the ENGINE's own fake-location system (v3.1.1).
 *
 * NewBlackbox already ships per-app fake location — BLocationManager with
 * CLOSE_MODE (0, real location), GLOBAL_MODE (1, one fake for all apps)
 * and OWN_MODE (2, one fake per app) — and its ILocationManagerProxy
 * intercepts getLastLocation / getLastKnownLocation / requestLocationUpdates
 * for the clone. So this file does NOT re-implement location spoofing: it
 * only drives the engine's API from the host app (Save + every launch).
 *
 * Everything here is reflection: BLocation's exact shape (constructor /
 * fields) was verified against the engine source (ALEX5402/NewBlackbox,
 * master, 2026-11-28): BLocation(double, double) exists, fields are
 * private mLatitude/mLongitude with public getLatitude()/getLongitude();
 * BLocationManager's methods (setPattern / setLocation / getLocation)
 * are taken from the same source. The bridge still probes the shape at
 * runtime, so a fork change degrades to a plain error, not a crash.
 * All calls are Binder calls — MUST run off the main thread.
 */
object FakeLocationBridge {

    const val CLOSE_MODE = 0
    const val OWN_MODE = 2

    data class GpsApplyResult(val ok: Boolean, val message: String)

    private const val MANAGER_CLASS = "top.niunaijun.blackbox.fake.frameworks.BLocationManager"
    private const val LOCATION_CLASS = "top.niunaijun.blackbox.entity.location.BLocation"

    /**
     * Applies (or clears) the clone's fake location. On success the
     * result is verified by reading the location back from the engine.
     * Never throws — every failure comes back as ok=false with the reason.
     */
    fun apply(packageName: String, userId: Int, lat: Double, lng: Double, enabled: Boolean): GpsApplyResult {
        return try {
            val managerClass = Class.forName(MANAGER_CLASS)
            val manager = managerClass.getMethod("get").invoke(null)
                ?: return GpsApplyResult(false, "BLocationManager.get() returned null.")
            val setPattern = managerClass.getMethod(
                "setPattern",
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            if (!enabled) {
                setPattern.invoke(manager, userId, packageName, CLOSE_MODE)
                return GpsApplyResult(true, "GPS spoof off — clone uses the real location.")
            }
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                return GpsApplyResult(false, "Bad coordinates ($lat, $lng).")
            }
            val location = makeBLocation(lat, lng)
                ?: return GpsApplyResult(false, "Could not build BLocation (shape unknown — see Device Info).")
            val setLocation = managerClass.getMethod(
                "setLocation",
                Int::class.javaPrimitiveType,
                String::class.java,
                Class.forName(LOCATION_CLASS)
            )
            setLocation.invoke(manager, userId, packageName, location)
            setPattern.invoke(manager, userId, packageName, OWN_MODE)
            // Verify by reading it back from the engine.
            val back = readBack(packageName, userId)
            if (back != null && kotlin.math.abs(back.first - lat) < 1e-6 && kotlin.math.abs(back.second - lng) < 1e-6) {
                GpsApplyResult(true, "GPS set to $lat, $lng (verified from the engine).")
            } else {
                GpsApplyResult(
                    false,
                    "GPS was sent but the engine did not return it (read-back: ${back?.toString() ?: "null"})."
                )
            }
        } catch (e: Exception) {
            GpsApplyResult(false, "GPS apply failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Reads the engine's stored fake location for this clone, or null. */
    fun readBack(packageName: String, userId: Int): Pair<Double, Double>? {
        return try {
            val managerClass = Class.forName(MANAGER_CLASS)
            val manager = managerClass.getMethod("get").invoke(null) ?: return null
            val getLocation = managerClass.getMethod(
                "getLocation",
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val loc = getLocation.invoke(manager, userId, packageName) ?: return null
            val lat = readDouble(loc, "latitude", "getLatitude", "mLatitude", "lat")
            val lng = readDouble(loc, "longitude", "getLongitude", "mLongitude", "lng")
            if (lat == null || lng == null) null else lat to lng
        } catch (e: Exception) {
            null
        }
    }

    /** Builds a BLocation for (lat, lng), probing the shape at runtime. */
    private fun makeBLocation(lat: Double, lng: Double): Any? {
        val cls = Class.forName(LOCATION_CLASS)
        // 1. A (double, double) constructor.
        for (ctor in cls.declaredConstructors) {
            val params = ctor.parameterTypes
            if (params.size == 2 &&
                (params[0] == Double::class.javaPrimitiveType || params[0] == Double::class.javaObjectType) &&
                (params[1] == Double::class.javaPrimitiveType || params[1] == Double::class.javaObjectType)
            ) {
                return try {
                    ctor.isAccessible = true
                    ctor.newInstance(lat, lng)
                } catch (e: Exception) {
                    null
                }
            }
        }
        // 2. No-arg constructor + public fields latitude/longitude.
        try {
            val ctor = cls.getDeclaredConstructor()
            ctor.isAccessible = true
            val instance = ctor.newInstance()
            val latOk = setDoubleField(instance, lat, "latitude", "mLatitude", "lat")
            val lngOk = setDoubleField(instance, lng, "longitude", "mLongitude", "lng")
            if (latOk && lngOk) return instance
            // 3. No-arg constructor + setters.
            if (callDoubleSetter(instance, "setLatitude", lat) && callDoubleSetter(instance, "setLongitude", lng)) {
                return instance
            }
        } catch (e: Exception) {
            // Fall through to null.
        }
        return null
    }

    private fun setDoubleField(instance: Any, value: Double, vararg names: String): Boolean {
        for (name in names) {
            try {
                val field = instance.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.setDouble(instance, value)
                return true
            } catch (e: Exception) {
                // Try the next name.
            }
        }
        return false
    }

    private fun callDoubleSetter(instance: Any, name: String, value: Double): Boolean {
        return try {
            val method = instance.javaClass.getMethod(name, Double::class.javaPrimitiveType)
            method.invoke(instance, value)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readDouble(instance: Any, vararg names: String): Double? {
        for (name in names) {
            try {
                val field = instance.javaClass.getDeclaredField(name)
                field.isAccessible = true
                return field.getDouble(instance)
            } catch (e: Exception) {
                // Try getter form next.
            }
            try {
                val method = instance.javaClass.getMethod(name)
                val value = method.invoke(instance)
                if (value is Number) return value.toDouble()
            } catch (e: Exception) {
                // Try the next name.
            }
        }
        return null
    }
}
