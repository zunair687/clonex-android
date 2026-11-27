import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // NO kotlin.android here — AGP 9.0+ applies Kotlin itself (run #4
    // failed when it was declared). The Compose compiler plugin stays:
    // it is a separate Kotlin subplugin, not part of AGP.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// RELEASE SIGNING — APK Signature Schemes V1 + V2 + V3 (+ V4 bonus)
// ---------------------------------------------------------------------------
// Credentials come ONLY from local.properties (on the builder machine) or
// from environment variables of the same names. Nothing is hardcoded here
// and the keystore / passwords are never committed (see .gitignore).
//
// local.properties keys:
//   CLONEX_STORE_FILE=/absolute/path/to/clonex-release.jks
//   CLONEX_STORE_PASSWORD=<store password>
//   CLONEX_KEY_ALIAS=clonex-release
//   CLONEX_KEY_PASSWORD=<key password>
//
// The SAME keystore must be reused for every future update: Play Store and
// Android reject updates signed with a different key.
// ---------------------------------------------------------------------------
val keystoreProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { keystoreProps.load(it) }
}

fun signingProp(name: String): String? =
    System.getenv(name) ?: keystoreProps.getProperty(name)

val releaseStoreFile: java.io.File? =
    signingProp("CLONEX_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.zee.clone"
    compileSdk = 37

    // v3.0: the NewBlackbox engine ships its native lib (libblackbox.so)
    // in the Bcore AAR. Bcore uses legacy packaging for it; keep the same
    // here so the engine can find its lib the way it expects.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.zee.clone"
        minSdk = 26
        // v3.0.7 (2026-11-26, user order: Play Store release, must run on
        // EVERY phone): targetSdk raised 28 → 35 — Play rejects anything
        // below 35 for new apps. RISK, stated honestly: NewBlackbox was
        // built for 28 and its hidden-API hooks may not survive 35; the
        // build must be installed and clone-tested on a real phone before
        // any Play listing. If the engine breaks at 35, the fallback is
        // sideload-only at 28.
        targetSdk = 35
        // v3.0.8 (2026-11-27): safe-mode startup — UI shows before any
        // heavy init (Room / DataStore / engine server start). v3.0.7
        // died on launch on a Samsung Note 10 Plus (Android 12) with no
        // UI at all; see ZeeCloneApplication / MainActivity.
        // v3.1.0 (2026-11-28): per-clone proxy — engine-level SOCKS5/HTTP
        // proxy per clone (Dobby hooks in the Bcore native module, see
        // tools/zee-proxy/ + tools/patch-bcore-split.py), per-clone proxy
        // settings UI with a Test button, fail-closed UDP/IPv6 blocking.
        // v3.0.9's safe-mode / crash-guard / COMPAT behaviour is unchanged.
        versionCode = 24
        versionName = "3.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Only wired up when the keystore actually exists on this machine.
            // CI builds without local.properties therefore still produce an
            // unsigned release APK artifact; signing happens locally.
            if (releaseStoreFile != null) {
                storeFile = releaseStoreFile
                storePassword = signingProp("CLONEX_STORE_PASSWORD")
                keyAlias = signingProp("CLONEX_KEY_ALIAS")
                keyPassword = signingProp("CLONEX_KEY_PASSWORD")
                // APK Signature Schemes V1+V2+V3+V4 (AGP 9.5 ApkSigningConfig
                // names — the old v1SigningEnabled etc. do not exist in
                // AGP 9; run #7 failed on them as unresolved references).
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8 — keeps the APK small
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // No android:debuggable in release — release builds are never
            // debuggable. (No buildTypes.debug block needed; AGP default.)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Run #31 failed on lintVitalRelease: ExpiredTargetSdkVersion —
    // Google Play requires target API 35+, but targetSdk 28 is an
    // ENGINE requirement (see defaultConfig above) and Zee Clone is
    // sideload-only, never Play. Lint must not fail the release build
    // for this deliberate choice; other fatal lint issues still report.
    //
    // v3.0.9: NewApi is an ERROR — an API-gap crash (a NoSuchMethodError
    // below Android 15, e.g. a Kotlin stdlib call compiled against a
    // new SDK) is suspect #2 for the Samsung launch crash (Claude
    // diagnosis, chat b2300a15). Run lintRelease and fix every NewApi
    // finding in the app's own code; abortOnError stays false so the
    // release build is not blocked by unrelated lint noise.
    lint {
        abortOnError = false
        disable += "ExpiredTargetSdkVersion"
        error += "NewApi"
    }

    // NOTE (AGP 9): android.kotlinOptions {} does NOT exist under AGP 9's
    // built-in Kotlin (run #7 failed on it as an unresolved reference).
    // JVM target is set in the top-level kotlin {} block below instead.

    buildFeatures {
        compose = true
    }

    // v3.0.7 (2026-11-26, user order: Play Store, every phone): UNIVERSAL
    // APK — arm64-v8a + armeabi-v7a both inside. v3.0.6 was arm64-only and
    // would not even install on 32-bit phones (diagnosed 2026-11-26).
    // Play's 64-bit rule is satisfied (arm64 present); the 32-bit slice
    // keeps older/cheaper phones working. (For the Play listing itself an
    // AAB is needed later — bundleRelease, not an APK.)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // NewBlackbox's Bcore AAR uses legacy .so packaging for
            // libblackbox.so; keep the same so the engine's native lib
            // stays loadable.
            useLegacyPackaging = true
        }
    }
}

// JVM target for Kotlin compilation (AGP 9 built-in Kotlin: the
// top-level kotlin {} extension is available without applying any
// Kotlin plugin; kotlinOptions inside android {} is gone in AGP 9).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    // v3.0: the Bcore AAR's resources need these (see libs.versions.toml):
    // Bcore's LauncherTheme extends Theme.AppCompat.Light.NoActionBar
    // and its launcher layout is a ConstraintLayout. An AAR does not
    // carry transitive deps, so without these the app build fails at
    // resource linking (run #90: Theme.AppCompat.Light.NoActionBar
    // not found).
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)

    // v3.0 fix (run #94): Bcore's remaining transitive deps — an AAR
    // carries none of them. material + toml4j + FreeReflection are
    // Bcore's own declared deps (its build.gradle); black-reflection
    // is Bcore's java-library module, built as a JAR in CI (see the
    // workflow) because it is a real runtime dependency (hidden-API
    // bypass) — a -dontwarn would hide classes the engine needs live.
    implementation(libs.material)
    implementation(libs.toml4j)
    implementation(libs.free.reflection)
    implementation(files("libs/black-reflection.jar"))

    // v3.0: NewBlackbox virtual engine (ALEX5402/NewBlackbox, Apache-2.0).
    // The Bcore AAR is built in CI from the pinned upstream commit plus
    // the MaxCloner split-APK patch — see .github/workflows/build.yml
    // and tools/patch-bcore-split.py.
    implementation(files("libs/bcore-release.aar"))
}
