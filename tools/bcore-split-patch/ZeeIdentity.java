package top.niunaijun.blackbox.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Zee Clone v3.1.1 — per-clone identity, applied inside the clone process
 * at bind time (called from BActivityThread.handleBindApplication, right
 * after the ZeeProxy call, before the cloned app's code runs).
 *
 * Reads the host app's identity file for this clone:
 *   <host filesDir>/identity/<package>_<userId>.json
 *   {"timezone":"Europe/Berlin","locale":"de-DE",
 *    "gpsEnabled":true,"lat":52.52,"lng":13.405,
 *    "androidId":"a1b2c3d4e5f60718"}
 * GPS is NOT applied here — it goes through the engine's own
 * BLocationManager (OWN_MODE) from the host app (FakeLocationBridge),
 * because the fake location lives in the engine's system server, not in
 * this process.
 *
 * What this file applies (each item fail-soft, outcome in the .status
 * file next to the config — a failure here must NEVER break the clone):
 *
 * 1. Timezone: java.util.TimeZone.setDefault + "user.timezone" property
 *    + ICU TimeZone default + TZ env var (native localtime).
 * 2. Locale: Locale.setDefault + LocaleList.setDefault + ICU ULocale
 *    default + the package context's Resources configuration, so the
 *    clone loads strings in the new language.
 * 3. ANDROID_ID: the clone's stable fake ID is seeded into
 *    Settings.Secure's per-process NameValueCache (mValues + mGeneration
 *    sync), so Settings.Secure.getString(ANDROID_ID) returns the fake
 *    ID instead of the host's — which is otherwise IDENTICAL for every
 *    clone (same host UID + same host signing key).
 *
 * Deliberately NOT spoofed here:
 * - Build.MODEL / fingerprint: static final String constants — javac
 *   inlines them into the app's own code at compile time, so a
 *   reflective change would not be seen by most apps. The clone shows
 *   the REAL phone model, which is at least consistent (a real device).
 * - Build serial / IMEI: unreadable by normal apps on Android 10+
 *   (SecurityException without READ_PRIVILEGED_PHONE_STATE), so
 *   Instagram/TikTok cannot read them anyway.
 */
public class ZeeIdentity {
    private static final String TAG = "ZeeIdentity";

    public static void apply(String packageName, int userId, String hostFilesDir,
                             Context packageContext) {
        try {
            File cfgFile = new File(hostFilesDir + "/identity/" + packageName + "_" + userId + ".json");
            if (!cfgFile.isFile()) {
                return;  // No identity configured for this clone: nothing to do.
            }
            String json = readFile(cfgFile);
            if (json == null || json.isEmpty()) {
                return;
            }
            JSONObject obj = new JSONObject(json);
            StringBuilder status = new StringBuilder();
            status.append("identity applied at ").append(System.currentTimeMillis()).append("\n");

            String timezone = obj.optString("timezone", "");
            if (!timezone.isEmpty()) {
                status.append("timezone=").append(applyTimezone(timezone)).append("\n");
            } else {
                status.append("timezone=skip:empty (host timezone)\n");
            }

            String locale = obj.optString("locale", "");
            if (!locale.isEmpty()) {
                status.append("locale=").append(applyLocale(locale, packageContext)).append("\n");
            } else {
                status.append("locale=skip:empty (host locale)\n");
            }

            String androidId = obj.optString("androidId", "");
            if (!androidId.isEmpty()) {
                status.append("androidId=").append(seedAndroidId(packageContext, androidId)).append("\n");
            } else {
                status.append("androidId=skip:empty (real ID)\n");
            }

            // GPS lives in the engine's system server (BLocationManager) —
            // the host app applies it (FakeLocationBridge). Record what
            // the config asked for so Device Info can show it.
            if (obj.optBoolean("gpsEnabled", false)) {
                status.append("gps=engine-side: ")
                        .append(obj.optDouble("lat", 0.0))
                        .append(",").append(obj.optDouble("lng", 0.0))
                        .append(" (applied by host app)\n");
            } else {
                status.append("gps=off (real location)\n");
            }

            writeFile(new File(hostFilesDir + "/identity/" + packageName + "_" + userId + ".status"),
                    status.toString());
        } catch (Throwable t) {
            // Identity must never break the clone start.
            Log.w(TAG, "ZeeIdentity.apply failed", t);
        }
    }

    private static String applyTimezone(String tzId) {
        try {
            boolean known = false;
            for (String id : TimeZone.getAvailableIDs()) {
                if (id.equals(tzId)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                return "FAIL:unknown timezone " + tzId;
            }
            TimeZone.setDefault(TimeZone.getTimeZone(tzId));
            System.setProperty("user.timezone", tzId);
            try {
                android.icu.util.TimeZone.setDefault(android.icu.util.TimeZone.getTimeZone(tzId));
            } catch (Throwable ignored) {
            }
            try {
                android.system.Os.setenv("TZ", tzId, true);
            } catch (Throwable ignored) {
            }
            return "OK:" + tzId;
        } catch (Throwable t) {
            return "FAIL:" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
        }
    }

    private static String applyLocale(String tag, Context packageContext) {
        try {
            String bcp = tag.replace('_', '-');
            Locale locale = Locale.forLanguageTag(bcp);
            if (locale.getLanguage().isEmpty()) {
                return "FAIL:bad locale " + tag;
            }
            Locale.setDefault(locale);
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    LocaleList.setDefault(new LocaleList(locale));
                } catch (Throwable ignored) {
                }
            }
            try {
                android.icu.util.ULocale.setDefault(new android.icu.util.ULocale(bcp));
            } catch (Throwable ignored) {
            }
            try {
                Configuration config = new Configuration(
                        packageContext.getResources().getConfiguration());
                config.setLocale(locale);
                if (Build.VERSION.SDK_INT >= 24) {
                    config.setLocales(new LocaleList(locale));
                }
                packageContext.getResources().updateConfiguration(
                        config, packageContext.getResources().getDisplayMetrics());
            } catch (Throwable ignored) {
            }
            return "OK:" + locale.toLanguageTag();
        } catch (Throwable t) {
            return "FAIL:" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
        }
    }

    /**
     * Seeds the clone's fake ANDROID_ID into Settings.Secure's
     * per-process NameValueCache: put the value into mValues and sync
     * mGeneration to the provider's current generation, so the next
     * getString(ANDROID_ID) is served from the cache without a provider
     * call. The cache is process-static and this process IS the clone,
     * so the seed is per-clone by construction.
     *
     * Fragile by nature (private platform internals, names differ across
     * Android versions) — every step is guarded and the outcome is
     * reported, never thrown. If the seed fails, the clone keeps the
     * host's ANDROID_ID and the status file says why.
     */
    private static String seedAndroidId(Context context, String fakeId) {
        try {
            Class<?> secureClass = Class.forName("android.provider.Settings$Secure");
            Field cacheField = secureClass.getDeclaredField("sNameValueCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            if (cache == null) {
                return "FAIL:sNameValueCache is null";
            }
            Class<?> cacheClass = Class.forName("android.provider.Settings$NameValueCache");
            Field valuesField = cacheClass.getDeclaredField("mValues");
            valuesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> values = (Map<String, String>) valuesField.get(cache);
            if (values == null) {
                return "FAIL:mValues is null";
            }
            // Sync the generation so the seeded value is served from the
            // cache instead of being re-queried from the provider.
            boolean generationSynced = false;
            try {
                Method genMethod = cacheClass.getDeclaredMethod("getGeneration", ContentResolver.class);
                genMethod.setAccessible(true);
                Object gen = genMethod.invoke(cache, context.getContentResolver());
                if (gen instanceof Integer) {
                    Field genField = cacheClass.getDeclaredField("mGeneration");
                    genField.setAccessible(true);
                    genField.setInt(cache, (Integer) gen);
                    generationSynced = true;
                }
            } catch (Throwable ignored) {
                // Generation sync unavailable on this Android version; the
                // seed may be overwritten on the next settings change.
            }
            values.put("android_id", fakeId);
            // The cache also keys some lookups by the "secure" table name;
            // "android_id" is the only key Settings.Secure.ANDROID_ID uses.
            return "OK:seeded" + (generationSynced ? "" : " (generation sync unavailable)");
        } catch (Throwable t) {
            return "FAIL:" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
        }
    }

    private static String readFile(File file) {
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] buf = new byte[(int) file.length()];
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new String(buf, 0, read, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeFile(File file, String text) {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
