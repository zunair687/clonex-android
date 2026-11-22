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
    print("SPLIT PATCH APPLIED + VERIFIED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
