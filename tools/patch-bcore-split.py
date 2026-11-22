#!/usr/bin/env python3
"""Apply the split-APK patch to a NewBlackbox checkout, for Zee Clone.

Usage: python3 tools/patch-bcore-split.py <path-to-newblackbox-checkout>

- Copies the 4 patched Java files from tools/bcore-split-patch/ over the
  upstream files (they are MaxCloner's split support, Apache-2.0).
- Adds "/data/data/com.zee.clone" to the hidden-paths list in
  Bcore/src/main/cpp/Utils/AntiDetection.cpp.
- Widens BlackBoxCore's class-load static block from catch (Exception)
  to catch (Throwable): on Android 16 the crash-prevention installers
  can throw Errors (hidden-API), and an Error there fails the whole
  class init -> every later use throws NoClassDefFoundError.
- v3.0.4 (Claude's diagnosis, implemented here — his script file did
  not arrive, only the analysis text): the "parser apk error." that
  blocks every clone install is SWALLOWED — parserApk catches Throwable
  and returns null, so the real exception never reaches the UI. The
  patch splits parse/cert-collection, logs full exceptions, surfaces
  the real cause in the install error ("parser apk error: <cause>"),
  no longer aborts on cert-collection failure, returns a real error
  (not fake success) from installPackageAsUserLocked's catch-all, and
  adds a reflection fallback for BPackage's mSigningDetails.
- Verifies: the 4 files are byte-identical to the patch dir, and the
  AntiDetection line is present. Exits non-zero on any failure.
"""
import hashlib
import pathlib
import sys

PATCH_FILES = {
    "Bcore/src/main/java/top/niunaijun/blackbox/core/env/BEnvironment.java": "BEnvironment.java",
    "Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/installer/CopyExecutor.java": "CopyExecutor.java",
    "Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/PackageManagerCompat.java": "PackageManagerCompat.java",
    "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java": "BActivityThread.java",
}
ANTI_DETECTION = "Bcore/src/main/cpp/Utils/AntiDetection.cpp"
ANCHOR = '    "/data/data/top.niunaijun.blackboxa",\n'
ADD_LINE = '    "/data/data/com.zee.clone",\n'

BLACKBOX_CORE = "Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java"
STATIC_CATCH_OLD = (
    '        } catch (Exception e) {\n'
    '            Slog.w(TAG, "Failed to install simple crash fix or stack trace filter at class loading: " + e.getMessage());\n'
    '        }'
)
STATIC_CATCH_NEW = (
    '        } catch (Throwable e) {\n'
    '            Slog.w(TAG, "Failed to install simple crash fix or stack trace filter at class loading: " + e.getMessage());\n'
    '        }'
)
# v3.0.3: BlackBoxCore's class init calls BRUserHandle.get().myUserId()
# (black-reflection wrapper) in a field initializer — on Android 16 that
# reflection path fails and kills class init (ExceptionInInitializerError).
# Replacement must compile against THIS build's android.jar: BOTH
# UserHandle.myUserId() and UserHandle.getUserId() are invisible to its
# javac (cannot find symbol — builds 45014510065 + 45014714947 failed on
# them). So compute it directly: userId = uid / 100000 (PER_USER_RANGE),
# exactly what UserHandle.getUserId() does. Process.myUid() is public
# and already used one line below, so this compiles for sure.
USERID_OLD = "    private final int mHostUserId = BRUserHandle.get().myUserId();"
USERID_NEW = "    private final int mHostUserId = android.os.Process.myUid() / 100000;"
USERID_IMPORT = "import black.android.os.BRUserHandle;\n"

# --- v3.0.4: parser diagnostics (Claude's design) -------------------------
# parserApk in BPackageManagerService + Settings swallows the real
# exception (catch Throwable -> printStackTrace -> null) so every clone
# install fails with the bare string "parser apk error." and nobody can
# tell why. Split parse/cert-collection, log fully, surface the cause.
BPM = "Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/BPackageManagerService.java"
SETTINGS = "Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/Settings.java"
BPACKAGE = "Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/BPackage.java"

PARSER_OLD = (
    "    private PackageParser.Package parserApk(String file) {\n"
    "        try {\n"
    "            PackageParser parser = PackageParserCompat.createParser(new File(file));\n"
    "            PackageParser.Package aPackage = PackageParserCompat.parsePackage(parser, new File(file), 0);\n"
    "            PackageParserCompat.collectCertificates(parser, aPackage, 0);\n"
    "            return aPackage;\n"
    "        } catch (Throwable t) {\n"
    "            t.printStackTrace();\n"
    "        }\n"
    "        return null;\n"
    "    }\n"
)
PARSER_NEW = (
    "    /** Last parserApk failure, surfaced in the install error so the UI shows the real cause. */\n"
    "    private static String sLastParseError = null;\n"
    "\n"
    "    private PackageParser.Package parserApk(String file) {\n"
    "        PackageParser parser = null;\n"
    "        PackageParser.Package aPackage = null;\n"
    "        try {\n"
    "            parser = PackageParserCompat.createParser(new File(file));\n"
    "            aPackage = PackageParserCompat.parsePackage(parser, new File(file), 0);\n"
    "        } catch (Throwable t) {\n"
    "            sLastParseError = t.getClass().getSimpleName() + \": \" + t.getMessage();\n"
    "            Slog.e(TAG, \"parserApk parsePackage failed\", t);\n"
    "            t.printStackTrace();\n"
    "            return null;\n"
    "        }\n"
    "        if (aPackage == null) {\n"
    "            sLastParseError = \"parsePackage returned null\";\n"
    "            Slog.e(TAG, \"parserApk parsePackage returned null\");\n"
    "            return null;\n"
    "        }\n"
    "        try {\n"
    "            PackageParserCompat.collectCertificates(parser, aPackage, 0);\n"
    "        } catch (Throwable t) {\n"
    "            // Host imports get signatures from the host PackageManager;\n"
    "            // a cert-collection failure must not abort the install.\n"
    "            Slog.w(TAG, \"parserApk collectCertificates failed (ignored): \" + t);\n"
    "            t.printStackTrace();\n"
    "        }\n"
    "        return aPackage;\n"
    "    }\n"
)
BPM_INSTALLERR_OLD = (
    "            PackageParser.Package aPackage = parserApk(apkFile.getAbsolutePath());\n"
    "            if (aPackage == null) {\n"
    "                return result.installError(\"parser apk error.\");\n"
    "            }\n"
)
BPM_INSTALLERR_NEW = (
    "            PackageParser.Package aPackage = parserApk(apkFile.getAbsolutePath());\n"
    "            if (aPackage == null) {\n"
    "                String cause = sLastParseError != null ? sLastParseError : \"unknown\";\n"
    "                return result.installError(\"parser apk error: \" + cause);\n"
    "            }\n"
)
BPM_CATCHALL_OLD = (
    "        } catch (Throwable t) {\n"
    "            t.printStackTrace();\n"
    "        } finally {\n"
    "            if (apkFile != null && option.isFlag(InstallOption.FLAG_URI_FILE)) {\n"
)
BPM_CATCHALL_NEW = (
    "        } catch (Throwable t) {\n"
    "            t.printStackTrace();\n"
    "            Slog.e(TAG, \"installPackageAsUserLocked failed\", t);\n"
    "            return result.installError(\"install failed: \" + t.getClass().getSimpleName() + \": \" + t.getMessage());\n"
    "        } finally {\n"
    "            if (apkFile != null && option.isFlag(InstallOption.FLAG_URI_FILE)) {\n"
)
SET_REINSTALL_OLD = (
    "        PackageParser.Package aPackage = parserApk(systemPackageInfo.applicationInfo.sourceDir);\n"
    "        if (aPackage == null) {\n"
    "            throw new RuntimeException(\"parser apk error.\");\n"
    "        }\n"
)
SET_REINSTALL_NEW = (
    "        PackageParser.Package aPackage = parserApk(systemPackageInfo.applicationInfo.sourceDir);\n"
    "        if (aPackage == null) {\n"
    "            String cause = sLastParseError != null ? sLastParseError : \"unknown\";\n"
    "            throw new RuntimeException(\"parser apk error: \" + cause);\n"
    "        }\n"
)
BP_SIGNING_OLD = (
    "        this.requestedPermissions = aPackage.requestedPermissions;\n"
    "        if (BuildCompat.isPie()) {\n"
    "            this.mSigningDetails = new SigningDetails(aPackage.mSigningDetails);\n"
    "            this.mSignatures = this.mSigningDetails.signatures;\n"
    "        } else {\n"
    "            this.mSignatures = aPackage.mSignatures;\n"
    "        }\n"
)
BP_SIGNING_NEW = (
    "        this.requestedPermissions = aPackage.requestedPermissions;\n"
    "        if (BuildCompat.isPie()) {\n"
    "            try {\n"
    "                this.mSigningDetails = new SigningDetails(aPackage.mSigningDetails);\n"
    "                this.mSignatures = this.mSigningDetails.signatures;\n"
    "            } catch (Throwable t) {\n"
    "                // Android 31+: Package.mSigningDetails may have moved or\n"
    "                // changed type; read it reflectively, else install\n"
    "                // unsigned (host imports get signatures from the host PM).\n"
    "                try {\n"
    "                    java.lang.reflect.Field f = aPackage.getClass().getField(\"mSigningDetails\");\n"
    "                    Object sd = f.get(aPackage);\n"
    "                    this.mSigningDetails = new SigningDetails((PackageParser.SigningDetails) sd);\n"
    "                    this.mSignatures = this.mSigningDetails.signatures;\n"
    "                } catch (Throwable t2) {\n"
    "                    this.mSigningDetails = null;\n"
    "                    this.mSignatures = null;\n"
    "                }\n"
    "            }\n"
    "        } else {\n"
    "            this.mSignatures = aPackage.mSignatures;\n"
    "        }\n"
)


def patch_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    assert text.count(old) == 1, f"{label}: anchor not found exactly once in {path}"
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    assert new in path.read_text(encoding="utf-8"), f"{label}: verify failed in {path}"
    print(f"patched {label}")


def sha(p: pathlib.Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: patch-bcore-split.py <newblackbox-checkout>", file=sys.stderr)
        return 2
    nb = pathlib.Path(sys.argv[1])
    here = pathlib.Path(__file__).resolve().parent
    patch_dir = here / "bcore-split-patch"
    for rel, name in PATCH_FILES.items():
        src = patch_dir / name
        dst = nb / rel
        assert src.is_file(), f"missing patch file {src}"
        assert dst.is_file(), f"missing upstream file {dst}"
        dst.write_bytes(src.read_bytes())
        assert sha(dst) == sha(src), f"verify failed for {rel}"
        print(f"patched {rel}")
    ad = nb / ANTI_DETECTION
    assert ad.is_file(), f"missing {ANTI_DETECTION}"
    text = ad.read_text(encoding="utf-8")
    if ADD_LINE.strip() not in text:
        assert ANCHOR in text, "AntiDetection anchor not found"
        text = text.replace(ANCHOR, ANCHOR + ADD_LINE, 1)
        ad.write_text(text, encoding="utf-8")
    assert ADD_LINE.strip() in ad.read_text(encoding="utf-8")
    print(f"patched {ANTI_DETECTION} (added com.zee.clone)")
    bb = nb / BLACKBOX_CORE
    assert bb.is_file(), f"missing {BLACKBOX_CORE}"
    text = bb.read_text(encoding="utf-8")
    assert text.count(STATIC_CATCH_OLD) == 1, "BlackBoxCore static-block catch anchor not found exactly once"
    bb.write_text(text.replace(STATIC_CATCH_OLD, STATIC_CATCH_NEW, 1), encoding="utf-8")
    assert STATIC_CATCH_NEW in bb.read_text(encoding="utf-8")
    print(f"patched {BLACKBOX_CORE} (static block catches Throwable)")
    text = bb.read_text(encoding="utf-8")
    assert text.count(USERID_OLD) == 1, "BlackBoxCore mHostUserId anchor not found exactly once"
    text = text.replace(USERID_OLD, USERID_NEW, 1)
    assert text.count(USERID_IMPORT) == 1, "BRUserHandle import not found exactly once"
    text = text.replace(USERID_IMPORT, "", 1)
    bb.write_text(text, encoding="utf-8")
    after = bb.read_text(encoding="utf-8")
    assert USERID_NEW in after and "BRUserHandle" not in after
    print(f"patched {BLACKBOX_CORE} (mHostUserId via public UserHandle.myUserId)")
    # v3.0.4: parser diagnostics (Claude's design).
    patch_once(nb / BPM, PARSER_OLD, PARSER_NEW, f"{BPM} (parserApk split + logging)")
    patch_once(nb / BPM, BPM_INSTALLERR_OLD, BPM_INSTALLERR_NEW, f"{BPM} (install error shows cause)")
    patch_once(nb / BPM, BPM_CATCHALL_OLD, BPM_CATCHALL_NEW, f"{BPM} (catch-all returns error, not fake success)")
    patch_once(nb / SETTINGS, PARSER_OLD, PARSER_NEW, f"{SETTINGS} (parserApk split + logging)")
    patch_once(nb / SETTINGS, SET_REINSTALL_OLD, SET_REINSTALL_NEW, f"{SETTINGS} (reInstall error shows cause)")
    patch_once(nb / BPACKAGE, BP_SIGNING_OLD, BP_SIGNING_NEW, f"{BPACKAGE} (mSigningDetails reflection fallback)")
    print("SPLIT PATCH APPLIED + VERIFIED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
