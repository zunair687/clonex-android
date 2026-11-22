# Clone App — Build Plan (Zunair's own cloner)

Owner: Muhammad Zunair Fiaz. Order: 2026-11-22 — "behtreen bnani hai, koi bug na ho, bugs handle karne ka system bhi ho, admin panel".
**App name (user 2026-11-22 12:10): ZEE CLONE.**
Base: CloneX v3.0 (github.com/XyronixDev/clonex-android, Kotlin + Jetpack Compose,
NewBlackbox/BlackBoxCore virtual engine, Apache-2.0) — repo cloned to ~/workspace/clonex-android/.

## 1. The app (rebrand of CloneX v3.0)
- Own name + package + icon (his pick — NOT CloneX). Up to 10 clones per app,
  each clone its own virtual user space, separate logins.
- Targets: WhatsApp, Facebook, Instagram, Telegram, TikTok (social/messaging).
  Split-APK import for WhatsApp/Facebook/Instagram.
- Already PRO (no upsell, no gating). Light-mode Compose UI, launcher-style tiles.
- Signing: release keystore generated ONCE, kept offline forever (same keystore for
  every future update or Android rejects updates). GitHub Secrets for CI signing.

## 2. Bug-handling system (in-app)
- **Firebase Crashlytics (free)** — automatic crash reports with stack trace,
  device model, Android version, app version. No user action needed.
- **In-app "Bug report"** — for bugs that are NOT crashes: user writes what happened,
  app auto-attaches device info + app version + last-crash log → sent to admin panel.
- **Diagnostics screen** (extends existing Device Info): engine status, last crash,
  export logs, copy device info.
- Needs: Firebase project on his Google account (no card, free tier) +
  google-services.json wired into the app build.

## 3. Admin panel (web dashboard)
- Built as a web dashboard (I build it): reads Crashlytics + bug reports.
- Shows: crash-free users %, top crashes (with stack traces), user bug reports
  (with device info), mark fixed / in-progress, app version stats.
- Later: remote notice to users (e.g. "update available"), kill-switch for a broken version.
- Backend: Firebase (Crashlytics + Firestore for bug reports) — free tier, no server to run.

## 4. Quality bar — "koi bug na ho"
- Build gate: GitHub Actions build must pass + apksigner verify (V1+V2+V3+V4).
- Phone test gates (his phone, before any release):
  1. App opens, engine starts, no crash on launch.
  2. Clone Instagram (split APK) → tap → OPENS.
  3. Clone WhatsApp, Facebook, Telegram → each opens + logs in separately.
  4. Long-press actions: launch / add clone / clear data / clear cache / kill / remove.
  5. 10 clones of one app; restart phone → clone library survives (Room).
  6. One banking app → clean refusal, NOT a host crash.
- Known honest limits (from CloneX README, do NOT promise otherwise):
  Banking/payment/UPI apps + REQUIRE_SECURE_ENV apps never run in clones.
  Play Store app itself crashes inside the sandbox (GMS-only). No virtual-space
  app runs 100% of apps — not even Parallel Space.

## 5. Build route
- Fork CloneX → rebrand → add Crashlytics + bug-report code → push main →
  GitHub Actions builds engine AAR + release APK → download artifact.
- This VM has NO Java/Android SDK — local build is NOT the route; CI is.
- Workflow file cannot be pushed via GitHub API (403) — upload via browser UI.

## 6. Blocked on user (needed before build)
1. **GitHub username** (also awaited for Apni Awaz) — for the fork + Actions build.
2. **App name** — his brand name for the cloner (e.g. his own name, not CloneX).
3. Firebase project — after fork; needs his Google sign-in (browser task or his phone).

## Status
- 2026-11-22 12:08: repo cloned, README + workflow read, plan written. Awaiting (1) + (2).
- 2026-11-22 12:11: GitHub signed in as zunair687; fork DONE: https://github.com/zunair687/clonex-android.
- 2026-11-22 12:16: **REBRAND DONE (verified by parent):** Zee Clone, package com.zee.clone, teal-green theme, 23 files changed (uncommitted). Anti-detection patch fixed to hide /data/data/com.zee.clone (was com.clonex.space — would have leaked the host path to cloned apps). README fork name corrected to zunair687/clonex-android. Remaining intentional CloneX refs: upstream attribution (README, Licenses dialog, ENGINE_NOTES.md), CLONEX_* signing secret names + workflow artifact names (workflow file must be renamed via browser UI later — fork's existing workflow still builds fine), ENGINE_RESEARCH.md (historical v2.0 doc). Custom app icon still pending.
- 2026-11-22 12:28: **PUSHED + BUILD RUNNING.** gh CLI authenticated as zunair687 (device-code flow). Release keystore generated (4096-bit RSA, 25y, alias zeeclone-release) at ~/workspace/zeeclone-release/zeeclone-release.jks — password ONLY in GitHub Secrets (never in chat/files/memory). 4 secrets set (ANDROID_KEYSTORE_BASE64, CLONEX_STORE_PASSWORD, CLONEX_KEY_ALIAS, CLONEX_KEY_PASSWORD). Commit 26f652f pushed to fork main. NOTE: push did NOT auto-trigger the workflow (0 runs) — manually dispatched via workflow_dispatch. Build run 45007678606 in_progress since 12:28 +03 (https://github.com/zunair687/clonex-android/actions/runs/45007678606).
- 2026-11-22 12:34: **BUILD SUCCEEDED (5m37s, all 14 steps ✓).** APK verified: app-arm64-v8a-release.apk, 2,412,877 bytes, package com.zee.clone, signed with release key (cert CN=Zee Clone, valid to 16 Nov 2051, SHA-256 2B:52:66:35:94:F9:BC:86:CC:76:58:ED:33:09:A7:8A:A9:06:63:53:3A:AE:89:D9:5B:9E:C9:00:D2:69:40:76; v2+v3 sig present). Delivered as ~/workspace/your_files/ZeeClone-v3.0.0.apk.
- NEXT: phone test gates (install → open → Instagram clone opens → WhatsApp/FB/Telegram separate logins → 10 clones → restart persistence → banking app = clean refusal), then Firebase project → Crashlytics + bug-report code → admin panel. Custom icon + workflow rename (browser UI) still pending.

- 2026-11-22 13:22: **v3.0.1 BUILD SUCCEEDED** (run 45011518609, 6m33s, all 14 steps ✓). APK verified: 2,413,427 bytes, versionName 3.0.1 (manifest), SAME signing cert (SHA-256 2B:52:66:35:...:76 — clean update over v3.0.0), libblackbox.so present. Delivered as ~/workspace/your_files/ZeeClone-v3.0.1.apk + fresh link (expires 24 Nov 13:22 +03). Changes: full engine-init failure (type + message + whole stack trace + device/Android) written to filesDir/engine-init-failure.txt and shown in Device Info. NEXT: he installs v3.0.1 (update over v3.0.0), opens Device Info, sends the full "Engine init failure" text → fix the exact cause.

- 2026-11-22 13:27: **v3.0.1 Device Info — EXACT ERROR: `java.lang.NoClassDefFoundError: top.niunaijun.blackbox.BlackBoxCore` at ZeeCloneApplication.onCreate.** Class IS in the dex (1,255 blackbox classes verified) — class init failed earlier in attachBaseContext; NCDFE is secondary, and the recorder overwrote the first failure. **FIX (v3.0.2, commit b1534df, build 45012719540):** (1) recordEngineFailure keeps FIRST failure (fresh file per launch); (2) explicit android:extractNativeLibs="true"; (3) CI patch widens BlackBoxCore static block catch (Exception) → catch (Throwable), verified locally. VersionCode 16.

- 2026-11-22 13:35: **v3.0.2 BUILD SUCCEEDED** (run 45012719540, 4m42s, all 14 steps ✓). APK verified: 2,413,407 bytes, versionName 3.0.2, SAME signing cert (SHA-256 2B:52:66:35:...:76 — clean update over v3.0.1), libblackbox.so present, extractNativeLibs in merged manifest. Delivered as ~/workspace/your_files/ZeeClone-v3.0.2.apk + fresh link (expires 24 Nov 13:37 +03). NEXT: he installs v3.0.2 → if engine starts, run phone test gates; if it still fails, Device Info now shows the FIRST (root-cause) failure.

- 2026-11-22 13:38: **v3.0.2 Device Info — ROOT CAUSE: `java.lang.ExceptionInInitializerError: null` at attachBaseContext.** Static-block fix did NOT help → failure is in `new BlackBoxCore()` before the static block: field initializer `BRUserHandle.get().myUserId()` (black-reflection wrapper). **FIX (v3.0.3, commit 27f54fd, build 45014510065):** mHostUserId now uses public `android.os.UserHandle.myUserId()` (same value, no reflection, import removed — patch verified locally). ALSO: Device Info dialog gains a Copy button. VersionCode 17.

- 2026-11-22 14:06: **v3.0.3 BUILD SUCCEEDED** (run 45014976616, 6m09s, all 14 steps ✓). Fix: mHostUserId = Process.myUid() / 100000 (pure arithmetic — the build's android.jar hides both UserHandle.myUserId() and getUserId()). APK verified: 2,414,555 bytes, versionName 3.0.3, SAME signing cert (SHA-256 2B:52:66:35:...:76 — clean update), libblackbox.so present. Delivered as ~/workspace/your_files/ZeeClone-v3.0.3.apk + fresh link (expires 24 Nov 14:05 +03). NEXT: phone test gates on his OPPO (engine start, Instagram split-APK clone, WhatsApp/FB/Telegram clones, 10 clones, restart persistence, banking clean refusal); if engine still fails, Device Info Copy button gives the full text.

- 2026-11-22 14:07: **ENGINE STARTED on his OPPO (Android 16): Device Info shows "Engine: NewBlackbox (BlackBoxCore) — OK", init failure: none, last crash: none.** v3.0.3 fix confirmed. Copy button worked (full text pasted). NEXT: phone test gates — Instagram split-APK clone OPEN, WhatsApp/FB/Telegram clones, 10 clones, restart persistence, banking clean refusal.
